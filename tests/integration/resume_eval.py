"""Run the read-only business/RAG checks, controlled live tasks, and restart checks."""
import argparse
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from hashlib import sha256
import json
import os
from pathlib import Path
import statistics
from time import perf_counter, sleep
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = {"hq": ("HQ", [1, 2, 3, 4]), "east": ("REGION", [1, 2]),
           "xuhui": ("STORE", [1]), "tianhe": ("STORE", [3])}
TOOLS = ("get_store_metrics", "search_company_policy")
RAG_METHODS = ("lexical", "vector", "citations")
FALLBACKS = {"核对退款原因、原订单和售后登记，按引用制度执行复核。",
             "没有找到支持该问题的企业制度。请先核对订单和售后登记，并补充相关制度。"}
HISTORICAL = ROOT / "docs/evidence"
LABELS = ROOT / "docs/evaluation/retrieval.jsonl"


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str) + "\n", encoding="utf-8")


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def fraction(passed, total):
    return {"passed": passed, "total": total, "rate": round(passed / total, 4) if total else None}


def latency(values):
    if not values:
        return {"n": 0, "p50_ms": None, "p95_ms": None}
    p95 = statistics.quantiles(values, n=100, method="inclusive")[94] if len(values) > 1 else values[0]
    return {"n": len(values), "p50_ms": round(statistics.median(values), 2), "p95_ms": round(p95, 2)}


def fingerprint(value):
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class Client:
    def __init__(self, base):
        self.base = base
        self.tokens = {}

    def request(self, path, user=None, payload=None, extra=None):
        headers = {"Content-Type": "application/json", **(extra or {})}
        if user:
            if user not in self.tokens:
                status, login = self.call("/api/auth/login", payload={
                    "username": user, "password": os.environ["OPSATLAS_DEMO_PASSWORD"]})
                if status != 200:
                    raise RuntimeError(f"Login {user} failed with HTTP {status}")
                self.tokens[user] = login["accessToken"]
            headers["Authorization"] = "Bearer " + self.tokens[user]
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
        return Request(self.base + path, data=data, headers=headers)

    def call(self, path, user=None, payload=None, extra=None):
        try:
            with urlopen(self.request(path, user, payload, extra), timeout=15) as response:
                status, body = response.status, response.read()
        except HTTPError as error:
            status, body = error.code, error.read()
        if not body:
            return status, {}
        text = body.decode("utf-8")
        if text.startswith(("event:", "data:")):
            text = next(line[5:] for line in text.splitlines() if line.startswith("data:"))
        try:
            return status, json.loads(text)
        except json.JSONDecodeError:
            return status, {"non_json_response": True}

    def get(self, path, user=None):
        status, result = self.call(path, user)
        if status != 200:
            raise RuntimeError(f"{path} returned HTTP {status}")
        return result

    def stream(self, user, task):
        request = self.request("/api/agent/stream", user,
                               {"question": task["question"], "storeId": task["store"], "local": False},
                               {"Accept": "text/event-stream"})
        events = []
        start = perf_counter()
        result = {"started_utc": datetime.now(timezone.utc).isoformat(), "events": events}
        try:
            with urlopen(request, timeout=70) as response:
                result["http_status"] = response.status
                if "text/event-stream" not in response.headers.get("Content-Type", ""):
                    result["transport_error"] = "unexpected_content_type"
                else:
                    event, data = "", []
                    for raw in response:
                        line = raw.decode("utf-8").rstrip("\r\n")
                        if not line and data:
                            elapsed = round((perf_counter() - start) * 1000, 3)
                            events.append({"event": event or "message", "ms": elapsed,
                                           "data": json.loads("\n".join(data))})
                            if event in ("done", "failure"):
                                break
                            event, data = "", []
                        elif line.startswith("event:"):
                            event = line[6:].lstrip()
                        elif line.startswith("data:"):
                            data.append(line[5:].lstrip())
        except HTTPError as error:
            result["http_status"] = error.code
        except (TimeoutError, URLError, OSError, UnicodeError, ValueError) as error:
            result["transport_error"] = type(error).__name__
        result["ended_utc"] = datetime.now(timezone.utc).isoformat()
        return result


def baseline(folder):
    entries = [line.split("\t") for line in (folder / "sql-baseline.tsv").read_text(encoding="utf-8").splitlines()]
    item = next(row for row in entries if row[0] == "dataset")
    end = date.fromisoformat(item[2])
    data = {"earliest_order": item[1], "latest_order": item[2], "orders": int(item[3]),
            "refund_rows": int(item[4]), "approved_refunds": int(item[5]),
            "schema_versions": {row[1]: row[2] for row in entries if row[0] == "migration"},
            "seed_file_sha256": {p.name: sha256(p.read_bytes()).hexdigest() for p in
                                 sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*.sql"))}}
    totals = {(row[1], int(row[2])): tuple(map(Decimal, row[3:])) for row in entries if row[0] == "total"}
    return data, end, totals


def compare(expected, actual):
    if isinstance(expected, Decimal):
        return actual is not None and expected == Decimal(str(actual))
    return expected == actual


def expected_dashboard(totals, window, stores):
    def period(name):
        orders, refunded, gross, refunds = [sum(column) for column in zip(
            *(totals[(window + ":" + name, store)] for store in stores))]
        rate = (refunded * 100 / orders).quantize(Decimal(".01"), rounding=ROUND_HALF_UP) if orders else Decimal(0)
        return {"orders": int(orders), "refundedOrders": int(refunded), "grossSales": gross,
                "refundAmount": refunds, "netSales": gross - refunds, "refundRate": rate}

    def change(current, previous):
        return ((current - previous) * 100 / previous).quantize(Decimal(".01"), rounding=ROUND_HALF_UP) if previous else None

    current, previous = period("current"), period("previous")
    return {"current": current, "previous": previous, "change": {
        "ordersPercent": change(Decimal(current["orders"]), Decimal(previous["orders"])),
        "grossSalesPercent": change(current["grossSales"], previous["grossSales"]),
        "refundRatePoints": current["refundRate"] - previous["refundRate"]}}


def dashboard_checks(actual, expected):
    return [{"field": f"{group}.{field}", "expected": wanted, "actual": actual.get(group, {}).get(field),
             "matched": compare(wanted, actual.get(group, {}).get(field))}
            for group, fields in expected.items() for field, wanted in fields.items()]


def business(client, end, totals, folder):
    windows = {"week": (end - timedelta(days=6), end),
               "fortnight": (end - timedelta(days=13), end),
               "empty": (end + timedelta(days=1), end + timedelta(days=7))}
    scopes = [("hq", {}, [1, 2, 3, 4]), ("hq", {"regionId": 1}, [1, 2]),
              ("hq", {"regionId": 2}, [3, 4]), ("hq", {"storeId": 4}, [4]),
              ("east", {}, [1, 2]), ("east", {"storeId": 2}, [2]),
              ("xuhui", {}, [1]), ("tianhe", {"storeId": 3}, [3])]
    cases = []
    for name, (since, until) in windows.items():
        for user, scope, stores in scopes:
            params = {**scope, "from": since.isoformat(), "to": until.isoformat()}
            status, actual = client.call("/api/dashboard?" + urlencode(params), user)
            checks = dashboard_checks(actual, expected_dashboard(totals, name, stores))
            previous_start = since - (until - since) - timedelta(days=1)
            scope_match = (actual.get("storeIds") == stores and actual.get("period") ==
                           {"from": str(since), "to": str(until)} and actual.get("comparison") ==
                           {"from": str(previous_start), "to": str(since - timedelta(days=1))})
            cases.append({"account": user, "window": name, "request": params, "http": status,
                          "scope_date_match": scope_match, "fields": checks,
                          "passed": status == 200 and scope_match and all(check["matched"] for check in checks)})
    policies = []
    params = {"from": str(windows["week"][0]), "to": str(end)}
    for user, (role, stores) in FIXTURE.items():
        for store in range(1, 5):
            for endpoint in ("dashboard", "orders", "refunds"):
                status, body = client.call(f"/api/{endpoint}?" + urlencode({**params, "storeId": store}), user)
                allow = store in stores
                if allow:
                    scoped = body.get("storeIds") == [store] if endpoint == "dashboard" and isinstance(body, dict) else (
                        isinstance(body, list) and bool(body) and all(row["storeId"] == store for row in body))
                else:
                    scoped = isinstance(body, dict) and not ({"current", "storeIds", "orders", "refundAmount"} & body.keys())
                policies.append({"account": user, "role": role, "target": store, "endpoint": endpoint,
                                 "allowed": allow, "http": status, "passed": scoped and status == (200 if allow else 403)})
    fields = [field for case in cases for field in case["fields"]]
    roles = {}
    for role in ("HQ", "REGION", "STORE"):
        selected = [row for row in policies if row["role"] == role]
        roles[role] = {name: fraction(sum(row["passed"] for row in subset), len(subset))
                       for name, subset in [("allowed", [r for r in selected if r["allowed"]]),
                                            ("denied", [r for r in selected if not r["allowed"]])]}
    denied = [row for row in policies if not row["allowed"]]
    allowed = [row for row in policies if row["allowed"]]
    summary = {"field_matches": fraction(sum(row["matched"] for row in fields), len(fields)),
               "dashboard_cases": fraction(sum(row["passed"] for row in cases), len(cases)),
               "denied_controls": fraction(sum(row["passed"] for row in denied), len(denied)),
               "allowed_controls": fraction(sum(row["passed"] for row in allowed), len(allowed)), "roles": roles}
    write(folder / "business.json", {"summary": summary, "sql_comparisons": cases, "permissions": policies})
    return summary


def rag_metrics(rows, top_k):
    positive = [row for row in rows if row["relevant"]]
    negative = [row for row in rows if not row["relevant"]]
    result = {}
    for method in RAG_METHODS:
        recalls, reciprocals, hits = [], [], []
        for row in positive:
            ranks = row["rankings"][method]
            observed = [index for index, name in enumerate(ranks, 1) if name in row["relevant"]]
            recalls.append(sum(index <= 3 for index in observed) / len(row["relevant"]))
            reciprocals.append(1 / observed[0] if observed else 0)
            hits.append(bool(observed and observed[0] == 1))
        result[method] = {"recall_at_3": round(statistics.mean(recalls), 4),
                          f"mrr_at_{top_k}": round(statistics.mean(reciprocals), 4),
                          "hit_at_1": fraction(sum(hits), len(hits)),
                          "no_answer_refusal": fraction(sum(not row["rankings"][method] for row in negative), len(negative))}
    return result


def retrieval(client, folder):
    archived = load(HISTORICAL / "phase-3-retrieval.json")
    samples = [json.loads(line) for line in LABELS.read_text(encoding="utf-8").splitlines() if line]
    if [(r["query"], r["relevant"]) for r in archived["details"]] != [(r["query"], r["relevant"]) for r in samples]:
        raise ValueError("Archived and current labels differ")
    index = client.get("/api/knowledge/index", "xuhui")
    if index["profile"] != "character-bigrams-384":
        raise ValueError("The evaluation baseline requires the local character-bigram embedding configuration")
    documents = client.get("/api/knowledge/documents", "xuhui")
    rows = []
    for sample in samples:
        query = urlencode({"q": sample["query"], "limit": 12})
        start = perf_counter()
        result = client.get("/api/knowledge/search?" + query, "xuhui")
        ms = round((perf_counter() - start) * 1000, 3)
        ranks = {name: list(dict.fromkeys(Path(row["source"]).stem for row in result[name])) for name in RAG_METHODS}
        row = {**sample, "rankings": ranks, "latency_ms": ms,
               "chunk_ranks": {name: [{"id": c["id"], "source": c["source"]} for c in result[name]]
                               for name in RAG_METHODS}}
        if not sample["relevant"]:
            answer = client.get("/api/knowledge/answer?" + urlencode({"q": sample["query"]}), "xuhui")
            row["api_refusal"] = answer.get("grounded") is False and not answer.get("citations") and bool(answer.get("answer"))
        rows.append(row)
    summary = {"label_sha256": sha256(LABELS.read_bytes()).hexdigest(),
               "positive": sum(bool(row["relevant"]) for row in samples),
               "negative": sum(not row["relevant"] for row in samples), "index": index,
               "document_count": len(documents), "corpus_fingerprint": fingerprint(sorted(documents, key=lambda d: d["id"])),
               "current": rag_metrics(rows, 12), "historical": rag_metrics(archived["details"], 3),
               "archived_index": archived["index"], "latency": latency([row["latency_ms"] for row in rows]),
               "answer_api_no_answer": fraction(sum(row.get("api_refusal", False) for row in rows),
                                                sum("api_refusal" in row for row in rows))}
    write(folder / "retrieval.json", {"summary": summary, "corpus_documents": documents, "results": rows})
    return summary


def mcp(client, folder):
    extra = {"Accept": "application/json, text/event-stream", "Mcp-Protocol-Version": "2025-06-18"}

    def call(user, method, params=None):
        body = {"jsonrpc": "2.0", "id": 1, "method": method}
        if params is not None:
            body["params"] = params
        return client.call("/mcp", user, body, extra)

    status, catalog = call("hq", "tools/list")
    names = [item["name"] for item in catalog.get("result", {}).get("tools", [])]
    cases = []
    for user, (role, allowed) in FIXTURE.items():
        for store in range(1, 5):
            status, body = call(user, "tools/call", {"name": "get_opsatlas_metrics", "arguments": {"storeId": store}})
            result = body.get("result", {})
            values = result.get("structuredContent")
            denied = result.get("isError") is True and not values and "无权" in json.dumps(result, ensure_ascii=False)
            allow = store in allowed
            cases.append({"account": user, "role": role, "target": store, "allow": allow,
                          "http": status, "response": body,
                          "passed": status == 200 and (bool(values and values.get("storeIds") == [store]) if allow else denied)})
    malformed = []
    for argument in ({"storeId": -1}, {"storeId": "unknown"}, {"storeId": 1, "userId": 1}):
        status, body = call("xuhui", "tools/call", {"name": "get_opsatlas_metrics", "arguments": argument})
        malformed.append({"argument": argument, "http": status, "response": body,
                          "passed": status == 200 and body.get("result", {}).get("isError") is True
                          and not body.get("result", {}).get("structuredContent")})
    denied = [row for row in cases if not row["allow"]]
    allowed = [row for row in cases if row["allow"]]
    audit = {user: client.get("/api/audit", user) for user in FIXTURE}
    for row in cases:
        row["denial_audit_seen"] = (not row["allow"] and any(
            item["target"] == f"store:{row['target']}" and item["actionCode"] == "mcp_metrics" and item["outcome"] == "rejected"
            for item in audit[row["account"]]))
    unauthenticated, _ = call(None, "tools/call", {"name": "get_opsatlas_metrics", "arguments": {"storeId": 1}})
    summary = {"mcp_catalog": names, "denied": fraction(sum(r["passed"] for r in denied), len(denied)),
               "allowed": fraction(sum(r["passed"] for r in allowed), len(allowed)),
               "malformed_rejected": fraction(sum(r["passed"] for r in malformed), len(malformed)),
               "denial_audited": fraction(sum(r["denial_audit_seen"] for r in denied), len(denied)),
               "unauthenticated_http": unauthenticated}
    write(folder / "mcp.json", {"summary": summary, "scope_tests": cases, "malformed_tests": malformed})
    return summary


def confirmation(client, user, draft):
    draft_id = draft["id"]
    endpoint = f"/api/reports/{draft_id}/confirm"
    wrong_user = "tianhe" if user != "tianhe" else "xuhui"
    before = not any(row["analysisId"] == draft_id for row in client.get("/api/reports", user))
    false_status, _ = client.call(endpoint, user, {"confirmed": False})
    missing_status, _ = client.call(endpoint, user, {})
    owner_status, _ = client.call(endpoint, wrong_user, {"confirmed": True})
    pre_save_absence = not any(row["analysisId"] == draft_id for row in client.get("/api/reports", user))
    status, saved = client.call(endpoint, user, {"confirmed": True})
    repeat_status, repeated = client.call(endpoint, user, {"confirmed": True})
    rows = [row for row in client.get("/api/reports", user) if row["analysisId"] == draft_id]
    audit = [row for row in client.get("/api/audit", user)
             if row["actionCode"] == "report_confirmed" and row["target"] == draft_id and row["outcome"] == "success"]
    return {"report_id": saved.get("id"), "status_codes": [false_status, missing_status, owner_status, status, repeat_status],
            "checks": {"no_auto_save": before, "false_rejected": false_status == 400,
                       "missing_rejected": missing_status == 400, "other_owner_rejected": owner_status == 404,
                       "negative_attempts_no_write": pre_save_absence,
                       "confirmed_content_matches": status == 200 and len(rows) == 1 and saved.get("content") == draft["draft"],
                       "repeat_idempotent": repeat_status == 200 and repeated.get("id") == saved.get("id") and bool(saved.get("id")),
                       "single_confirmation_audit": len(audit) == 1}}


def classify(stream):
    if stream.get("transport_error"):
        return "transport"
    status = stream.get("http_status")
    if status != 200:
        return f"http_{status}"
    failure = next((row["data"] for row in stream["events"] if row["event"] == "failure"), None)
    if not failure:
        return "no_terminal_event"
    code, message = failure["status"], failure.get("message", "")
    if code == 502:
        return "provider_error" if "模型服务" in message else "tool_error"
    if code == 400:
        return "invalid_tool_selection" if "未授权的工具" in message else "tool_parameters"
    return {403: "authorization", 422: "tool_chain_incomplete", 503: "service_unavailable",
            504: "provider_timeout"}.get(code, f"sse_{code}")


def period_and_scope(metric, since, end, store):
    previous_start = since - (end - since) - timedelta(days=1)
    previous_end = since - timedelta(days=1)
    expected = ((metric["period"], since, end), (metric["comparison"], previous_start, previous_end))
    return metric["storeIds"] == [store] and all(
        period[field] in (day.isoformat(), [day.year, day.month, day.day])
        for period, start, stop in expected for field, day in (("from", start), ("to", stop))
    )


def live_model(client, end, totals, folder):
    if not client.get("/api/agent/status", "xuhui")["modelConfigured"]:
        raise ValueError("Model status is not configured")
    since = end - timedelta(days=6)
    task_specs = [
        ("quality", "xuhui", 1, "商品质量投诉、批次凭证和售后复核", ["refund-quality"]),
        ("return", "east", 2, "普通退换货、原订单、包装附件和尺寸不合适", ["normal-return"]),
        ("escalation", "hq", 3, "退款异常、向区域运营升级和门店投诉处理", ["incident-escalation"]),
    ]
    tasks = [{"task": name, "user": user, "store": store, "expected_policies": documents,
              "question": f"分析门店编号 {store} 从 {since} 到 {end} 的经营数据和退款情况，与上一周期比较。"
                          f"请检索关于{topic}的企业制度，给出对策并生成经营周报草稿。"}
             for name, user, store, topic, documents in task_specs]
    rows = []
    for run in range(1, 4):
        for task in tasks:
            user, store = task["user"], task["store"]
            stream = client.stream(user, task)
            events = stream["events"]
            stages = [item["data"]["stage"] for item in events if item["event"] == "step"]
            tool_events = [item for item in events if item["event"] == "step" and item["data"].get("stage") in TOOLS]
            terminal = next((item["data"] for item in events if item["event"] == "done"), None)
            row = {**task, **stream, "run": run, "observed_tools": [item["data"]["stage"] for item in tool_events],
                   "first_event_ms": events[0]["ms"] if events else None, "first_stage": stages[0] if stages else None,
                   "first_tool_ms": tool_events[0]["ms"] if tool_events else None,
                   "done_ms": next((event["ms"] for event in events if event["event"] == "done"), None)}
            row["tool_chain_passed"] = bool(terminal and terminal["draft"]["mode"] == "model"
                                             and all(row["observed_tools"].count(tool) == 1 for tool in TOOLS))
            row["tool_failure_category"] = None if row["tool_chain_passed"] else (
                "unexpected_tool_set" if terminal else classify(stream))
            row["e2e_passed"] = False
            if terminal:
                draft = terminal["draft"]
                metric = draft["metrics"]
                citations = draft["citations"]
                references = [client.call("/api/knowledge/citations/" + item["id"], user) for item in citations]
                row["quality_checks"] = {
                    "draft_state": terminal["state"] == "DRAFT",
                    "server_metrics": all(c["matched"] for c in dashboard_checks(metric, expected_dashboard(totals, "week", [store]))),
                    "period_and_scope": period_and_scope(metric, since, end, store),
                    "model_advice": bool(draft["advice"].strip()) and draft["advice"] not in FALLBACKS,
                    "relevant_citation": any(Path(item["source"]).stem in task["expected_policies"] for item in citations),
                    "all_citations_resolve": bool(citations) and all(status == 200 and body.get("content") == citation["content"]
                                                for (status, body), citation in zip(references, citations)),
                }
                row["confirmation"] = confirmation(client, user, terminal)
                row["e2e_passed"] = (row["tool_chain_passed"] and all(row["quality_checks"].values())
                                     and all(row["confirmation"]["checks"].values()))
            rows.append(row)
            write(folder / "live-model-raw.json", rows)
            print(f"Live {task['task']}/{run}: tool_chain={row['tool_chain_passed']} e2e={row['e2e_passed']}", flush=True)
    return live_summary(rows)

def live_summary(rows):
    checks = [check for row in rows for check in row.get("confirmation", {}).get("checks", {}).values()]
    tool_slot_counts = sum(len(set(row["observed_tools"])) for row in rows)
    summary = {"model": "deepseek-flash", "run_count": len(rows), "runs_per_prompt": 3, "concurrency": 1,
               "tool_chain": fraction(sum(row["tool_chain_passed"] for row in rows), len(rows)),
               "tool_selection_and_successful_execution_slots": fraction(tool_slot_counts, len(rows) * len(TOOLS)),
               "failure_categories": dict(Counter(row["tool_failure_category"] for row in rows if row["tool_failure_category"])),
               "e2e": fraction(sum(row["e2e_passed"] for row in rows), len(rows)),
               "failed_quality_requirements": dict(Counter(name for row in rows
                    for name, passed in row.get("quality_checks", {}).items() if not passed)),
               "confirmation_checks": fraction(sum(checks), len(checks)),
               "drafts_checked": sum("confirmation" in row for row in rows)}
    summary["first_stage_counts"] = dict(Counter(row["first_stage"] for row in rows if row["first_stage"]))
    summary["per_prompt"] = []
    for task in dict.fromkeys(row["task"] for row in rows):
        cohort = [row for row in rows if row["task"] == task]
        drafts = [event["data"]["draft"] for row in cohort for event in row["events"] if event["event"] == "done"]
        summary["per_prompt"].append({"task": task, "runs": len(cohort),
            "tool_chain_successes": sum(row["tool_chain_passed"] for row in cohort),
            "completed_e2e": sum(row["e2e_passed"] for row in cohort),
            "distinct_advice_texts": len({draft["advice"].strip() for draft in drafts}),
            "distinct_citation_source_sets": len({tuple(sorted({item["source"] for item in draft["citations"]}))
                                                   for draft in drafts})})
    for label in ("first_event_ms", "first_tool_ms", "done_ms"):
        summary[label] = latency([row[label] for row in rows if row[label] is not None])
    return summary


def replay_live(folder):
    path = folder / "live-model-raw.json"
    raw = load(path)
    data, end, _ = baseline(folder)
    since = end - timedelta(days=6)
    old_summary = load(folder / "summary.json")
    original_summary = folder / "live-model-before-date-normalization.json"
    if not original_summary.exists():
        write(original_summary, old_summary["live"])
    corrections = []
    for row in raw:
        terminal = next((event["data"] for event in row["events"] if event["event"] == "done"), None)
        if terminal is None:
            continue
        metric = terminal["draft"]["metrics"]
        matched = period_and_scope(metric, since, end, row["store"])
        corrections.append({"task": row["task"], "run": row["run"], "period": metric["period"],
                            "comparison": metric["comparison"], "storeIds": metric["storeIds"],
                            "corrected_period_and_scope": matched})
        row["quality_checks"]["period_and_scope"] = matched
        row["e2e_passed"] = (row["tool_chain_passed"] and all(row["quality_checks"].values())
                             and all(row["confirmation"]["checks"].values()))
    old_summary["live"] = live_summary(raw)
    write(folder / "live-model-date-replay.json", {"original_sse_sha256": sha256(path.read_bytes()).hexdigest(),
        "fixture_end": data["latest_order"], "corrections": corrections, "live": old_summary["live"]})
    write(folder / "summary.json", old_summary)
    print(json.dumps(old_summary["live"], ensure_ascii=True))


def restart_checks(client, folder, stage):
    if stage != "before-restart":
        for _ in range(45):
            try:
                if client.get("/api/health")["status"] == "ok":
                    break
            except (RuntimeError, TimeoutError, URLError, OSError):
                pass
            sleep(1)
        else:
            raise RuntimeError("OpsAtlas did not recover after restart")
    before_file = folder / "before-restart.json"
    if stage == "before-restart":
        targets = [("xuhui", evidence["reportId"]) for evidence in (
            load(HISTORICAL / "phase-6-persistence.json"),
            load(HISTORICAL / "live-model/post-verification.json"))]
        file = folder / "live-model-raw.json"
        if file.exists():
            targets += [(row["user"], row["confirmation"]["report_id"]) for row in load(file)
                        if row.get("confirmation", {}).get("report_id")]
        retrieval_data = load(folder / "retrieval.json")
        citation_ids = list(dict.fromkeys(row["chunk_ranks"]["citations"][0]["id"] for row in retrieval_data["results"]
                                          if row["chunk_ranks"]["citations"]))[:3]
    else:
        prior = load(before_file)
        targets = [(row["account"], row["id"]) for row in prior["reports"]]
        citation_ids = [row["id"] for row in prior["citations"]]
    reports_by_user = {user: client.get("/api/reports", user) for user in {user for user, _ in targets}}
    reports = [{"account": user, "id": report_id, "hash": next(
        (fingerprint(row) for row in reports_by_user[user] if row["id"] == report_id), None)} for user, report_id in targets]
    citations = []
    for identifier in citation_ids:
        status, item = client.call("/api/knowledge/citations/" + identifier, "xuhui")
        citations.append({"id": identifier, "hash": fingerprint(item) if status == 200 else None})
    index = client.get("/api/knowledge/index", "xuhui")
    state = {"stage": stage, "reports": reports, "citations": citations, "index": index}
    if stage == "before-restart":
        if not index["points"] or not all(row["hash"] for row in reports + citations):
            raise RuntimeError("A chosen check target is missing before restart")
    else:
        report_matches = fraction(sum(a == b for a, b in zip(prior["reports"], reports)), len(reports))
        citation_matches = fraction(sum(a == b for a, b in zip(prior["citations"], citations)), len(citations))
        index_match = index == prior["index"]
        state["summary"] = {"report_records": report_matches, "citation_records": citation_matches,
                            "index_count_and_profile_match": index_match}
        if stage == "after-restart":
            storage = load(folder / "storage-restart.json")
            state["summary"]["index_same_before_app_restart"] = storage["summary"]["index_count_and_profile_match"]
            summary = load(folder / "summary.json")
            summary["persistence"] = state["summary"]
            write(folder / "summary.json", summary)
    write(folder / f"{stage}.json", state)
    if "summary" in state:
        print(json.dumps(state["summary"], ensure_ascii=True), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("measure", "rescore-live", "before-restart", "storage-restart", "after-restart"))
    parser.add_argument("base")
    parser.add_argument("folder", type=Path)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    if args.stage == "rescore-live":
        replay_live(args.folder)
        return
    client = Client(args.base)
    if args.stage != "measure":
        restart_checks(client, args.folder, args.stage)
        return
    data, end, totals = baseline(args.folder)
    summary = {"dataset": data, "started_utc": datetime.now(timezone.utc).isoformat()}
    summary["business"] = business(client, end, totals, args.folder)
    print("SQL and role verification saved.", flush=True)
    summary["rag"] = retrieval(client, args.folder)
    summary["mcp"] = mcp(client, args.folder)
    print("RAG and MCP evidence saved.", flush=True)
    write(args.folder / "summary.json", summary)
    if args.live:
        summary["live"] = live_model(client, end, totals, args.folder)
    summary["finished_utc"] = datetime.now(timezone.utc).isoformat()
    write(args.folder / "summary.json", summary)


if __name__ == "__main__":
    main()
