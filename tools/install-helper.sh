#!/bin/sh
# Killa YuE network helper: one line turns a computer into a helper node that lets phones anywhere reach each other,
# and, if you name a music folder, hosts every song in it (new songs appear by themselves).
#
#   curl -fsSL https://github.com/JackKnifeAI/killa-yue/releases/latest/download/install-helper.sh | sh
#   curl -fsSL https://github.com/JackKnifeAI/killa-yue/releases/latest/download/install-helper.sh | sh -s -- ~/Music
#
# 64-bit Intel/AMD Linux with systemd (others: BUILDING.md). Needs Java 17 or newer. It runs as you, never as root, listens on port 47291
# (TCP and UDP; opening it on your router lets it help the most) and shares only the folder you name. Remove it with
#   sh ~/.local/share/killa-yue-helper/uninstall.sh
set -eu
REPO="${KILLA_HELPER_FROM:-https://github.com/JackKnifeAI/killa-yue/releases/latest/download}"
HOME_DIR="${KILLA_HELPER_HOME:-$HOME/.local/share/killa-yue-helper}"
MUSIC="${1:-}"

say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { say "Killa YuE helper needs $1. $2"; exit 1; }; }

need curl "Install curl with your package manager."
need unzip "Install unzip with your package manager."
if ! command -v java >/dev/null 2>&1; then
    say "Killa YuE helper needs Java 17 or newer:"
    say "  Debian/Ubuntu/Mint: sudo apt install openjdk-17-jre-headless"
    say "  Fedora: sudo dnf install java-17-openjdk-headless    Arch: sudo pacman -S jre-openjdk-headless"
    say "  macOS: brew install openjdk"
    exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "${JAVA_MAJOR:-0}" -ge 17 ] || { say "Java $JAVA_MAJOR is too old; Killa YuE helper needs Java 17 or newer."; exit 1; }
if [ -n "$MUSIC" ]; then
    [ -d "$MUSIC" ] || { say "No folder at $MUSIC"; exit 1; }
    MUSIC=$(cd "$MUSIC" && pwd)
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) ;;
    *) say "This helper build runs on 64-bit Intel/AMD Linux. Other systems: see BUILDING.md (peer/build-native.sh)."; exit 1 ;;
esac

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM
say "Downloading the Killa YuE helper…"
curl -fsSL "$REPO/killa-yue-helper.zip" -o "$TMP/helper.zip"
curl -fsSL "$REPO/SHA256SUMS" -o "$TMP/SHA256SUMS"
WANT=$(grep ' killa-yue-helper.zip$' "$TMP/SHA256SUMS" | cut -d' ' -f1)
GOT=$( (sha256sum "$TMP/helper.zip" 2>/dev/null || shasum -a 256 "$TMP/helper.zip") | cut -d' ' -f1)
[ -n "$WANT" ] && [ "$WANT" = "$GOT" ] || { say "The download does not match the release's SHA256SUMS; nothing was installed."; exit 1; }

mkdir -p "$HOME_DIR/state"
rm -rf "$HOME_DIR/hammer-server"
unzip -q "$TMP/helper.zip" -d "$HOME_DIR"
APP="$HOME_DIR/hammer-server"
ARGS="--daemon --help-network --upnp --network-port 47291 --data $HOME_DIR/state --address 127.0.0.1 --bind 127.0.0.1"
[ -n "$MUSIC" ] && ARGS="$ARGS --watch $MUSIC"
JAVA=$(command -v java)

if command -v systemctl >/dev/null 2>&1 && [ "$(uname -s)" = Linux ]; then
    mkdir -p "$HOME/.config/systemd/user"
    cat > "$HOME/.config/systemd/user/killa-yue-helper.service" <<EOF
[Unit]
Description=Killa YuE network helper
After=network-online.target

[Service]
WorkingDirectory=$APP
ExecStart=$JAVA -XX:ActiveProcessorCount=2 -Xms32m -Xmx384m -Djava.awt.headless=true -Djava.library.path=native/linux-x86_64 -jar hammer-server.jar $ARGS
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable killa-yue-helper.service
    # restart, not start: run again over an earlier install, the new version must replace the one still running
    systemctl --user restart killa-yue-helper.service
    loginctl enable-linger "$(id -un)" 2>/dev/null || say "(It runs while you are logged in; 'sudo loginctl enable-linger $(id -un)' keeps it running always.)"
    cat > "$HOME_DIR/uninstall.sh" <<EOF
systemctl --user disable --now killa-yue-helper.service
rm -f "$HOME/.config/systemd/user/killa-yue-helper.service"
rm -rf "$HOME_DIR"
echo "Killa YuE helper removed."
EOF
else
    say "No systemd here: start it yourself with"
    say "  cd $APP && java -Djava.awt.headless=true -Djava.library.path=native/linux-x86_64 -jar hammer-server.jar $ARGS"
    printf 'rm -rf "%s"\necho "Killa YuE helper removed."\n' "$HOME_DIR" > "$HOME_DIR/uninstall.sh"
fi

say ""
say "Killa YuE helper is running. Thank you for helping phones find each other."
[ -n "$MUSIC" ] && say "Every song in $MUSIC is shared publicly, with the cover, title and lyrics inside each file; new songs appear within five minutes."
say "Port 47291 (TCP and UDP) open on your router lets it help the most. Remove it: sh $HOME_DIR/uninstall.sh"
