#!/bin/bash
# Plain-Java tests for the app's logic that needs no Android (score preparation and the like).
# Runs on any machine with a JDK: app/test/run.sh
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
javac -Xlint:-options --release 8 -d "$T" src/ai/jackknife/killayue/ScorePrep.java src/ai/jackknife/killayue/SvgScore.java src/ai/jackknife/killayue/Instruments.java src/ai/jackknife/killayue/Transpose.java src/ai/jackknife/killayue/Tabs.java src/ai/jackknife/killayue/SheetLayout.java src/ai/jackknife/killayue/Chords.java src/ai/jackknife/killayue/ChordSheet.java src/ai/jackknife/killayue/MusicXml.java src/ai/jackknife/killayue/LyricLines.java test/ai/jackknife/killayue/*Test.java \
    $(ls src/ai/jackknife/killayue/studio/*.java test/ai/jackknife/killayue/studio/*Test.java 2>/dev/null)
for t in test/ai/jackknife/killayue/*Test.java; do
    java -cp "$T" "ai.jackknife.killayue.$(basename "$t" .java)"
done
# the Studio's project model (ai.jackknife.killayue.studio): plain Java, no Android
for t in test/ai/jackknife/killayue/studio/*Test.java; do
    [ -f "$t" ] && java -cp "$T" "ai.jackknife.killayue.studio.$(basename "$t" .java)"
done
