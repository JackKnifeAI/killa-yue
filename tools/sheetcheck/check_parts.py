"""check_parts.py ABCJS_FILE SCORE.abc... : proves every instrument part keeps every note.

For every score, both voices and every instrument in the app (Instruments.java), the part the app writes
(Transpose + ScorePrep, compiled from app/src) must:
  1. read without warnings in abcjs;
  2. be written, note for note, as the concert voice moved by exactly the instrument's interval plus the
     whole octaves the part says it moved (same starts, same lengths, ties joined), as read by abcjs
     (which plays a treble-8 clef an octave lower, as the clef means) and by music21 (an independent reader);
  4. be in a key of at most 7 sharps or flats.
Run with a Python that has music21: python -I check_parts.py abcjs-basic.js songs/*.abc
"""
import json, os, subprocess, sys, tempfile
from fractions import Fraction
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check  # timeline helpers from check.py
APP = os.path.join(HERE, '..', '..', 'app')

def main(lib, scores):
    with tempfile.TemporaryDirectory() as tmp:
        cls = os.path.join(tmp, 'classes')
        src = [os.path.join(APP, 'src/ai/jackknife/killayue', f) for f in ('ScorePrep.java', 'Instruments.java', 'Transpose.java')]
        subprocess.run(['javac', '-Xlint:-options', '--release', '8', '-d', cls] + src + [os.path.join(HERE, 'PartTool.java')], check=True)
        out = os.path.join(tmp, 'parts')
        subprocess.run(['java', '-cp', cls, 'ai.jackknife.killayue.PartTool', out] + scores, check=True)
        rows = [l.split('\t') for l in open(os.path.join(out, 'parts.tsv')).read().splitlines()]
        listing = os.path.join(tmp, 'list.txt')
        open(listing, 'w').write('\n'.join(os.path.join(out, r[0]) for r in rows))
        raw = subprocess.run(['node', os.path.join(HERE, 'partline.js'), lib, listing], check=True, capture_output=True, text=True).stdout
        parts = {os.path.basename(j['file']): j for j in map(json.loads, raw.splitlines())}
        concert = {}
        for s in scores:
            tl = check.abcjs(lib, s)
            name = os.path.basename(s).replace('.abc', '')
            concert[(name, 'Vocal')], concert[(name, 'Ins')] = tl.get('track0', []), tl.get('track1', [])
        bad = checked = notes = 0
        for f, song, voice, ins, semis, octaves, key, n, out_of_range, *_ in rows:
            problems = []
            part = parts[f]
            if part['warnings']:
                problems.append(f"{part['warnings']} abcjs warnings")
            # a treble clef with an 8 below sounds an octave under what is written: abcjs plays it that way
            text = open(os.path.join(out, f)).read()
            clef8 = 12 if 'clef=treble-8' in text else 0
            got = sorted((Fraction(a).limit_denominator(4096), p + clef8, Fraction(d).limit_denominator(4096)) for a, p, d in part['notes'])
            shift = int(semis) + 12 * int(octaves)
            want = sorted((a, p + shift, d) for a, p, d in concert[(song, voice)])
            d = check.first_difference(want, got)
            if d:
                problems.append('abcjs: not the concert voice moved ' + str(shift) + ' semitones: ' + d)
            m21 = check.music21_voices(os.path.join(out, f))  # music21 reads the written notes
            d = check.first_difference(want, m21[0] if m21 else [])
            if d:
                problems.append('music21: not the concert voice moved ' + str(shift) + ' semitones: ' + d)
            if abs(check_fifths(key)) > 7:
                problems.append('key ' + key + ' has too many accidentals')
            checked += 1
            notes += len(got)
            if problems:
                bad += 1
                print(f'{song} {voice} {ins} ({key}, {octaves} octaves): ' + '; '.join(problems))
        print(f'{checked - bad} of {checked} parts pass ({notes} notes checked)')
        sys.exit(1 if bad else 0)

def check_fifths(key):
    letters, fifths = 'CDEFGAB', [0, 2, 4, -1, 1, 3, 5]
    f = fifths[letters.index(key[0])] + (7 if key[1:2] == '#' else -7 if key[1:2] == 'b' else 0)
    return f - 3 if key.endswith('m') else f

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
