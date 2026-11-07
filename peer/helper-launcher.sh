#!/bin/sh
# Starts the Killa YuE helper. systemd runs this, and runs it again whenever the helper exits. It moves to a new version
# when one is waiting, and goes back to the one before if the new one can't prove it works after two starts. It lives
# outside the versions on purpose, so a bad release can't break the way back.
set -u
cd "$(dirname "$0")"
KILLA_HELPER_HOME=$(pwd)
export KILLA_HELPER_HOME
. ./launcher.env
U=update
mkdir -p "$U"

if [ -f "$U/next" ]; then
    cp "$U/current" "$U/previous"
    mv "$U/next" "$U/current"
    echo 0 > "$U/trying"
fi
if [ -f "$U/trying" ]; then
    starts=$(($(cat "$U/trying") + 1))
    if [ "$starts" -gt 2 ] && [ -s "$U/previous" ]; then
        echo "Version $(cat "$U/current") never proved itself: going back to $(cat "$U/previous")"
        cat "$U/current" >> "$U/bad"
        cp "$U/previous" "$U/current"
        rm -f "$U/trying"
    else
        echo "$starts" > "$U/trying"
    fi
fi

cd "versions/$(cat "$U/current")/hammer-server" || exit 1
# shellcheck disable=SC2086
exec "$JAVA" -XX:ActiveProcessorCount=2 -Xms32m -Xmx384m -Djava.awt.headless=true \
    -Djava.library.path=native/linux-x86_64 -jar hammer-server.jar $ARGS
