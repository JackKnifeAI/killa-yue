"""Prints the measured pitch of sample files (YIN on the steady part), to check a library's octave naming."""
import subprocess, sys
import numpy as np

def load(path, rate=48000):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(rate), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)

def yin(x, rate=48000, fmin=25, fmax=4200):
    start = int(np.argmax(np.abs(x) > 0.05 * np.max(np.abs(x)))) if len(x) else 0
    seg = x[start + int(0.15 * rate): start + int(0.15 * rate) + 8192]
    if len(seg) < 4096:
        seg = x[start: start + 8192]
    w = len(seg) // 2
    tau_max = min(int(rate / fmin), w - 1)
    d = np.array([np.sum((seg[:w] - seg[t:t + w]) ** 2) for t in range(tau_max)])
    d[0] = 1
    cmnd = d * np.arange(len(d)) / np.maximum(np.cumsum(d), 1e-12)
    lo = int(rate / fmax)
    for t in range(lo, len(cmnd) - 1):
        if cmnd[t] < 0.15 and cmnd[t] <= cmnd[t + 1]:
            return rate / t
    return rate / (lo + int(np.argmin(cmnd[lo:])))

def midi(f):
    return 69 + 12 * np.log2(f / 440.0)

if __name__ == "__main__":
    for p in sys.argv[1:]:
        f = yin(load(p))
        print(f"{midi(f):6.1f}  {f:8.1f} Hz  {p.rsplit('/', 1)[-1]}")
