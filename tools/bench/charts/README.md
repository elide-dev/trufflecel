# Elide benchmark chart renderer

This presentation layer reads the immutable
[`summary.json`](../../../docs/benchmarks/release-0.10.0-20261109/summary.json) and
writes new charts to [`docs/images/benchmark-0.10.0-elide`](../../../docs/images/benchmark-0.10.0-elide/README.md).
It does not modify historical reports or published release artifacts.

The renderer validates the complete 48-cell matrix, finite positive values,
three-fork ranges, and all computed lower/tied/higher counts against the recorded
summary before drawing. It shows all four engine modes. Each card uses one
zero-based linear scale; direct values and comparison labels remove the need to
infer winners from a legend or a logarithmic axis. The separate prepared-input
controls and the limits of RSS measurements remain prominent.

## Brand and font provenance

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

## Reproduce

Use Python 3.12 or newer and an isolated environment:

```sh
python3 -m venv /tmp/trufflecel-charts
/tmp/trufflecel-charts/bin/pip install -r tools/bench/charts/requirements.txt
/tmp/trufflecel-charts/bin/python tools/bench/charts/render.py
git diff --check
```

The renderer has no network access requirement after installing Python packages.
It writes SVG, 1.5× PNG, and `SHA256SUMS` for the three charts. Font source
checksums are in [`fonts/SHA256SUMS`](fonts/SHA256SUMS). Dependency versions are
pinned; byte-identical rendering additionally depends on the platform's rendering
libraries. The output is visually reviewed at full size before committing.
