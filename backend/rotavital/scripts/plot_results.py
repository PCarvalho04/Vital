#!/usr/bin/env python3
"""
Gera os gráficos a partir do CSV do benchmark_endpoint.py.
Requer matplotlib:  pip install matplotlib
Uso: python3 scripts/plot_results.py benchmark-results.csv
"""
import csv
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

LABELS = {"PLATFORM_THREADS": "Threads de plataforma", "VIRTUAL_THREADS": "Virtual threads"}
COLORS = {"PLATFORM_THREADS": "#2a6fdb", "VIRTUAL_THREADS": "#e0782b"}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "benchmark-results.csv"
    rows = list(csv.DictReader(open(path)))
    sizes = sorted({int(r["size"]) for r in rows})

    fig, axes = plt.subplots(2, len(sizes), figsize=(6 * len(sizes), 9), squeeze=False)
    for col, size in enumerate(sizes):
        data = [r for r in rows if int(r["size"]) == size]
        seq = next(r for r in data if r["mode"] == "SEQUENTIAL")
        series = defaultdict(list)
        for r in data:
            if r["mode"] != "SEQUENTIAL":
                series[r["mode"]].append((int(r["threads"]), float(r["endpoint_avg_ms"]), float(r["speedup"])))

        ax_time, ax_speed = axes[0][col], axes[1][col]
        ax_time.axhline(float(seq["endpoint_avg_ms"]), color="gray", linestyle="--", label="Sequencial")
        max_threads = 1
        for mode, points in series.items():
            points.sort()
            threads = [p[0] for p in points]
            max_threads = max(max_threads, *threads)
            ax_time.plot(threads, [p[1] for p in points], "o-", color=COLORS[mode], label=LABELS[mode])
            ax_speed.plot([1] + threads, [1.0] + [p[2] for p in points], "o-", color=COLORS[mode], label=LABELS[mode])
        ideal = list(range(1, max_threads + 1))
        ax_speed.plot(ideal, ideal, ":", color="gray", label="Ideal (linear)")

        ax_time.set_title(f"Tempo de resposta — {size:,} registros".replace(",", "."))
        ax_time.set_xlabel("Threads")
        ax_time.set_ylabel("ms (média)")
        ax_time.set_ylim(bottom=0)
        ax_speed.set_title(f"Speedup — {size:,} registros".replace(",", "."))
        ax_speed.set_xlabel("Threads")
        ax_speed.set_ylabel("Speedup (T_seq / T_par)")
        for ax in (ax_time, ax_speed):
            ax.grid(alpha=0.3)
            ax.legend()

    fig.tight_layout()
    out = path.rsplit(".", 1)[0] + ".png"
    fig.savefig(out, dpi=130)
    print(f"Gráfico salvo em {out}")


if __name__ == "__main__":
    main()
