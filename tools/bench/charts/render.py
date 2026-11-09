#!/usr/bin/env python3
# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Render the immutable 0.10.0 measurements using Elide's design tokens."""

import hashlib
import itertools
import json
import math
import tempfile
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from matplotlib.font_manager import FontProperties
from matplotlib.patches import FancyBboxPatch, Rectangle

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = ROOT / "docs/benchmarks/release-0.10.0-20261109/summary.json"
OUTPUT = ROOT / "docs/images/benchmark-0.10.0-elide"
ENGINES = ("truffle-interpreter", "truffle-jit", "cel-java", "cel-java-optimized")
LABELS = ("TruffleCEL interpreter", "TruffleCEL guest JIT", "cel-java default", "cel-java optimized")
WORKLOADS = ("scalar", "scalar-wide", "proto-policy", "proto-repeated", "map-regex", "optimizer-policy")
TITLES = ("Scalar", "Wide activation", "Protobuf policy", "Repeated protobuf", "Map + regex", "Optimizer policy")
RUNTIMES = ("jvm", "svm")

# elide-dev/components cc312c76a7a6cce8c6277e3cf94c73686994d5a2,
# packages/tokens/src/tokens.css: dark surfaces, royal purple, status emphasis.
BG, CARD, BORDER = "#0a0a0a", "#0e0e11", "#1e1e22"
TEXT, MUTED = "#fafcfa", "#9a9a9a"
PURPLE, PALE, SUCCESS, WARNING = "#8b5cf6", "#c4b5fd", "#3fb950", "#e0a020"
COLORS = (PURPLE, PALE, "#9a9a9a", "#525252")
METRICS = (
    ("time_ns", "time_forks_ns", "Evaluation time", "ns/op", "evaluation-time"),
    ("allocation_bytes", "allocation_forks_bytes", "Allocation per evaluation", "B/op", "evaluation-allocation"),
    ("rss_mib", "rss_forks_mib", "Process memory", "MiB", "evaluation-rss"),
    ("peak_rss_mib", "peak_rss_forks_mib", "Peak process memory", "MiB", "evaluation-peak-rss"),
)
WIDTH, HEIGHT = 1280, 1780


def load_cells():
    source = json.loads(SOURCE.read_text())
    cells = {(c["runtime"], c["engine"], c["workload"]): c for c in source["cells"]}
    expected = set(itertools.product(RUNTIMES, ENGINES, WORKLOADS))
    if set(cells) != expected or len(source["cells"]) != len(expected):
        raise ValueError("Expected exactly 48 distinct ordinary-map cells")
    for cell in cells.values():
        for metric, forks, *_ in METRICS:
            values = [cell[metric], *cell[forks]]
            if len(cell[forks]) != 3 or not all(math.isfinite(v) and v > 0 for v in values):
                raise ValueError(f"Invalid measurements: {cell}")
            if not min(cell[forks]) <= cell[metric] <= max(cell[forks]):
                raise ValueError("Aggregate falls outside its observed fork range")
    return source, cells


def fonts(directory):
    result = {}
    for role, family, weight in (
        ("body", "inter", 400),
        ("strong", "inter", 600),
        ("display", "instrument-sans", 600),
        ("mono", "jetbrains-mono", 400),
    ):
        font = TTFont(HERE / "fonts" / f"{family}-latin-wght-normal.woff2")
        # Distinct PostScript names prevent SVG glyph IDs from colliding between
        # Inter Regular and SemiBold when Matplotlib embeds both sets of outlines.
        font = instantiateVariableFont(font, {"wght": weight}, inplace=True, updateFontNames=True)
        font.flavor = None
        path = directory / f"{role}.ttf"
        font.save(path)
        result[role] = FontProperties(fname=path)
    return result


def comparison(value, baseline, metric):
    if math.isclose(value, baseline, rel_tol=1e-9):
        return "tied", MUTED
    if metric == "time_ns":
        if value < baseline:
            return f"{baseline / value:.1f}× faster", SUCCESS
        return f"{value / baseline:.1f}× slower", WARNING
    percent = abs(value / baseline - 1) * 100
    return f"{percent:.1f}% {'less' if value < baseline else 'more'}", SUCCESS if value < baseline else WARNING


def totals(source, cells, metric, engines=ENGINES[:2]):
    result = {"lower": 0, "tied": 0, "higher": 0}
    for runtime, workload in itertools.product(RUNTIMES, WORKLOADS):
        baseline = min(cells[runtime, e, workload][metric] for e in ENGINES[2:])
        for engine in engines:
            value = cells[runtime, engine, workload][metric]
            key = "tied" if math.isclose(value, baseline, rel_tol=1e-9) else "lower" if value < baseline else "higher"
            result[key] += 1
    for key, count in result.items():
        recorded = sum(source["counts"][f"{runtime}/{engine}"][metric][key]
                       for runtime, engine in itertools.product(RUNTIMES, engines))
        if count != recorded:
            raise ValueError(f"Computed {metric}/{key} differs from recorded summary")
    return result


def render(source, cells, font, spec, interpreter_only=False):
    metric, forks, title, unit, filename = spec
    if interpreter_only:
        title, filename = "Interpreter evaluation", "interpreter-time"
    fig = plt.figure(figsize=(WIDTH / 100, HEIGHT / 100), dpi=100, facecolor=BG)
    ax = fig.add_axes((0, 0, 1, 1))
    ax.set(xlim=(0, WIDTH), ylim=(HEIGHT, 0))
    ax.axis("off")

    def text(x, y, value, size=16, color=TEXT, role="body", **kwargs):
        return ax.text(x, y, value, fontsize=size * .72, color=color,
                       fontproperties=font[role], va="top", **kwargs)

    def line(x1, y1, x2, y2, color=BORDER, width=1):
        ax.plot((x1, x2), (y1, y2), color=color, linewidth=width, solid_capstyle="butt")

    text(36, 28, "ELIDE  /  TruffleCEL 0.10.0", 16, PALE, "strong")
    text(WIDTH - 36, 28, "BENCHMARKS  ·  09 NOV 2026", 13, MUTED, "mono", ha="right")
    text(36, 64, title, 44, role="display")
    text(36, 123, f"Lower is better  ·  Shorter bars win  ·  {unit}", 19, role="strong")
    text(36, 158, "TruffleData 0.10.0  /  cel-java 0.15.0  /  GraalVM 25.5.5-elide.27", 15, MUTED)

    # Keep interpreter and guest-JIT counts separate: these answer different questions.
    outcomes = []
    for engine, label in zip(ENGINES[:1] if interpreter_only else ENGINES[:2], ("Interpreter", "Guest JIT")):
        mode = totals(source, cells, metric, (engine,))
        outcome = f"{label}: {mode['lower']}/12 lower"
        if mode["tied"]:
            outcome += f" · {mode['tied']} tied"
        if mode["higher"]:
            outcome += f" · {mode['higher']} higher"
        outcomes.append(outcome)
    outcome = "    /    ".join(outcomes)
    text(36, 201, outcome, 21, PALE, "display")
    text(36, 237, "Compared with the lowest cel-java aggregate per fixture. Prepared inputs are a separate scenario.", 15, MUTED)
    line(36, 273, WIDTH - 36, 273)

    for col, runtime in enumerate(RUNTIMES):
        x = 36 + col * 616
        text(x, 294, "JVM" if runtime == "jvm" else "Native Image", 26, role="display")
        text(x, 329, "C2 host compiler" if runtime == "jvm" else "Serial GC", 14, MUTED)
        for row, (workload, name) in enumerate(zip(WORKLOADS, TITLES)):
            y, w, h = 364 + row * 210, 592, 194
            ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=8",
                                       facecolor=CARD, edgecolor=BORDER, linewidth=.8))
            text(x + 16, y + 13, name, 20, role="display")
            text(x + w - 16, y + 17, unit, 13, MUTED, "mono", ha="right")
            rows = [cells[runtime, e, workload] for e in ENGINES]
            baseline = min(r[metric] for r in rows[2:])
            for offset, index, label in ((16, 0, "Interpreter"), (302, 1, "Guest JIT")):
                if interpreter_only and index == 1:
                    continue
                verdict, color = comparison(rows[index][metric], baseline, metric)
                text(x + offset, y + 43, f"{label}: {verdict}", 14, color, "strong")
            # Every card has a linear scale, starting at zero and shared by its four engines.
            # Maximum fork value leaves room for the complete observed range.
            maximum = max(max(r[forks]) for r in rows)
            bar_x, bar_w = x + 185, 286
            line(bar_x, y + 74, bar_x, y + 173)
            for index, (r, label, color) in enumerate(zip(rows, LABELS, COLORS)):
                if interpreter_only and index == 1:
                    continue
                position = index if not interpreter_only or index == 0 else index - 1
                center = y + 84 + position * (36 if interpreter_only else 26)
                text(x + 16, center - 8, label, 14, TEXT if index < 2 else MUTED)
                length = r[metric] / maximum * bar_w
                ax.add_patch(Rectangle((bar_x, center - 6), length, 12, facecolor=color))
                low, high = (bar_x + v / maximum * bar_w for v in (min(r[forks]), max(r[forks])))
                line(low, center, high, center, TEXT, .8)
                if high - low > .5:
                    line(low, center - 3, low, center + 3, TEXT, .8)
                    line(high, center - 3, high, center + 3, TEXT, .8)
                value = f"{r[metric]:,.0f}" if metric == "allocation_bytes" else f"{r[metric]:,.2f}"
                text(x + w - 16, center - 8, value, 14, TEXT, "mono", ha="right")
            text(bar_x, y + 176, "0", 10, MUTED, "mono", ha="center")

    line(36, 1640, WIDTH - 36, 1640)
    text(36, 1659, "READING THIS CHART", 12, PALE, "strong")
    text(36, 1682, "Linear bars; each card has its own scale. White ticks show observed ranges across three forks, not confidence intervals.", 14, MUTED)
    text(36, 1705, "Ordinary Map inputs only. Prepared immutable inputs reduce cel-java’s costs; see the separate controls before generalizing.", 14, MUTED)
    note = {
        "time_ns": "JVM: mean of fork means. Native: median of fork medians. Hot evaluation excludes preparation and warmup.",
        "allocation_bytes": "Median of three forks. Current-thread allocated bytes per operation; this does not measure retained heap or process size.",
        "rss_mib": "Median of three forks. Current RSS after fixed work includes setup and compilation; this is not retained heap or peak RSS.",
        "peak_rss_mib": "Median of three forks. Lifetime high-water RSS includes setup, calibration, warmup and compilation; this is not retained heap.",
    }[metric]
    text(36, 1728, note, 14, MUTED)
    text(36, 1751, "Linux x86-64 / AMD Threadripper PRO 9965WX  ·  Measured source cf0d51d85cd6  ·  Release report: 2026-11-09", 12, MUTED, "mono")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT / f"{filename}.svg", metadata={"Date": None, "Title": title, "Description": outcome + ". Lower is better. Ordinary Map inputs; three forks per cell."})
    svg = OUTPUT / f"{filename}.svg"
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text().splitlines()) + "\n")
    fig.savefig(OUTPUT / f"{filename}.png", dpi=150)
    plt.close(fig)


def render_prepared(font):
    """Keep this separate control experiment out of the ordinary-map win counts."""
    path = SOURCE.with_name("prepared-inputs.json")
    source = json.loads(path.read_text())
    engines = ("cel-java-prepared-map", "cel-java-optimized-prepared-map",
               "cel-java-immutable", "cel-java-optimized-immutable")
    workloads = ("scalar-wide", "proto-policy")
    cells = {(c["runtime"], c["engine"], c["workload"]): c for c in source["cells"]}
    if len(source["cells"]) != 16 or set(cells) != set(itertools.product(RUNTIMES, engines, workloads)):
        raise ValueError("Expected all 16 prepared-input control cells")
    for metric, forks, _, unit, _ in METRICS[:2]:
        fig = plt.figure(figsize=(12.8, 9), dpi=100, facecolor=BG)
        ax = fig.add_axes((0, 0, 1, 1))
        ax.set(xlim=(0, 1280), ylim=(900, 0))
        ax.axis("off")

        def text(x, y, value, size=16, color=TEXT, role="body", **kwargs):
            ax.text(x, y, value, fontsize=size * .72, color=color,
                    fontproperties=font[role], va="top", **kwargs)

        text(36, 28, "ELIDE  /  TruffleCEL 0.10.0 benchmark controls", 16, PALE, "strong")
        text(36, 65, "cel-java input preparation", 42, role="display")
        title = "Evaluation time" if metric == "time_ns" else "Allocation per evaluation"
        text(36, 123, f"{title}  ·  {unit}  ·  Lower is better", 19, role="strong")
        text(36, 161, "Same four full 69-binding inputs · Default and optimized planners · Caller preparation excluded", 16, MUTED)
        text(36, 199, "Ordinary maps are copied by cel-java. Guava ImmutableMaps can be reused.", 20, PALE, "display")
        for col, runtime in enumerate(RUNTIMES):
            x = 36 + col * 616
            text(x, 247, "JVM / C2" if runtime == "jvm" else "Native Image / Serial GC", 23, role="display")
            for row, (workload, name) in enumerate(zip(workloads, ("Wide activation", "Protobuf policy"))):
                y, w = 291 + row * 215, 592
                ax.add_patch(FancyBboxPatch((x, y), w, 196, boxstyle="round,pad=0,rounding_size=8",
                                           facecolor=CARD, edgecolor=BORDER, linewidth=.8))
                text(x + 16, y + 13, name, 20, role="display")
                text(x + w - 16, y + 17, unit, 13, MUTED, "mono", ha="right")
                rows = [cells[runtime, e, workload] for e in engines]
                for r in rows:
                    if (len(r[forks]) != 3 or not all(math.isfinite(v) and v > 0 for v in [r[metric], *r[forks]])
                            or not min(r[forks]) <= r[metric] <= max(r[forks])):
                        raise ValueError("Invalid prepared-input aggregate or fork range")
                maximum = max(max(r[forks]) for r in rows)
                labels = ("Ordinary · default", "Ordinary · optimized", "Immutable · default", "Immutable · optimized")
                for index, (r, label) in enumerate(zip(rows, labels)):
                    center, bar_x, bar_w = y + 62 + index * 29, x + 185, 286
                    text(x + 16, center - 8, label, 14, MUTED if index < 2 else TEXT)
                    ax.add_patch(Rectangle((bar_x, center - 6), r[metric] / maximum * bar_w, 12,
                                           facecolor="#525252" if index < 2 else "#c0c0c0"))
                    low, high = (bar_x + v / maximum * bar_w for v in (min(r[forks]), max(r[forks])))
                    ax.plot((low, high), (center, center), color=TEXT, linewidth=.8)
                    value = f"{r[metric]:,.0f}" if metric == "allocation_bytes" else f"{r[metric]:,.2f}"
                    text(x + w - 16, center - 8, value, 14, TEXT, "mono", ha="right")
                text(x + 185, y + 172, "0", 10, MUTED, "mono", ha="center")
        text(36, 748, "SEPARATE CONTROL EXPERIMENT", 12, PALE, "strong")
        text(36, 777, "Linear scales start at zero and vary by card. White marks: observed ranges of three forks, not confidence intervals.", 14, MUTED)
        text(36, 802, "Only these two fixtures were tested with prepared inputs. Do not extend ordinary-map rankings to this API path.", 14, MUTED)
        note = ("JVM: mean of fork means. Native: median of fork medians. Setup, input preparation and warmup are excluded."
                if metric == "time_ns" else "Median of three forks. Current-thread bytes/op excludes compiler/background allocations; this is not retained heap.")
        text(36, 827, note, 14, MUTED)
        text(36, 860, "cel-java 0.15.0 / GraalVM 25.5.5-elide.27 / AMD Threadripper PRO 9965WX / Release report 2026-11-09", 12, MUTED, "mono")
        filename = "prepared-time" if metric == "time_ns" else "prepared-allocation"
        svg = OUTPUT / f"{filename}.svg"
        fig.savefig(svg, metadata={"Date": None, "Title": f"cel-java prepared-input control: {title}"})
        svg.write_text("\n".join(line.rstrip() for line in svg.read_text().splitlines()) + "\n")
        fig.savefig(OUTPUT / f"{filename}.png", dpi=150)
        plt.close(fig)


def main():
    # Outlined text preserves the exact fonts in GitHub's image renderer and offline.
    plt.rcParams.update({"svg.fonttype": "path", "svg.hashsalt": "trufflecel-elide-010"})
    source, cells = load_cells()
    with tempfile.TemporaryDirectory(prefix="trufflecel-fonts-") as temp:
        font = fonts(Path(temp))
        render(source, cells, font, METRICS[0], interpreter_only=True)
        for spec in METRICS:
            render(source, cells, font, spec)
        render_prepared(font)
    files = sorted(OUTPUT.glob("*.svg")) + sorted(OUTPUT.glob("*.png"))
    (OUTPUT / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in files))
    print(f"Validated 48 evaluation cells and 16 separate input controls; rendered {len(files) // 2} SVG/PNG chart pairs in {OUTPUT}")


if __name__ == "__main__":
    main()
