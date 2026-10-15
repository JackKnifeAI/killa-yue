# Sheet music: abcjs engine, instrument parts, tabs and Showpiece

14 October 2026. Agreed with Alexander in chat; this note records it.

## Goals

- Sheet music a musician can trust and use: correct notes, readable beaming, even line layout, vector PDF.
- Parts for many instruments (transposed, right clef), guitar tabs, MusicXML for notation programs.
- A Showpiece style: the same music in the skin's look, with Pixella-made symbols.
- Accuracy is never traded for looks. There is no fallback: every symbol is generated and verified until it is right.
- All work happens in the app on the phone. Ops makes Pixella art and checks accuracy, nothing else.

## How a sheet is made

```
song's ABC score ──> ScorePrep (Java) ──> abcjs in an offscreen WebView ──> SVG ──> SvgScore (Java) ──> pages ──> PDF / PNG
```

1. **ScorePrep** rewrites the score for engraving without changing a single note:
   - beams regrouped by beat (the song model writes no spaces, so abcjs would beam whole bars);
   - multi-bar rests (`Z4`) spelled out as single bars in a full score, so all staves keep matching bar lines;
   - section comments (`% chorus`) turned into labels above the top staff;
   - bars reflowed into evenly filled systems;
   - title, subtitle and readable part names added.
2. **abcjs 6.7.1** (MIT, bundled in `app/assets/abcjs`, offline) lays the music out: spacing, stems, beams, ties, slurs, accidentals, chord names. Later it also handles transposition and tablature.
3. **SvgScore** reads abcjs's SVG and draws every path and text itself onto Android canvases. Drawing `WebView` into a PDF gave a raster page, so we don't. Every element keeps its abcjs class and glyph name (`noteheads`, `rests.quarter`, `clefs.G`, `accidentals.sharp`, `flags.u8th`, `abcjs-beam-elem`, `abcjs-tie`, ...).
4. **Pages**: systems are packed onto A4 pages, with the title on page 1 and page numbers. Plain draws in black ink as a vector PDF. Showpiece draws the same elements in the skin's material, with Pixella symbols on the glyph positions.

The old engraver (`SheetMusic.java`) stays in the repository for in-app drawing (score preview, the staff progress bar, small art).

## Accuracy checks (ops)

- **Score prep:** music21 reads the original score and the prepared score; every voice's notes (pitch, length, ties) must be identical. This runs over every song score we have.
- **Interpretation:** abcjs's own parse of the prepared score must give the same notes as music21's parse of the original.
- **Drawing:** our renderer's output, drawn unpaginated at the WebView's scale, must match abcjs's own WebView rendering pixel for pixel, within anti-aliasing.
- **Showpiece:** a red-outline overlay of the plain symbols on every page, plus a numeric log of each placed symbol against its glyph box.

## Build order

1. Engine: ScorePrep, offscreen abcjs, SvgScore, Plain PDF pages, the export wired in, and the checks above.
2. Instrument parts: an instrument picker, transposition and clef (alto sax first).
3. Guitar tabs and chord diagrams.
4. MusicXML export.
5. Showpiece: a second Pixella sheet (64th notes, breve, articulations, fermata, x and diamond heads, percussion clef, grace notes), Neon and Foundry art, and the material renderer.

## Pixella symbols (done for Killa Gold)

25 symbols in one Landscape 4:3 GPT Image 2.5 picture: notes whole to 32nd, rests whole to 32nd, treble, bass and alto clefs, sharp, flat, natural, double sharp, double flat, filled, hollow and whole heads, a flag, a dot. The prompt is `~/showcase/killa-yue-art/notation/gold_all.one`. `notation/cut.py` cuts them out and refuses if any row's count is wrong.
