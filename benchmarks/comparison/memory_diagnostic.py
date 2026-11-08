#!/usr/bin/env python3
"""Paired native process phase snapshots; profiling and timing use separate processes."""

import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import selectors
import statistics
import subprocess
import sys
import time

VARIANTS = {
    "default": [],
    "shared-compiler": ["-XX:-CompileInIsolates"],
    "bounded-compiler": ["-XX:CompilationIsolateOptions=-Xmx16m"],
    "no-graph-cache": ["-Dpolyglot.compiler.EncodedGraphCache=false"],
    "release-free-chunks": ["-XX:MaxHeapFree=1m"],
    "shared-release-free-chunks": ["-XX:-CompileInIsolates", "-XX:MaxHeapFree=1m"],
    "bounded-release-free-chunks": ["-XX:CompilationIsolateOptions=-Xmx16m", "-XX:MaxHeapFree=1m"],
    "shared-free-32m": ["-XX:-CompileInIsolates", "-XX:MaxHeapFree=32m"],
}
ENGINES = ["trufflecel", "truffle-standalone", "truffle-interpreter", "cel-java"]
WORKLOADS = ["scalar", "policy", "comprehension"]
PHASES = ["baseline", "context", "prepared", "first_results", "warmup", "compiled",
          "evaluated", "application_gc", "idle", "idle_application_gc", "context_closed_gc"]
HEADER = re.compile(r"^[0-9a-f]+-[0-9a-f]+\s+(\S+)\s+\S+\s+\S+\s+\d+(?:\s+(.*))?$")


def kb_fields(text):
    return {key: int(value) * 1024 for key, value in
            re.findall(r"^(\w+):\s+(\d+) kB$", text, re.MULTILINE)}


def mapping_totals(text):
    """Classify mappings by backing and permissions, never infer compiler-isolate ownership."""
    totals = {}
    category = None
    for line in text.splitlines():
        match = HEADER.match(line)
        if match:
            permissions, name = match.groups()
            backing = "file" if name and not name.startswith("[") else "anonymous"
            category = backing + ("_executable" if "x" in permissions else "_data")
        elif category and line.startswith("Rss:"):
            totals[category] = totals.get(category, 0) + int(line.split()[1]) * 1024
    return totals


def snapshot(pid, directory, phase):
    proc = Path("/proc") / str(pid)
    result = {}
    for name in ("status", "smaps_rollup", "smaps"):
        content = (proc / name).read_text()
        with gzip.open(directory / (phase + "." + name + ".gz"), "wt") as output:
            output.write(content)
        if name == "smaps":
            result["mapping_rss"] = mapping_totals(content)
        else:
            result[name] = kb_fields(content)
    return result


def run_profile(command, directory):
    directory.mkdir(parents=True)
    phases = []
    with (directory / "stderr.txt").open("w+") as stderr:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=stderr, text=True, bufsize=1)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                for expected in PHASES:
                    if not selector.select(timeout=180):
                        raise TimeoutError("checkpoint timed out: " + expected)
                    line = process.stdout.readline()
                    if not line:
                        raise RuntimeError("process ended before checkpoint " + expected)
                    value = json.loads(line)
                    if value["phase"] != expected:
                        raise ValueError("unexpected phase: " + line)
                    value.update(snapshot(process.pid, directory, expected))
                    phases.append(value)
                    (directory / "phases.json").write_text(json.dumps(phases, indent=2) + "\n")
                    process.stdin.write("\n")
                    process.stdin.flush()
            stdout, _ = process.communicate(timeout=180)
            if process.returncode or stdout.strip():
                raise RuntimeError("unexpected process completion: " + stdout)
        except BaseException:
            process.kill()
            process.wait()
            stderr.seek(0)
            print(stderr.read(), file=sys.stderr)
            raise
        finally:
            process.stdin.close()
            process.stdout.close()
    return phases


def selected(name, values, defaults=None):
    result = os.environ.get(name, ",".join(values if defaults is None else defaults)).split(",")
    if not result or len(set(result)) != len(result) or any(x not in values for x in result):
        raise ValueError("invalid selection for " + name)
    return result


def main():
    binary, output, forks, idle_ms = sys.argv[1:]
    binary = str(Path(binary).resolve())
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    forks, idle_ms = int(forks), int(idle_ms)
    if forks < 1 or idle_ms < 0:
        raise ValueError("invalid forks or idle duration")
    variants = selected("MEMORY_VARIANTS", VARIANTS, ["default", "shared-compiler", "bounded-compiler"])
    engines = selected("MEMORY_ENGINES", ENGINES, ["trufflecel", "cel-java"])
    workloads = selected("MEMORY_WORKLOADS", WORKLOADS)
    cpuinfo = Path("/proc/cpuinfo").read_text()
    model = re.search(r"^model name\s*:\s*(.+)$", cpuinfo, re.MULTILINE)
    artifacts = json.loads(Path("maven_install.json").read_text())["artifacts"]
    report = {
        "schema": 2,
        "metadata": {
            "source_revision": os.environ.get("MEMORY_SOURCE_REVISION", "unrecorded"),
            "graalvm": artifacts["org.graalvm.truffle:truffle-runtime"]["version"],
            "truffledata": artifacts["dev.elide:truffledata-core"]["version"],
            "driver_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "binary_sha256": hashlib.sha256(Path(binary).read_bytes()).hexdigest(),
            "binary_bytes": Path(binary).stat().st_size,
            "kernel": platform.release(), "cpu_model": model.group(1) if model else "unknown",
            "affinity": sorted(os.sched_getaffinity(0)), "forks": forks, "idle_ms": idle_ms,
            "variants": {name: VARIANTS[name] for name in variants},
            "engines": engines, "workloads": workloads,
            "common_runtime_flags": ["-Xmx512m"],
            "native_image": "serial GC, optimization 3, combined executable containing both engines",
            "timing": "separate process; fixed 524288 evaluation warmup; seven >=150ms samples",
            "notes": "smaps_rollup.Rss is current resident process pages; status.VmHWM is lifetime peak. MemoryMXBean heap counters describe application isolate, not compiler isolate. Native .27 non-heap usage counts RuntimeCodeInfoMemory (generated code and metadata). Application System.gc does not collect a separate compiler isolate. Mapping categories do not establish isolate ownership. Forced GC and idle phases are diagnostics, not deployment defaults. Closing the Polyglot context does not necessarily dispose the standalone target's fallback engine. truffle-standalone means the context-free Java API within the same combined executable, not a standalone binary.",
        },
        "records": [],
    }
    path = output / "report.json"
    for fork in range(forks):
        # Alternate order to reduce systematic placement bias, keeping every pair in one action.
        for variant in variants[::1 if fork % 2 == 0 else -1]:
            for workload in workloads:
                for engine in engines[::1 if fork % 2 == 0 else -1]:
                    key = f"{fork}-{variant}-{workload}-{engine}"
                    common = [binary, "-Xmx512m", *VARIANTS[variant], engine, workload]
                    start = time.monotonic()
                    phases = run_profile([*common, "true", str(idle_ms)], output / key)
                    measured = subprocess.run([*common, "false", "0"], capture_output=True,
                                              text=True, timeout=180)
                    (output / key / "timing.stderr.txt").write_text(measured.stderr)
                    (output / key / "timing.stdout.txt").write_text(measured.stdout)
                    measured.check_returncode()
                    timing = json.loads(measured.stdout)
                    if len(timing["samples_ns"]) != 7 or any(x <= 0 for x in timing["samples_ns"]):
                        raise ValueError("invalid timing samples")
                    if engine in ("trufflecel", "truffle-standalone") and not all(p["last_tier"] for p in phases[5:-1]):
                        raise ValueError("last-tier evidence missing")
                    record = {"fork": fork, "variant": variant, "workload": workload,
                              "engine": engine, "phases": phases, **timing}
                    report["records"].append(record)
                    path.write_text(json.dumps(report, indent=2) + "\n")
                    end = phases[6]
                    print(f"{key}: evaluated RSS {end['smaps_rollup']['Rss'] / 1048576:.2f} MiB, "
                          f"time {statistics.median(timing['samples_ns']):.1f} ns, "
                          f"elapsed {time.monotonic() - start:.1f}s", flush=True)
    report["complete"] = len(report["records"]) == forks * len(variants) * len(engines) * len(workloads)
    path.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
