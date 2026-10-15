"""check_musicxml.py ABCJS_FILE XSD SCORE.abc... : proves the MusicXML export keeps every note and chord.

Every score, and every voice of it written out for every instrument (the 684 parts), is exported as the app
exports it (MusicXml.java, compiled from app/src). Each file must:
  1. validate against the MusicXML 4.0 schema (XSD, from w3c/musicxml);
  2. read back in music21 (an independent reader) and, at sounding pitch, hold exactly the song's notes:
     every start, pitch and length (ties joined) as abcjs reads the original score, moved by the whole
     octaves a part says it moved;
  3. hold one chord symbol for each chord name in the score, at the same moment, sounding the same notes as
     music21 reads the name itself.
Run with a Python that has music21: python -I check_musicxml.py abcjs-basic.js musicxml-local.xsd songs/*.abc
"""
import os, re, subprocess, sys, tempfile
from fractions import Fraction
from multiprocessing import Pool
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check  # timeline helpers from check.py
APP = os.path.join(HERE, '..', '..', 'app')

def main(lib, xsd, scores):
    with tempfile.TemporaryDirectory() as tmp:
        cls = os.path.join(tmp, 'classes')
        src = [os.path.join(APP, 'src/ai/jackknife/killayue', f) for f in
               ('ScorePrep.java', 'Instruments.java', 'Transpose.java', 'Chords.java', 'MusicXml.java')]
        subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls] + src + [os.path.join(HERE, 'XmlTool.java')], check=True)
        out = os.path.join(tmp, 'xml')
        subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.XmlTool', out] + scores, check=True)
        rows = [l.split('\t') for l in open(os.path.join(out, 'files.tsv')).read().splitlines()]
        files = [os.path.join(out, r[0]) for r in rows]
        v = subprocess.run(['xmllint', '--noout', '--schema', xsd] + files, capture_output=True, text=True)
        invalid = {l.split(':')[0] for l in v.stderr.splitlines() if 'validates' not in l and l.strip()}
        concert = {}
        for s in scores:
            tl = check.abcjs(lib, s)
            name = os.path.basename(s).replace('.abc', '')
            concert[(name, 'Vocal')], concert[(name, 'Ins')] = tl.get('track0', []), tl.get('track1', [])
        originals = {os.path.basename(s).replace('.abc', ''): open(s).read() for s in scores}
        jobs = []
        for (f, song, voice, ins, octaves), path in zip(rows, files):
            if voice == 'score':
                abc = originals[song]
                want = [notes_of(concert[(song, 'Vocal')], 0), notes_of(concert[(song, 'Ins')], 0)]
                names = [chord_names(abc, 'Vocal'), chord_names(abc, 'Ins')]
            else:
                abc = open(path.replace('.musicxml', '.abc')).read()
                want = [notes_of(concert[(song, voice)], 12 * int(octaves))]
                names = [chord_names(abc, 'P')]
            jobs.append((path, f, want, names))
        with Pool(6) as pool:
            results = pool.map(check_file, jobs)
        bad = notes = chords = 0
        for (path, f, want, names), (problems, n, c) in zip(jobs, results):
            if path in invalid:
                problems = ['not valid MusicXML 4.0: ' + next(l for l in v.stderr.splitlines() if l.startswith(path))[len(path):][:200]] + problems
            notes += n
            chords += c
            if problems:
                bad += 1
                print(f + ': ' + '; '.join(problems[:3]))
        print(f'{len(jobs) - bad} of {len(jobs)} MusicXML files pass ({notes} notes and {chords} chord symbols checked)')
        sys.exit(1 if bad else 0)

def notes_of(timeline, shift):
    return sorted((Fraction(a).limit_denominator(4096) * 4, p + shift, Fraction(d).limit_denominator(4096) * 4) for a, p, d in timeline)

TOKEN = re.compile(r'("[^"]*")|([\^_=]*[A-Ga-g][,\']*)(\d*)(/*)(\d*)|([zx])(\d*)(/*)(\d*)|Z(\d*)|(-)|(::|\[\||\|\]|:?\|\|?:?)|( +)')

def chord_names(abc, voice):
    """(quarter-note time, name) of every chord name in the voice, read here, apart from the app's reader"""
    unit = Fraction(re.search(r'^L:\s*(\d+)/(\d+)', abc, re.M).group(0)[2:].strip().replace(' ', '')) * 4
    m = re.search(r'^M:\s*(\d+)/(\d+)', abc, re.M)
    bar = Fraction(int(m.group(1)), int(m.group(2))) * 4
    t, cur, out, body = Fraction(0), None, [], False
    for row in abc.split('\n'):
        r = row.strip()
        if r.startswith('K:'):
            body = True
            continue
        if not body or not r or r.startswith('%'):
            continue
        if r.startswith('V:'):
            cur = r[2:].split()[0]
            continue
        if cur != voice:
            continue
        for g in TOKEN.finditer(r):
            if g.group(1):
                out.append((t, g.group(1)[1:-1]))
            elif g.group(2) or g.group(6):
                num, slashes, den = (g.group(3), g.group(4), g.group(5)) if g.group(2) else (g.group(7), g.group(8), g.group(9))
                t += unit * int(num or 1) / (int(den) if den else 2 ** len(slashes))
            elif g.group(10) is not None:
                t += bar * int(g.group(10) or 1)
    return out

def figure(name):
    f = name.replace('\u266d', 'b').replace('\u266f', '#')
    f = re.sub(r'^([A-G])b', r'\1-', f)
    return re.sub(r'/([A-G])b', r'/\1-', f)

def check_file(job):
    from music21 import converter, harmony
    path, f, want, names = job
    problems, n, c = [], 0, 0
    try:
        score = converter.parse(path)
    except Exception as e:
        return [f'music21 cannot read it: {e}'], 0, 0
    parts = list(score.parts)
    if len(parts) != len(want):
        return [f'{len(parts)} parts, not {len(want)}'], 0, 0
    for k, (p, w, nm) in enumerate(zip(parts, want, names)):
        written = p.flatten()
        got_chords = [(Fraction(cs.offset).limit_denominator(4096), sorted({x.pitchClass for x in cs.pitches}))
                      for cs in written.getElementsByClass(harmony.ChordSymbol)]
        want_chords = [(t, sorted({x.pitchClass for x in harmony.ChordSymbol(figure(name)).pitches})) for t, name in nm]
        if got_chords != want_chords:
            first = next((i for i, (a, b) in enumerate(zip(got_chords, want_chords)) if a != b), min(len(got_chords), len(want_chords)))
            problems.append(f'part {k + 1}: chord symbols differ from chord {first + 1} '
                            f'({got_chords[first] if first < len(got_chords) else None} vs {want_chords[first] if first < len(want_chords) else None})')
        c += len(got_chords)
        sounding = p.toSoundingPitch() if not p.atSoundingPitch else p
        got = sorted((Fraction(x.offset).limit_denominator(4096), x.pitch.midi, Fraction(x.quarterLength).limit_denominator(4096))
                     for x in sounding.flatten().stripTies().getElementsByClass('Note'))
        d = check.first_difference(w, got)
        if d:
            problems.append(f'part {k + 1}: not the song\'s notes: {d}')
        n += len(got)
    return problems, n, c

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
