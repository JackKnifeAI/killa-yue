"""check_lyrics.py CHROMIUM ABCJS_FILE SONG.json... : proves the words go under the line of music they are sung in.

For each song with synced lyrics (its _times), the vocal line is prepared as the app prepares it and laid out
by the app's own engrave.html in headless Chromium, which hands back the SVG and each line of music's start.
  1. Each line's start, from the app's JavaScript, must equal an independent count here: whole bars before it
     (a multi-bar rest counts all its bars) times the bar's length at the score's tempo.
  2. The app's LyricLines must put every lyric line, whole and in order, under the line of music that is
     playing at its synced time (the first line for one sung before the first note).
  3. Reported, not failed: lyric lines with no vocal note starting within 1.5 s of their time, as a check on
     the sync itself (scores of rapped songs have few vocal notes).
Run: python -I check_lyrics.py ~/.local/bin/chromium abcjs-basic.js songs/*.json
"""
import json, os, re, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check
APP = os.path.join(HERE, '..', '..', 'app')
ASSETS = os.path.abspath(os.path.join(APP, 'assets', 'abcjs'))

def main(chromium, lib, songs):
    with tempfile.TemporaryDirectory() as tmp:
        cls = os.path.join(tmp, 'classes')
        src = [os.path.join(APP, 'src/ai/jackknife/killayue', f) for f in ('ScorePrep.java', 'Instruments.java', 'Transpose.java',
               'Tabs.java', 'SvgScore.java', 'Chords.java', 'ChordSheet.java', 'LyricLines.java', 'Karaoke.java')]
        subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls] + src
                       + [os.path.join(HERE, f) for f in ('ChordTool.java', 'LyricTool.java')], check=True)
        bad = placed = 0
        for path in songs:
            song = json.load(open(path))
            title = song.get('_title', os.path.basename(path))
            times = song.get('_times')
            if not times:
                print(f'{title}: no synced lyrics, skipped')
                continue
            base = os.path.join(tmp, os.path.basename(path).replace('.json', ''))
            open(base + '.abc', 'w').write(song['abc'])
            subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.ChordTool', 'prep', tmp + '/prep', base + '.abc'], check=True)
            prepared = open(os.path.join(tmp, 'prep', os.path.basename(base) + '.abc')).read()
            svg, starts = engrave(chromium, prepared, tmp)
            problems = []
            want = bar_starts(prepared)
            got = [starts[k] for k in sorted(starts, key=int)]
            if [round(w) for w in want] != got:
                problems.append(f'line starts {got[:6]}... but counting bars gives {[round(w) for w in want][:6]}...')
            open(base + '.svg', 'w').write(svg)
            open(base + '.starts', 'w').write(json.dumps(starts))
            open(base + '.lyrics', 'w').write(song['lyrics'])
            out = subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.LyricTool', base + '.svg', base + '.starts', base + '.lyrics',
                                  ','.join(str(t) for t in times)], check=True, capture_output=True, text=True).stdout.splitlines()
            lyrics = [l.split('\t', 3)[1:] for l in out if l.startswith('LYRIC')]
            rows = {}
            for l in out:
                if l.startswith('ROW'):
                    _, line, text = l.split('\t', 2)
                    rows.setdefault(int(line), []).append(text)
            order = sorted(starts, key=lambda k: starts[k])
            expect = {}
            for i, t, text in lyrics:
                line = int(order[0])
                for k in order:
                    if starts[k] <= int(t):
                        line = int(k)
                expect.setdefault(line, []).append(text.strip())
            for line in sorted(set(expect) | set(rows)):
                if ' '.join(expect.get(line, [])) != ' '.join(rows.get(line, [])):
                    problems.append(f'line {line}: expected {expect.get(line)} got {rows.get(line)}')
            placed += len(lyrics)
            # a check on the sync: is the singer's line in the score near each lyric's time?
            tl = check.abcjs(lib, base + '.abc')
            bpm = int(re.search(r'Q:\s*1/4\s*=\s*(\d+)', song['abc']).group(1))
            onsets = [float(a) * 4 * 60000 / bpm for a, p, d in tl.get('track0', [])]
            lonely = [text[:30] for i, t, text in lyrics if not any(abs(o - int(t)) <= 1500 for o in onsets)]
            print(f'{title}: {len(lyrics)} lyric lines under {len(rows)} lines of music; '
                  f'{len(lonely)} with no vocal note in the score within 1.5 s' + (f' (e.g. {lonely[:3]})' if lonely else ''))
            if problems:
                bad += 1
                print('  ' + '; '.join(problems[:4]))
        print(f'{"all" if not bad else bad} songs {"pass" if not bad else "fail"} ({placed} lyric lines placed)')
        sys.exit(1 if bad else 0)

def engrave(chromium, abc, tmp):
    """the app's engrave.html, run as the phone runs it, with Killa answering into the page"""
    page = open(os.path.join(ASSETS, 'engrave.html')).read()
    page = page.replace('<script src="abcjs-basic-min.js"></script>', '<script src="file://' + os.path.join(ASSETS, 'abcjs-basic-min.js') + '"></script>'
                        '<script>window.Killa={starts:function(j){document.body.setAttribute("data-starts",j)},'
                        'svg:function(s){document.body.setAttribute("data-done","1")},fail:function(w){document.body.setAttribute("data-fail",w)}};</script>')
    page = page.replace('</body></html>', '<script>engrave(' + json.dumps(abc) + ',760,null)</script></body></html>')
    f = os.path.join(tmp, 'engrave.html')
    open(f, 'w').write(page)
    dom = subprocess.run([chromium, '--headless=new', '--no-sandbox', '--disable-gpu', '--allow-file-access-from-files', '--dump-dom',
                          'file://' + f], check=True, capture_output=True, text=True, timeout=300).stdout
    failed = re.search(r'data-fail="([^"]*)"', dom)
    if failed:
        sys.exit('engrave failed: ' + failed.group(1))
    starts = json.loads(re.search(r'data-starts="([^"]*)"', dom).group(1).replace('&quot;', '"'))
    i = dom.find('<svg')
    return dom[i:dom.find('</svg>', i) + 6], starts

def bar_starts(abc):
    """each music line's start in ms, counted here: bars before it times the bar's length"""
    m = re.search(r'^M:\s*(\d+)/(\d+)', abc, re.M)
    bpm = int(re.search(r'^Q:\s*1/4\s*=\s*(\d+)', abc, re.M).group(1))
    bar_ms = int(m.group(1)) / int(m.group(2)) * 4 * 60000 / bpm
    out, bars, body = [], 0, False
    for row in abc.split('\n'):
        r = row.strip()
        if r.startswith('K:'):
            body = True
            continue
        if not body or not r or r.startswith('%') or re.match(r'^[A-Za-z]:', r):
            continue
        out.append(bars * bar_ms)
        music = re.sub(r'\[[A-Za-z]:[^\]]*\]', '', re.sub(r'"[^"]*"', '', r))
        bars += music.count('|') + sum(int(n or 1) - 1 for n in re.findall(r'Z(\d*)', music))
    return out

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
