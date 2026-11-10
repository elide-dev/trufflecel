# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Benchmark cards: hand-built SVG in Elide's design tokens, with subset fonts embedded.

Ported from TruffleData's tools/bench/cards.py (elide-dev/truffledata 6148472a) so both
projects publish benchmarks in one visual system. Changes: configurable legend labels and
reproducible font subsets (no recalculated head.modified timestamp).

The renderer draws only what a card spec gives it; it never computes a statistic. A card
spec is plain data (see `Card`), so every number on a card can be traced to the loader that
produced it. Each card renders twice, light and dark, for a README `<picture>` element.

Fonts are the three variable families in `@elide/tokens` (tools/bench/fonts, OFL 1.1).
Each SVG embeds a subset holding only the glyphs it draws, so GitHub's image proxy renders
the same typography as a browser with the fonts installed.
"""
import base64
from dataclasses import dataclass, field
import functools
import io
from pathlib import Path
from xml.sax.saxutils import escape

FONTS = Path(__file__).with_name('fonts')
FAMILIES = {
    'display': ('Instrument Sans', 'instrument-sans-latin-wght-normal.woff2'),
    'sans': ('Inter', 'inter-latin-wght-normal.woff2'),
    'mono': ('JetBrains Mono', 'jetbrains-mono-latin-wght-normal.woff2'),
}
FALLBACK = {'display': 'Inter, ui-sans-serif, system-ui, sans-serif',
            'sans': 'ui-sans-serif, system-ui, sans-serif',
            'mono': 'ui-monospace, SFMono-Regular, Menlo, monospace'}

# Elide tokens (elide-components packages/tokens/src/tokens.css). Bars use two classes only:
# TruffleData and every other implementation; names are always printed beside the bars.
# Validated with the dataviz palette script: CVD ΔE 25.6 (light) / 25.5 (dark), both >= 3:1.
THEMES = {
    'light': dict(surface='#ffffff', border='#e4e4e7', ink='#0a0a0a', ink2='#3a3a3a',
                  muted='#696969', grid='#ececee', ours='#7c3aed', other='#8a8a8a',
                  whisker='#1e1e22', eyebrow='#5b21b6', track='#f5f5f5', ours_soft='#f5f3ff',
                  good='#117c3a', good_bg='#e3f6ea', bad='#9c5e02', bad_bg='#fbf1d9'),
    'dark': dict(surface='#0e0e11', border='#26262b', ink='#fafcfa', ink2='#d4d4d4',
                 muted='#9a9a9a', grid='#1f1f24', ours='#8b5cf6', other='#6b6b75',
                 whisker='#e4e4e7', eyebrow='#c4b5fd', track='#18181c', ours_soft='#17121f',
                 good='#3fb950', good_bg='#12291a', bad='#e0a020', bad_bg='#2d2310'),
}
BRAND = ('#662d91', '#7c3aed', '#e7008c')


@dataclass
class Row:
    label: str
    value: float
    lo: float
    hi: float
    ours: bool = False
    text: str = ''          # printed value; defaults to fmt(value)


@dataclass
class Panel:
    title: str
    unit: str
    rows: list
    better: str = 'lower'   # or 'higher'
    chip: tuple = None      # (text, 'good' | 'bad' | 'neutral')


@dataclass
class Card:
    slug: str
    eyebrow: str
    title: str
    subtitle: str
    panels: list
    notes: list = field(default_factory=list)
    columns: int = 0        # 0 = one row of panels
    source: str = ''
    ours_label: str = 'TruffleData'
    other_label: str = 'Other implementations'


@functools.cache
def _font(role, weight):
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    font = TTFont(FONTS / FAMILIES[role][1])
    return instancer.instantiateVariableFont(font, {'wght': weight})


@functools.cache
def _metrics(role, weight):
    font = _font(role, weight)
    return font.getBestCmap(), font['hmtx'].metrics, font['head'].unitsPerEm


def width(text, size, role='sans', weight=400):
    cmap, hmtx, upem = _metrics(role, weight)
    total = 0
    for ch in text:
        glyph = cmap.get(ord(ch)) or cmap.get(ord('0'))
        total += hmtx[glyph][0]
    return total * size / upem


def fmt(value, unit=''):
    if value >= 10_000:
        text = f'{value:,.0f}'
    elif value >= 100:
        text = f'{value:,.0f}'
    elif value >= 10:
        text = f'{value:.1f}'
    elif value >= 1:
        text = f'{value:.2f}'
    else:
        text = f'{value:.3f}'
    return text + (' ' + unit if unit else '')


def _subset(role, chars):
    from fontTools.subset import Options, Subsetter
    from fontTools.ttLib import TTFont
    # Keep the source head.modified timestamp so identical inputs give identical SVG bytes.
    font = TTFont(FONTS / FAMILIES[role][1], recalcTimestamp=False)
    options = Options()
    options.flavor = 'woff2'
    options.layout_features = ['kern', 'liga', 'calt', 'tnum', 'case']
    options.name_IDs = []
    options.notdef_outline = True
    sub = Subsetter(options)
    sub.populate(text=''.join(sorted(set(chars) | {' '})))
    sub.subset(font)
    out = io.BytesIO()
    font.flavor = 'woff2'
    font.save(out)
    return base64.b64encode(out.getvalue()).decode()


class Svg:
    def __init__(self, theme):
        self.t = THEMES[theme]
        self.parts = []
        self.chars = {role: set() for role in FAMILIES}

    def text(self, x, y, s, size, role='sans', weight=400, fill='ink', anchor='start', extra=''):
        self.chars[role].update(s)
        color = self.t.get(fill, fill)
        tracking = ' letter-spacing="0.08em"' if role == 'mono' and s.isupper() and len(s) > 3 else ''
        self.parts.append(
            f'<text x="{x:.1f}" y="{y:.1f}" class="{role}" font-size="{size}" font-weight="{weight}" '
            f'fill="{color}" text-anchor="{anchor}"{tracking}{extra}>{escape(s)}</text>')

    def rect(self, x, y, w, h, fill, rx=0, extra=''):
        self.parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{max(w, 0):.1f}" height="{h:.1f}" '
                          f'rx="{rx}" fill="{self.t.get(fill, fill)}"{extra}/>')

    def line(self, x1, y1, x2, y2, stroke, w=1, extra=''):
        self.parts.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
                          f'stroke="{self.t.get(stroke, stroke)}" stroke-width="{w}"{extra}/>')

    def bar(self, x, y, w, h, fill, r=4):
        """Square at the baseline, rounded data end."""
        r = min(r, w / 2, h / 2)
        if w <= 0:
            return
        self.parts.append(
            f'<path d="M{x:.1f},{y:.1f}H{x + w - r:.1f}A{r},{r} 0 0 1 {x + w:.1f},{y + r:.1f}'
            f'V{y + h - r:.1f}A{r},{r} 0 0 1 {x + w - r:.1f},{y + h:.1f}H{x:.1f}Z" '
            f'fill="{self.t.get(fill, fill)}"/>')

    def document(self, w, h, title, desc):
        faces = []
        for role, (family, _) in FAMILIES.items():
            if self.chars[role]:
                faces.append(f"@font-face{{font-family:'{family} Card';font-weight:100 900;"
                             f"src:url(data:font/woff2;base64,{_subset(role, self.chars[role])}) format('woff2')}}")
        classes = ''.join(f".{role}{{font-family:'{family} Card',{FALLBACK[role]}}}"
                          for role, (family, _) in FAMILIES.items())
        style = ''.join(faces) + classes + '.mono{font-variant-numeric:tabular-nums}'
        return ('<svg xmlns="http://www.w3.org/2000/svg" '
                f'width="{w}" height="{h:.0f}" viewBox="0 0 {w} {h:.0f}" role="img" aria-labelledby="t d">\n'
                f'<title id="t">{escape(title)}</title>\n<desc id="d">{escape(desc)}</desc>\n'
                f'<style>{style}</style>\n' + '\n'.join(self.parts) + '\n</svg>\n')


W = 1280
PAD = 48
ROW = 38
BAR = 18
GAP = 44


def _wrap(text, size, limit, role='sans'):
    lines, line = [], ''
    for word in text.split(' '):
        trial = (line + ' ' + word).strip()
        if line and width(trial, size, role) > limit:
            lines.append(line)
            line = word
        else:
            line = trial
    return lines + [line] if line else lines


def _panel(svg, p, x, y, pw):
    t = svg.t
    svg.text(x, y, p.title, 16, 'sans', 600)
    unit = p.unit + (' · lower is better' if p.better == 'lower' else ' · higher is better')
    svg.text(x, y + 21, unit, 12.5, 'sans', 400, 'muted')
    top = y + 44
    if p.chip:
        text, kind = p.chip
        cw = width(text, 12, 'sans', 600) + 20
        fg, bg = {'good': ('good', 'good_bg'), 'bad': ('bad', 'bad_bg')}.get(kind, ('ink2', 'track'))
        svg.rect(x, y + 34, cw, 24, bg, rx=12)
        svg.text(x + cw / 2, y + 50.5, text, 12, 'sans', 600, fg, 'middle')
        top = y + 74
    label_w = max(width(r.label, 13.5, 'sans', 600 if r.ours else 450) for r in p.rows) + 16
    texts = [r.text or fmt(r.value) for r in p.rows]
    value_w = max(width(s, 12.5, 'mono', 600) for s in texts) + 14
    x0, x1 = x + label_w, x + pw - value_w
    top_value = max(r.hi for r in p.rows)
    scale = (x1 - x0) / top_value
    # Recessive ticks: 0 plus about four clean steps, widened until their labels cannot touch.
    step = _nice(top_value / 4)
    while True:
        ticks = [k * step for k in range(int(top_value / step + 1e-9) + 1)]
        widest = max(width(_tick(v), 11, 'mono') for v in ticks)
        if len(ticks) < 3 or step * scale >= widest + 14:
            break
        step = _nice(step * 2)
    bottom = top + ROW * len(p.rows)
    mixed = len({r.ours for r in p.rows}) > 1
    for i, r in enumerate(p.rows):
        if r.ours and mixed:
            svg.rect(x - 10, top + ROW * i + 2, pw + 10, ROW - 4, 'ours_soft', rx=8)
    for v in ticks:
        gx = x0 + v * scale
        svg.line(gx, top - 6, gx, bottom, 'grid', 1)
        svg.text(gx, bottom + 18, _tick(v), 11, 'mono', 400, 'muted', 'middle')
    for i, (r, s) in enumerate(zip(p.rows, texts)):
        cy = top + ROW * i + ROW / 2
        svg.text(x0 - 12, cy + 4.5, r.label, 13.5, 'sans', 600 if r.ours else 450,
                 'ink' if r.ours else 'ink2', 'end')
        svg.bar(x0, cy - BAR / 2, r.value * scale, BAR, 'ours' if r.ours else 'other')
        lo, hi = x0 + r.lo * scale, x0 + r.hi * scale
        if hi - lo >= 1.5:
            svg.line(lo, cy, hi, cy, 'whisker', 1.25)
            svg.line(lo, cy - 4, lo, cy + 4, 'whisker', 1.25)
            svg.line(hi, cy - 4, hi, cy + 4, 'whisker', 1.25)
        svg.text(max(x0 + r.value * scale, hi) + 8, cy + 4.5, s, 12.5, 'mono', 600 if r.ours else 400,
                 'ink' if r.ours else 'ink2')
    return bottom + 30


def _tick(v):
    if v == int(v):
        return f'{int(v):,}'
    return f'{v:g}'


def _nice(raw):
    import math
    exp = 10 ** math.floor(math.log10(raw))
    return min((m * exp for m in (1, 2, 2.5, 5, 10)), key=lambda step: abs(step - raw))


def render(card, theme):
    svg = Svg(theme)
    t = svg.t
    body = []
    # Header.
    svg.text(PAD, 64, card.eyebrow.upper(), 12, 'mono', 600, 'eyebrow')
    svg.text(PAD, 102, card.title, 32, 'display', 600)
    for i, line in enumerate(_wrap(card.subtitle, 15, W - 2 * PAD - 300)):
        svg.text(PAD, 132 + 21 * i, line, 15, 'sans', 400, 'ink2')
    # Legend (top right): two classes.
    lx = W - PAD
    present = {r.ours for panel in card.panels for r in panel.rows}
    for label, fill in [(n, f) for n, f, o in ((card.other_label, 'other', False),
                                                 (card.ours_label, 'ours', True)) if o in present]:
        tw = width(label, 13, 'sans', 500)
        svg.text(lx, 68, label, 13, 'sans', 500, 'ink2', 'end')
        svg.rect(lx - tw - 20, 58, 12, 12, fill, rx=3)
        lx -= tw + 40
    y = 132 + 21 * len(_wrap(card.subtitle, 15, W - 2 * PAD - 300)) + 34
    columns = card.columns or len(card.panels)
    pw = (W - 2 * PAD - GAP * (columns - 1)) / columns
    for start in range(0, len(card.panels), columns):
        bottoms = [_panel(svg, p, PAD + (pw + GAP) * k, y + 16, pw)
                   for k, p in enumerate(card.panels[start:start + columns])]
        y = max(bottoms) + 18
    # Notes.
    svg.line(PAD, y, W - PAD, y, 'grid', 1)
    y += 26
    for note in card.notes:
        for line in _wrap(note, 12.5, W - 2 * PAD):
            svg.text(PAD, y, line, 12.5, 'sans', 400, 'muted')
            y += 19
    if card.source:
        y += 4
        svg.text(PAD, y, card.source, 11, 'mono', 400, 'muted')
        y += 14
    h = y + 30
    # Card chrome goes first so everything above draws on top.
    gradient = ('<defs><linearGradient id="accent" x1="0" x2="1"><stop stop-color="%s"/>'
                '<stop offset=".55" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>'
                '<clipPath id="card"><rect width="%d" height="%.0f" rx="16"/></clipPath></defs>') % (*BRAND, W, h)
    chrome = [gradient,
              f'<rect x=".5" y=".5" width="{W - 1}" height="{h - 1:.0f}" rx="16" fill="{t["surface"]}" stroke="{t["border"]}"/>',
              f'<rect width="{W}" height="4" fill="url(#accent)" clip-path="url(#card)"/>']
    svg.parts[:0] = chrome
    desc = ' '.join(f'{p.title}: ' + ', '.join(f'{r.label} {r.text or fmt(r.value)}' for r in p.rows) + '.'
                    for p in card.panels)
    return svg.document(W, h, f'{card.eyebrow}: {card.title}', desc)
