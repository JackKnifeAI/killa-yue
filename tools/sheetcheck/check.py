"""check.py ABCJS_FILE SCORE.abc... : proves the sheet-music pipeline keeps every note.

For each score:
  1. the app's ScorePrep (compiled from app/src) prepares it, as the app does before engraving;
  2. abcjs's timeline of the prepared score must equal its timeline of the original (every track:
     voices and the chord accompaniment, every note's start, pitch and length, ties joined);
  3. music21, an independent reader, must hear the same voices in the original as abcjs does.
Run with a Python that has music21, for example: python -I check.py abcjs-basic.js songs/*.abc
"""
import json, os, re, subprocess, sys, tempfile
from fractions import Fraction
import music21

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', '..', 'app')

def prepared(scores, tmp):
    cls = os.path.join(tmp, 'classes')
    subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls,
                    os.path.join(APP, 'src/ai/jackknife/killayue/ScorePrep.java'), os.path.join(HERE, 'PrepTool.java')], check=True)
    out = os.path.join(tmp, 'prepared')
    subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.PrepTool', out] + scores, check=True)
    return out

def abcjs(lib, path):
    raw = subprocess.run(['node', os.path.join(HERE, 'timeline.js'), lib, path], check=True, capture_output=True, text=True).stdout
    return {k: [(Fraction(s).limit_denominator(4096), p, Fraction(d).limit_denominator(4096)) for s, p, d in v] for k, v in json.loads(raw).items()}

def split_voices(text):
    """One single-voice score per voice: music21 runs voices together when their lines take turns."""
    head, order, music, cur, body = [], [], {}, None, False
    for row in text.replace('\r', '').split('\n'):
        r = row.strip()
        if not body:
            if r.startswith('V:'):
                vid = r[2:].split()[0]
                order.append(vid)
                music.setdefault(vid, [])
            else:
                head.append(row)
            body = r.startswith('K:')
            continue
        if r.startswith('V:'):
            cur = r[2:].split()[0]
            if cur not in music:
                order.append(cur)
                music[cur] = []
        elif r and not r.startswith('%') and cur is not None:
            music[cur].append(r)
    # music21 counts a multi-bar rest Zn as one bar; the ABC standard (and abcjs) make it n bars
    meter = next((h[2:].strip() for h in head if h.startswith('M:')), '4/4')
    meter = {'C': '4/4', 'C|': '2/2'}.get(meter, meter)
    unit = next((h[2:].strip() for h in head if h.startswith('L:')), '1/8')
    bar = Fraction(meter) / Fraction(unit)
    full = 'z' + (str(bar.numerator) if bar.denominator == 1 else f'{bar.numerator}/{bar.denominator}')
    spell = lambda m: '|'.join([full] * int(m.group(1) or 1))
    return ['\n'.join(head + [carry(re.sub(r'Z(\d*)', spell, line)) for line in music[v]]) + '\n' for v in order]

NOTE = re.compile(r'("[^"]*")|([\^_=]*)([A-Ga-g][,\']*)|(\|)')

def carry(line):
    """Writes out accidentals that carry to later notes of the same pitch in a bar (ABC 2.1, as in printed
    music); music21's ABC reader does not carry them."""
    held = {}
    def one(m):
        if m.group(1):
            return m.group(1)
        if m.group(4):
            held.clear()
            return '|'
        acc, pitch = m.group(2), m.group(3)
        if acc:
            held[pitch] = acc
            return acc + pitch
        return held.get(pitch, '') + pitch
    return NOTE.sub(one, line)

def music21_voices(path):
    voices = []
    for text in split_voices(open(path).read()):
        part = music21.converter.parse(text, format='abc').parts[0]
        notes = []
        for n in part.stripTies().flatten().notes:
            if isinstance(n, music21.harmony.ChordSymbol):
                continue  # chord names above the staff: abcjs plays them in a track of their own
            for p in (n.pitches if n.isChord else [n.pitch]):
                notes.append((Fraction(n.offset).limit_denominator(4096) / 4, p.midi, Fraction(n.quarterLength).limit_denominator(4096) / 4))
        voices.append(sorted(notes))
    return voices

def first_difference(a, b):
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return f'note {i}: {x} vs {y}'
    return f'lengths {len(a)} vs {len(b)}' if len(a) != len(b) else None

def main(lib, scores):
    bad = 0
    with tempfile.TemporaryDirectory() as tmp:
        prep = prepared(scores, tmp)
        for s in scores:
            name = os.path.basename(s)
            orig, prepd = abcjs(lib, s), abcjs(lib, os.path.join(prep, name))
            problems = []
            for k in orig:
                d = first_difference(orig[k], prepd.get(k, []))
                if d: problems.append(f'prepared {k} differs at {d}')
            m21 = music21_voices(s)
            for i, voice in enumerate(m21):
                d = first_difference(sorted(orig.get(f'track{i}', [])), voice)
                if d: problems.append(f'music21 voice {i} differs from abcjs at {d}')
            notes = sum(len(v) for v in orig.values())
            print(f'{name}: ' + ('OK, ' + str(notes) + ' notes identical' if not problems else 'PROBLEM\n    ' + '\n    '.join(problems)))
            bad += bool(problems)
    print(f'{len(scores) - bad} of {len(scores)} scores pass')
    sys.exit(1 if bad else 0)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
