#!/usr/bin/env python3
"""Regenerate the embedded Vulkan 1.0 shader with an installed glslc compiler."""
import pathlib
import struct
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory() as tmp:
    output = pathlib.Path(tmp) / "matrix.spv"
    subprocess.run(["glslc", "--target-env=vulkan1.0", "-O", str(root / "shaders/matrix.comp"), "-o", str(output)], check=True)
    data = output.read_bytes()
words = struct.unpack("<" + "I" * (len(data) // 4), data)
(root / "src/matrix_spirv.rs").write_text(
    "// Generated from shaders/matrix.comp by shaders/compile.py. Do not edit.\n"
    "pub const MATRIX_SPIRV: &[u32] = &[\n" + "".join(
        "    " + ", ".join(f"0x{x:08x}" for x in words[i:i+8]) + ",\n"
        for i in range(0, len(words), 8)
    ) + "];\n"
)
