"""check_chords.py CHROMIUM SCORE.abc... : proves every chord sheet shows playable shapes of the right chords.

For every score and every instrument that plays chords (guitar, ukulele, mandolin, banjo), the vocal line the
app prepares is laid out by the app's own abcjs in headless Chromium, and the app's ChordSheet adds the chord
tab and diagrams. Then, read back from what is drawn:
  1. every chord name has one tab column under it, and every column has a chord name;
  2. a column, read off the string lines in standard tuning (a table here, not the app's), sounds only the
     notes of its chord as music21 reads the name, and all of them but a fifth or an upper extension;
  3. on a guitar its lowest note is the chord's bass (the root, or a slash chord's bass note);
  4. it fits in four frets and four fingers (a barre over the lowest fret counts as one);
  5. the chord's diagram, read back from its dots, barre, open rings and crosses, shows the same shape.
Run with a Python that has music21: python -I check_chords.py ~/.local/bin/chromium songs/*.abc
"""
import json, os, re, subprocess, sys, tempfile
from music21 import harmony
HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', '..', 'app')
ENGRAVER = os.path.abspath(os.path.join(APP, 'assets', 'abcjs', 'abcjs-basic-min.js'))
# the strings chords are played on, as they sound, low string first (the banjo's short fifth string is a drone)
STANDARD = {'guitar': [40, 45, 50, 55, 59, 64], 'ukulele': [67, 60, 64, 69], 'mandolin': [55, 62, 69, 76], 'banjo': [50, 55, 59, 62]}

def main(chromium, scores):
    with tempfile.TemporaryDirectory() as tmp:
        cls = os.path.join(tmp, 'classes')
        src = [os.path.join(APP, 'src/ai/jackknife/killayue', f) for f in
               ('ScorePrep.java', 'Instruments.java', 'Transpose.java', 'Tabs.java', 'SvgScore.java', 'Chords.java', 'ChordSheet.java')]
        subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls] + src + [os.path.join(HERE, 'ChordTool.java')], check=True)
        prep = os.path.join(tmp, 'prep')
        subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.ChordTool', 'prep', prep] + scores, check=True)
        names = sorted(os.listdir(prep))
        page = os.path.join(tmp, 'vocal.html')
        with open(page, 'w') as h:
            h.write('<!doctype html><html><head><meta charset="utf-8"><script src="file://' + ENGRAVER + '"></script></head><body>\n')
            for n, f in enumerate(names):
                h.write(f'<div id="p{n}"></div><script>(function(){{var t=ABCJS.renderAbc("p{n}",{json.dumps(open(os.path.join(prep, f)).read())},'
                        '{staffwidth:760,add_classes:true,paddingtop:0,paddingbottom:0,paddingleft:0,paddingright:4});'
                        f'if((t[0].warnings||[]).length)document.getElementById("p{n}").setAttribute("data-warn","1");}})();</script>\n')
            h.write('</body></html>')
        dom = subprocess.run([chromium, '--headless=new', '--no-sandbox', '--disable-gpu', '--allow-file-access-from-files',
                              '--dump-dom', 'file://' + page], check=True, capture_output=True, text=True, timeout=600).stdout
        svgs = []
        for n, f in enumerate(names):
            start = dom.find(f'<div id="p{n}"')
            seg = dom[start:dom.find('</svg>', start) + 6]
            if 'data-warn' in seg[:80]:
                sys.exit(f'{f}: abcjs warnings')
            path = os.path.join(tmp, f.replace('.abc', '.svg'))
            open(path, 'w').write(seg[seg.find('<svg'):])
            svgs.append(path)
        bad = sheets = columns = 0
        for ins, strings in STANDARD.items():
            out = subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.ChordTool', 'add', ins] + svgs,
                                 check=True, capture_output=True, text=True).stdout
            for row in map(json.loads, out.splitlines()):
                sheets += 1
                problems = [row['error']] if 'error' in row else check_sheet(row['items'], strings, ins == 'guitar')
                columns += sum(1 for i in row.get('items', []) if 'abcjs-chord' in i['cls'] and not no_chord(i['text']))
                if problems:
                    bad += 1
                    print(f"{row['song']} {ins}: " + '; '.join(problems[:4]))
        print(f'{sheets - bad} of {sheets} chord sheets pass ({columns} chord columns checked)')
        sys.exit(1 if bad else 0)

def no_chord(t):
    return t.strip() in ('N.C.', 'NC', 'N.C', 'N')

def chord_tones(name):
    """root and bass pitch classes, every tone's interval above the root, and the intervals a shape may leave out"""
    figure = name.replace('♭', 'b').replace('♯', '#')
    figure = re.sub(r'^([A-G])b', r'\1-', figure)
    figure = re.sub(r'/([A-G])b', r'/\1-', figure)
    c = harmony.ChordSymbol(figure)
    root, bass = c.root().pitchClass, c.bass().pitchClass
    tones = {(p.pitchClass - root) % 12 for p in c.pitches}
    may_miss = {7} if len(tones) >= 4 else set()
    q = c.chordKind or ''
    if '11th' in q or '13th' in q:
        may_miss |= {2, 5}          # the ninth, the eleventh
    if q == 'dominant-11th':
        may_miss |= {4}             # an eleventh chord drops its third
    return root, bass, tones, may_miss

def check_sheet(items, strings, rooted):
    problems = []
    by_line = {}
    for i in items:
        by_line.setdefault(i['line'], []).append(i)
    shape_of = {}
    for line, its in by_line.items():
        if line < 0:
            continue
        ys = sorted(i['box'][1] for i in its if 'killa-tab-line' in i['cls'])
        if len(ys) != len(strings):
            problems.append(f'line {line}: {len(ys)} string lines, not {len(strings)}')
            continue
        cols = {}
        for i in its:
            if 'killa-chord-tab' in i['cls'] and 'abcjs-tab-number' in i['cls']:
                k = min(range(len(ys)), key=lambda k: abs(i['y'] - 0.36 * 11 - (ys[k] + 0.3)))
                cols.setdefault(round(i['x'], 2), {})[len(ys) - 1 - k] = int(i['text'])  # bottom line = lowest string
        names = {round(i['x'], 2): i['text'] for i in its if 'abcjs-chord' in i['cls'] and not no_chord(i['text'])}
        if set(cols) != set(names):
            problems.append(f'line {line}: chord names at {sorted(names)} but tab columns at {sorted(cols)}')
        for x, name in names.items():
            frets = [cols.get(x, {}).get(s, -1) for s in range(len(strings))]
            if name in shape_of and shape_of[name] != frets:
                problems.append(f'{name}: two different shapes')
            shape_of[name] = frets
            problems += check_shape(name, frets, strings, rooted)
    problems += check_diagrams(by_line.get(-1, []), shape_of, len(strings))
    return problems

def check_shape(name, frets, strings, rooted):
    root, bass, tones, may_miss = chord_tones(name)
    sounding = [strings[s] + f for s, f in enumerate(frets) if f >= 0]
    if len(sounding) < 3:
        return [f'{name} {frets}: fewer than three strings']
    heard = {(p % 12 - root) % 12 for p in sounding}
    out = []
    if heard - tones:
        out.append(f'{name} {frets}: plays notes not in the chord')
    if tones - heard - may_miss:
        out.append(f'{name} {frets}: leaves out notes the chord needs')
    if rooted and min(sounding) % 12 != bass:
        out.append(f'{name} {frets}: lowest note is not the bass')
    fretted = [f for f in frets if f > 0]
    if fretted:
        low = min(fretted)
        if max(fretted) - low > 3:
            out.append(f'{name} {frets}: stretches over more than four frets')
        first = frets.index(low)
        barre_ok = all(f >= low for f in frets[first:]) and frets.count(low) >= 2
        fingers = min(len(fretted), 1 + sum(1 for f in fretted if f > low) if barre_ok else 99)
        if fingers > 4:
            out.append(f'{name} {frets}: needs {fingers} fingers')
    return out

def check_diagrams(items, shape_of, n):
    """each diagram read back: strings by x, frets from dots and the barre, open rings, crosses, the start fret"""
    out = []
    names = [i for i in items if 'killa-diagram-name' in i['cls']]
    if sorted(i['text'] for i in names) != sorted(shape_of):
        return [f'diagrams for {sorted(i["text"] for i in names)}, chords {sorted(shape_of)}']
    for d in names:
        cx = d['x']
        near = [i for i in items if 'killa-diagram' in i['cls'] and abs(cell_x(i) - cx) < 45 and 0 <= cell_y(i) - d['y'] < 110]
        lines = sorted({round((i['box'][0] + i['box'][2]) / 2, 2) for i in near if i.get('box') and i['box'][3] - i['box'][1] > 30 and i['box'][2] - i['box'][0] < 1.5})
        if len(lines) != n:
            out.append(f'diagram {d["text"]}: {len(lines)} strings')
            continue
        frets_y = sorted({round((i['box'][1] + i['box'][3]) / 2, 2) for i in near if i.get('box') and i['box'][2] - i['box'][0] > 20
                          and 'barre' not in i['cls']})
        grid_top, space = frets_y[0], frets_y[1] - frets_y[0]   # the nut or top fret line, and the fret spacing
        label = [i for i in near if 'killa-diagram-fret' in i['cls']]
        start = int(label[0]['text'].replace('fr', '')) if label else 1
        got = []
        for x in lines:
            f = None
            if any('unplayed' in i['cls'] and abs(i['x'] - x) < 1.5 for i in near):
                f = -1
            elif any('open' in i['cls'] and abs(cell_x(i) - x) < 1.5 for i in near):
                f = 0
            dots = [i for i in near if 'dot' in i['cls'] and abs(cell_x(i) - x) < 1.5]
            if dots:
                f = start + int((cell_y(dots[0]) - grid_top) // space)
            elif f is None:
                bars = [i for i in near if 'barre' in i['cls'] and i['box'][0] <= x <= i['box'][2]]
                if bars:
                    f = start + int((cell_y(bars[0]) - grid_top) // space)
            got.append(f)
        if got != shape_of[d['text']]:
            out.append(f'diagram {d["text"]} shows {got}, the tab plays {shape_of[d["text"]]}')
    return out

def cell_x(i):
    return (i['box'][0] + i['box'][2]) / 2 if i.get('box') else i['x']

def cell_y(i):
    return (i['box'][1] + i['box'][3]) / 2 if i.get('box') else i['y']

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
