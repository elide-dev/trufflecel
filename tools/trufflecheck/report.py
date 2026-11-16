#!/usr/bin/env python3
# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Builds every Java target with --config=trufflecheck_report and writes a Markdown baseline of the findings.

  bazel run --config=local //tools/trufflecheck:report [-- --output docs/TRUFFLECHECK_BASELINE.md] [--log <file>]

Two sequential local builds:
  1. the configured profile (Error Prone defaults + trufflecheck + NullAway), errors downgraded to warnings;
  2. a NullAway probe treating dev.trufflecel as annotated, which sizes the nullness work before any @NullMarked lands.
Each build passes a unique, inert -XepOpt so compile actions rerun: Bazel does not replay cached actions' warnings.
"""
import argparse
import collections
import datetime
import json
import os
import pathlib
import re
import subprocess
import sys

DIAGNOSTIC = re.compile(r"^(?P<path>[^\s:][^:]*\.java):(?P<line>\d+): (?P<kind>warning|error): "
                        r"\[(?P<check>[^\]]+)\] (?P<message>.*)$")
TARGETS = 'kind("^java_(library|binary|test) rule", //...)'
PROBE = ["--javacopt=-XepOpt:NullAway:OnlyNullMarked=false",
         "--javacopt=-XepOpt:NullAway:AnnotatedPackages=dev.trufflecel"]
AREAS = [("src/main/", "main"), ("src/test/", "test"), ("benchmarks/", "benchmarks"), ("tools/", "tools")]
NULLNESS = {"NullAway", "RequireExplicitNullMarking", "ReturnMissingNullable", "FieldMissingNullable",
            "EqualsMissingNullable", "VoidMissingNullable", "RedundantNullCheck", "ParameterMissingNullable"}
SEVERITY_ORDER = {"ERROR": 0, "WARN": 1, "OFF": 2}


def bazel(workspace, *args, capture=True):
    command = ["bazelisk", *args]
    print("+", " ".join(command[:6]), "..." if len(command) > 6 else "", file=sys.stderr)
    return subprocess.run(command, cwd=workspace, text=True, capture_output=capture, check=False)


def build(workspace, targets, run, extra):
    nonce = "--javacopt=-XepOpt:TrufflecheckReport:Run=%s" % run
    result = bazel(workspace, "build", "--config=local", "--config=trufflecheck_report", nonce, *extra, "--",
                   *targets)
    return result.returncode, result.stdout + result.stderr


def parse(log):
    findings = {}
    lines = log.splitlines()
    for index, line in enumerate(lines):
        match = DIAGNOSTIC.match(line)
        # Generated sources: Error Prone already skips them (-XepExcludedPaths); this also drops javac's own lints.
        if not match or match["path"].startswith("bazel-out/"):
            continue
        snippet = lines[index + 1].strip() if index + 1 < len(lines) else ""
        key = (match["path"], int(match["line"]), match["check"], match["message"])
        findings[key] = {"path": match["path"], "line": int(match["line"]), "check": match["check"],
                         "message": match["message"].strip(), "snippet": snippet, "kind": match["kind"]}
    return list(findings.values())


def severities(workspace):
    """Each check's effective severity under --config=trufflecheck (the last -Xep flag wins)."""
    result = {"NullAway": "ERROR"}
    rc = (workspace / "tools/trufflecheck/errorprone.bazelrc").read_text(encoding="utf-8")
    for match in re.finditer(r"^build:trufflecheck --javacopt=-Xep:([A-Za-z]+):([A-Z]+)$", rc, re.MULTILINE):
        result[match[1]] = match[2]
    return result


def area(path):
    for prefix, name in AREAS:
        if path.startswith(prefix):
            return name
    return "other"


def source(check):
    if check.startswith("Truffle"):
        return "trufflecheck"
    if check in NULLNESS:
        return "nullness"
    if check.islower():
        return "javac lint"
    return "Error Prone"


def priority(finding, severity):
    """P1: trufflecheck errors in shipped code; P2: other errors in shipped code; P3: shipped-code warnings;
    P4: tests, benchmarks and tools."""
    if area(finding["path"]) != "main":
        return 4
    if severity == "ERROR":
        return 1 if finding["check"].startswith("Truffle") else 2
    return 3


def link(path, line=None):
    target = "../" + path + ("#L%d" % line if line else "")
    return "[%s](%s)" % (path if line is None else line, target)


def common_message(items):
    return collections.Counter(item["message"] for item in items).most_common(1)[0][0]


def render(findings, probe, severity, meta):
    out = []
    emit = out.append
    by_check = collections.defaultdict(list)
    for finding in findings:
        by_check[finding["check"]].append(finding)

    def sev(check):
        # Unlisted checks are Bazel's always-on Error Prone errors; javac lints are warnings.
        return "WARN" if check.islower() else severity.get(check, "ERROR")

    def check_priority(check):
        return min(priority(item, sev(check)) for item in by_check[check])

    ordered = sorted(by_check, key=lambda c: (check_priority(c), SEVERITY_ORDER.get(sev(c), 3), -len(by_check[c]), c))
    areas = [name for _, name in AREAS] + ["other"]
    totals = collections.Counter(area(item["path"]) for item in findings)

    emit("# trufflecheck baseline")
    emit("")
    emit("Generated by `bazel run --config=local //tools/trufflecheck:report` on %s at `%s`. Do not edit by hand:"
         % (meta["date"], meta["commit"]))
    emit("fix or suppress findings, then regenerate. Counts should only go down.")
    emit("")
    emit("| | |")
    emit("| :--- | :--- |")
    emit("| Rules | trufflecheck %s, NullAway %s, Error Prone %s (Bazel java_tools) |"
         % (meta["trufflecheck"], meta["nullaway"], meta["errorprone"]))
    emit("| Profile | `tools/trufflecheck/errorprone.bazelrc` (`--config=trufflecheck`), policy "
         "`tools/trufflecheck/trufflecheck.properties` |")
    emit("| Scope | %d Java targets (`%s`); Bazel-generated sources excluded |" % (meta["targets"], TARGETS))
    emit("| Findings | **%d** (%s) |" % (len(findings), ", ".join("%s %d" % (a, totals[a]) for a in areas if totals[a])))
    emit("| NullAway probe | **%d** findings if `dev.trufflecel` were `@NullMarked` today |" % len(probe))
    if meta["failures"]:
        emit("| Build | ⚠️ %s |" % meta["failures"])
    emit("")
    emit("Severity is what `--config=trufflecheck` enforces (this report downgrades errors to warnings so every target")
    emit("compiles). Priority: **P1** trufflecheck errors in `src/main`; **P2** other errors in `src/main`; **P3**")
    emit("warnings in `src/main`; **P4** tests, benchmarks and tools. A finding is resolved by fixing it, or by a")
    emit("narrow `@SuppressWarnings(\"<Check>\")` with a comment saying why the rule's premise does not hold there.")
    emit("")
    emit("## Summary")
    emit("")
    emit("| Check | Source | Severity | Priority | " + " | ".join(areas[:-1]) + " | Total |")
    emit("| :--- | :--- | :--- | :---: | " + " | ".join("---:" for _ in areas[:-1]) + " | ---: |")
    for check in ordered:
        counts = collections.Counter(area(item["path"]) for item in by_check[check])
        emit("| [`%s`](#%s) | %s | %s | P%d | %s | %d |" % (
            check, check.lower(), source(check), sev(check), check_priority(check),
            " | ".join(str(counts[a]) if counts[a] else "" for a in areas[:-1]), len(by_check[check])))
    emit("")

    emit("## NullAway probe")
    emit("")
    emit("NullAway runs in JSpecify mode on `@NullMarked` code only, and nothing is `@NullMarked` yet, so the profile")
    emit("reports no `NullAway` findings. This probe reruns it with `AnnotatedPackages=dev.trufflecel` to size the work.")
    emit("Adopting it means adding `org.jspecify:jspecify` as a dependency, then `@NullMarked` package by package.")
    emit("")
    if probe:
        emit("| File | Area | Findings |")
        emit("| :--- | :--- | ---: |")
        for path, count in sorted(collections.Counter(item["path"] for item in probe).items(),
                                  key=lambda pair: (-pair[1], pair[0])):
            emit("| %s | %s | %d |" % (link(path), area(path), count))
        kinds = collections.Counter(re.sub(r"'[^']*'|`[^`]*`", "…", item["message"]).split(" (")[0][:90]
                                    for item in probe)
        emit("")
        emit("<details><summary>Most common NullAway messages</summary>")
        emit("")
        for message, count in kinds.most_common(15):
            emit("- %d × %s" % (count, message.replace("|", "\\|")))
        emit("")
        emit("</details>")
    else:
        emit("No findings.")
    emit("")

    emit("## Findings")
    for check in ordered:
        items = by_check[check]
        emit("")
        emit("### `%s`" % check)
        emit("")
        doc = ("https://github.com/elide-dev/trufflecheck#%s" % check.lower() if check.startswith("Truffle")
               else "https://github.com/uber/NullAway/wiki" if check in ("NullAway", "RequireExplicitNullMarking")
               else None if check.islower() else "https://errorprone.info/bugpattern/%s" % check)
        emit("%s · %s · P%d · %d finding%s%s" % (source(check), sev(check), check_priority(check), len(items),
                                                 "" if len(items) == 1 else "s", " · [docs](%s)" % doc if doc else ""))
        emit("")
        common = common_message(items)
        varied = any(item["message"] != common for item in items)
        # When messages vary, their first sentence names the call or field; the rest is the rule's shared advice.
        advice = common.split(". ", 1)[-1] if varied and ". " in common else common
        emit("> " + advice.replace("\n", " "))
        emit("")
        by_file = collections.defaultdict(list)
        for item in items:
            by_file[item["path"]].append(item)
        for path in sorted(by_file, key=lambda p: (priority(by_file[p][0], sev(check)), p)):
            entries = sorted(by_file[path], key=lambda item: (item["line"], item["message"]))
            lines = ", ".join(dict.fromkeys(link(path, item["line"]) for item in entries))
            emit("- [ ] `%s` (%s): %s" % (path, area(path), lines))
            # Varied messages (which call, which field) are the useful detail; otherwise show short groups' code.
            if varied or len(entries) <= 3:
                for item in entries:
                    detail = item["message"].split(". ")[0] if varied else item["snippet"]
                    if detail:
                        emit("  - L%d: `%s`" % (item["line"], detail.replace("`", "'")[:160]))
    emit("")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="docs/TRUFFLECHECK_BASELINE.md")
    parser.add_argument("--log", help="also write the raw build logs here (prefix)")
    parser.add_argument("--from-logs", help="re-render from logs written by --log (prefix) instead of building")
    options = parser.parse_args()
    workspace = pathlib.Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", ".")).resolve()

    targets = bazel(workspace, "query", "--config=local", TARGETS).stdout.split()
    if options.from_logs:
        code = probe_code = 0
        log = pathlib.Path(options.from_logs + ".profile.log").read_text(encoding="utf-8")
        probe_log = pathlib.Path(options.from_logs + ".probe.log").read_text(encoding="utf-8")
    else:
        run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
        code, log = build(workspace, targets, run, [])
        probe_code, probe_log = build(workspace, targets, run + "-probe", PROBE)
    if options.log and not options.from_logs:
        pathlib.Path(options.log + ".profile.log").write_text(log, encoding="utf-8")
        pathlib.Path(options.log + ".probe.log").write_text(probe_log, encoding="utf-8")
    failures = []
    for name, rc, text in (("profile", code, log), ("probe", probe_code, probe_log)):
        if rc != 0:
            errors = [line for line in text.splitlines() if line.startswith("ERROR:")][:3]
            failures.append("%s build exited %d: %s" % (name, rc, "; ".join(errors)))
            print("\n".join(errors), file=sys.stderr)

    pins = json.loads((workspace / "trufflecheck_install.json").read_text(encoding="utf-8"))["artifacts"]
    rc_text = (workspace / "tools/trufflecheck/errorprone-default-warnings.txt").read_text(encoding="utf-8")
    meta = {
        "date": datetime.date.today().isoformat(),
        "commit": subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=workspace, text=True,
                                 capture_output=True).stdout.strip(),
        "trufflecheck": pins["dev.elide:trufflecheck-errorprone"]["version"],
        "nullaway": pins["com.uber.nullaway:nullaway"]["version"],
        "errorprone": (re.search(r"Error Prone (\S+)", rc_text) or [None, "?"])[1],
        "targets": len(targets),
        "failures": "; ".join(failures),
    }
    findings = parse(log)
    probe = [item for item in parse(probe_log) if item["check"] == "NullAway"]
    output = workspace / options.output
    output.write_text(render(findings, probe, severities(workspace), meta), encoding="utf-8")
    print("wrote %s: %d findings, %d NullAway probe findings" % (options.output, len(findings), len(probe)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
