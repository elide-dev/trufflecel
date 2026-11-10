# Elide benchmark chart renderers

## Release benchmark cards (0.11.0 and later)

`render_cards.py` turns a retained release report into light and dark SVG cards
that share TruffleData's benchmark design. The drawing module, [`cards.py`](cards.py),
is ported from [`elide-dev/truffledata` `tools/bench/cards.py` at `6148472a`](https://github.com/elide-dev/truffledata/blob/6148472afcbeec47700d10e5ba5daf40bddfcaba/tools/bench/cards.py);
the only changes are configurable legend labels and reproducible font subsets. Both repositories use the same
light/dark Elide token values, two bar classes (the project's own engines and the
comparison library), observed-range whiskers, plain-language comparison chips and
embedded font subsets. The `fonts/` files are byte-identical to TruffleData's.

```sh
python3 -m venv /tmp/trufflecel-charts
/tmp/trufflecel-charts/bin/pip install -r tools/bench/charts/requirements.txt
/tmp/trufflecel-charts/bin/python tools/bench/charts/render_cards.py \
  --report docs/benchmarks/release-0.11.0-20261110 \
  --output docs/images/benchmark-0.11.0 --measured 2026-11-10
python3 -m unittest discover -s tools/bench -p 'test_chart_cards.py'
```

The renderer reads `summary.json`, `prepared-inputs.json` and `source.json` from the
report. Before drawing, it requires exactly 48 ordinary-map cells and 16 separate
prepared-input cells, three finite positive forks per metric, and each aggregate
inside its observed fork range. It recomputes every lower/tied/higher count against
the lower cel-java planner aggregate and fails if any differs from `summary.json`.
It never computes a new statistic: bars are the report's aggregates and whiskers
are the minimum and maximum fork. A report with a `host-audit.json` that records
concurrent work receives a visible shared-host note on every card.

Each SVG embeds WOFF2 subsets containing only the glyphs it draws, so GitHub's
image proxy displays Instrument Sans, Inter and JetBrains Mono without a font
fetch; text remains selectable and searchable. Output is deterministic for pinned
fontTools and Brotli. The renderer writes `cards.md` (every plotted value and range
as text) and `manifest.json` (source, renderer, font and output hashes). It refuses
to write into a directory that it did not create, so archived charts are preserved.
README pages use `<picture>` with the dark card for `prefers-color-scheme: dark`.
Review both themes at full size before committing; for example, headless Chrome
can screenshot each SVG at 1280 px width.

## Archived 0.10.0 charts

The 0.10.0 gallery predates the card design. This presentation layer reads the immutable
[`summary.json`](../../../docs/benchmarks/release-0.10.0-20261109/summary.json) and
separate [`prepared-inputs.json`](../../../docs/benchmarks/release-0.10.0-20261109/prepared-inputs.json), then
writes new charts to [`docs/images/benchmark-0.10.0-elide`](../../../docs/images/benchmark-0.10.0-elide/README.md).
It does not modify historical reports or published release artifacts.

The renderer validates the complete 48-cell matrix, finite positive values,
three-fork ranges, and all computed lower/tied/higher counts against the recorded
summary before drawing. It also validates all 16 prepared-input cells and their
fork ranges. It leads with interpreter-only evaluation and shows all four engine
modes in the full matrix, keeping interpreter/JIT counts separate. Each card uses one
zero-based linear scale; direct values and comparison labels remove the need to
infer winners from a legend or a logarithmic axis. The separate prepared-input
controls and the limits of RSS measurements remain prominent.

### Brand and font provenance

Colors, surfaces and type roles come from
[`elide-dev/components` at `cc312c76a7a6cce8c6277e3cf94c73686994d5a2`](https://github.com/elide-dev/components/blob/cc312c76a7a6cce8c6277e3cf94c73686994d5a2/packages/tokens/src/tokens.css).
The font packages and versions match its
[`fonts.css`](https://github.com/elide-dev/components/blob/cc312c76a7a6cce8c6277e3cf94c73686994d5a2/packages/tokens/src/fonts.css)
and [lockfile](https://github.com/elide-dev/components/blob/cc312c76a7a6cce8c6277e3cf94c73686994d5a2/bun.lock).

| Chart role | Design token | Value |
| --- | --- | --- |
| Headings | `--eld-font-display` | Instrument Sans, weight 600 |
| Labels | `--eld-font-sans` | Inter, weights 400 and 600 |
| Values | `--eld-font-mono` | JetBrains Mono, weight 400 |
| Background / cards | `--eld-neutral-950` / `--eld-neutral-900` | `#0a0a0a` / `#0e0e11` |
| Text / muted labels | `--eld-neutral-50` / `--eld-neutral-400` | `#fafcfa` / `#9a9a9a` |
| TruffleCEL interpreter / guest JIT | `--eld-purple-500` / `--eld-purple-300` | `#8b5cf6` / `#c4b5fd` |
| cel-java default / optimized | `--eld-neutral-400` / `--eld-neutral-600` | `#9a9a9a` / `#525252` |
| Better / worse comparisons | `--eld-success-strong` / `--eld-warning-strong` | `#3fb950` / `#e0a020` |
| Corners | `--eld-radius-xl` | 8 px |

The three unmodified Latin variable WOFF2 files in `fonts/` come from the public
npm packages `@fontsource-variable/instrument-sans`, `@fontsource-variable/inter`,
and `@fontsource-variable/jetbrains-mono`, all version **5.3.0**. Each font's SIL
Open Font License and copyright notice accompanies it. These fonts are chart
tooling assets, not TruffleCEL runtime dependencies.

FontTools instantiates the requested weights into temporary TTF files. Matplotlib
loads those exact files and outlines the glyphs in SVG; no web-font fetch or
system-font fallback is needed when GitHub displays a chart. PNG exports use the
same font files. SVG/PNG chart content is covered by the repository license;
the font files retain their accompanying SIL OFL terms.

### Reproduce

Use Python 3.12 or newer and an isolated environment:

```sh
python3 -m venv /tmp/trufflecel-charts
/tmp/trufflecel-charts/bin/pip install -r tools/bench/charts/requirements.txt
/tmp/trufflecel-charts/bin/python tools/bench/charts/render.py
git diff --check
```

The renderer has no network access requirement after installing Python packages.
It writes SVG, 1.5× PNG, and `SHA256SUMS` for seven charts: interpreter time,
all-mode time, allocation, current RSS, peak RSS, and the separate prepared-input
time/allocation controls. Font source
checksums are in [`fonts/SHA256SUMS`](fonts/SHA256SUMS). Dependency versions are
pinned; byte-identical rendering additionally depends on the platform's rendering
libraries. The output is visually reviewed at full size before committing.

## Native launch profiles

`render_native_profiles.py` uses these same tokens and exact font files for the
separate [native configuration gallery](../../../docs/images/native-profiles-20261109/README.md).
Run it with the same Python environment. It regenerates and validates the complete
72-cell summary against the retained raw records before drawing evaluation time,
current RSS and peak RSS. Each workload shares a zero-based scale across its
interpreter and guest-JIT columns. The charts compare application launch settings;
all matched cel-java controls and unchanged allocation medians remain in the tables.
This renderer does not alter the release charts or their historical measurements.
