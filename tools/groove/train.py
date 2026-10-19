"""
Trains Killa Groove, the Studio's drum-groove model, on the Groove MIDI Dataset (Magenta, CC BY 4.0):
13.6 hours of real drummers in 18 styles. Each 16th-note step holds, for nine drum voices, whether it is hit, how
hard, and how far off the grid (GrooVAE's representation, Gillick et al. 2019), so grooves keep a human feel. A
two-layer GRU learns to play the next step from the steps before, the style and the place in the bar.

  python3 train.py GROOVE_DIR OUT.bin

Needs torch and mido. Writes the weights as little-endian float32 for the app's Java player (GrooveModel.java).
"""
import csv, os, random, struct, sys
import mido, numpy as np, torch, torch.nn as nn

VOICES = [[35, 36], [38, 40, 37], [42, 22, 44], [46, 26], [43, 58], [47, 45], [48, 50], [49, 52, 55, 57], [51, 53, 59]]
NAMES = ["kick", "snare", "closed hat", "open hat", "low tom", "mid tom", "high tom", "crash", "ride"]
STYLES = ["rock", "funk", "jazz", "latin", "hiphop", "soul", "pop", "afrobeat", "reggae", "punk", "gospel", "blues",
          "country", "neworleans", "dance", "highlife", "middleeastern", "afrocuban"]
V = len(VOICES)
F = 3 * V
STEPS = 64  # four bars of sixteenths per training sequence

def voice(pitch):
    for i, ps in enumerate(VOICES):
        if pitch in ps:
            return i
    return None

def load(path, bpm):
    mid = mido.MidiFile(path)
    t, hits = 0.0, []
    for msg in mid:  # iterating a MidiFile yields delta seconds, tempo changes applied
        t += msg.time
        if msg.type == "note_on" and msg.velocity > 0:
            v = voice(msg.note)
            if v is not None:
                hits.append((t, v, msg.velocity / 127.0))
    if not hits:
        return None
    step = 60.0 / bpm / 4
    n = int(round(hits[-1][0] / step)) + 2
    x = np.zeros((n, F), np.float32)
    for t, v, vel in hits:
        s = int(round(t / step))
        off = t / step - s
        if s < 0 or s >= n:
            continue
        if x[s, v] == 0 or vel > x[s, V + v]:
            x[s, v] = 1
            x[s, V + v] = vel
            x[s, 2 * V + v] = max(-.5, min(.5, off))
    return x

def style_of(s):
    head = s.split("/")[0].split("-")[0]
    return STYLES.index(head) if head in STYLES else None

def dataset(root):
    seqs = []
    for row in csv.DictReader(open(os.path.join(root, "info.csv"))):
        if row["beat_type"] != "beat" or row["time_signature"] != "4-4":
            continue
        st = style_of(row["style"])
        if st is None:
            continue
        x = load(os.path.join(root, row["midi_filename"]), float(row["bpm"]))
        if x is None:
            continue
        for start in range(0, len(x) - STEPS, 16):  # every bar, four bars long
            seg = x[start:start + STEPS]
            if seg[:, :V].sum() >= 16:
                seqs.append((st, seg, row["split"]))
    return seqs

class Groove(nn.Module):
    def __init__(self, hidden=256):
        super().__init__()
        self.inp = F + len(STYLES) + 16
        self.gru = nn.GRU(self.inp, hidden, num_layers=2, batch_first=True, dropout=0.3)
        self.drop = nn.Dropout(0.2)
        self.out = nn.Linear(hidden, F)
    def forward(self, x, h=None):
        y, h = self.gru(x, h)
        return self.out(self.drop(y)), h

def inputs(st, seg):
    """Step t's input: step t-1 (zeros first), the style, and t's place in the bar."""
    n = seg.shape[0]
    prev = np.vstack([np.zeros((1, F), np.float32), seg[:-1]])
    style = np.zeros((n, len(STYLES)), np.float32)
    style[:, st] = 1
    pos = np.zeros((n, 16), np.float32)
    pos[np.arange(n), np.arange(n) % 16] = 1
    return np.concatenate([prev, style, pos], 1)

def loss(pred, target):
    hit = target[..., :V]
    l_hit = nn.functional.binary_cross_entropy_with_logits(pred[..., :V], hit)
    vel = torch.sigmoid(pred[..., V:2 * V])
    off = torch.tanh(pred[..., 2 * V:]) * .5
    m = hit.sum().clamp(min=1)
    l_vel = ((vel - target[..., V:2 * V]) ** 2 * hit).sum() / m
    l_off = ((off - target[..., 2 * V:]) ** 2 * hit).sum() / m
    return l_hit + l_vel + 2 * l_off, l_hit

def main():
    root, out = sys.argv[1], sys.argv[2]
    torch.manual_seed(7)
    random.seed(7)
    data = dataset(root)
    train = [d for d in data if d[2] == "train"]
    valid = [d for d in data if d[2] != "train"]
    print(f"{len(train)} training sequences, {len(valid)} held out", flush=True)
    model = Groove()
    opt = torch.optim.AdamW(model.parameters(), 1.5e-3, weight_decay=1e-4)
    def augment(seg):
        """Another take of the same phrase: a little louder or softer, a little earlier or later per hit."""
        s = seg.copy()
        hit = s[:, :V] > 0
        s[:, V:2 * V] = np.where(hit, np.clip(s[:, V:2 * V] * np.random.uniform(.85, 1.15) +
                                              np.random.normal(0, .04, hit.shape), .05, 1), 0)
        s[:, 2 * V:] = np.where(hit, np.clip(s[:, 2 * V:] + np.random.normal(0, .03, hit.shape), -.5, .5), 0)
        return s
    def batch(items, train=False):
        segs = [augment(seg) if train else seg for _, seg, _ in items]
        x = torch.tensor(np.stack([inputs(st, seg) for (st, _, _), seg in zip(items, segs)]))
        y = torch.tensor(np.stack(segs))
        return x, y
    vx, vy = batch(valid)
    best, best_state = 1e9, None
    torch.set_num_threads(6)
    epochs, patience, waited = int(os.environ.get("EPOCHS", "60")), 8, 0
    for epoch in range(epochs):
        random.shuffle(train)
        model.train()
        for i in range(0, len(train), 64):
            x, y = batch(train[i:i + 64], True)
            p, _ = model(x)
            l, _ = loss(p, y)
            opt.zero_grad()
            l.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
        model.eval()
        with torch.no_grad():
            vl, vh = loss(model(vx)[0], vy)
        print(f"epoch {epoch + 1}: held-out loss {vl.item():.4f} (hits {vh.item():.4f})", flush=True)
        if vl.item() < best:
            best, best_state, waited = vl.item(), {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            waited += 1
            if waited >= patience:
                print(f"no better for {patience} epochs: stopping", flush=True)
                break
        for g in opt.param_groups:
            g["lr"] *= .95
    model.load_state_dict(best_state)
    # format: magic, version, input, hidden, layers, outputs, then for each layer W_ih, W_hh, b_ih, b_hh
    # (PyTorch order: reset, update, new), then the output layer's weight and bias
    sd = model.state_dict()
    with open(out, "wb") as f:
        f.write(b"KGRV")
        f.write(struct.pack("<5i", 1, model.inp, 256, 2, F))
        for layer in range(2):
            for name in ["weight_ih", "weight_hh", "bias_ih", "bias_hh"]:
                f.write(sd[f"gru.{name}_l{layer}"].numpy().astype("<f4").tobytes())
        f.write(sd["out.weight"].numpy().astype("<f4").tobytes())
        f.write(sd["out.bias"].numpy().astype("<f4").tobytes())
    print(f"best held-out loss {best:.4f}; wrote {out} ({os.path.getsize(out)} bytes)")

if __name__ == "__main__":
    main()
