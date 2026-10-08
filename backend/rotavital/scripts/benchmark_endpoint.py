#!/usr/bin/env python3
"""
Mede o tempo de resposta do endpoint GET /api/reports/request-statistics (do lado do cliente),
para cada tamanho de entrada, versão (sequencial / threads de plataforma / virtual threads) e
número de threads. Confere se todas as versões devolvem exatamente o mesmo relatório e grava um CSV.

Uso (com a aplicação rodando):
    python3 scripts/benchmark_endpoint.py --sizes 100000,1000000 --threads 2,4,8 --repetitions 10

Só usa a biblioteca padrão do Python.
"""
import argparse
import csv
import json
import statistics
import time
import urllib.request

ENDPOINT = "/api/reports/request-statistics"


def call(base_url, mode, threads, size):
    url = f"{base_url}{ENDPOINT}?mode={mode}&threads={threads}&size={size}"
    start = time.perf_counter()
    with urllib.request.urlopen(url) as response:
        body = response.read()
    elapsed_ms = (time.perf_counter() - start) * 1000
    payload = json.loads(body)
    return elapsed_ms, payload["processingTimeMs"], payload["report"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--sizes", default="100000,1000000")
    parser.add_argument("--threads", default="2,4,8")
    parser.add_argument("--repetitions", type=int, default=10)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--no-virtual", action="store_true", help="não mede as virtual threads")
    parser.add_argument("--output", default="benchmark-results.csv")
    args = parser.parse_args()

    sizes = [int(s) for s in args.sizes.split(",")]
    thread_counts = [int(t) for t in args.threads.split(",")]
    modes = ["PLATFORM_THREADS"] + ([] if args.no_virtual else ["VIRTUAL_THREADS"])

    rows = []
    for size in sizes:
        # gera o histórico antes de medir, para a geração dos dados não entrar no tempo
        req = urllib.request.Request(f"{args.base_url}{ENDPOINT}/dataset?size={size}", method="POST")
        with urllib.request.urlopen(req) as response:
            print(f"dataset {size}: {response.read().decode()}")

        configs = [("SEQUENTIAL", 1)] + [(m, t) for m in modes for t in thread_counts]
        expected = None
        sequential_avg = None
        for mode, threads in configs:
            for _ in range(args.warmup):
                call(args.base_url, mode, threads, size)
            client_times, server_times = [], []
            same = True
            for _ in range(args.repetitions):
                client_ms, server_ms, report = call(args.base_url, mode, threads, size)
                client_times.append(client_ms)
                server_times.append(server_ms)
                if expected is None:
                    expected = report
                same = same and report == expected

            avg = statistics.mean(client_times)
            if mode == "SEQUENTIAL":
                sequential_avg = avg
            speedup = sequential_avg / avg
            row = {
                "size": size,
                "mode": mode,
                "threads": threads,
                "endpoint_avg_ms": round(avg, 3),
                "endpoint_median_ms": round(statistics.median(client_times), 3),
                "endpoint_min_ms": round(min(client_times), 3),
                "processing_avg_ms": round(statistics.mean(server_times), 3),
                "speedup": round(speedup, 3),
                "efficiency": round(speedup / threads, 3),
                "same_result": same,
            }
            rows.append(row)
            print(row)

    with open(args.output, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nResultados gravados em {args.output}")


if __name__ == "__main__":
    main()
