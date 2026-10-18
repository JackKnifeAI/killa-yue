#!/usr/bin/env bash
# Apply the complete app patch stack to a clean public source checkout.
set -euo pipefail
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 CLEAN_YUE_SOURCE (with its pinned ggml submodule initialized)" >&2
    exit 2
fi
source_dir=$(realpath "$1")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
expected=83b65f5d6bf4806557ef17fe763021ff4eead928
if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$expected" ]; then
    echo "Expected yue2.cpp $expected. Review newer GGML revisions separately." >&2
    exit 1
fi
if [ "$(git -C "$source_dir/ggml" rev-parse HEAD)" != 2eddaf94a8cdbbf1c30017d28313cd661a9da543 ]; then
    echo "Wrong GGML revision; initialize the pinned submodule first." >&2
    exit 1
fi
for patch in yue2-gentle yue2-jni yue2-resume yue2-preview ggml-vulkan-mobile \
             yue2-context-budget yue2-mobile-weights yue2-mobile-backend yue2-android-runtime; do
    git -C "$source_dir" apply --check "$script_dir/$patch.patch"
    git -C "$source_dir" apply "$script_dir/$patch.patch"
done
echo "Applied nine mobile patches to pinned public sources."
