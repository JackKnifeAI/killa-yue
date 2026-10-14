# Making a Killa YuE skin

A skin is a zip file. Anyone can install one from the Engine tab ("Install a skin"), and the three built-in skins always stay. The quickest way to start: choose a skin, press "Make your own", and the app writes `Documents/Killa YuE/skin-template.zip` with that skin's pictures and a `skin.json` already filled in.

## What goes in the zip

The files can sit at the top of the zip or inside one folder. They have the same names as the built-in skins in `app/assets/skins/<id>/`:

| File | What it is | Built-in size (px) |
|---|---|---|
| `btnA.png` | Main button plate. The left and right ends keep their shape and the middle stretches. | 1032 × 258 |
| `btnB.png` | Quieter button plate, same layout | |
| `staff.png` | The progress bar: a music staff that notes travel along | 1027 × 155 |
| `dial.png` | The record that turns in the player and the status bar, and the length dial | 380 × 380 |
| `round.png` | Round transport buttons | 240 × 237 |
| `note1.png` to `note6.png` | Notes that travel along the staff | 94 × 150 |
| `wall.jpg` | Page background, cropped to fill the screen (a photo or painting) | |
| `bg.png` | Instead of `wall.jpg` when `tiledBackground` is true: a tile repeated across the page | |
| `skin.json` | Name, colours and measurements | |

All pictures except `wall.jpg` need transparency, so they are PNG. Each file may be up to 12 MB, the whole skin up to 40 MB, and no picture more than 4096 pixels on a side.

## skin.json

```json
{
  "name": "Neon Rose",
  "author": "you",
  "base": "cyber",
  "scriptTitles": false,
  "tiledBackground": false,
  "colors": {
    "accent": "#FF4FA3", "hi": "#FFD6EA", "lo": "#8A1F55", "glow": "#FF2BD6",
    "text": "#FFEAF5", "dim": "#B08AA0", "faint": "#6A4A5C", "edge": "#4A2238",
    "off": "#1C1018", "deck": "#08040A", "card": "#F20A0610", "pill": "#0A0610"
  },
  "staff": [1030, 171, 112, 83, 41, 989, 23, 141, 116, 975, 45, 61, 77, 93, 109]
}
```

- `name` is required. A skin installed again under the same name replaces the earlier one.
- `base` is `gold`, `cyber` or `foundry`. The skin borrows that skin's dialog colours and the look of its phone-to-phone share page, and any colour left out comes from it.
- `scriptTitles`: true sets titles and main buttons in the gold skin's script lettering; false uses the text face in capitals.
- Colours are `#RRGGBB` or `#AARRGGBB`. `accent` is required. In order: `accent` is the main colour (titles, buttons, the status-bar chip); `hi` and `lo` are its light and dark shades; `glow` is the glow behind lit things; `text`, `dim` and `faint` are three strengths of writing; `edge` is borders; `off` is unlit controls; `deck` is the bars at the top and bottom; `card` is panels (keep it slightly see-through); `pill` is the background of small labels.
- `staff` (optional) gives 15 measurements of `staff.png`, in its own pixels: width, height, left end cap, right end cap, the channel's left, right, top and bottom, where notes enter and where they leave, then the centres of the five staff lines from top to bottom. Leave it out and the base skin's measurements are scaled to your picture, which works when your staff has the same layout as the base skin's.

## Checks on install

The app keeps only the files listed above, checks each picture opens and is a sensible size, and tells you what is missing. A skin that fails to load later (a damaged file, say) is set aside and the app goes back to Killa Gold.
