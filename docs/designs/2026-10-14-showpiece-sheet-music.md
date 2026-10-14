# Showpiece sheet music

14 October 2026. Status: proposed, waiting for Alexander's review.

## What it is

"Sheet music and MIDI" asks for a style:

- **Plain**: today's sheet (A4, black on white, vector PDF). It stays exactly as it is.
- **Showpiece**: the same music in the active skin's look, made to share, post or frame.

MIDI and the `.abc` score are exported with either style.

## Where the work happens

- **Pixella (on ops, once)** makes the notation art for each built-in skin, the same way the existing skin art was made. The art ships inside the app.
- **The app does everything else on the phone**: it measures each symbol, scales and anchors it, and draws the pages.
- **Ops only checks accuracy** on pages the phone exports. Nothing the app needs is computed on ops.

## The symbols (17 per skin)

| Group | Symbols | Asset names |
|---|---|---|
| Noteheads | filled, hollow (half), whole | `head_filled`, `head_half`, `head_whole` |
| Flag | one flag; stacked for 16ths and 32nds, mirrored for down stems | `flag` |
| Rests | whole, half, quarter, eighth, sixteenth, thirty-second | `rest_1`, `rest_2`, `rest_4`, `rest_8`, `rest_16`, `rest_32` |
| Clef | treble | `clef_treble` |
| Accidentals | sharp, flat, natural, double sharp, double flat | `acc_sharp`, `acc_flat`, `acc_natural`, `acc_dsharp`, `acc_dflat` |
| Dot | the dot that lengthens a note | `dot` |

Files: `app/assets/skins/<skin>/notation/<name>.png`, transparent background, about 3–4 MB in total for the three skins.

Each skin needs about three Pixella sprite sheets: black background, evenly spaced objects, no staff lines, no text. They are cut out on ops into single transparent symbols. Before a symbol goes into the app, I check it by eye and by count, for example that a 16th rest has exactly two hooks. Wrong ones are regenerated.

Community skins use the set of the skin they are built on. Packs that bring their own notation art can come later; the in-app measuring below already works for any art.

Pixella cannot draw anything whose length varies, or any lettering. These stay drawn by the app in skin colours:
- stems, beams, staff lines, ledger lines, ties, bar lines and the bracket, with a metal gradient in the skin's HI → ACCENT → LO colours;
- time signatures, chord names, titles and section labels, in the skin's fonts.

## Accuracy

1. **One layout.** Both styles use the same layout pass: pitch, position, stem direction and spacing are computed once. The style only decides how each symbol is drawn.
2. **In-app measuring.** When a skin's notation is first used, the app reads each symbol's alpha mask:
   - noteheads: the centre of the opaque area becomes the anchor, and the width is scaled to the plain head (1.32 staff spaces);
   - flags: the anchor is the point where the stem attaches;
   - rests, accidentals and the dot: the bounding box is fitted to the plain symbol's box;
   - treble clef: the bounding box is fitted to standard engraving proportions, 7.0 staff spaces tall with the G line 2.6 spaces above the bottom (SMuFL `gClef`).

   A symbol that measures as empty or wildly out of proportion falls back to the plain drawing, so a bad art file can never move a note.
3. **Proof on ops.** A hidden debug export (a long press on "Showpiece") draws the plain symbols as thin red outlines over the Showpiece. I check every page: each Pixella head must sit inside its outline, on the right line or space. The app also logs each placed anchor against its target, so the check is numeric as well as visual.

## The page

- The skin's background (leather for Killa Gold), with a gold double frame and corner ornaments like the app's dialogs.
- The title in the skin's script font. Page 1 shows the song's cover in the skin's round frame.
- The existing note art (`note1`–`note6`) as ornaments beside the title and in the footer.
- Section labels on small plaques; chord names in the accent colour.
- Staves 1.4× the plain size, so the shine is visible. Cut the Line goes from 7 pages to about 10.

## Output

The same drawing code draws on two canvases:
- **A PDF.** Lines and text stay vector. Each sprite is embedded once and reused, so the file stays small and sharp at any zoom.
- **PNG page images** at 200 dpi, for posting.

Pages are drawn one at a time, to keep memory low on the phone.

## Testing

- **Plain unchanged:** the text positions and a pixel render of today's Plain PDF match the new build's Plain PDF.
- **Showpiece:** Cut the Line exported on the phone in Killa Gold, Neon District, Foundry and the rose-gold community skin. Red-outline proof on every page; the anchor log checked; size, time and memory measured. Targets: PDF under 15 MB, export under 30 s.
- Alexander sees the best pages before anything is merged.

## Not in this version

Bass clef (no song uses it), tuplet brackets (the plain sheet doesn't draw them either), and notation art inside community skin packs.
