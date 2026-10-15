# Studio 1a: project model and mixing core — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Studio's foundation, testable on ops without a phone: a project model with stable IDs, undo, and crash-safe saving; and a C++ mixing core that turns a session of stems into a clean stereo mix, ready for the Oboe device layer (plan 1b).

**Architecture:** The project model is plain Java (`ai.jackknife.killayue.studio`), with no Android classes, so `app/test/run.sh` tests it on ops. It has its own small JSON reader and writer, because `org.json` is Android-only. The mixing core is C++17 in `studio/native/`. Its parts:
- `Mixer` renders tracks from sources into stereo through constant-power pan, mute/solo rules and a lookahead safety limiter;
- `Spsc` is a lock-free queue for edits;
- `Engine` applies queued edits at the start of each block and never locks, allocates or touches files in `process()`;
- `Streamer` keeps each track's audio read ahead by a disk thread;
- `Wav` reads and writes float WAV/RF64;
- `Export` renders a session offline through the same `Mixer`.

A host test runner builds everything with AddressSanitizer and UBSan, and the threaded tests with ThreadSanitizer.

**Tech Stack:** Java 8 (javac `--release 8`), C++17 (g++ on ops, NDK clang later), POSIX threads, no third-party code in this plan.

**Spec:** `docs/designs/2026-10-14-studio.md` (build order step 1, "Remix"). Later plans: 1b Oboe device layer and JNI bridge; 1c Studio screen, import, mixer UI and export; 1d stems. 1d starts with measuring Demucs on the S25, and needs the phone.

## Global Constraints

- Java: 8 bytecode, no lambdas, no `java.util.function`, no streams (the app is dexed with classic dx). The studio package must not import `android.*`.
- C++: C++17. `Engine::process()` and everything it calls must not lock, allocate, free, throw or do I/O.
- Project audio is stored at the project's rate as 32-bit float WAV (RF64 past 4 GB). The rate is chosen when the project is created (48000 by default); files at other rates are converted on import (plan 1c), never in the real-time path.
- Pan: constant power. Master: soft safety limiter with lookahead, ceiling −0.3 dBFS; never a hard clip.
- Every ID is stable and unique within a project ("t1", "c1", ...). Undo and redo restore exactly the same IDs.
- Saving writes `project.json.tmp`, flushes it to disk, then renames it over `project.json`, keeping the previous good file as `project.json.bak`. Loading falls back to the backup.
- Commits end with `Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>`.

## Review Focus

1. **A half-written or damaged `project.json`** (power cut mid-save, a hand edit). Loading must fall back to `project.json.bak`, say that it did, and never crash. Tests: Task 4, Step 1.
2. **Four stems at full scale summed** (stems are often mastered loud). The master output must never exceed −0.3 dBFS, and below the ceiling it must pass unchanged. Tests: Task 5, Step 1.
3. **The disk thread falls behind, or the user seeks.** `process()` must never wait. Missing audio becomes silence and is counted, not a crash or a stall. Tests: Task 7, Step 1.
4. **Remove a track, undo, redo.** It must give exactly the same project, IDs and JSON included. Tests: Task 3, Step 1.
5. **Names with quotes, backslashes, emoji or new lines.** They must round-trip through JSON exactly. Tests: Task 1, Step 1.

---

## File structure

```
app/src/ai/jackknife/killayue/studio/
  Json.java          small JSON reader/writer (objects as LinkedHashMap, arrays as ArrayList)
  Project.java       Project, Track, Clip; IDs; validation; to/from JSON
  Edit.java          one undoable change (apply/revert) and the edits phase 1 needs
  History.java       undo/redo stacks
  ProjectStore.java  save (tmp, flush, rename, backup) and load (with fallback)
app/test/ai/jackknife/killayue/studio/
  JsonTest.java ProjectTest.java HistoryTest.java ProjectStoreTest.java
app/test/run.sh      (modify) also compile studio/*.java and run studio/*Test.java

studio/native/
  include/studio/mix.h       gains, pan law, mute/solo, Mixer, Limiter
  include/studio/spsc.h      lock-free single-producer single-consumer queue
  include/studio/engine.h    Engine: session snapshot, messages, process(), status
  include/studio/streamer.h  per-track read-ahead ring fed by a disk thread
  include/studio/wav.h       float32 WAV/RF64 read and write
  include/studio/export.h    offline render to WAV
  src/mix.cpp engine.cpp streamer.cpp wav.cpp export.cpp
  test/test.h                tiny assertion harness
  test/mix_test.cpp spsc_test.cpp engine_test.cpp streamer_test.cpp wav_test.cpp export_test.cpp
  test/run.sh                builds and runs every test (ASan+UBSan; TSan for threaded ones)
```

---

### Task 1: JSON reader and writer

**Files:**
- Create: `app/src/ai/jackknife/killayue/studio/Json.java`
- Create: `app/test/ai/jackknife/killayue/studio/JsonTest.java`
- Modify: `app/test/run.sh`

**Interfaces:**
- Produces:
  - `static Object Json.parse(String text)`: returns `LinkedHashMap<String,Object>`, `ArrayList<Object>`, `String`, `Double`, `Boolean` or `null`. Throws `IllegalArgumentException("JSON: ...")` with the offset.
  - `static String Json.write(Object value)`: compact JSON, keys in insertion order. Numbers that are whole print without ".0". Throws for NaN or infinity.

- [ ] **Step 1: Write the failing test** (`JsonTest.java`)

```java
package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class JsonTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        String text = "{\"name\":\"Gold \\\"in\\\" the\\\\Wire\\n\\u00e9 \\ud83c\\udfb5\",\"gain\":-3.5,\"n\":48000,\"on\":true,\"x\":null,\"list\":[1,2,[]]}";
        Object v = Json.parse(text);
        same("round trip keeps every character, order and number", text.replace("\\u00e9", "é").replace("\\ud83c\\udfb5", "🎵"), Json.write(v));
        LinkedHashMap<?, ?> m = (LinkedHashMap<?, ?>) v;
        same("strings unescaped", "Gold \"in\" the\\Wire\né 🎵", m.get("name"));
        same("numbers are doubles", -3.5, m.get("gain"));
        same("whole numbers print without .0", "48000", Json.write(m.get("n")));
        same("nested arrays", "[1,2,[]]", Json.write(m.get("list")));
        boolean threw = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().startsWith("JSON:");
        }
        same("a trailing comma is an error with a message", true, threw);
        threw = false;
        try {
            Json.parse("{\"a\":1");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        same("a cut-off file is an error", true, threw);
        threw = false;
        try {
            Json.write(Double.NaN);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        same("NaN cannot be written", true, threw);
        same("control characters are escaped", "\"a\\u0001b\\tc\"", Json.write("a\u0001b\tc"));
        ArrayList<Object> empty = new ArrayList<Object>();
        same("empty array", "[]", Json.write(empty));
        System.out.println("JsonTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: Make run.sh build and run the studio package; run it and see it fail**

In `app/test/run.sh`, after the existing `javac` line, add a second compile that includes the studio sources and tests. After the existing test loop, add a loop for `test/ai/jackknife/killayue/studio/*Test.java` that runs `ai.jackknife.killayue.studio.<Name>`. The existing compile line already lists the old sources explicitly; add `src/ai/jackknife/killayue/studio/*.java test/ai/jackknife/killayue/studio/*Test.java` to it.

Run: `bash app/test/run.sh`
Expected: javac fails, `cannot find symbol: Json`.

- [ ] **Step 3: Write `Json.java`**

A recursive-descent parser over a `String` with an index:
- **Values:** object, array, string (with `\" \\ \/ \b \f \n \r \t \uXXXX`, keeping surrogate pairs as two chars), number (`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`, parsed with `Double.parseDouble`), `true`, `false` and `null`. Whitespace is allowed between tokens.
- **End of input:** after the top-level value only whitespace may remain.
- **Errors:** `throw new IllegalArgumentException("JSON: " + what + " at " + index)`.
- **Writer:** `Map` writes as an object in iteration order. `List` writes as an array. `String` is escaped: `"` and `\`, control characters below 0x20 as `\uXXXX`, except `\n \r \t` which use their short forms. `Number`: NaN or infinity throws; a whole double within ±2^53 prints as a long; otherwise `Double.toString`. `Boolean` and `null` print as words.

- [ ] **Step 4: Run tests**

Run: `bash app/test/run.sh`
Expected: `JsonTest: 10 passed, 0 failed`, and every older suite still 0 failed.

- [ ] **Step 5: Commit**

```bash
git add app/src/ai/jackknife/killayue/studio/Json.java app/test/ai/jackknife/killayue/studio/JsonTest.java app/test/run.sh
git commit -m "Studio: a small JSON reader and writer for the project model

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 2: Project, tracks and clips

**Files:**
- Create: `app/src/ai/jackknife/killayue/studio/Project.java`
- Create: `app/test/ai/jackknife/killayue/studio/ProjectTest.java`

**Interfaces:**
- Consumes: `Json.parse`, `Json.write`.
- Produces:
  - `class Project { int version = 1; String name; int rate; ArrayList<Track> tracks; int nextId; float masterDb; String newId(String prefix); Track track(String id); Clip clip(String id); void validate(); LinkedHashMap<String,Object> toJson(); static Project fromJson(Object json); String toText(); static Project fromText(String text); Project copy(); }`
  - `class Track { String id, name; float gainDb, pan; boolean mute, solo; ArrayList<Clip> clips; }`
  - `class Clip { String id, file; long start, offset, length; float gainDb; }`. Positions are in project frames; `file` is relative to the project folder, for example `audio/vocals.wav`.
  - `boolean Project.audible(Track t)`: `!t.mute && (no track is soloed || t.solo)`.
- `validate()` throws `IllegalArgumentException` with a reader-friendly message:
  - rate is not in 8000–384000;
  - a duplicate ID;
  - a negative start, offset or length;
  - pan outside −1..1;
  - a gain that is not finite;
  - a file path that is absolute or contains "..";
  - `nextId` is not greater than every numeric ID suffix.

- [ ] **Step 1: Write the failing test** (`ProjectTest.java`)

```java
package ai.jackknife.killayue.studio;

public class ProjectTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static boolean rejects(String text) {
        try {
            Project.fromText(text);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void main(String[] a) {
        Project p = new Project("Gold in the Wire", 48000);
        Track v = p.addTrack("Vocals");
        Clip c = p.addClip(v, "audio/vocals.wav", 0, 0, 48000 * 180);
        same("IDs are short, typed and stable", "t1 c2", v.id + " " + c.id);
        same("a new track is at unity gain, centred, not muted or soloed", "0.0 0.0 false false", v.gainDb + " " + v.pan + " " + v.mute + " " + v.solo);
        String text = p.toText();
        same("JSON round trip is exact", text, Project.fromText(text).toText());
        Track d = p.addTrack("Drums");
        d.solo = true;
        same("solo: only soloed tracks play", "false true", p.audible(v) + " " + p.audible(d));
        d.mute = true;
        same("mute beats solo", false, p.audible(d));
        same("a copy is equal and independent", text.length() < p.copy().toText().length(), true);
        same("an absolute file path is refused", true, rejects(text.replace("audio/vocals.wav", "/sdcard/x.wav")));
        same("a path climbing out of the project is refused", true, rejects(text.replace("audio/vocals.wav", "../x.wav")));
        same("a pan beyond hard left is refused", true, rejects(text.replace("\"pan\":0", "\"pan\":-1.5")));
        same("a duplicate ID is refused", true, rejects(text.replace("\"c2\"", "\"t1\"")));
        same("a negative clip start is refused", true, rejects(text.replace("\"start\":0", "\"start\":-1")));
        same("an impossible rate is refused", true, rejects(text.replace("48000", "7")));
        System.out.println("ProjectTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `bash app/test/run.sh`
Expected: compile error, `cannot find symbol: Project`.

- [ ] **Step 3: Write `Project.java`**
- `Project(String name, int rate)` sets `nextId = 1`.
- `newId(prefix)` returns `prefix + nextId++`.
- `addTrack(name)` makes a track with `newId("t")`. `addClip(track, file, start, offset, length)` makes a clip with `newId("c")`, appends it and returns it.
- `toJson()` writes keys in this order:
  - project: `version, name, rate, nextId, masterDb, tracks`;
  - track: `id, name, gainDb, pan, mute, solo, clips`;
  - clip: `id, file, start, offset, length, gainDb`.

  Floats are written as numbers; longs as whole numbers.
- `fromJson` reads the same, rejects unknown types and calls `validate()`.
- `copy()` is `fromText(toText())`.

- [ ] **Step 4: Run tests**

Run: `bash app/test/run.sh`
Expected: `ProjectTest: 12 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add app/src/ai/jackknife/killayue/studio/Project.java app/test/ai/jackknife/killayue/studio/ProjectTest.java
git commit -m "Studio: project model with stable IDs, validation and exact JSON

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 3: Undoable edits and history

**Files:**
- Create: `app/src/ai/jackknife/killayue/studio/Edit.java`
- Create: `app/src/ai/jackknife/killayue/studio/History.java`
- Create: `app/test/ai/jackknife/killayue/studio/HistoryTest.java`

**Interfaces:**
- Consumes: `Project`, `Track`, `Clip`.
- Produces:
  - `abstract class Edit { abstract void apply(Project p); abstract void revert(Project p); abstract String label(); }`
  - Factories:
    - `Edit.gain(String trackId, float db)`
    - `Edit.pan(String trackId, float pan)`
    - `Edit.mute(String trackId, boolean on)`
    - `Edit.solo(String trackId, boolean on)`
    - `Edit.rename(String trackId, String name)`
    - `Edit.masterGain(float db)`
    - `Edit.removeTrack(String trackId)`
    - `Edit.addTrack(String name, String file, long length)`, which adds a track with one clip at 0. Its IDs are taken on first apply and reused on redo.
  - Each factory records the old value when first applied.
  - `class History { History(Project p); Project project(); void run(Edit e); boolean undo(); boolean redo(); boolean canUndo(); boolean canRedo(); String undoLabel(); int changes(); }`
  - `changes()` counts every run, undo and redo; autosave uses it.

- [ ] **Step 1: Write the failing test** (`HistoryTest.java`)

```java
package ai.jackknife.killayue.studio;

public class HistoryTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        Project p = new Project("Remix", 48000);
        History h = new History(p);
        h.run(Edit.addTrack("Vocals", "audio/vocals.wav", 480000));
        h.run(Edit.addTrack("Drums", "audio/drums.wav", 480000));
        String two = p.toText();
        h.run(Edit.gain("t1", -6f));
        h.run(Edit.solo("t3", true));
        same("edits apply", "-6.0 true", p.track("t1").gainDb + " " + p.track("t3").solo);
        String edited = p.toText();
        h.run(Edit.removeTrack("t1"));
        same("removed", null, p.track("t1"));
        h.undo();
        same("undoing a removal restores the track exactly, in its place", edited, p.toText());
        h.redo();
        h.undo();
        same("redo then undo again lands on the same text", edited, p.toText());
        h.undo();
        h.undo();
        same("undo walks back to before the edits", two, p.toText());
        h.undo();
        h.undo();
        same("all the way back", new Project("Remix", 48000).toText(), p.toText());
        same("nothing left to undo", false, h.canUndo());
        h.redo();
        h.redo();
        same("redo re-adds the tracks with the same IDs", "t1 t3", p.tracks.get(0).id + " " + p.tracks.get(1).id);
        h.run(Edit.pan("t3", 0.5f));
        same("a new edit clears redo", false, h.canRedo());
        same("every step is counted for autosave", 11, h.changes());
        same("the undo label says what it undoes", "Pan", h.undoLabel());
        System.out.println("HistoryTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `bash app/test/run.sh`
Expected: compile error, `cannot find symbol: Edit`.

- [ ] **Step 3: Write `Edit.java` and `History.java`**
- Each factory returns an anonymous subclass:
  - `apply` looks up the track by ID, stores the old value the first time, and sets the new value;
  - `revert` restores the old value.
- `removeTrack` stores the removed `Track` object and its index; `revert` inserts it back at that index.
- `addTrack` creates its IDs on the first `apply` with `p.newId`. On a later apply (a redo) it re-inserts the same `Track` object. `revert` removes it by ID.
- `History.run` applies the edit, pushes it on undo, clears redo and increments `changes`. `undo` pops, reverts and pushes it on redo. `redo` does the reverse.

- [ ] **Step 4: Run tests**

Run: `bash app/test/run.sh`
Expected: `HistoryTest: 13 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add app/src/ai/jackknife/killayue/studio/Edit.java app/src/ai/jackknife/killayue/studio/History.java app/test/ai/jackknife/killayue/studio/HistoryTest.java
git commit -m "Studio: undoable edits as commands, with exact undo of track removal

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 4: Crash-safe save and load

**Files:**
- Create: `app/src/ai/jackknife/killayue/studio/ProjectStore.java`
- Create: `app/test/ai/jackknife/killayue/studio/ProjectStoreTest.java`

**Interfaces:**
- Consumes: `Project.toText`, `Project.fromText`.
- Produces:
  - `static void ProjectStore.save(Project p, File dir) throws IOException`. It writes `project.json.tmp`, calls `FileOutputStream.getFD().sync()`, moves the current `project.json` to `project.json.bak` (replacing it), then renames tmp to `project.json`.
  - `static Loaded ProjectStore.load(File dir) throws IOException`, with `class Loaded { Project project; boolean fromBackup; String problem; }`.
    - Load order: `project.json`, then `project.json.bak`, then `project.json.tmp`. `problem` says why the main file was not used.
    - Throws `IOException` with the main file's problem when none of them loads.

- [ ] **Step 1: Write the failing test** (`ProjectStoreTest.java`)

```java
package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

public class ProjectStoreTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("studio").toFile();
        Project p = new Project("Remix", 48000);
        p.addTrack("Vocals");
        ProjectStore.save(p, dir);
        String first = p.toText();
        p.addTrack("Drums");
        ProjectStore.save(p, dir);
        ProjectStore.Loaded ok = ProjectStore.load(dir);
        same("the latest save loads", p.toText(), ok.project.toText());
        same("not from the backup", false, ok.fromBackup);
        same("the previous save is kept as the backup", first, new String(Files.readAllBytes(new File(dir, "project.json.bak").toPath()), "UTF-8"));
        same("no temporary file is left behind", false, new File(dir, "project.json.tmp").exists());

        write(new File(dir, "project.json"), p.toText().substring(0, 40)); // power cut mid-write
        ProjectStore.Loaded rescued = ProjectStore.load(dir);
        same("a cut-off project loads from the backup", first, rescued.project.toText());
        same("and says so", true, rescued.fromBackup && rescued.problem != null && rescued.problem.length() > 0);

        write(new File(dir, "project.json"), "");
        write(new File(dir, "project.json.bak"), "{}");
        boolean threw = false;
        try {
            ProjectStore.load(dir);
        } catch (java.io.IOException e) {
            threw = true;
        }
        same("nothing usable: a clear error, not a crash", true, threw);
        System.out.println("ProjectStoreTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `bash app/test/run.sh`
Expected: compile error, `cannot find symbol: ProjectStore`.

- [ ] **Step 3: Write `ProjectStore.java`** as described in Interfaces. Use `File.renameTo` and check its result; on failure, throw an `IOException` naming the file. Read files as UTF-8.

- [ ] **Step 4: Run tests**

Run: `bash app/test/run.sh`
Expected: `ProjectStoreTest: 7 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add app/src/ai/jackknife/killayue/studio/ProjectStore.java app/test/ai/jackknife/killayue/studio/ProjectStoreTest.java
git commit -m "Studio: crash-safe project saving with a backup and fallback loading

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 5: Mixer and safety limiter (C++)

**Files:**
- Create: `studio/native/include/studio/mix.h`, `studio/native/src/mix.cpp`
- Create: `studio/native/test/test.h`, `studio/native/test/mix_test.cpp`, `studio/native/test/run.sh`

**Interfaces:**
- Produces (namespace `studio`):
  - `float dbToGain(float db)`
  - `void panGains(float pan, int channels, float& left, float& right)`:
    - mono source: `left = cos(θ)`, `right = sin(θ)` with θ = (pan+1)·π/4;
    - stereo source: `left = min(1, √2·cos θ)`, `right = min(1, √2·sin θ)`, so the centre is unity.
  - `struct TrackMix { float gain; float panL, panR; bool audible; int channels; }`
  - `class Limiter { Limiter(int rate, float ceilingDb = -0.3f, float lookaheadMs = 1.5f, float releaseMs = 80.f); int latency() const; void process(float* interleavedStereo, int frames); }`
    - Lookahead peak limiter. A delay line of `latency()` frames. The gain is the minimum of `ceiling / peak` over the lookahead window, reached by a linear attack across the window and released exponentially.
    - All memory is allocated in the constructor.
  - `void mixTrack(const TrackMix& t, const float* source, int frames, float* outStereo)` adds into `out`. `source` is interleaved with `t.channels` channels.

- [ ] **Step 1: Write the failing tests** (`test.h`, `mix_test.cpp`)

```cpp
// test.h
#pragma once
#include <cmath>
#include <cstdio>
static int g_pass = 0, g_fail = 0;
#define CHECK(cond, what) do { if (cond) g_pass++; else { g_fail++; std::printf("FAIL %s (%s:%d)\n", what, __FILE__, __LINE__); } } while (0)
#define NEAR(a, b, eps, what) CHECK(std::fabs((double)(a) - (double)(b)) <= (eps), what)
#define DONE(name) do { std::printf("%s: %d passed, %d failed\n", name, g_pass, g_fail); return g_fail ? 1 : 0; } while (0)
```

```cpp
// mix_test.cpp
#include "studio/mix.h"
#include "test.h"
#include <vector>
#include <random>
using namespace studio;

int main() {
    float l, r;
    panGains(0.f, 1, l, r);
    NEAR(l, std::sqrt(0.5), 1e-6, "mono centre is -3 dB each side");
    NEAR(l * l + r * r, 1.0, 1e-6, "mono constant power at centre");
    panGains(0.37f, 1, l, r);
    NEAR(l * l + r * r, 1.0, 1e-6, "mono constant power anywhere");
    panGains(-1.f, 1, l, r);
    NEAR(r, 0.0, 1e-6, "hard left");
    panGains(0.f, 2, l, r);
    NEAR(l, 1.0, 1e-6, "stereo centre is unity");
    NEAR(r, 1.0, 1e-6, "stereo centre is unity (right)");
    panGains(1.f, 2, l, r);
    NEAR(l, 0.0, 1e-6, "stereo hard right silences the left");
    NEAR(dbToGain(-6.0206f), 0.5, 1e-5, "-6.02 dB is half");

    // a stereo track at -6 dB, centred
    TrackMix t{dbToGain(-6.0206f), 1.f, 1.f, true, 2};
    float src[4] = {0.8f, -0.4f, 0.2f, 0.1f}, out[4] = {0, 0, 0, 0};
    mixTrack(t, src, 2, out);
    NEAR(out[0], 0.4, 1e-5, "gain applied, left");
    NEAR(out[1], -0.2, 1e-5, "gain applied, right");
    t.audible = false;
    mixTrack(t, src, 2, out);
    NEAR(out[0], 0.4, 1e-5, "a silent track adds nothing");

    // the limiter: never above the ceiling, transparent below it
    const int rate = 48000;
    Limiter lim(rate);
    const float ceiling = dbToGain(-0.3f);
    std::vector<float> loud(2 * rate);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> u(-4.f, 4.f); // four stems at full scale and then some
    for (auto& s : loud) s = u(rng);
    lim.process(loud.data(), rate);
    float peak = 0;
    for (float s : loud) peak = std::fmax(peak, std::fabs(s));
    CHECK(peak <= ceiling + 1e-6f, "loud input never exceeds the ceiling");

    Limiter clean(rate);
    std::vector<float> quiet(2 * 4800), in(2 * 4800);
    for (int i = 0; i < 4800; i++) quiet[2 * i] = quiet[2 * i + 1] = in[2 * i] = in[2 * i + 1] = 0.5f * std::sin(2 * M_PI * 440 * i / rate);
    clean.process(quiet.data(), 4800);
    int d = clean.latency();
    float worst = 0;
    for (int i = d; i < 4800; i++) worst = std::fmax(worst, std::fabs(quiet[2 * i] - in[2 * (i - d)]));
    CHECK(worst == 0.f, "below the ceiling the signal passes bit for bit, only delayed");
    CHECK(d == 72, "1.5 ms of lookahead at 48 kHz is 72 frames");
    DONE("mix_test");
}
```

- [ ] **Step 2: Write `run.sh`; run it and see it fail**

```bash
#!/bin/bash
# Host tests for the Studio's native core: every test with AddressSanitizer and UBSan,
# threaded tests also with ThreadSanitizer. Run on ops: studio/native/test/run.sh
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CXX=${CXX:-g++}
FLAGS="-std=c++17 -O1 -g -Wall -Wextra -Werror -Iinclude -Itest"
SRC=$(ls src/*.cpp 2>/dev/null || true)
for t in test/*_test.cpp; do
    n=$(basename "$t" .cpp)
    $CXX $FLAGS -fsanitize=address,undefined -fno-sanitize-recover=all "$t" $SRC -o "$OUT/$n" -lpthread
    "$OUT/$n"
    if grep -q "THREADED" "$t"; then
        $CXX $FLAGS -fsanitize=thread "$t" $SRC -o "$OUT/$n-tsan" -lpthread
        "$OUT/$n-tsan"
    fi
done
```

Run: `bash studio/native/test/run.sh`
Expected: compile error, `studio/mix.h: No such file`.

- [ ] **Step 3: Write `mix.h` and `mix.cpp`** as specified in Interfaces.
- `Limiter` keeps:
  - a ring delay of `latency()` stereo frames;
  - a ring of per-frame absolute peaks (the maximum of the left and right absolute values) for the window, with a running window maximum. Recompute the maximum when the oldest value leaves and it was the maximum; the window is short, so that is O(window).
- The target gain is `min(1, ceiling / windowMax)`. When the target is lower than the current gain, move linearly to it within the remaining lookahead; otherwise release with `g += (target - g) * (1 - exp(-1/(releaseMs·rate/1000)))`.
- The output is the delayed sample times the gain.
- Assert the ceiling exactly by computing the gain from the delayed frame's own peak as a final guard: `g = min(g, ceiling / |sample|)` for that frame. This is a gain, not a clip; it only acts in the worst case.

- [ ] **Step 4: Run tests**

Run: `bash studio/native/test/run.sh`
Expected: `mix_test: 16 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add studio/native
git commit -m "Studio engine: constant-power pan, track mixing and a lookahead safety limiter

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 6: Lock-free edit queue and the Engine's block loop

**Files:**
- Create: `studio/native/include/studio/spsc.h` (header only)
- Create: `studio/native/include/studio/engine.h`, `studio/native/src/engine.cpp`
- Create: `studio/native/test/spsc_test.cpp`, `studio/native/test/engine_test.cpp`

**Interfaces:**
- Consumes: `mixTrack`, `Limiter`, `panGains`, `dbToGain`.
- Produces:
  - `template<class T, size_t N> class Spsc { bool push(const T&); bool pop(T&); }`. N is a power of two; it uses acquire/release atomics; push fails when full.
  - `struct Source { virtual int read(float* out, int frames) = 0; virtual int channels() const = 0; virtual void seek(long frame) = 0; virtual void setOffline(bool) {} virtual bool waitReady(int ms) { return true; } virtual ~Source() {} }`:
    - `read` returns the frames delivered, padding the rest with zeros;
    - `seek` is called only from the engine thread and must not block (a `Streamer` posts the request to its disk thread);
    - `setOffline(true)` lets `read` wait for data; it is used only by export, never in real time;
    - `waitReady` waits up to `ms` for the read-ahead to fill (off-line only).
  - `struct Session { int rate; std::vector<TrackSlot> tracks; }` with `struct TrackSlot { Source* source; float gainDb, pan; bool mute, solo; }`. Built off the real-time thread.
  - `enum class Msg : uint8_t { Gain, Pan, Mute, Solo, Master, Play, Stop, Seek, Swap }` and `struct Message { Msg kind; int track; float value; long frame; Session* session; }`.
  - `class Engine`:
    - `Engine(int rate, int maxFrames)`
    - `bool post(const Message&)` (UI thread)
    - `void process(float* outStereo, int frames)` (audio thread)
    - `Session* takeRetired()` (UI thread; returns sessions the engine no longer uses, so they can be freed off the audio thread)
    - `bool waitSourcesReady(int ms)`, `void setOffline(bool)` and `int latency() const` (non-real-time, used by export; the first two pass through to the current session's sources, the last is the limiter's delay)
    - Ownership: a session handed over by Swap belongs to the engine until it is retired. `~Engine()` frees the current session and any retired ones not yet taken. Sources are not owned.
    - `Status status() const`, with `struct Status { long position; long underruns; int playing; float peakL, peakR; }`. The fields are atomics inside; `status()` copies them.
  - `underruns` counts blocks where a playing source delivered fewer frames than asked.

- [ ] **Step 1: Write the failing tests**

```cpp
// spsc_test.cpp — THREADED
#include "studio/spsc.h"
#include "test.h"
#include <thread>
using studio::Spsc;

int main() {
    Spsc<int, 4> q;
    int v = 0;
    CHECK(q.push(1) && q.push(2) && q.push(3) && q.push(4), "holds N items");
    CHECK(!q.push(5), "refuses when full");
    CHECK(q.pop(v) && v == 1, "first in, first out");
    Spsc<long, 1024> big;
    const long n = 2000000;
    std::thread producer([&] { for (long i = 0; i < n; i++) while (!big.push(i)) {} });
    long expect = 0, got = 0;
    bool inOrder = true;
    while (expect < n) if (big.pop(got)) { if (got != expect) inOrder = false; expect++; }
    producer.join();
    CHECK(inOrder, "two million items cross threads in order");
    DONE("spsc_test");
}
```

```cpp
// engine_test.cpp
#include "studio/engine.h"
#include "test.h"
#include <vector>
using namespace studio;

struct Constant : Source { // a stereo source of a fixed value, of a given length
    float value; long length, at = 0;
    Constant(float v, long len) : value(v), length(len) {}
    int read(float* out, int frames) override {
        int n = (int)std::min<long>(frames, length - at);
        if (n < 0) n = 0;
        for (int i = 0; i < 2 * frames; i++) out[i] = i < 2 * n ? value : 0.f;
        at += n;
        return n;
    }
    int channels() const override { return 2; }
    void seek(long f) override { at = f; }
};

int main() {
    Engine e(48000, 256);
    Constant a(0.25f, 48000), b(0.25f, 48000);
    auto* s = new Session{48000, {{&a, 0.f, 0.f, false, false}, {&b, 0.f, 0.f, false, false}}};
    e.post({Msg::Swap, 0, 0, 0, s});
    e.post({Msg::Play, 0, 0, 0, nullptr});
    std::vector<float> out(512);
    e.process(out.data(), 256); // two stereo tracks at 0.25, centred, unity: 0.5, delayed by the limiter
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.5, 1e-6, "two tracks sum");
    e.post({Msg::Gain, 1, -120.f, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.25, 1e-5, "a gain change applies at the next block, without a restart");
    e.post({Msg::Solo, 1, 1.f, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.0, 1e-5, "soloing the silent track silences the other");
    CHECK(e.status().position == 6 * 256, "position counts frames played");
    CHECK(e.status().underruns == 0, "no underruns while sources keep up");
    Constant shortOne(0.1f, 100);
    e.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&shortOne, 0.f, 0.f, false, false}}}});
    e.process(out.data(), 256);
    CHECK(e.status().underruns == 1, "a source that runs dry while playing is counted");
    Session* old = e.takeRetired();
    CHECK(old == s, "the replaced session comes back for freeing off the audio thread");
    delete old;
    e.post({Msg::Stop, 0, 0, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    CHECK(e.status().playing == 0, "stopped");
    delete e.takeRetired();
    DONE("engine_test");
}
```

- [ ] **Step 2: Run and see them fail**

Run: `bash studio/native/test/run.sh`
Expected: compile error, `studio/spsc.h: No such file`.

- [ ] **Step 3: Write `spsc.h`, `engine.h` and `engine.cpp`**
- `Engine` keeps:
  - `Spsc<Message,256> in`;
  - `Spsc<Session*,64> retired`;
  - the current `Session*`;
  - per-track scratch buffers allocated in the constructor for up to 64 tracks × `maxFrames` × 2 channels (refuse more in Swap by ignoring it and counting it in `status`);
  - a `Limiter`.
- `process`:
  1. drain `in` and apply each message: Gain, Pan, Mute and Solo change the current session's slot; Swap pushes the old session to `retired` and installs the new one; Seek calls `source->seek` on every track; Play and Stop set the transport;
  2. if not playing, write zeros and still run the limiter's delay so a restart is click-free;
  3. otherwise, for each track: `n = source->read(scratch, frames)`; if `n < frames`, count one underrun for the block;
  4. compute `TrackMix` from the slot and `Project`'s audible rule, and call `mixTrack`;
  5. apply the master gain, run the limiter, update peaks and the position.
- No `new`, `delete`, locks or I/O in `process`.

- [ ] **Step 4: Run tests**

Run: `bash studio/native/test/run.sh`
Expected: `spsc_test: 4 passed, 0 failed` (with ASan and TSan), `engine_test: 8 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add studio/native
git commit -m "Studio engine: lock-free edits applied per block, sessions swapped and retired safely

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 7: Streamer — read-ahead without blocking the audio thread

**Files:**
- Create: `studio/native/include/studio/streamer.h`, `studio/native/src/streamer.cpp`
- Create: `studio/native/test/streamer_test.cpp`

**Interfaces:**
- Consumes: `Source`.
- Produces:
  - `struct Reader { virtual long frames() const = 0; virtual int channels() const = 0; virtual int read(long at, float* out, int frames) = 0; virtual ~Reader() {} }`. This is the disk side (`WavReader` in Task 8).
  - `class Streamer : public Source { Streamer(Reader* r, long clipStart, long fileOffset, long length, int ringFrames = 1 << 17); void startThread(); void stopThread(); int read(float*, int) override; void seek(long projectFrame) override; long starved() const; }`
    - The disk thread fills a lock-free ring.
    - The audio thread only reads it. Before `clipStart` and after the clip's end it delivers silence and counts it as delivered (it is the clip's own silence, not starvation).
    - `seek` bumps an atomic generation number. The disk thread sees it, clears and refills. Until the refill arrives, `read` returns silence counted as starved.
    - `read` always advances the clip's position, whether or not audio was there, so a track stays in time with the others. When the refill starts behind the current position, `read` skips ahead in the ring.
    - In offline mode (`setOffline(true)`), `read` waits for data instead of starving. `waitReady(ms)` waits until the ring holds data for the current position.

- [ ] **Step 1: Write the failing test** (`streamer_test.cpp`, THREADED)

```cpp
// streamer_test.cpp — THREADED
#include "studio/streamer.h"
#include "test.h"
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
using namespace studio;

struct Ramp : Reader { // sample i of the file is i (both channels); can be made slow
    long n; std::atomic<int> delayUs{0};
    explicit Ramp(long len) : n(len) {}
    long frames() const override { return n; }
    int channels() const override { return 2; }
    int read(long at, float* out, int frames) override {
        if (delayUs) std::this_thread::sleep_for(std::chrono::microseconds(delayUs.load()));
        int k = (int)std::max(0L, std::min<long>(frames, n - at));
        for (int i = 0; i < k; i++) out[2 * i] = out[2 * i + 1] = (float)(at + i);
        return k;
    }
};

int main() {
    Ramp file(480000);
    Streamer s(&file, 1000, 500, 100000, 1 << 14); // the clip starts at project frame 1000, plays file frames 500..100499
    s.startThread();
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    std::vector<float> out(2 * 512);
    bool exact = true;
    for (long at = 0; at < 120000; at += 512) {
        s.read(out.data(), 512);
        for (int i = 0; i < 512; i++) {
            long p = at + i;
            float want = (p >= 1000 && p < 101000) ? (float)(p - 1000 + 500) : 0.f;
            if (out[2 * i] != want) exact = false;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(200));
    }
    CHECK(exact, "every frame is the right file frame, with silence around the clip");
    CHECK(s.starved() == 0, "a disk that keeps up never starves");

    file.delayUs = 200000; // a disk that stalls for 0.2 s
    s.seek(50000);
    auto t0 = std::chrono::steady_clock::now();
    int got = s.read(out.data(), 512);
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
    CHECK(us < 1000, "reading never waits for the disk");
    CHECK(got == 0 && out[0] == 0.f && s.starved() > 0, "missing audio is silence, counted as starved");
    file.delayUs = 0;
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    s.read(out.data(), 512); // time kept moving during the stall: this block starts at 50000 + 512
    CHECK(out[0] == (float)(50000 + 512 - 1000 + 500), "after the stall it plays in time, from where the song has got to");
    s.stopThread();
    DONE("streamer_test");
}
```

- [ ] **Step 2: Run and see it fail**

Run: `bash studio/native/test/run.sh`
Expected: compile error, `studio/streamer.h: No such file`.

- [ ] **Step 3: Write `streamer.h` and `streamer.cpp`**
- The ring holds interleaved stereo floats (mono files are duplicated to two channels by the reader adapter). It has atomic write and read counters, a `std::atomic<long> generation`, and a `std::atomic<long> seekTo`.
- The disk thread loops:
  - if the generation changed, reset the ring and set its read position to `seekTo`;
  - otherwise top up the free space with `Reader::read` in chunks of 4096 frames, mapping project frames to file frames (silence outside the clip);
  - sleep 2 ms when the ring is full;
  - exit on stop.
- `read` copies what is available; it delivers zeros and counts starvation only when the ring lacks data that the clip has.

- [ ] **Step 4: Run tests**

Run: `bash studio/native/test/run.sh`
Expected: `streamer_test: 5 passed, 0 failed` (ASan and TSan).

- [ ] **Step 5: Commit**

```bash
git add studio/native
git commit -m "Studio engine: clip read-ahead on a disk thread; the audio thread never waits

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 8: Float WAV/RF64 and offline export

**Files:**
- Create: `studio/native/include/studio/wav.h`, `studio/native/src/wav.cpp`
- Create: `studio/native/include/studio/export.h`, `studio/native/src/export.cpp`
- Create: `studio/native/test/wav_test.cpp`, `studio/native/test/export_test.cpp`

**Interfaces:**
- Consumes: `Reader`, `Session`, `Engine`.
- Produces:
  - `class WavReader : public Reader { static WavReader* open(const char* path, std::string& error); int rate() const; }`. Reads 32-bit float and 16/24-bit PCM, mono or stereo, RIFF and RF64; converts to float stereo.
  - `class WavWriter { static WavWriter* create(const char* path, int rate, int channels, std::string& error); bool write(const float*, int frames); bool finish(); }`.
    - Writes float32.
    - Starts with a JUNK chunk sized for a `ds64`.
    - Becomes RF64 if the data passes 4 GB.
    - `finish()` patches the sizes, flushes, `fsync`s, and renames from `path + ".part"`.
  - `bool exportMix(Engine& e, long frames, const char* path, int rate, std::string& error)`. It plays the engine from 0 in 4096-frame blocks as fast as it can, with the sources offline so no audio is ever missing. It pre-rolls by the limiter's latency so the file is not delayed, writes the result, and puts the sources back to real time.

- [ ] **Step 1: Write the failing tests**

```cpp
// wav_test.cpp
#include "studio/wav.h"
#include "test.h"
#include <cstdio>
#include <string>
#include <vector>
using namespace studio;

int main() {
    std::string err;
    const char* path = "/tmp/studio_wav_test.wav";
    WavWriter* w = WavWriter::create(path, 48000, 2, err);
    CHECK(w != nullptr, "create");
    std::vector<float> data(2 * 48000);
    for (size_t i = 0; i < data.size(); i++) data[i] = (float)i / data.size() - 0.5f;
    CHECK(w->write(data.data(), 48000) && w->finish(), "write and finish");
    delete w;
    WavReader* r = WavReader::open(path, err);
    CHECK(r && r->frames() == 48000 && r->channels() == 2 && r->rate() == 48000, "header read back");
    std::vector<float> back(2 * 48000);
    r->read(0, back.data(), 48000);
    CHECK(back == data, "float samples read back bit for bit");
    delete r;
    FILE* f = std::fopen("/tmp/studio_not_a_wav.wav", "wb");
    std::fputs("hello", f);
    std::fclose(f);
    CHECK(WavReader::open("/tmp/studio_not_a_wav.wav", err) == nullptr && !err.empty(), "a broken file is refused with a reason");
    std::remove(path);
    DONE("wav_test");
}
```

```cpp
// export_test.cpp
#include "studio/engine.h"
#include "studio/export.h"
#include "studio/streamer.h"
#include "studio/wav.h"
#include "test.h"
#include <cmath>
#include <string>
#include <vector>
using namespace studio;

int main() {
    std::string err;
    const int rate = 48000;
    const char* stem = "/tmp/studio_export_stem.wav";
    WavWriter* w = WavWriter::create(stem, rate, 2, err);
    std::vector<float> sine(2 * rate);
    for (int i = 0; i < rate; i++) sine[2 * i] = sine[2 * i + 1] = 0.3f * std::sin(2 * M_PI * 220 * i / rate);
    w->write(sine.data(), rate);
    w->finish();
    delete w;
    WavReader* r = WavReader::open(stem, err);
    Streamer s(r, 0, 0, rate);
    s.startThread();
    Engine e(rate, 4096);
    e.post({Msg::Swap, 0, 0, 0, new Session{rate, {{&s, -6.0206f, 0.f, false, false}}}});
    const char* out = "/tmp/studio_export_mix.wav";
    CHECK(exportMix(e, rate, out, rate, err), "export");
    s.stopThread();
    WavReader* m = WavReader::open(out, err);
    std::vector<float> mix(2 * rate);
    m->read(0, mix.data(), rate);
    float worst = 0;
    for (int i = 0; i < rate; i++) worst = std::fmax(worst, std::fabs(mix[2 * i] - 0.5f * sine[2 * i]));
    CHECK(m->frames() == rate, "exactly the asked length");
    CHECK(worst < 1e-6f, "the export is the stem at -6 dB, not delayed, sample for sample");
    delete m;
    delete r;
    delete e.takeRetired();
    std::remove(stem);
    std::remove(out);
    DONE("export_test");
}
```

- [ ] **Step 2: Run and see them fail**

Run: `bash studio/native/test/run.sh`
Expected: compile error, `studio/wav.h: No such file`.

- [ ] **Step 3: Write `wav.*` and `export.*`** as in Interfaces.
- Plain `FILE*` with `fseeko`/`ftello` for 64-bit offsets.
- The reader parses `fmt `, `ds64` and `data`, and skips unknown chunks with word alignment.
- `exportMix`:
  1. calls `e.setOffline(true)`;
  2. posts Seek 0 and Play;
  3. calls `e.waitSourcesReady(2000)`;
  4. discards the first `e.latency()` frames of output;
  5. writes exactly `frames` frames;
  6. posts Stop and calls `e.setOffline(false)`.

  `Engine::process` is the same code path as real time; only the sources wait.

- [ ] **Step 4: Run tests**

Run: `bash studio/native/test/run.sh`
Expected: `wav_test: 5 passed`, `export_test: 3 passed`, every suite 0 failed.

- [ ] **Step 5: Commit**

```bash
git add studio/native
git commit -m "Studio engine: float WAV/RF64 files and offline export through the same mixer

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

### Task 9: Ten-minute, sixteen-track host stress run

**Files:**
- Create: `studio/native/test/stress_test.cpp`

**Interfaces:**
- Consumes: `Engine`, `Streamer`, `WavReader`, `WavWriter`.

- [ ] **Step 1: Write the test.** THREADED. It does the following:
  1. Write 16 stereo stems of 10 minutes at 48 kHz to `/tmp`, each a sine at a different frequency.
  2. Wrap each in a `Streamer`.
  3. Run `Engine::process` in 192-frame blocks on a thread paced at real time (sleep until each block's deadline), for 10 minutes of audio.
  4. Meanwhile a UI thread posts random Gain, Pan, Mute and Solo messages every 5 ms, and a Seek every 20 s.
  5. Record the slowest `process` call.

  `CHECK(underruns == 0)` outside the 300 ms after each seek, which are counted separately and reported. Also `CHECK(slowest < 2 ms)` (the block's budget is 4 ms). Print the slowest and the 99th percentile.

  Gate it behind `STRESS=1` so the normal run stays quick: `if (!getenv("STRESS")) { std::printf("stress_test: skipped (STRESS=1 to run)\n"); return 0; }`

- [ ] **Step 2: Run it**

Run: `STRESS=1 bash studio/native/test/run.sh`
Expected: `stress_test: 2 passed, 0 failed`, with the slowest block printed. It takes about 10 minutes of wall time per sanitizer build; run it in the background.

- [ ] **Step 3: Commit**

```bash
git add studio/native/test/stress_test.cpp
git commit -m "Studio engine: ten-minute sixteen-track stress test, zero underruns required

Co-Authored-By: Claudia Casavant <claudia@jackknifeai.com>"
```

---

## Self-review notes

- **Spec coverage for this plan's scope** (project model, mixing core, export):
  - stable IDs: Task 2;
  - undo as commands: Task 3;
  - autosave counter: Task 3, `History.changes`;
  - backup and fallback: Task 4;
  - constant-power pan and soft limiter: Task 5;
  - lock-free edits without restarts: Task 6;
  - read-ahead disk thread: Task 7;
  - export: Task 8;
  - zero underruns for 16 tracks over ten minutes: Task 9 on the host. It is repeated on the phone in plan 1b.
- **Out of this plan, by design:**
  - Oboe, the JNI bridge, route changes and latency measurement (1b);
  - the Studio screen, import with resampling, the mixer UI, and autosave scheduling (1c);
  - stems (1d).
