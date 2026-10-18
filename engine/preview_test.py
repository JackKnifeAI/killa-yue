#!/usr/bin/env python3
"""Preview test: a song stopped mid-render can be heard as far as it got, and the preview leaves its checkpoint
as it was, so it still carries on to the same render as an uninterrupted one."""
import hashlib, io, json, os, shutil, struct, subprocess, sys, time, urllib.request, wave
import numpy as np

BIN = os.path.expanduser("~/yue2-resume/build-host/yue-server")
M = os.path.expanduser("~/models/yue2")
PORT = 8099
LOG = "/tmp/yp-server.log"
log = open(LOG, "w")
srv = subprocess.Popen(["nice", "-n", "10", BIN, "--model", f"{M}/YuE2-3B-Q6_K.gguf", "--vae", f"{M}/YuE2-Vae-F32.gguf",
                        "--device", "CPU", "--host", "127.0.0.1", "--port", str(PORT)], stdout=log, stderr=subprocess.STDOUT)
failures = []


def http(method, path, body=None, timeout=60):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


for _ in range(300):
    try:
        if b"ok" in http("GET", "/health", timeout=3):
            break
    except Exception:
        time.sleep(1)


def log_text():
    return open(LOG, errors="replace").read()


def run(req, cancel_when=None):
    """Runs a synth job; returns (status, result bytes or None)."""
    mark = len(log_text())
    jid = json.loads(http("POST", "/synth", req))["id"]
    while True:
        st = json.loads(http("GET", f"/job?id={jid}"))["status"]
        if st in ("done", "failed", "cancelled", "error"):
            return st, (http("GET", f"/job?id={jid}&result=1", timeout=120) if st == "done" else None)
        if cancel_when and cancel_when(log_text()[mark:]):
            http("POST", f"/job?id={jid}&cancel=1")
            cancel_when = None
        time.sleep(0.5)


def wav_of(raw):
    """The first WAV in a job result, as float stereo [n, 2]."""
    i = raw.find(b"RIFF")
    size = struct.unpack("<I", raw[i + 4:i + 8])[0] + 8
    w = wave.open(io.BytesIO(raw[i:i + size]))
    a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return a.reshape(-1, w.getnchannels()), w.getframerate()


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()[:16] if os.path.exists(path) else "missing"


def check(name, ok, detail=""):
    print(("ok   " if ok else "FAIL ") + name + (": " + detail if detail else ""))
    if not ok:
        failures.append(name)


def corr(a, b):
    a, b = a.mean(axis=1), b.mean(axis=1)
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


base = {"style": "lo-fi hip hop, mellow piano, soft drums, male vocal, 80 bpm",
        "lyrics": "[verse]\nThe phone is warm inside my hand\nThe night is long, the song is planned\n",
        "duration": 6, "steps": 6, "seed": 7, "lm_seed": 7, "cot": "full", "output_format": "wav16"}
for d in ("/tmp/ppA", "/tmp/ppB", "/tmp/ppD", "/tmp/ppN"):
    shutil.rmtree(d, ignore_errors=True)

st, _ = run(dict(base, checkpoint="/tmp/ppA"))
comp = json.load(open("/tmp/ppA/composed-0.json"))
replay = dict(base, semantic_tokens=comp["semantic_tokens"], abc=comp["abc"], abc_tokens=comp["abc_tokens"],
              seed=comp["seed"], max_chunk=60)
frames = comp["frames"]
print(f"composed {frames} frames ({frames / 25:.1f} s), chunks of 60")

st, raw_d = run(dict(replay, checkpoint="/tmp/ppD"))
full, rate = wav_of(raw_d)
check("D uninterrupted render", st == "done", f"{len(full) / rate:.2f} s")

st, _ = run(dict(replay, checkpoint="/tmp/ppB"), lambda t: t.count("[NAR] Step 3/6") >= 2)
check("B cancelled mid chunk 2", st == "cancelled")
before = digest("/tmp/ppB/render-0.bin")

st, raw_p = run(dict(replay, checkpoint="/tmp/ppB", preview=True))
check("P preview of B", st == "done")
prev, _ = wav_of(raw_p)
want = 120 / 25  # chunk 1 finished + chunk 2 from step 3: 120 frames
check("P length is chunk 1 + chunk 2", abs(len(prev) / rate - want) < 0.1, f"{len(prev) / rate:.2f} s, want {want:.2f}")
check("P left the checkpoint as it was", digest("/tmp/ppB/render-0.bin") == before, before)
n1 = int(2.0 * rate)  # well inside the finished chunk (2.4 s), away from the decode tile edge
c1 = corr(prev[:n1], full[:n1])
check("P finished part matches the real song", c1 > 0.98, f"correlation {c1:.4f}")
s2, e2 = int(2.6 * rate), min(len(prev), len(full), int(4.6 * rate))
c2 = corr(prev[s2:e2], full[s2:e2])
check("P guessed part resembles the real song", c2 > 0.5, f"correlation {c2:.4f}")
print("  preview log:", [l for l in log_text().splitlines() if "[Preview]" in l or "Preview from" in l][-2:])

st, _ = run(dict(replay, checkpoint="/tmp/ppB"))
d, c = digest("/tmp/ppD/render-0.bin"), digest("/tmp/ppB/render-0.bin")
check("C resume after the preview equals D", st == "done" and d == c and d != "missing", f"D {d}  B+P+C {c}")

os.makedirs("/tmp/ppN", exist_ok=True)
st, _ = run(dict(replay, checkpoint="/tmp/ppN", preview=True))
check("preview without a saved render fails cleanly", st in ("failed", "error"), st)
check("server still answers", b"ok" in http("GET", "/health"))
srv.terminate()
print("ALL PASS" if not failures else f"{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
