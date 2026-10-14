#!/bin/bash
# Plain-Java tests for the app's logic that needs no Android (score preparation and the like).
# Runs on any machine with a JDK: app/test/run.sh
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
javac -Xlint:-options --release 8 -d "$T" src/ai/jackknife/killayue/ScorePrep.java src/ai/jackknife/killayue/SvgScore.java src/ai/jackknife/killayue/Instruments.java src/ai/jackknife/killayue/Transpose.java test/ai/jackknife/killayue/*Test.java
for t in test/ai/jackknife/killayue/*Test.java; do
    java -cp "$T" "ai.jackknife.killayue.$(basename "$t" .java)"
done
