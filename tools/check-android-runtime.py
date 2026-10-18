#!/usr/bin/env python3
"""Inspect the actual APK's portable libraries, dependencies and ARM64 ELF alignment."""
import argparse
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import tempfile
import zipfile


def audit(apk, readelf):
    variants = ["armv8.0_1", "armv8.2_1", "armv8.2_2", "armv8.6_1", "armv9.0_1", "armv9.2_1", "armv9.2_2"]
    required = {"libyueserver_portable.so", "libyueengine_portable.so", "libkyggml.so", "libkyggml-base.so", "libkyvulkan.so"}
    required.update("libkycpu-android_" + v + ".so" for v in variants)
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libvulkan.so"}
    results, failures = [], []
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
        libraries = {pathlib.PurePosixPath(n).name: n for n in archive.namelist() if n.startswith("lib/arm64-v8a/") and n.endswith(".so")}
        for name in sorted(required - libraries.keys()):
            failures.append("Missing " + name)
        for name in libraries:
            if name.startswith("libggml-") or name in ("libggml.so", "libggml-base.so"):
                failures.append("Legacy engine could discover portable backend: " + name)
        for name, member in sorted(libraries.items()):
            data = archive.read(member)
            if data[:4] != b"\x7fELF":
                failures.append("Not an ELF: " + name)
                continue
            machine = struct.unpack_from("<H", data, 18)[0]
            if machine != 183:  # EM_AARCH64; the QNN Hexagon payload is separate.
                results.append({"file": name, "machine": machine, "arm64": False})
                continue
            if data[4:6] != b"\x02\x01":
                failures.append("Unexpected ARM64 ELF format: " + name)
                continue
            phoff = struct.unpack_from("<Q", data, 32)[0]
            entsize, count = struct.unpack_from("<HH", data, 54)
            alignments = []
            for i in range(count):
                p = struct.unpack_from("<IIQQQQQQ", data, phoff + i * entsize)
                if p[0] == 1:
                    alignments.append(p[7])
                    if p[7] < 16384 or p[2] % 16384 != p[3] % 16384:
                        failures.append("16 KB PT_LOAD alignment failed: " + name)
            item = {"file": name, "arm64": True, "load_alignments": alignments, "sha256": hashlib.sha256(data).hexdigest()}
            if name in required:
                path = pathlib.Path(temp) / name
                path.write_bytes(data)
                dynamic = subprocess.check_output([readelf, "-d", str(path)], text=True)
                needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
                item["needed"] = needed
                for dep in needed:
                    if dep not in system and dep not in libraries:
                        failures.append(name + " needs unshipped " + dep)
                    if ("vulkan" in dep or "turnip" in dep) and name != "libkyvulkan.so":
                        failures.append("CPU path links GPU dependency: " + name + " -> " + dep)
                if name == "libkyvulkan.so" and "libvulkan.so" not in needed:
                    failures.append("System GPU module must link Android libvulkan.so")
                if "libc++_shared.so" not in needed:
                    failures.append("Portable modules must use the packaged shared C++ runtime: " + name)
            results.append(item)
    return {"apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "libraries": results, "failures": failures, "passed": not failures}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    result = audit(args.apk, args.readelf)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
