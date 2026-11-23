import argparse
import json
import os
import statistics
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from time import perf_counter
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def run(base):
    payload = json.dumps({"username": "xuhui", "password": os.environ["OPSATLAS_DEMO_PASSWORD"]}).encode()
    request = Request(base + "/api/auth/login", data=payload, headers={"Content-Type": "application/json"})
    with urlopen(request, timeout=10) as response:
        token = json.load(response)["accessToken"]
    headers = {"Authorization": "Bearer " + token}

    def dashboard():
        start = perf_counter()
        with urlopen(Request(base + "/api/dashboard?storeId=1", headers=headers), timeout=10) as response:
            assert http_ok(response.status, json.load(response))
        return (perf_counter() - start) * 1000

    for _ in range(5):
        dashboard()
    start = perf_counter()
    with ThreadPoolExecutor(max_workers=4) as executor:
        samples = list(executor.map(lambda unused: dashboard(), range(40)))
    result = {"target": base, "endpoint": "/api/dashboard?storeId=1", "warmup_requests": 5,
              "requests": len(samples), "concurrency": 4, "http_errors": 0,
              "p50_ms": round(statistics.median(samples), 2),
              "p95_ms": round(statistics.quantiles(samples, n=100, method="inclusive")[94], 2),
              "max_ms": round(max(samples), 2), "requests_per_second": round(len(samples) / (perf_counter() - start), 2)}
    (ROOT / "docs/evidence/phase-6-performance.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


def http_ok(status, result):
    return status == 200 and result["current"]["orders"] == 42


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    run(parser.parse_args().base)
