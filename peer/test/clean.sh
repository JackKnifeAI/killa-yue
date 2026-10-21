#!/bin/bash
# Songs leave the phone without data that identifies the artist (HammerClean). Builds tagged MP3, FLAC and WAV files
# the way other programs write them, with a cover carrying GPS in its EXIF, then checks every clean copy: the
# identifying data is gone, the kept tags and the cover are there, and the audio decodes to the same samples.
# The artist fields carry the artist's Killa YuE name, or nothing before there is one. A song made by Killa YuE and a
# bare WAV must come out unchanged. Needs java, ffmpeg, ffprobe and python3 with PIL.
set -euo pipefail
cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM
mkdir -p "$tmp/classes"
javac -Xlint:-options --release 8 -d "$tmp/classes" app/src/ai/jackknife/killayue/peer/*.java peer/test/CleanTest.java
cd "$tmp"

# (the MP3 encoder's own "Lavc"/"LAME" mark inside the first audio frame is part of the audio and stays)
SECRETS=(Secret Exif GPS APETAGEX LYRICSBEGIN Lavf "2026-05-01" PRIV TXXX TYER TDAT TCOM TENC TCOP TSRC COMM)
fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

python3 - <<'EOF'
from PIL import Image
import struct
img = Image.new("RGB", (64, 64), (200, 120, 40))
exif = Image.Exif()
exif[0x013B] = "Real Name Secret"            # Artist
exif[0x0110] = "Secret Phone Model"           # Model
gps = {1: "N", 2: (40.0, 26.0, 46.0), 3: "W", 4: (79.0, 58.0, 56.0)}
exif[0x8825] = gps                           # GPSInfo
img.save("cover-gps.jpg", exif=exif.tobytes(), comment=b"Secret comment in the picture")
img.save("cover-plain.jpg")                  # JFIF only, like Android's encoder
EOF
check "test cover really carries GPS" "grep -q GPS cover-gps.jpg || grep -q Exif cover-gps.jpg"

meta=(-metadata title="Song Title" -metadata artist="Stage Name" -metadata album="Album X" -metadata genre="Protest"
      -metadata comment="recorded at 12 Secret St" -metadata date="2026-05-01" -metadata composer="Real Name Secret"
      -metadata encoded_by="Real Name Secret" -metadata copyright="Secret Label" -metadata ISRC="USSECRET0001")
src=(-f lavfi -i "sine=frequency=330:duration=4")

ffmpeg -v error -y "${src[@]}" -i cover-gps.jpg -map 0 -map 1 -c:a libmp3lame -b:a 128k -c:v copy -id3v2_version 3 \
    "${meta[@]}" -disposition:v attached_pic tagged-raw.mp3
python3 - <<'EOF'
import struct
d = open("tagged-raw.mp3", "rb").read()
# an APEv2 tag and a Lyrics3 v2 block, then an ID3v1 tag, the way old taggers leave them at the end
item = b"Secret Owner"
ape_items = struct.pack("<II", len(item), 0) + b"Artist\0" + item
footer = b"APETAGEX" + struct.pack("<IIII", 2000, len(ape_items) + 32, 1, 0) + b"\0" * 8
lyr = b"LYRICSBEGIN" + b"IND00002" + b"10"
lyr += b"%06d" % (len(lyr)) + b"LYRICS200"
v1 = b"TAG" + b"Song Title".ljust(30, b"\0") + b"Real Name Secret".ljust(30, b"\0") + b"\0" * 30 + b"2026" + \
     b"Secret".ljust(30, b"\0") + b"\xff"
open("tagged.mp3", "wb").write(d + ape_items + footer + lyr + v1)
EOF
ffmpeg -v error -y "${src[@]}" -i cover-gps.jpg -map 0 -map 1 -c:a flac -c:v copy "${meta[@]}" \
    -disposition:v attached_pic tagged.flac
ffmpeg -v error -y "${src[@]}" -c:a pcm_s16le "${meta[@]}" tagged.wav
ffmpeg -v error -y "${src[@]}" -c:a pcm_s16le -bitexact -map_metadata -1 bare.wav

# a song as Killa YuE writes it: ID3v2.4 with title, the Killa YuE mark, lyrics and a plain JPEG cover
ffmpeg -v error -y "${src[@]}" -c:a libmp3lame -b:a 128k -id3v2_version 0 -write_xing 0 -map_metadata -1 audio-only.mp3
python3 - <<'EOF'
def ss(n): return bytes([(n >> 21) & 127, (n >> 14) & 127, (n >> 7) & 127, n & 127])
def frame(i, body): return i.encode() + ss(len(body)) + b"\0\0" + body
def text(s): return b"\x03" + s.encode()
cover = open("cover-plain.jpg", "rb").read()
f = frame("TIT2", text("Made in Killa")) + frame("TSSE", text("Killa YuE"))
f += frame("USLT", b"\x03eng\0" + "line one\nline two\n".encode())
f += frame("SYLT", b"\x03eng\x02\x01\0" + b"line one\0\0\0\0\0" + b"line two\0\0\0\x07\xd0")
f += frame("APIC", b"\0image/jpeg\0\x03\0" + cover)
audio = open("audio-only.mp3", "rb").read()
open("killa-made.mp3", "wb").write(b"ID3\x04\0\0" + ss(len(f)) + f + audio)
# an album export, which also names the artist
f += frame("TALB", text("Album X")) + frame("TPE1", text("Ghost Name")) + frame("TPE2", text("Ghost Name"))
open("killa-album.mp3", "wb").write(b"ID3\x04\0\0" + ss(len(f)) + f + audio)
EOF
cp tagged.mp3 nameless.mp3
cp tagged.flac nameless.flac
cp tagged.wav nameless.wav

java -cp "$tmp/classes" ai.jackknife.killayue.peer.CleanTest --artist "Ghost Name" tagged.mp3 tagged.flac tagged.wav \
    bare.wav killa-made.mp3 killa-album.mp3 | tee result.txt
java -cp "$tmp/classes" ai.jackknife.killayue.peer.CleanTest nameless.mp3 nameless.flac nameless.wav | tee -a result.txt

pcm() { ffmpeg -v error -i "$1" -map 0:a -f s16le -ac 1 - | md5sum | cut -d' ' -f1; }
tags() { ffprobe -v error -show_entries format_tags:stream_tags -of default=nw=1 "$1" | tr 'A-Z' 'a-z'; }

for f in tagged.mp3 tagged.flac tagged.wav; do
    c=${f%.*}.clean.${f##*.}
    echo "$f"
    check "a clean copy was made" "grep -q 'cleaned $f' result.txt"
    check "the input really carried the secrets" "grep -aq Secret $f && grep -aq 2026 $f"
    for s in "${SECRETS[@]}"; do
        check "no '$s' in the copy" "! grep -aq -- '$s' $c"
    done
    check "same audio samples" "[ \"\$(pcm $f)\" = \"\$(pcm $c)\" ]"
    check "title kept" "tags $c | grep -q 'title=song title'"
    check "artist is the Killa YuE name" "tags $c | grep -q 'tag:artist=ghost name'"
    check "no 'Stage Name' from the file" "! grep -aq 'Stage Name' $c && ! grep -aq 'S.t.a.g.e. .N.a.m.e' $c"
    if [ "$f" != tagged.wav ]; then
        check "album kept" "tags $c | grep -q 'album=album x'"
        check "genre kept" "tags $c | grep -q 'genre=protest'"
        check "cover kept" "ffprobe -v error -select_streams v -show_entries stream=codec_name -of csv=p=0 $c | grep -q mjpeg"
        check "cover still decodes" "ffmpeg -v error -i $c -map 0:v -frames:v 1 -f null -"
    fi
    for k in comment date composer encoded_by encoder copyright isrc; do
        check "no $k tag" "! tags $c | grep -q '^$k='"
    done
done
for f in nameless.mp3 nameless.flac nameless.wav; do
    c=${f%.*}.clean.${f##*.}
    echo "$f, published before there is an artist name"
    check "no artist tag" "! tags $c | grep -q 'artist='"
    check "no 'Stage Name'" "! grep -aq 'Stage Name' $c && ! grep -aq 'S.t.a.g.e. .N.a.m.e' $c"
    check "title kept" "tags $c | grep -q 'title=song title'"
done
echo "unchanged files"
check "Killa YuE's own song is published as it is" "grep -q 'unchanged killa-made.mp3' result.txt"
check "a Killa YuE album song already naming the artist is published as it is" \
    "grep -q 'unchanged killa-album.mp3' result.txt"
check "a bare WAV is published as it is" "grep -q 'unchanged bare.wav' result.txt"

echo "publishing through the store"
java -cp "$tmp/classes" ai.jackknife.killayue.peer.CleanTest --store "$tmp/store" tagged.mp3 killa-made.mp3 | tee store.txt
check "store checks" "grep -q 'store: all checks passed' store.txt"

[ $fail = 0 ] && echo "clean.sh: all checks passed" || { echo "clean.sh: FAILED"; exit 1; }
