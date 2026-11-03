#!/usr/bin/env python3
"""Build with Bazel/RBE, measure locally under CodSpeed, fail on incomplete regions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build/simulation"


def run(*args, **kwargs):
    return subprocess.run(*args, cwd=ROOT, check=True, **kwargs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    names = run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--",
                 "MODULE.bazel", "MODULE.bazel.lock", "maven_install.json", ".bazelversion", ".bazelrc",
                 "bazel", "src/main", "benchmarks", "tools/bench"],
                capture_output=True).stdout.split(b"\0")
    result = hashlib.sha256()
    for name in sorted(set(names) - {b""}):
        path = ROOT / os.fsdecode(name)
        if path.is_file():
            result.update(name + b"\0" + path.read_bytes() + b"\0")
    return result.hexdigest()


def instructions(folder, uri):
    """Ignore startup/warmup/shutdown; demand one positive measured region."""
    matches = []
    for path in folder.glob("*.out"):
        selected = False
        events = []
        for line in path.read_text().splitlines():
            if line.startswith("part:"):
                selected = False
            elif line.startswith("desc: Trigger:"):
                selected = line == f"desc: Trigger: Client Request: {uri}"
            elif line.startswith("events:"):
                events = line.split()[1:]
            elif selected and line.startswith("totals:"):
                values = list(map(int, line.split()[1:]))
                if len(values) != len(events) or "Ir" not in events:
                    raise ValueError("Malformed instruction counters")
                matches.append(dict(zip(events, values))["Ir"])
    if len(matches) != 1 or matches[0] <= 0:
        raise ValueError(f"Expected one positive instruction count for {uri}; got {matches}")
    return matches[0]


def prepare():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "prepared.json").unlink(missing_ok=True)
    source_digest = inputs()
    labels = ["//benchmarks:simulation", "//benchmarks:native_debug"]
    run(["bazel", "build", *labels])
    result = run(["bazel", "cquery", "set(" + " ".join(labels) + ")", "--output=files"],
                 capture_output=True, text=True)
    for name in result.stdout.splitlines():
        source = ROOT / name
        shutil.copy2(source, OUTPUT / source.name)
    binary = OUTPUT / "trufflecel-simulation"
    if not binary.is_file():
        raise ValueError("Bazel did not produce simulation binary")
    binary.chmod(0o755)
    if inputs() != source_digest:
        raise ValueError("Inputs changed during native build; prepare again")
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    manifest = {"sources_sha256": source_digest, "binary_sha256": digest(binary),
                "cases_sha256": digest(ROOT / "benchmarks/cases.json"),
                "commit": commit.stdout.strip() if commit.returncode == 0 else None,
                "platform": "linux-amd64", "mode": "native-aot", "build_system": "bazel"}
    (OUTPUT / "prepared.json").write_text(json.dumps(manifest, indent=2) + "\n")


def execute(smoke):
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise ValueError("Simulation image requires Linux x86_64")
    binary = OUTPUT / "trufflecel-simulation"
    manifest = json.loads((OUTPUT / "prepared.json").read_text())
    if inputs() != manifest["sources_sha256"] or digest(binary) != manifest["binary_sha256"] or digest(ROOT / "benchmarks/cases.json") != manifest["cases_sha256"]:
        raise ValueError("Prepared benchmark changed; prepare again")
    env = dict(os.environ)
    if smoke:
        run([str(binary), "--smoke"], env=env, timeout=300)
        run([str(binary), "evaluate-scalar"], env=env, timeout=300)
        rejected = subprocess.run([str(binary), "evaluate-scalar"], cwd=ROOT,
                                  env={**env, "TRUFFLECEL_REQUIRE_CODSPEED": "1"},
                                  capture_output=True, text=True, timeout=300)
        if rejected.returncode == 0 or "CodSpeed hook failed" not in rejected.stderr:
            raise ValueError("Native hooks did not reject uninstrumented measurement")
        return
    if env.get("CODSPEED_RUNNER_MODE") not in ("simulation", "instrumentation") or not env.get("CODSPEED_PROFILE_FOLDER"):
        raise ValueError("Simulation requires the CodSpeed runner and profile folder")
    env["TRUFFLECEL_REQUIRE_CODSPEED"] = "1"
    cases = json.loads((ROOT / "benchmarks/cases.json").read_text())
    summaries = {}
    for case in cases:
        name = case["name"]
        uri = f"benchmarks/cases.json::native-aot/{name}[iterations=256]"
        with (OUTPUT / f"{name}.log").open("w") as log:
            run([str(binary), name], env=env, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        rows = [json.loads(line) for line in (OUTPUT / f"{name}.log").read_text().splitlines() if line.startswith("{")]
        if len(rows) != 1 or rows[0] != {"case": name, "iterations": 256, "checksum": case["checksum"]}:
            raise ValueError(f"Missing/mismatched correctness result: {name}: {rows}")
        count = instructions(Path(env["CODSPEED_PROFILE_FOLDER"]), uri)
        summaries[uri] = {"instructions": count, "instructions_per_operation": count / 256, **rows[0]}
        print(f"{name}: {count} instructions ({count / 256:.2f}/operation)", flush=True)
    (OUTPUT / "results.json").write_text(json.dumps(summaries, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "smoke", "run"])
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
    else:
        execute(args.action == "smoke")
