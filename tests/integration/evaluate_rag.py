import argparse
import json
import os
import statistics
from pathlib import Path
from time import perf_counter
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def evaluate(base):
    def call(path, token=None, data=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        payload = None if data is None else json.dumps(data).encode()
        with urlopen(Request(base + path, data=payload, headers=headers), timeout=10) as response:
            return json.load(response)

    token = call("/api/auth/login", data={"username": "xuhui", "password": os.environ["OPSATLAS_DEMO_PASSWORD"]})["accessToken"]
    samples = [json.loads(line) for line in (ROOT / "docs/evaluation/retrieval.jsonl").read_text(encoding="utf-8").splitlines()]
    details = []
    for sample in samples:
        start = perf_counter()
        result = call("/api/knowledge/search?" + urlencode({"q": sample["query"], "limit": 8}), token)
        ranks = {
            method: list(dict.fromkeys(Path(item["source"]).stem for item in candidates))[:3]
            for method, candidates in result.items()
        }
        latency = (perf_counter() - start) * 1000
        if result["citations"]:
            item = result["citations"][0]
            assert call("/api/knowledge/citations/" + item["id"], token)["content"] == item["content"]
        details.append({**sample, "rankings": ranks, "latency_ms": round(latency, 3)})
    positive = [sample for sample in details if sample["relevant"]]
    negative = [sample for sample in details if not sample["relevant"]]
    metrics = {}
    for name in ("lexical", "vector", "citations"):
        recall, rr = [], []
        for sample in positive:
            ranks = [i for i, source in enumerate(sample["rankings"][name], 1) if source in sample["relevant"]]
            recall.append(len(ranks) / len(sample["relevant"]))
            rr.append(1 / ranks[0] if ranks else 0)
        metrics[name] = {"recall@3": round(statistics.mean(recall), 4), "mrr@3": round(statistics.mean(rr), 4),
                         "negative_answered": sum(bool(sample["rankings"][name]) for sample in negative)}
    assert metrics["citations"]["recall@3"] >= .8
    assert metrics["citations"]["negative_answered"] == 0
    latencies = [s["latency_ms"] for s in details]
    result = {"index": call("/api/knowledge/index", token), "positive_queries": len(positive),
              "negative_queries": len(negative), "concurrency": 1, "metrics": metrics,
              "latency_ms": {"p50": statistics.median(latencies),
                             "p95": statistics.quantiles(latencies, n=100, method="inclusive")[94]},
              "details": details}
    (ROOT / "docs/evidence/phase-3-retrieval.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k:v for k,v in result.items() if k != "details"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    evaluate(parser.parse_args().base)
