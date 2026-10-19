"""
Builds the Studio's acoustic sound packs (killa-<id>.zip) from freely redistributable recordings:
the Salamander Grand Piano V3 (Alexander Holm, CC BY 3.0) and Versilian Studios' VSCO 2 Community Edition (CC0).

  python3 build.py SALAMANDER_DIR VSCO_DIR OUT_DIR [pack ...]

SALAMANDER_DIR is the unpacked SalamanderGrandPiano-SFZ+FLAC-V3+20200602 folder; VSCO_DIR a checkout of
github.com/sgossner/VSCO-2-CE. Needs ffmpeg and numpy. For each pack it picks root notes and velocity layers,
measures every root's pitch (file names in these libraries disagree about octave numbering), trims silence,
extends sustained notes with crossfaded loops where a recording is shorter than a note may ring, levels the pack
as a whole (layers keep their real difference in loudness), and writes 16-bit 48 kHz FLAC with pack.json and the
licence. It prints each zip's size and SHA-256 for SoundPacks.PACKS.
"""
import hashlib, json, os, re, subprocess, sys, zipfile
import numpy as np
from pitch import yin, midi

RATE = 48000
NAMES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}

def note(name):
    m = re.fullmatch(r"([A-G])(#|b)?(-?\d)", name)
    k = NAMES[m.group(1)] + (1 if m.group(2) == "#" else -1 if m.group(2) == "b" else 0)
    return 12 * (int(m.group(3)) + 1) + k

def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ar", str(RATE), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    ch = int(subprocess.run(["ffprobe", "-v", "quiet", "-select_streams", "a:0", "-show_entries", "stream=channels",
                             "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.strip())
    x = np.frombuffer(raw, dtype=np.float32).reshape(-1, ch)
    if ch > 2:
        x = x[:, :2]
    return x

def trim(x, threshold=10 ** (-54 / 20)):
    level = np.max(np.abs(x), axis=1)
    loud = np.nonzero(level > threshold)[0]
    if len(loud) == 0:
        return x
    start = max(0, loud[0] - int(0.002 * RATE))
    return x[start:]

def extend(x, seconds):
    """Loops the steady middle with equal-power crossfades until the note can ring for seconds."""
    want = int(seconds * RATE)
    if len(x) >= want or len(x) < int(1.6 * RATE):
        return x
    a, b, fade = int(0.6 * RATE), len(x) - int(0.4 * RATE), int(0.15 * RATE)
    loop = x[a:b]
    if len(loop) < 3 * fade:
        return x
    out = x[:b].copy()
    t = np.linspace(0, np.pi / 2, fade)[:, None]
    while len(out) < want + int(0.4 * RATE):
        head = out[-fade:] * np.cos(t) + loop[:fade] * np.sin(t)
        out = np.concatenate([out[:-fade], head, loop[fade:]])
    tail = x[b:]  # the recording's own release, crossfaded on
    head = out[-fade:] * np.cos(t) + tail[:fade] * np.sin(t) if len(tail) > fade else out[-fade:]
    return np.concatenate([out[:-fade], head, tail[fade:]])

def cut(x, seconds):
    n = min(len(x), int(seconds * RATE))
    x = x[:n].copy()
    f = min(n, int(0.08 * RATE))
    x[n - f:] *= np.linspace(1, 0, f)[:, None]
    return x

def rms(x):
    return float(np.sqrt(np.mean(x[: int(1.0 * RATE)] ** 2)))

# ------------------------------------------------------------------ the packs

def vsco(base, folder):
    return os.path.join(base, folder)

def files(d, pattern):
    """(nominal key, dynamic, path) for every file matching pattern, first round-robin only."""
    out = []
    rx = re.compile(pattern)
    for f in sorted(os.listdir(d)):
        m = rx.fullmatch(f)
        if not m:
            continue
        g = m.groupdict()
        if g.get("rr") not in (None, "1", "01"):
            continue
        key = note(g["note"]) if g.get("note") else 35 + int(g["idx"]) if g.get("idx") else None
        out.append((key, g.get("dyn") or "", os.path.join(d, f)))
    return out

def packs(sal, vs):
    S = os.path.join(sal, "samples")
    P = {}
    P["grand"] = dict(name="Grand Piano", credit="Salamander Grand Piano V3 by Alexander Holm, CC BY 3.0",
                      layers=["5", "10", "15"], seconds=7, offset=0, every=1, sustain=False,
                      files=files(S, r"(?P<note>[A-G]#?\d)v(?P<dyn>\d+)\.flac"))
    up = os.path.join(vs, "Keys", "Upright Piano")
    chart = {}
    for line in open(os.path.join(up, "MappingChart.txt")):
        m = re.match(r"(\d{3})=(\d+)", line.strip())
        if m:
            chart[m.group(1)] = int(m.group(2))
    ups = []
    for f in sorted(os.listdir(up)):
        m = re.fullmatch(r"Player_dyn(\d)_rr1_(\d{3})\.wav", f)
        if m:
            ups.append((chart[m.group(2)], m.group(1), os.path.join(up, f)))
    P["upright"] = dict(name="Upright Piano", credit="VSCO 2 CE (Upright Piano by Simon Dalzell, Ivy Audio), CC0",
                        layers=["1", "3"], seconds=6, offset=0, every=1, sustain=False, files=ups)
    P["upright-bright"] = dict(name="Bright Upright", credit="VSCO 2 Community Edition by Versilian Studios, CC0",
                               layers=["pp", "f"], seconds=6, offset=0, every=1, sustain=False,
                               files=files(os.path.join(vs, "Keys", "Upright Nr1"), r"UR1_(?P<note>[A-G]#?\d)_(?P<dyn>pp|mf|f)_RR(?P<rr>\d)\.wav"))
    P["organ"] = dict(name="Organ", credit="VSCO 2 CE (Organ by Simon Dalzell, Ivy Audio), CC0",
                      layers=[""], seconds=6, offset=0, every=1, sustain=True,  # Man3Open_NN is key 35 + NN
                      files=files(os.path.join(vs, "Keys", "Organ", "Loud"), r"Rode_Man3Open_(?P<idx>\d+)\.wav"))
    strings = []
    for folder, pat, lo, hi in [("Cello Section/susvib", r"susvib_(?P<note>[A-G]#?\d)_(?P<dyn>v\d)_(?P<rr>\d)\.wav", 0, 54),
                                ("Viola Section/susvib", r"ViolaEns_susvib_(?P<note>[A-G]#?\d)_(?P<dyn>v\d)_(?P<rr>\d)\.wav", 55, 66),
                                ("Violin Section/susVib", r"VlnEns_susVib_(?P<note>[A-G]#?\d)_(?P<dyn>v\d)\.wav", 67, 127)]:
        for k, d, p in files(os.path.join(vs, "Strings", folder), pat):
            if lo <= k + 12 <= hi:
                strings.append((k, "v", p) if d == max(dd for kk, dd, pp in files(os.path.join(vs, "Strings", folder), pat) if kk == k) else (k, "skip", p))
    P["strings"] = dict(name="String Ensemble", credit="VSCO 2 Community Edition by Versilian Studios, CC0",
                        layers=["v"], seconds=6, offset=12, every=1, sustain=True, files=strings)
    pizz = [(k, "v", p) for k, d, p in files(os.path.join(vs, "Strings", "Cello Section", "pizzT"),
                                             r"pizzT_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_RR(?P<rr>\d)\.wav") if k + 12 < 55]
    pizz += [(k, "v", p) for k, d, p in files(os.path.join(vs, "Strings", "Violin Section", "Pizz"),
                                              r"VlnEns_Pizz_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_rr(?P<rr>\d)\.wav")]
    P["pizzicato"] = dict(name="Pizzicato Strings", credit="VSCO 2 Community Edition by Versilian Studios, CC0",
                          layers=["v"], seconds=3, offset=12, every=1, sustain=False, files=pizz)
    P["harp"] = dict(name="Harp", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["mf"], seconds=6,
                     offset=0, every=1, sustain=False,
                     files=files(os.path.join(vs, "Strings", "Harp"), r"KSHarp_(?P<note>[A-G]#?\d)_(?P<dyn>mf)\.wav"))
    P["flute"] = dict(name="Flute", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["v1"], seconds=5,
                      offset=12, every=1, sustain=True,
                      files=files(os.path.join(vs, "Woodwinds", "Flute", "susvib"), r"LDFlute_susvib_(?P<note>[A-G]#?\d)_(?P<dyn>v1)_(?P<rr>\d)\.wav"))
    P["clarinet"] = dict(name="Clarinet", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["v2"], seconds=5,
                         offset=12, every=1, sustain=True,
                         files=files(os.path.join(vs, "Woodwinds", "Clarinet", "susLong"), r"DCClar_susLong_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_rr(?P<rr>\d)_sum\.wav"))
    P["trumpet"] = dict(name="Trumpet", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["v2"], seconds=5,
                        offset=12, every=1, sustain=True,
                        files=files(os.path.join(vs, "Brass", "Trumpet", "susvib"), r"Sum_SHTrumpet_susvib_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_rr(?P<rr>\d)\.wav"))
    P["horn"] = dict(name="French Horn", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["v2"], seconds=5,
                     offset=12, every=1, sustain=True,
                     files=files(os.path.join(vs, "Brass", "F Horn", "sus"), r"MOHorn_sus_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_(?P<rr>\d)\.wav"))
    P["trombone"] = dict(name="Trombone", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["v2"], seconds=5,
                         offset=12, every=1, sustain=True,
                         files=files(os.path.join(vs, "Brass", "Tenor Trombone", "sus"), r"tenortbn_sus_(?P<note>[A-G]#?\d)_(?P<dyn>v2)_(?P<rr>\d)\.wav"))
    P["marimba"] = dict(name="Marimba", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["loud"], seconds=4,
                        offset=12, every=1, sustain=False,
                        files=files(os.path.join(vs, "Percussion", "Marimba"), r"Marimba_hit_Outrigger_(?P<note>[A-G]#?\d)_(?P<dyn>loud)_0(?P<rr>1)\.wav"))
    P["glockenspiel"] = dict(name="Glockenspiel", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["medium"],
                             seconds=4, offset=12, every=1, sustain=False,
                             files=files(os.path.join(vs, "Percussion", "Glock"), r"glock_(?P<dyn>medium)_(?P<note>[A-G]#?\d)\.wav"))
    P["xylophone"] = dict(name="Xylophone", credit="VSCO 2 Community Edition by Versilian Studios, CC0", layers=["ff"], seconds=3,
                          offset=12, every=1, sustain=False,
                          files=files(os.path.join(vs, "Percussion", "Xylo"), r"Xylo_Medium_(?P<note>[A-G]#?\d)_(?P<dyn>ff)_0(?P<rr>1)_far\.wav"))
    return P

LICENCE = {
    "Salamander": "Salamander Grand Piano V3 by Alexander Holm.\nLicensed under Creative Commons Attribution 3.0 "
                  "(https://creativecommons.org/licenses/by/3.0/).\nRecorded from a Yamaha C5. Trimmed, levelled and "
                  "encoded for Killa YuE; the original is at https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html\n",
    "VSCO": "From the Versilian Studios Chamber Orchestra 2 Community Edition (https://github.com/sgossner/VSCO-2-CE).\n"
            "Released under CC0 1.0 (public domain). Credit to the original authors and Versilian Studios is encouraged.\n"
            "Trimmed, levelled, looped where needed and encoded for Killa YuE.\n",
}

def build(pid, spec, out):
    roots = {}
    for nominal, dyn, path in spec["files"]:
        if dyn not in spec["layers"]:
            continue
        layer = spec["layers"].index(dyn)
        x = trim(load(path))
        f = yin(x[:, 0] if x.shape[1] == 1 else x.mean(axis=1))
        measured = midi(f)
        if spec["offset"] is None:  # no names: trust the measurement
            key = int(round(measured))
        else:
            key = nominal + spec["offset"]
            if abs(measured - key) > 0.6 and abs(measured - key - 12 * round((measured - key) / 12)) < 0.6:
                print(f"  {os.path.basename(path)}: named {key}, sounds {measured:.1f}; taking the octave it sounds", file=sys.stderr)
                key += 12 * int(round((measured - key) / 12))
            elif abs(measured - key) > 0.6:
                print(f"  {os.path.basename(path)}: named {key}, measured {measured:.1f} (unclear; keeping the name)", file=sys.stderr)
        if key < 21 or key > 108 or (key, layer) in roots:
            continue
        roots[(key, layer)] = x
    keys = sorted({k for k, l in roots})
    if spec["every"] > 1:
        keep = set(keys[:: spec["every"]] + [keys[-1]])
        roots = {kl: v for kl, v in roots.items() if kl[0] in keep}
    # the longest a key needs: its sound's seconds, pitched up by at most the gap to the next root
    gap = max([b - a for a, b in zip(keys, keys[1:])] or [12])
    need = spec["seconds"] * 2 ** (min(gap, 12) / 2 / 12) + 0.3
    peak = max(float(np.max(np.abs(v))) for v in roots.values())
    gain = 10 ** (-1 / 20) / peak
    os.makedirs(out, exist_ok=True)
    zpath = os.path.join(out, f"killa-{pid}.zip")
    entries = []
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
        for (key, layer), x in sorted(roots.items()):
            if spec["sustain"]:
                x = extend(x, need)
            x = cut(x * gain, need)
            pcm = (np.clip(x, -1, 1) * 32767).round().astype("<i2")
            name = f"{key}-{layer}.flac"
            enc = subprocess.run(["ffmpeg", "-v", "quiet", "-f", "s16le", "-ar", str(RATE), "-ac", str(x.shape[1]), "-i", "-",
                                  "-c:a", "flac", "-compression_level", "8", "-f", "flac", "-"],
                                 input=pcm.tobytes(), capture_output=True, check=True).stdout
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 18, 0, 0, 0))
            z.writestr(info, enc)
            entries.append({"key": key, "layer": layer, "file": name})
        manifest = {"id": pid, "name": spec["name"], "credit": spec["credit"], "rate": RATE,
                    "layers": len(spec["layers"]), "roots": entries}
        z.writestr(zipfile.ZipInfo("pack.json", date_time=(2026, 10, 18, 0, 0, 0)), json.dumps(manifest, indent=1))
        lic = LICENCE["Salamander" if pid == "grand" else "VSCO"]
        z.writestr(zipfile.ZipInfo("LICENSE.txt", date_time=(2026, 10, 18, 0, 0, 0)), lic)
    data = open(zpath, "rb").read()
    print(f'{pid}\t{len(data)}\t{hashlib.sha256(data).hexdigest()}\t{len(entries)} roots, keys {keys[0]}-{keys[-1]}')

if __name__ == "__main__":
    sal, vs, out = sys.argv[1:4]
    want = sys.argv[4:]
    for pid, spec in packs(sal, vs).items():
        if not want or pid in want:
            build(pid, spec, out)
