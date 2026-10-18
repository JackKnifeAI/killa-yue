#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
javac --release 8 -d "$stage" "$root/app/src/ai/jackknife/killayue/compat/CompatibilityPlan.java" \
    "$root/app/src/ai/jackknife/killayue/compat/DiagnosticProcess.java" "$root"/compatlab/test/*.java
java -cp "$stage" ai.jackknife.killayue.compat.CompatibilityTest
python3 -m unittest discover -s "$root/tools/compatibility" -p 'test_*.py'
