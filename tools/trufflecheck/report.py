#!/usr/bin/env python3
# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Builds every Java target with --config=trufflecheck_report and reports the findings as Markdown and/or SARIF.

  bazel run --config=local //tools/trufflecheck:report                  # refresh docs/TRUFFLECHECK_BASELINE.md
  tools/trufflecheck/report.py --remote --no-probe --output '' --sarif build/reports/trufflecheck.sarif   # CI

Two sequential builds:
  1. the configured profile (Error Prone defaults + trufflecheck + NullAway), errors downgraded to warnings;
  2. unless --no-probe, a NullAway probe treating dev.trufflecel as annotated, which sizes the nullness work before
     any @NullMarked lands. Probe findings are hypothetical, so they appear only in the Markdown, never in SARIF.
Each build passes a unique, inert -XepOpt so compile actions rerun: Bazel does not replay cached actions' warnings.
Builds use --config=local unless --remote, which keeps the configured (RBE or user.bazelrc) execution.
"""
import argparse
import collections
import datetime
import json
import os
import pathlib
import re
import shutil
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
SARIF_LEVEL = {"ERROR": "error", "WARN": "warning"}


def bazel(workspace, *args):
    command = [shutil.which("bazelisk") or "bazel", *args]
    print("+", " ".join(command[:6]), "..." if len(command) > 6 else "", file=sys.stderr)
    return subprocess.run(command, cwd=workspace, text=True, capture_output=True, check=False)


def build(workspace, configs, targets, run, extra):
    nonce = "--javacopt=-XepOpt:TrufflecheckReport:Run=%s" % run
    result = bazel(workspace, "build", *configs, "--config=trufflecheck_report", nonce, *extra, "--", *targets)
    return result.returncode, result.stdout + result.stderr


def parse(log):
    findings = {}
    lines = log.splitlines()
    for index, line in enumerate(lines):
        match = DIAGNOSTIC.match(line)
        # Generated sources: Error Prone already skips them (-XepExcludedPaths); this also drops javac's own lints.
        if not match or match["path"].startswith("bazel-out/"):
            continue
        snippet = lines[index + 1] if index + 1 < len(lines) else ""
        caret = lines[index + 2] if index + 2 < len(lines) else ""
        # javac echoes the source line verbatim, then a caret under the reported position.
        column = caret.index("^") + 1 if caret.strip() == "^" else None
        key = (match["path"], int(match["line"]), match["check"], match["message"])
        findings[key] = {"path": match["path"], "line": int(match["line"]), "column": column,
                         "check": match["check"], "message": match["message"].strip(), "snippet": snippet.strip(),
                         "kind": match["kind"]}
    return list(findings.values())


def severities(workspace):
    """Each check's effective severity under --config=trufflecheck (the last -Xep flag wins)."""
    result = {"NullAway": "ERROR"}
    rc = (workspace / "tools/trufflecheck/errorprone.bazelrc").read_text(encoding="utf-8")
    for match in re.finditer(r"^build:trufflecheck --javacopt=-Xep:([A-Za-z]+):([A-Z]+)$", rc, re.MULTILINE):
        result[match[1]] = match[2]
    return result


def effective(check, severity):
    """Severity under --config=trufflecheck. Unlisted checks are Bazel's always-on Error Prone errors; javac lints
    (lowercase keys such as deprecation) are warnings."""
    return "WARN" if check.islower() else severity.get(check, "ERROR")


def doc_url(check):
    if check.startswith("Truffle"):
        return "https://github.com/elide-dev/trufflecheck#%s" % check.lower()
    if check in ("NullAway", "RequireExplicitNullMarking"):
        return "https://github.com/uber/NullAway/wiki"
    return None if check.islower() else "https://errorprone.info/bugpattern/%s" % check


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
        return effective(check, severity)

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
    if meta.get("probe", True):
        emit("| NullAway probe | **%d** findings if `dev.trufflecel` were `@NullMarked` today |" % len(probe))
    else:
        emit("| NullAway probe | not run (`--no-probe`) |")
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
        doc = doc_url(check)
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


def sarif(findings, severity, meta):
    """SARIF 2.1.0 for GitHub code scanning: one run, one rule per check, levels from the enforced profile."""
    checks = sorted({item["check"] for item in findings})
    index = {check: position for position, check in enumerate(checks)}
    rules = []
    for check in checks:
        level = SARIF_LEVEL.get(effective(check, severity), "warning")
        advice = common_message([item for item in findings if item["check"] == check])
        rule = {
            "id": check,
            "name": check,
            "shortDescription": {"text": check},
            "fullDescription": {"text": advice},
            "defaultConfiguration": {"level": level},
            "properties": {"tags": [source(check)], "precision": "high" if check.startswith("Truffle") else "medium",
                           # GitHub ranks alerts by this; map the profile's errors above its warnings.
                           "problem.severity": "error" if level == "error" else "warning"},
        }
        if doc_url(check):
            rule["helpUri"] = doc_url(check)
        rules.append(rule)
    results = []
    for item in sorted(findings, key=lambda f: (f["path"], f["line"], f["check"], f["message"])):
        region = {"startLine": item["line"]}
        if item["column"]:
            region["startColumn"] = item["column"]
        results.append({
            "ruleId": item["check"],
            "ruleIndex": index[item["check"]],
            "level": SARIF_LEVEL.get(effective(item["check"], severity), "warning"),
            "message": {"text": item["message"]},
            "locations": [{"physicalLocation": {
                "artifactLocation": {"uri": item["path"], "uriBaseId": "%SRCROOT%"},
                "region": region,
            }}],
            "properties": {"priority": "P%d" % priority(item, effective(item["check"], severity)),
                           "area": area(item["path"])},
        })
    return {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": [{
            "tool": {"driver": {
                "name": "trufflecheck",
                "fullName": "Error Prone %s with trufflecheck %s and NullAway %s"
                            % (meta["errorprone"], meta["trufflecheck"], meta["nullaway"]),
                "version": meta["trufflecheck"],
                "informationUri": "https://github.com/elide-dev/trufflecheck",
                "rules": rules,
            }},
            "automationDetails": {"id": "trufflecheck/"},
            "results": results,
        }],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="docs/TRUFFLECHECK_BASELINE.md", help="Markdown report; '' to skip")
    parser.add_argument("--sarif", help="also write SARIF 2.1.0 here (relative to the workspace)")
    parser.add_argument("--no-probe", action="store_true", help="skip the NullAway probe build")
    parser.add_argument("--remote", action="store_true", help="keep the configured execution, not --config=local")
    parser.add_argument("--log", help="also write the raw build logs here (prefix)")
    parser.add_argument("--from-logs", help="re-render from logs written by --log (prefix) instead of building")
    options = parser.parse_args()
    workspace = pathlib.Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", ".")).resolve()

    configs = [] if options.remote else ["--config=local"]
    query = bazel(workspace, "query", *configs, TARGETS)
    targets = query.stdout.split()
    if query.returncode != 0 or not targets:
        print(query.stderr, file=sys.stderr)
        return 1
    probe_log, probe_code = "", 0
    if options.from_logs:
        code = 0
        log = pathlib.Path(options.from_logs + ".profile.log").read_text(encoding="utf-8")
        probe_path = pathlib.Path(options.from_logs + ".probe.log")
        probe_log = "" if options.no_probe or not probe_path.exists() else probe_path.read_text(encoding="utf-8")
    else:
        run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
        code, log = build(workspace, configs, targets, run, [])
        if not options.no_probe:
            probe_code, probe_log = build(workspace, configs, targets, run + "-probe", PROBE)
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
        "probe": bool(probe_log),
    }
    findings = parse(log)
    probe = [item for item in parse(probe_log) if item["check"] == "NullAway"]
    severity = severities(workspace)
    if options.output:
        (workspace / options.output).write_text(render(findings, probe, severity, meta), encoding="utf-8")
        print("wrote %s: %d findings, %d NullAway probe findings" % (options.output, len(findings), len(probe)))
    if options.sarif:
        path = workspace / options.sarif
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(sarif(findings, severity, meta), indent=1) + "\n", encoding="utf-8")
        print("wrote %s: %d results" % (options.sarif, len(findings)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
