"""drawcheck.py DIR SONG... : proves the app draws exactly what abcjs laid out.

Needs, per song, three files from the SheetTest screen of a test build (branch test/abcjs-spike):
SONG.png (abcjs's own drawing in the WebView), SONG-ours.png (the app's renderer, same score, same
scale) and SONG.svg. Pixels that differ by more than 35% are counted; text areas (abcjs reports
where each text is) are left out, because Chrome and Android smooth letter edges differently and the
app boxes section labels. Every other differing pixel is a failure.
Run with a Python that has numpy and Pillow, and ImageMagick's magick on the PATH.
"""
import re, subprocess, sys
import numpy as np
from PIL import Image

SCALE = 1080 / 980  # the WebView lays the page out 980 CSS px wide on a 1080 px screen

def main(folder, songs):
    bad = 0
    for s in songs:
        p = f'{folder}/{s}'
        subprocess.run(['magick', 'compare', '-fuzz', '35%', p + '.png', p + '-ours.png', '-compose', 'src',
                        '-highlight-color', 'red', '-lowlight-color', 'white', p + '-diff.png'], capture_output=True)
        d = np.asarray(Image.open(p + '-diff.png').convert('RGB')).astype(int)
        red = (d[..., 0] > 200) & (d[..., 1] < 80) & (d[..., 2] < 80)
        text = np.zeros_like(red)
        for m in re.finditer(r'<text([^>]*)>(.*?)</text>', open(p + '.svg').read(), re.S):
            a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
            words = re.sub(r'<[^>]+>', '', m.group(2))
            x, y, size = float(a.get('x', 0)), float(a.get('y', 0)), float(a.get('font-size', 12))
            w = len(words) * size * 0.62 + size * 0.8
            anchor = a.get('text-anchor', 'start')
            x0 = x - w / 2 if anchor == 'middle' else x - w if anchor == 'end' else x - size * 0.4
            X0, X1, Y0, Y1 = [int(v * SCALE) for v in (x0, x0 + w, y - size * 1.15, y + size * 0.5)]
            text[max(0, Y0):max(0, Y1), max(0, X0):max(0, X1)] = True
        text[-3:, :] = True  # the capture's last rows (height rounding)
        outside = int((red & ~text).sum())
        print(f'{s}: {int(red.sum())} pixels differ, {outside} outside text' + ('' if outside == 0 else '  <-- FAIL'))
        bad += outside > 0
    sys.exit(1 if bad else 0)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
