#!/usr/bin/env python3
"""Prepare compatible clock paths; native Canvas keeps new digits' stroke width constant.

The supplied 0/3/9/colon contours remain verbatim. Six is the rotated nine. Other
live-time digits use paired cubic centre lines: only stems grow, never stroke width.
"""
import json
from pathlib import Path
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FONT = ROOT / 'system/assistant/res/font/space_grotesk_regular.ttf'
OUTPUT = ROOT / 'system/lockscreen/src/com/android/systemui/taku/lockscreen/TakuClockContours.kt'


class Spine:
    def __init__(self, compact, tall):
        self.a = list(compact)
        self.b = list(tall)

    def line(self, compact, tall):
        for points, end in [(self.a, compact), (self.b, tall)]:
            start = points[-2:]
            points.extend(start[i]+(end[i]-start[i])*t for t in (1/3, 2/3, 1) for i in range(2))
        return self

    def curve(self, compact, tall):
        self.a.extend(compact)
        self.b.extend(tall)
        return self

    def data(self):
        return {'a':self.a, 'b':self.b, 'closed':False}


def bowl(top, bottom, left, right, cap):
    """Oval centre line with circular/elliptic caps and straight vertical stems."""
    x = (left+right)/2
    rx = (right-left)/2
    k = .5522847498307936
    return [x,top,
            x+rx*k,top,right,top+cap*(1-k),right,top+cap,
            right,top+cap+(bottom-top-2*cap)/3,right,bottom-cap-(bottom-top-2*cap)/3,right,bottom-cap,
            right,bottom-cap*(1-k),x+rx*k,bottom,x,bottom,
            x-rx*k,bottom,left,bottom-cap*(1-k),left,bottom-cap,
            left,bottom-cap-(bottom-top-2*cap)/3,left,top+cap+(bottom-top-2*cap)/3,left,top+cap,
            left,top+cap*(1-k),x-rx*k,top,x,top]


def spines():
    # Coordinates are paired landmarks, not y-scaled filled font contours. Width is applied
    # AFTER interpolation by Android's stroker (14 tall -> 10.08 compact design px).
    return {
        '1': [Spine((7.2,25),(14,75)).line((35.52,5.04),(49,7)).line((35.52,84),(49,320)).data()],
        '2': [Spine((11.4,24),(7,48)).line((11.4,23),(7,34))
              .curve((11.4,11,21,3.36,36.42,3.36),(7,18,18,7,34,7))
              .curve((51.8,3.36,61.44,12,61.44,24),(50,7,61,18,61,34))
              .line((61.44,25),(61,42))
              # Two C2-matched cubics spread the turn over the shoulder. Their curvature
              # falls to zero at both straight joins, so the diagonal never starts abruptly.
              # The final two handles sit 0.25 / 0.15 of the following line behind its start
              # at BOTH endpoints, preserving that smooth join throughout interpolation.
              .curve((61.44,27.716,61.44,29.74,59.586667,32.5),(61,70.05,61,88.25,59,105))
              .curve((57.733333,35.26,54.026667,38.756,48.466667,44),(57,121.75,53,137.05,47,160))
              .line((11.4,78.96),(7,313)).line((66.48,78.96),(68,313)).data()],
        '4': [Spine((55,84),(54,320)).line((55,5.04),(54,7))
              .line((8.88,60),(7,231)).line((71.76,60),(68,231)).data()],
        '5': [Spine((66.12,5.04),(68,7)).line((11.16,5.04),(7,7))
              .line((11.16,42),(7,160))
              .curve((18,35,26,33,36.12,33),(14,150,24,143,34,143))
              .curve((52,33,61.08,43,61.08,56),(50,143,61,155,61,171))
              .line((61.08,59),(61,286))
              .curve((61.08,72,52,80.64,36.12,80.64),(61,302,50,313,34,313))
              .curve((20,80.64,11.16,72,11.16,59),(18,313,7,302,7,286))
              .line((11.16,58),(7,270)).data()],
        '7': [Spine((3.84,5.04),(0,7)).line((58.92,5.04),(61,7))
              .line((18,84),(7,320)).data()],
        # Both bowls touch at the SAME centre line. Their shared horizontal stroke is one
        # waist, not two separated bowls joined by a stretched vertical bridge.
        '8': [{'a':bowl(3.36,40,10.56,64.08,18.32),
               'b':bowl(7,160,7,61,34), 'closed':False},
              {'a':bowl(40,80.64,10.56,64.08,20.32),
               'b':bowl(160,313,7,61,34), 'closed':False}],
    }


def glyph_data():
    font = TTFont(FONT)
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    designer = json.loads((HERE/'designer-0930.json').read_text())['contours']
    offsets = {'0': (48,48), '9': (121.32,122), ':': (191.16,196), '3': (217.92,220)}
    indices = {'0': [0,1], '9': [4,5], '3': [6], ':': [7,8]}
    result = {}
    centre_lines = spines()
    for d in '0123456789:':
        glyph = glyphs[cmap[ord(d)]]
        if d in indices or d == '6':
            source = '9' if d == '6' else d
            contours = []
            for i in indices[source]:
                c = {k: [round(v-(offsets[source][0 if k=='a' else 1] if j%2==0 else 125),4)
                         for j,v in enumerate(designer[i][k])] for k in ('a','b')}
                if d == '6':
                    for k in ('a','b'):
                        centre = (72.36,84) if k == 'a' else (68,320)
                        c[k] = [round(centre[j%2]-v,4) for j,v in enumerate(c[k])]
                contours.append(c)
        else:
            contours = centre_lines[d]
        result[d] = {'advance':glyph.width*.12-3.6, 'contours':contours,
                     'stroke':d in centre_lines, 'horizontalTerminal':d == '7'}
    return result


def kotlin(data):
    def array(a):
        return 'floatArrayOf('+','.join(f'{v:.4f}f' for v in a)+')'
    lines = ['// Generated by motion/generate.py. Do not hand-edit.',
             '// Designer 0/3/9/colon; mirrored 6; constant-width paired centre lines for other digits.',
             '// Space Grotesk metrics: OFL, system/assistant/design-system/licenses/SpaceGrotesk-OFL.txt.',
             'package com.android.systemui.taku.lockscreen', '',
             'internal object TakuClockContours {', '    val glyphs = mapOf(']
    for d,g in data.items():
        lines.append(f"        '{d}' to TakuClockGlyphs.Glyph({g['advance']:.4f}f, listOf(")
        for c in g['contours']:
            closed = '' if c.get('closed',True) else ', closed = false'
            lines.append('            TakuClockGlyphs.Contour('+array(c['a'])+', '+array(c['b'])+closed+'),')
        terminal = ', horizontalTerminal = true' if g['horizontalTerminal'] else ''
        lines.append('        ), stroke = '+str(g['stroke']).lower()+terminal+'),')
    return '\n'.join(lines+['    )','}',''])


if __name__ == '__main__':
    OUTPUT.write_text(kotlin(glyph_data()))
