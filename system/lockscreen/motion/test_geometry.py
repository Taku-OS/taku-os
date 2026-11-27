#!/usr/bin/env python3
"""Reproducibility and preservation checks; native runtime tests live in tests/native."""
import json
import math
import unittest
from generate import HERE, OUTPUT, glyph_data, kotlin


class ClockContours(unittest.TestCase):
    def test_generated_kotlin_matches_sources(self):
        self.assertEqual(OUTPUT.read_text(), kotlin(glyph_data()))

    def test_complete_digits_and_compatible_cubics(self):
        data = glyph_data()
        self.assertEqual(set(data), set('0123456789:'))
        for d, glyph in data.items():
            for c in glyph['contours']:
                self.assertEqual(len(c['a']),len(c['b']),d)
                self.assertEqual((len(c['a'])-(0 if c.get('closed',True) else 2))%6,0,d)
                self.assertGreaterEqual(len(c['a']),18 if c.get('closed',True) else 8,d)
                self.assertTrue(all(math.isfinite(v) for v in c['a']+c['b']),d)

    def test_designer_0930_preserved_at_both_endpoints(self):
        original = json.loads((HERE/'designer-0930.json').read_text())['contours']
        data = glyph_data()
        for d, ids, compact, tall in [('0',[0,1],48,48), ('9',[4,5],121.32,122),
                                      (':',[7,8],191.16,196), ('3',[6],217.92,220)]:
            for contour, idx in zip(data[d]['contours'],ids):
                for key,x in [('a',compact),('b',tall)]:
                    for i,value in enumerate(contour[key]):
                        self.assertAlmostEqual(value+(125 if i%2 else x),original[idx][key][i],places=3)

    def test_six_preserves_designer_nine_junction(self):
        data = glyph_data()
        for six,nine in zip(data['6']['contours'],data['9']['contours']):
            for key,centre in [('a',(72.36,84)),('b',(68,320))]:
                for i,(a,b) in enumerate(zip(six[key],nine[key])):
                    self.assertAlmostEqual(a+b,centre[i%2],places=3)

    def test_two_shoulder_has_no_curvature_jump_during_morph(self):
        contour = glyph_data()['2']['contours'][0]
        def sub(a, b): return tuple(x-y for x,y in zip(a,b))
        def cross(a, b): return a[0]*b[1]-a[1]*b[0]
        def acceleration(a, b, c): return tuple(x-2*y+z for x,y,z in zip(a,b,c))
        for step in range(-1, 42):
            p = step/40
            points = [b+(a-b)*p for a,b in zip(contour['a'],contour['b'])]
            def segment(index):
                return [tuple(points[j:j+2]) for j in range(index*6,index*6+8,2)]
            incoming, first, second, outgoing = (segment(i) for i in (3,4,5,6))
            # Tangents agree at the join between curves, as do the second derivatives.
            for left,right in zip(sub(first[3],first[2]),sub(second[1],second[0])):
                self.assertAlmostEqual(left,right,places=5)
            for left,right in zip(acceleration(first[3],first[2],first[1]),
                                  acceleration(second[2],second[1],second[0])):
                self.assertAlmostEqual(left,right,places=5)
            # Both ends approach their straight segments with zero curvature, not only
            # matching tangent direction. Includes the animation's slight overshoot.
            for curve,line,at_end in [(first,incoming,False),(second,outgoing,True)]:
                a,b,c = (curve[3],curve[2],curve[1]) if at_end else (curve[0],curve[1],curve[2])
                tangent = sub(a,b)
                self.assertAlmostEqual(cross(tangent,sub(line[3],line[0])),0,delta=.001)
                self.assertAlmostEqual(cross(tangent,acceleration(a,b,c)),0,delta=.001)


if __name__ == '__main__':
    unittest.main()
