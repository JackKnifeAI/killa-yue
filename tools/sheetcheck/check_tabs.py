"""check_tabs.py CHROMIUM ABCJS_FILE SCORE.abc... : proves every tab number gives the right note.

For every score, both voices and every fretted instrument (Instruments.java), the part the app writes is
drawn by the app's own abcjs (app/assets/abcjs, with its tablature) in headless Chromium, as the phone's
WebView draws it. Then, for every note of the song, in order:
  1. the tab shows one number, on one string line;
  2. that string's open pitch in standard tuning (a table here, not the app's) plus the fret is the note as it sounds: the concert note from the song
     (read by abcjs from the original score) moved by the whole octaves the part says it moved;
  3. or the tab shows "?" and no string of the real instrument can play the note;
  4. the number of "?" is the number the app reports in its export message.
No abcjs warnings are allowed. ABCJS_FILE is the unmodified abcjs used to read the concert notes.
Run: python -I check_tabs.py ~/.local/bin/chromium abcjs-basic.js songs/*.abc
"""
import json, os, re, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check  # timeline helpers from check.py
APP = os.path.join(HERE, '..', '..', 'app')
ENGRAVER = os.environ.get('ENGRAVER') or os.path.abspath(os.path.join(APP, 'assets', 'abcjs', 'abcjs-basic-min.js'))
# standard tunings as they sound, MIDI, the tab's bottom line first: the truth the app's tunings must match
STANDARD = {'guitar': [40, 45, 50, 55, 59, 64], 'bass-guitar': [28, 33, 38, 43], 'ukulele': [67, 60, 64, 69],
            'mandolin': [55, 62, 69, 76], 'banjo': [67, 50, 55, 59, 62]}

def main(chromium, lib, scores):
    with tempfile.TemporaryDirectory() as tmp:
        cls = os.path.join(tmp, 'classes')
        src = [os.path.join(APP, 'src/ai/jackknife/killayue', f) for f in ('ScorePrep.java', 'Instruments.java', 'Transpose.java', 'Tabs.java')]
        subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls] + src
                       + [os.path.join(HERE, 'PartTool.java'), os.path.join(HERE, 'TabTool.java')], check=True)
        tabs = {}
        for line in subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.TabTool'], check=True, capture_output=True, text=True).stdout.splitlines():
            ins, option, strings = line.split('\t')
            tabs[ins] = (json.loads(option), [int(s) for s in strings.split(',')])
        if sorted(tabs) != sorted(STANDARD) or any(tabs[i][1] != STANDARD[i] for i in tabs):
            sys.exit('the app\'s tunings are not the standard ones: ' + str({i: tabs[i][1] for i in tabs}))
        out = os.path.join(tmp, 'parts')
        subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.PartTool', out] + scores, check=True)
        rows = [l.split('\t') for l in open(os.path.join(out, 'parts.tsv')).read().splitlines()]
        rows = [r for r in rows if r[3] in tabs]
        page = os.path.join(tmp, 'tabs.html')
        with open(page, 'w') as h:
            h.write('<!doctype html><html><head><meta charset="utf-8"><script src="file://' + ENGRAVER + '"></script></head><body>\n')
            for n, r in enumerate(rows):
                h.write(f'<div id="p{n}"></div><script>(function(){{var t=ABCJS.renderAbc("p{n}",{json.dumps(open(os.path.join(out, r[0])).read())},'
                        f'{{staffwidth:760,add_classes:true,paddingtop:0,paddingbottom:0,paddingleft:0,paddingright:4,tablature:{json.dumps(tabs[r[3]][0])}}});'
                        f'document.getElementById("p{n}").setAttribute("data-warn",JSON.stringify((t[0]&&t[0].warnings)||[]));}})();</script>\n')
            h.write('</body></html>')
        dom = subprocess.run([chromium, '--headless=new', '--no-sandbox', '--disable-gpu', '--allow-file-access-from-files',
                              '--dump-dom', 'file://' + page], check=True, capture_output=True, text=True, timeout=600).stdout
        concert = {}
        for s in scores:
            tl = check.abcjs(lib, s)
            name = os.path.basename(s).replace('.abc', '')
            concert[(name, 'Vocal')], concert[(name, 'Ins')] = tl.get('track0', []), tl.get('track1', [])
        bad = numbers = unplayable = 0
        for n, (f, song, voice, ins, semis, octaves, key, notes, out_of_range, below) in enumerate(rows):
            start = dom.find(f'<div id="p{n}"')
            end = dom.find('<div id="p', start + 1)
            seg = dom[start:end if end > 0 else len(dom)]
            problems = []
            warn = re.search(r'data-warn="([^"]*)"', seg)
            if warn is None or warn.group(1) != '[]':
                problems.append('abcjs warnings: ' + (warn.group(1) if warn else 'part not drawn'))
            strings = STANDARD[ins]
            got = read_tab(seg, len(strings), problems)
            want = [p + 12 * int(octaves) for a, p, d in sorted(concert[(song, voice)])]
            if len(got) != len(want):
                problems.append(f'{len(got)} tab numbers for {len(want)} notes')
            lowest = min(s for i, s in enumerate(strings) if not (i + 1 < len(strings) and s > strings[i + 1]))
            for i, ((line, fret), pitch) in enumerate(zip(got, want)):
                if fret == '?':
                    if pitch >= lowest:
                        problems.append(f'note {i + 1}: "?" for a note the strings can play (MIDI {pitch})')
                        break
                    unplayable += 1
                    continue
                sounds = strings[len(strings) - 1 - line] + int(fret)
                if sounds != pitch:
                    problems.append(f'note {i + 1}: line {line + 1} fret {fret} sounds MIDI {sounds}, the song has {pitch}')
                    break
            marks = sum(1 for line, fret in got if fret == '?')
            if marks != int(below):
                problems.append(f'the app counts {below} notes below the lowest string, the tab marks {marks}')
            numbers += len(got)
            if problems:
                bad += 1
                print(f'{song} {voice} {ins}: ' + '; '.join(problems))
        print(f'{len(rows) - bad} of {len(rows)} tabs pass ({numbers} tab numbers checked, {unplayable} notes no string can play shown as "?")')
        sys.exit(1 if bad else 0)

def read_tab(seg, lines, problems):
    """(line from the top, fret) for every tab number, in reading order. A number's baseline sits 5.3 below its line."""
    staff = {}
    for m in re.finditer(r'<g class="abcjs-staff abcjs-l(\d+) abcjs-v1">(.*?)</g>', seg):
        ys = sorted(float(y) for y in re.findall(r'<path d="M [\d.]+ ([\d.]+) L', m.group(2)))
        if len(ys) != lines:
            problems.append(f'system {m.group(1)}: tab has {len(ys)} lines, not {lines}')
        staff[int(m.group(1))] = ys
    out = []
    for m in re.finditer(r'<g class="abcjs-tabNumber[^"]*abcjs-l(\d+) [^"]*"[^>]*><text[^>]*x="([\d.]+)" y="([\d.]+)"[^>]*><tspan[^>]*>([^<]*)</tspan>', seg):
        system, x, y, text = int(m.group(1)), float(m.group(2)), float(m.group(3)), m.group(4)
        ys = staff.get(system, [])
        line = min(range(len(ys)), key=lambda k: abs(y - 5.3 - ys[k])) if ys else -1
        if line < 0 or abs(y - 5.3 - ys[line]) > 1.0:
            problems.append(f'system {system}: number "{text}" at y {y} is on no line')
            continue
        out.append(((system, x), (line, text if text == '?' else int(text))))
    return [t for _, t in sorted(out)]

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
