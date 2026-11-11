# Benchmark chart renderer

`render_cards.py` turns a retained release report into light and dark SVG cards
that share TruffleData's benchmark design. The drawing module, [`cards.py`](cards.py),
is ported from [`elide-dev/truffledata` `tools/bench/cards.py` at `6148472a`](https://github.com/elide-dev/truffledata/blob/6148472afcbeec47700d10e5ba5daf40bddfcaba/tools/bench/cards.py);
the only changes are configurable legend labels and reproducible font subsets.
Both repositories use the same light/dark palette, two bar classes (the project's own engines and the
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

## Design and font provenance

The card palette is TruffleData's (`THEMES` in [`cards.py`](cards.py)). Surfaces, text,
the purple accents and status colors are values from Elide's design tokens
([`elide-dev/components` `packages/tokens/src/tokens.css` at `cc312c76`](https://github.com/elide-dev/components/blob/cc312c76a7a6cce8c6277e3cf94c73686994d5a2/packages/tokens/src/tokens.css));
borders, grid lines and tinted chip/row backgrounds are TruffleData's derived shades.
Titles use Instrument Sans, labels use Inter, and values use JetBrains Mono, matching
the token font roles.

The three unmodified Latin variable WOFF2 files in `fonts/` come from the public
npm packages `@fontsource-variable/instrument-sans`, `@fontsource-variable/inter`,
and `@fontsource-variable/jetbrains-mono`, all version **5.3.0**. Each font's SIL
Open Font License and copyright notice accompanies it. These fonts are chart
tooling assets, not TruffleCEL runtime dependencies.

Each card embeds subsets of these fonts with their name tables removed. SVG content
is covered by the repository license; the fonts retain their SIL OFL terms. Font
source checksums are in [`fonts/SHA256SUMS`](fonts/SHA256SUMS).

## Archived charts

Earlier galleries — [0.10.0](../../../docs/images/benchmark-0.10.0-elide/README.md),
the [native launch profiles](../../../docs/images/native-profiles-20261109/README.md)
and [release 0.10.0](../../../docs/images/release-0.10.0) — were drawn with a
Matplotlib renderer that has since been removed. They remain unchanged as historical
presentations. To reproduce them byte-for-byte, use the renderer and pinned
requirements at commit `710e163`:

```sh
git worktree add /tmp/trufflecel-710e163 710e163
cd /tmp/trufflecel-710e163
python3 -m venv /tmp/trufflecel-legacy-charts
/tmp/trufflecel-legacy-charts/bin/pip install -r tools/bench/charts/requirements.txt
/tmp/trufflecel-legacy-charts/bin/python tools/bench/charts/render.py
/tmp/trufflecel-legacy-charts/bin/python tools/bench/charts/render_native_profiles.py
```

Release reports that carry their own renderer copy, such as
[release 0.10.0](../../../docs/benchmarks/release-0.10.0-20261109/README.md), use
that retained copy instead. Never overwrite an archived chart with a new rendering;
render a new report into a new directory.
