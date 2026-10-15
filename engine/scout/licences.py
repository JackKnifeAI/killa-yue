"""Write the licence appendix for libkillascout.so: every crate compiled into it, with its own licence files.

Usage: python3 -I licences.py METADATA_JSON THIRD_PARTY_LICENSES.md
METADATA_JSON is `cargo metadata --format-version 1 --filter-platform aarch64-linux-android --features android`.
"""
import hashlib, json, os, sys

meta = json.load(open(sys.argv[1]))
nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
pkgs = {p["id"]: p for p in meta["packages"]}
root = meta["resolve"]["root"]

# normal dependencies only: what is compiled into the library (no build scripts' or proc-macros' build-only deps)
seen, stack = set(), [root]
while stack:
    i = stack.pop()
    if i in seen:
        continue
    seen.add(i)
    for d in nodes[i]["deps"]:
        if any(k["kind"] in (None, "normal") for k in d["dep_kinds"]):
            stack.append(d["pkg"])
seen.discard(root)

crates = sorted((pkgs[i] for i in seen), key=lambda p: (p["name"].lower(), p["version"]))
NAMES = ("LICENSE", "LICENCE", "COPYING", "NOTICE", "UNLICENSE", "COPYRIGHT")
texts = {}   # sha -> (text, [crate labels])
missing = []
for p in crates:
    d = os.path.dirname(p["manifest_path"])
    files = sorted(f for f in os.listdir(d) if f.upper().startswith(NAMES) and os.path.isfile(os.path.join(d, f)))
    if p.get("license_file"):
        lf = os.path.normpath(os.path.join(d, p["license_file"]))
        if os.path.isfile(lf) and os.path.basename(lf) not in files:
            files.append(os.path.relpath(lf, d))
    label = "%s %s" % (p["name"], p["version"])
    if not files:
        missing.append(p)
        continue
    for f in files:
        t = open(os.path.join(d, f), encoding="utf-8", errors="replace").read().strip("\n")
        h = hashlib.sha256(" ".join(t.split()).encode()).hexdigest()  # same text, different wrapping: one copy
        texts.setdefault(h, [t, []])[1].append("%s (%s)" % (label, f))

out = []
w = out.append
w("# Rust crates in libkillascout.so (research for Write with AI)")
w("")
w("`libkillascout.so` is built from `engine/scout` (MIT) with Rust and the crates below, at the exact versions in")
w("`engine/scout/Cargo.lock`. Rust's standard library is MIT OR Apache-2.0. Generated from `cargo metadata` and each")
w("crate's own licence files; where a crate offers a choice of licences, all of its licence files are")
w("reproduced.")
w("")
w("The MPL-2.0 crates (`cssparser`, `cssparser-macros`, `dtoa-short`, `selectors`) are used unmodified; their source")
w("code is at https://crates.io/crates/NAME/VERSION for the versions listed here.")
w("")
w("## The crates")
w("")
w("| Crate | Version | Licence |")
w("|---|---|---|")
for p in crates:
    w("| %s | %s | %s |" % (p["name"], p["version"], p.get("license") or "see its licence file"))
w("")
if missing:
    w("## Crates without a licence file in their package")
    w("")
    w("These packages declare their licence in their manifest only; the standard text of that licence, as reproduced")
    w("below for other crates, applies.")
    w("")
    for p in missing:
        w("- %s %s: %s (%s)" % (p["name"], p["version"], p.get("license"), p.get("repository") or "no repository listed"))
    w("")
w("## Licence texts")
w("")
w("Each text below is reproduced from the crates that carry it, exactly as packaged.")
w("")
for h, (t, who) in sorted(texts.items(), key=lambda kv: kv[1][1][0].lower()):
    w("### " + ", ".join(who))
    w("")
    fence = "````" if "```" in t else "```"
    w(fence)
    w(t)
    w(fence)
    w("")
open(sys.argv[2], "w").write("\n".join(out))
print(len(crates), "crates;", len(texts), "distinct licence texts;", len(missing), "without a licence file")
for p in missing:
    print("  no file:", p["name"], p["version"], p.get("license"))
