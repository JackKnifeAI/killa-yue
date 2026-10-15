#!/usr/bin/env python3
"""Resume test for the checkpoint patch: an interrupted-and-resumed render must equal an uninterrupted one."""
import hashlib, json, os, shutil, subprocess, sys, time, urllib.request

BIN = os.path.expanduser("~/yue2-resume/build-host/yue-server")
M = os.path.expanduser("~/models/yue2")
PORT = 8099
LOG = "/tmp/yr-server.log"
log = open(LOG, "w")
srv = subprocess.Popen(["nice", "-n", "10", BIN, "--model", f"{M}/YuE2-3B-Q6_K.gguf", "--vae", f"{M}/YuE2-Vae-F32.gguf",
                        "--device", "CPU", "--host", "127.0.0.1", "--port", str(PORT)], stdout=log, stderr=subprocess.STDOUT)


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
    """Runs a synth job; cancel_when(log_since_start) -> True cancels it. Returns final status."""
    mark = len(log_text())
    jid = json.loads(http("POST", "/synth", req))["id"]
    while True:
        st = json.loads(http("GET", f"/job?id={jid}"))["status"]
        if st in ("done", "failed", "cancelled", "error"):
            return st
        if cancel_when and cancel_when(log_text()[mark:]):
            http("POST", f"/job?id={jid}&cancel=1")
            cancel_when = None
        time.sleep(0.5)


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()[:16] if os.path.exists(path) else "missing"


base = {"style": "lo-fi hip hop, mellow piano, soft drums, male vocal, 80 bpm",
        "lyrics": "[verse]\nThe phone is warm inside my hand\nThe night is long, the song is planned\n",
        "duration": 6, "steps": 6, "seed": 7, "lm_seed": 7, "cot": "full", "output_format": "wav16"}
for d in ("/tmp/cpA", "/tmp/cpB", "/tmp/cpD", "/tmp/cpE"):
    shutil.rmtree(d, ignore_errors=True)

print("A (compose + render, saving):", run(dict(base, checkpoint="/tmp/cpA")))
comp = json.load(open("/tmp/cpA/composed-0.json"))
print("  composed frames:", comp["frames"], "seed:", comp["seed"])
print("  score tokens saved:", len(comp.get("abc_tokens", "").split(",")) if comp.get("abc_tokens") else 0)
exact = dict(base, semantic_tokens=comp["semantic_tokens"], abc=comp["abc"], abc_tokens=comp["abc_tokens"], seed=comp["seed"])
print("E (replay with the saved score tokens, no chunking):", run(dict(exact, checkpoint="/tmp/cpE")))
a, e = digest("/tmp/cpA/render-0.bin"), digest("/tmp/cpE/render-0.bin")
print("original render A:", a, " replayed E:", e, " ->", "IDENTICAL" if a == e and a != "missing" else "DIFFERENT")
replay = dict(exact, max_chunk=60)

print("D (replay in chunks, uninterrupted):", run(dict(replay, checkpoint="/tmp/cpD")))


def second_chunk_step3(text):
    # the second chunk's third step: the solver's step counter restarts every chunk
    return text.count("[NAR] Step 3/6") >= 2


print("B (same replay, cancelled mid chunk 2):", run(dict(replay, checkpoint="/tmp/cpB"), second_chunk_step3))
print("  checkpoint after cancel:", subprocess.run(["sh", "-c", "grep -a '' /dev/null; ls -la /tmp/cpB"], capture_output=True, text=True).stdout.strip().splitlines()[-1])
print("C (resume B):", run(dict(replay, checkpoint="/tmp/cpB")))
tail = log_text().splitlines()
print("\n".join(l for l in tail if "[Checkpoint]" in l or "Resumed" in l)[-800:])
d, c = digest("/tmp/cpD/render-0.bin"), digest("/tmp/cpB/render-0.bin")
print("final render state  D:", d, " B+C:", c, " ->", "IDENTICAL" if d == c and d != "missing" else "DIFFERENT")
srv.terminate()
