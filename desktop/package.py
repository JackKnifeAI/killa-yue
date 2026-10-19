#!/usr/bin/env python3
"""Build a platform-labelled preview with pinned dependency licenses and hashes."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

root = pathlib.Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=pathlib.Path)
args = parser.parse_args()
manifest = str(root / "Cargo.toml")
subprocess.run(["cargo", "build", "--release", "--locked", "--manifest-path", manifest], check=True)
target = next(line.split(": ", 1)[1] for line in subprocess.check_output(["rustc", "-vV"], text=True).splitlines() if line.startswith("host: "))
# Include every dependency resolved for this package's target, including build
# dependencies. Unused GNU Windows import libraries are not part of an MSVC,
# Linux or macOS binary and their old crates omit standalone license files.
subprocess.run(["cargo", "fetch", "--locked", "--target", target, "--manifest-path", manifest], check=True)
metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--locked", "--filter-platform", target, "--format-version", "1", "--manifest-path", manifest]))
resolved = {node["id"] for node in metadata["resolve"]["nodes"]}
executable = "killa-desktop.exe" if os.name == "nt" else "killa-desktop"
binary = pathlib.Path(metadata["target_directory"]) / "release" / executable
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    folder = pathlib.Path(tmp) / "killa-desktop-lab"
    folder.mkdir()
    shutil.copy2(binary, folder / executable)
    shutil.copy2(root / "README.md", folder / "README.md")
    shutil.copy2(root.parent / "LICENSE", folder / "LICENSE")
    shutil.copy2(root / "Cargo.lock", folder / "Cargo.lock")
    dependencies = []
    for package in metadata["packages"]:
        if package["name"] == "killa-desktop" or package["id"] not in resolved:
            continue
        source = pathlib.Path(package["manifest_path"]).parent
        destination = folder / "licenses" / f'{package["name"]}-{package["version"]}'
        destination.mkdir(parents=True)
        files = [p for p in source.iterdir() if p.is_file() and p.name.lower().startswith(("license", "licence", "copying", "copyright"))]
        if package.get("license_file"):
            files.append(source / package["license_file"])
        if not files:
            raise RuntimeError(f'No license text found for {package["name"]}')
        for p in set(files):
            shutil.copy2(p, destination / p.name)
        dependencies.append({"name": package["name"], "version": package["version"], "license": package["license"]})
    try:
        commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL).strip()
        dirty = bool(subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True))
    except subprocess.CalledProcessError:
        commit, dirty = "unknown", True
    build = {"schema": 1, "target": target, "source_commit": commit, "source_dirty": dirty, "stable_release": False, "dependencies": dependencies}
    (folder / "build.json").write_text(json.dumps(build, indent=2) + "\n")
    hashes = "".join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(folder).as_posix()}\n' for p in sorted(folder.rglob("*")) if p.is_file())
    (folder / "SHA256SUMS").write_text(hashes)
    archive = args.output / f"killa-desktop-lab-{target}.zip"
    # Cargo normalizes some source timestamps to before ZIP's 1980 epoch.
    # Clamp those timestamps and only expose the finished archive.
    temporary_archive = archive.with_suffix(".zip.tmp")
    with zipfile.ZipFile(temporary_archive, "w", zipfile.ZIP_DEFLATED, strict_timestamps=False) as z:
        for p in sorted(folder.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(pathlib.Path(tmp)).as_posix())
    temporary_archive.replace(archive)
    print(archive)
