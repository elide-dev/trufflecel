#!/usr/bin/env python3
"""Isolated timing processes for the bounded CEL comparison; raw JSON and standalone SVG."""
import argparse
import datetime
import hashlib
import html
import itertools
import json
import math
import os
from pathlib import Path
import platform
import random
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
WORKLOADS = ("scalar", "policy", "comprehension")
ENGINES = ("trufflecel", "cel-java")
LEGACY_PANELS = (("jvm", "compile"), ("svm", "compile"), ("jvm", "evaluate"),
          ("svm", "evaluate"), ("svm", "image-ast"))
PANELS = LEGACY_PANELS + (("jvm", "interpret"), ("svm", "interpret"), ("svm", "image-interpret"))


def panels(data):
    return LEGACY_PANELS if data["schema"] == 1 else PANELS


def run(command, **kwargs):
    try:
        return subprocess.run(command, cwd=ROOT, check=True, **kwargs)
    except subprocess.CalledProcessError as error:
        for output in (error.stdout, error.stderr):
            if output:
                print(output.decode() if isinstance(output, bytes) else output, file=sys.stderr)
        raise


def source_digest():
    files = run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--",
                 "MODULE.bazel", "MODULE.bazel.lock", "maven_install.json", "cel_java_install.json",
                 ".bazelrc", ".bazelversion", "src/main", "benchmarks/comparison", "tools/bench/compare.py"],
                capture_output=True).stdout.split(b"\0")
    h = hashlib.sha256()
    for name in sorted(set(files) - {b""}):
        h.update(name + b"\0" + (ROOT / os.fsdecode(name)).read_bytes() + b"\0")
    return h.hexdigest()


def validate(data):
    if data["schema"] not in (1, 2, 3) or data["forks"] != 3:
        raise ValueError("Expected schema 1/2/3 and three independent processes per cell")
    expected = set(itertools.product(panels(data), WORKLOADS, ENGINES, range(data["forks"])))
    seen = set()
    for item in data["results"]:
        key = ((item["runtime"], item["stage"]), item["workload"], item["engine"], item["fork"])
        if key not in expected or key in seen:
            raise ValueError("Unexpected or duplicate measurement: " + str(key))
        seen.add(key)
        if len(item["ns_per_op"]) != 7 or any(not math.isfinite(n) or n <= 0 for n in item["ns_per_op"]):
            raise ValueError("Invalid samples")
        if item["runtime"] == "svm" and (not item["image_build_ast"] or item["image_prepare_ns"] <= 0):
            raise ValueError("Missing image-build AST preparation")
        interpreted = item["stage"] in ("interpret", "image-interpret")
        if item["engine"] == "trufflecel" and item["stage"] != "compile" and not interpreted and not item["guest_compiled"]:
            raise ValueError("Missing guest compilation")
        if data["schema"] >= 2:
            compiled = item["engine"] == "trufflecel" and item["stage"] in ("evaluate", "image-ast")
            if item["interpreter"] != interpreted or item["guest_compiled"] != compiled:
                raise ValueError("Invalid interpreter execution tier")
            if type(item["peak_rss_kib"]) is not int or item["peak_rss_kib"] <= 0:
                raise ValueError("Invalid per-process peak RSS")
        if data["schema"] >= 3:
            allocations = item["allocated_bytes_per_op"]
            if len(allocations) != 7 or any(not math.isfinite(n) or n < 0 for n in allocations):
                raise ValueError("Invalid allocation samples")
            if item["memory_operations_per_sample"] != (1024 if item["stage"] == "compile" else 65536):
                raise ValueError("Memory workload must use equal operation counts")
            if type(item["timed_peak_rss_kib"]) is not int or item["timed_peak_rss_kib"] <= 0:
                raise ValueError("Missing timed-process RSS")
    if seen != expected:
        raise ValueError("Incomplete comparison matrix")


def render(data, path, metric="time"):
    validate(data)
    memory = metric == "peak-rss"
    allocations = metric == "allocations"
    if allocations and data["schema"] < 3:
        raise ValueError("This historical dataset has no allocation measurements")
    if memory and data["schema"] < 2:
        raise ValueError("This historical dataset has no RSS measurements")
    selected = panels(data)
    chart_bottom = 128 + math.ceil(len(selected) / 2) * 258
    height = chart_bottom + 200
    esc = html.escape
    title = "Allocated heap bytes per operation" if allocations else "Peak process RSS" if memory else "Public API costs"
    subtitle = ("MiB · entire process lifetime, including startup, warmup, and guest compilation"
                if memory else "Median time per operation · setup and guest compilation excluded")
    if allocations:
        subtitle = "Bytes/op · current-thread heap allocation · public API including input normalization"
    elif memory and data["schema"] >= 3:
        subtitle = "MiB · whole process · equal operation counts, including setup and guest compilation"
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="{height}" viewBox="0 0 1200 {height}" role="img" aria-labelledby="title desc">',
             f'<title id="title">TruffleCEL vs cel-java · {title}</title>',
             f'<desc id="desc">{subtitle}. Lower is better. Whiskers show the minimum and maximum across three independent processes.</desc>',
             f'<rect width="1200" height="{height}" rx="16" fill="#f8f3e9"/>',
             '<style>text{font-family:system-ui,sans-serif;fill:#243746}.heading{font-size:25px;font-weight:700}.label{font-size:14px}.small{font-size:12px}.panel{font-size:16px;font-weight:650}</style>',
             f'<text x="32" y="42" class="heading">TruffleCEL vs cel-java · {title}</text>',
             f'<text x="32" y="68" class="label">Lower is better · {subtitle}</text>',
             '<rect x="32" y="84" width="14" height="14" fill="#ef8d40"/><text x="54" y="96" class="label">TruffleCEL</text>',
             '<rect x="175" y="84" width="14" height="14" fill="#28618b"/><text x="197" y="96" class="label">cel-java 0.15.0 (planner)</text>']
    colors = {"trufflecel": "#ef8d40", "cel-java": "#28618b"}
    for index, panel in enumerate(selected):
        x, y = 32 + (index % 2) * 584, 128 + (index // 2) * 258
        groups = {}
        for workload, engine in itertools.product(WORKLOADS, ENGINES):
            values = [(r["peak_rss_kib"] / 1024 if memory else statistics.median(r["allocated_bytes_per_op"] if allocations else r["ns_per_op"]))
                      for r in data["results"]
                      if (r["runtime"], r["stage"], r["workload"], r["engine"]) == (*panel, workload, engine)]
            groups[(workload, engine)] = (statistics.median(values), min(values), max(values))
        maximum = max(1, max(v[2] for v in groups.values()))
        heading = {"compile": "Compile + create program", "evaluate": "Evaluate · runtime-compiled AST",
                   "image-ast": "Evaluate · image-build checked AST",
                   "interpret": "Interpret · runtime-compiled AST",
                   "image-interpret": "Interpret · image-build checked AST"}[panel[1]]
        parts += [f'<rect x="{x}" y="{y}" width="552" height="242" rx="10" fill="white"/>',
                  f'<text x="{x+16}" y="{y+27}" class="panel">{panel[0].upper()} · {heading}</text>']
        for row, workload in enumerate(WORKLOADS):
            yy = y + 52 + row * 61
            parts.append(f'<text x="{x+16}" y="{yy}" class="label">{workload}</text>')
            for offset, engine in enumerate(ENGINES):
                median, low, high = groups[(workload, engine)]
                bx, by = x + 145, yy - 11 + offset * 22
                scale = 270 / maximum
                label = (f'{median:,.1f} B/op' if allocations else f'{median:,.1f} MiB' if memory else
                         f'{median/1000:,.2f} µs' if median >= 1000 else f'{median:,.1f} ns')
                parts.extend([f'<rect x="{bx}" y="{by}" width="{median*scale:.2f}" height="14" fill="{colors[engine]}"/>',
                              f'<path d="M{bx+low*scale:.2f},{by+7} H{bx+high*scale:.2f}" stroke="#243746" stroke-width="2"/>',
                              f'<text x="{bx+278:.2f}" y="{by+12}" class="small">{label}</text>'])
    notes = ["3 fresh processes per cell; min/max process whiskers.", "Linear scale within each panel; not confidence intervals.",
             "Interpret: guest JIT disabled; host JVM JIT / native AOT allowed.", "SVM embeds checked ASTs, not guest machine code.",
             "RSS is whole-process high-water memory, not bytes/op.", "One engine executes per process; common native image."]
    if data["schema"] >= 3:
        notes[4] = "Memory: 8,192 compiles or 524,288 evaluations, plus setup."
        if allocations:
            notes[2] = "Heap allocation only; excludes compiler/background threads."
    if data["schema"] == 1:
        notes = ["3 fresh processes per cell; min/max process whiskers.",
                 "Linear scale within each panel; not confidence intervals.",
                 "TruffleCEL evaluation requires last-tier guest JIT.",
                 "SVM embeds checked ASTs, not guest machine code.",
                 "Historical harness initializes both engines per process.",
                 "Historical dataset: no RSS or interpreter measurements."]
    for i, note in enumerate(notes):
        parts.append(f'<text x="{32+(i%2)*584}" y="{chart_bottom+24+(i//2)*23}" class="label">{esc(note)}</text>')
    footer = [f'Captured {data["captured_at"]} · source {data["commit"][:12]} · GraalVM {data["versions"]["graalvm"]}',
              f'{data["host"]["system"]} {data["host"]["machine"]} · {data["host"]["cpu"][:100]}',
              'Shared CI hardware: diagnostic results for these fixtures, not a performance guarantee. Raw samples accompany this SVG.']
    for i, line in enumerate(footer):
        parts.append(f'<text x="32" y="{chart_bottom+124+i*22}" class="small">{esc(line)}</text>')
    parts.append('</svg>')
    path.write_text('\n'.join(parts) + '\n')


def measure(args):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("The full paired suite uses the pinned Linux x86_64 Native Image toolchain")
    args.output.mkdir(parents=True, exist_ok=True)
    before = source_digest()
    labels = ["//benchmarks/comparison:jvm", "//benchmarks/comparison:svm"]
    run(["bazel", "build", "--build_runfile_links", "--remote_download_toplevel", *labels])
    paths = {}
    for runtime, label in zip(("jvm", "svm"), labels):
        candidates = run(["bazel", "cquery", label, "--output=files"], capture_output=True, text=True).stdout.splitlines()
        paths[runtime] = next(ROOT / p for p in candidates if not p.endswith('.jar') and (ROOT / p).is_file() and os.access(ROOT / p, os.X_OK))
        run([str(paths[runtime]), "--smoke"], timeout=180)
    cpu = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                if line.startswith('model name')), platform.processor())
    data = {"schema": 3, "forks": 3, "captured_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "commit": run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip(),
            "dirty": bool(run(["git", "status", "--porcelain"], capture_output=True, text=True).stdout.strip()),
            "sources_sha256": before, "host": {"system": platform.system(), "machine": platform.machine(),
            "cpu": cpu, "kernel": platform.release(), "logical_cpus": os.cpu_count()},
            "versions": {"cel-java": "0.15.0", "graalvm": "25.5.5-elide.24"},
            "svm_binary_sha256": hashlib.sha256(paths["svm"].read_bytes()).hexdigest(),
            "memory_measurement": {"method": "GNU time %M", "unit": "KiB",
                                   "scope": "whole process lifetime", "native_image_layout": "shared two-engine harness",
                                   "workload": "fixed operations; one warmup batch and seven measured batches",
                                   "compile_batch": 1024, "evaluate_batch": 65536,
                                   "calibration": "one escaped 1 MiB array; included in RSS, excluded from bytes/op"},
            "allocation_measurement": {"method": "ThreadMXBean.getThreadAllocatedBytes", "unit": "heap bytes/op",
                                       "scope": "current thread; excludes compiler and background threads"},
            "results": []}
    jobs = list(itertools.product(PANELS, WORKLOADS, ENGINES, range(3)))
    random.Random(20261103).shuffle(jobs)
    for panel, workload, engine, fork in jobs:
        runtime, stage = panel
        print(f'{runtime}/{stage}/{workload}/{engine} fork {fork+1}/3', flush=True)
        measured = []
        for memory in (False, True):
            rss_file = args.output / 'peak-rss.txt'
            rss_file.unlink(missing_ok=True)
            result = run(['/usr/bin/time', '-f', '%M', '-o', str(rss_file),
                          str(paths[runtime]), engine, stage, workload] + (['--memory'] if memory else []),
                         capture_output=True, text=True, timeout=180)
            rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
            if len(rows) != 1:
                raise ValueError("Expected one measurement record")
            row = rows[0]
            if (row['runtime'], row['stage'], row['workload'], row['engine']) != (runtime, stage, workload, engine):
                raise ValueError("Mislabeled measurement")
            row['peak_rss_kib'] = int(rss_file.read_text().strip())
            rss_file.unlink()
            measured.append(row)
        row, memory_row = measured
        for key in ('image_build_ast', 'image_prepare_ns', 'guest_compiled', 'interpreter'):
            if row[key] != memory_row[key]:
                raise ValueError("Memory process used a different execution tier")
        row['fork'] = fork
        row['timed_peak_rss_kib'] = row['peak_rss_kib']
        for key in ('peak_rss_kib', 'allocated_bytes_per_op', 'memory_operations_per_sample'):
            row[key] = memory_row[key]
        data['results'].append(row)
        (args.output / 'comparison.partial.json').write_text(json.dumps(data, indent=2) + '\n')
    if source_digest() != before:
        raise ValueError("Benchmark sources changed during measurement")
    validate(data)
    (args.output / 'comparison.json').write_text(json.dumps(data, indent=2) + '\n')
    render(data, args.output / 'comparison.svg')
    render(data, args.output / 'peak-rss.svg', 'peak-rss')
    render(data, args.output / 'allocations.svg', 'allocations')
    (args.output / 'comparison.partial.json').unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    m = sub.add_parser('run')
    m.add_argument('--output', type=Path, default=ROOT / 'build/comparison')
    r = sub.add_parser('render')
    r.add_argument('input', type=Path)
    r.add_argument('output', type=Path)
    r.add_argument('--metric', choices=['time', 'peak-rss', 'allocations'], default='time')
    args = parser.parse_args()
    if args.command == 'run':
        measure(args)
    else:
        render(json.loads(args.input.read_text()), args.output, args.metric)


if __name__ == '__main__':
    main()
