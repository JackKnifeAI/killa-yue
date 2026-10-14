// node timeline.js ABCJS_FILE SCORE.abc : the score's sounding notes per voice, as abcjs hears them
// (ties joined, key signature and accidentals applied), printed as JSON: {voice: [[start, midi, length], ...]}
// with start and length in whole notes.
const fs = require('fs');
const ABCJS = require(process.argv[2]);
const tune = ABCJS.parseOnly(fs.readFileSync(process.argv[3], 'utf8'))[0];
const seq = tune.setUpAudio({});
const out = {};
seq.tracks.forEach((track, i) => {
  const notes = [];
  for (const ev of track) {
    if (ev.cmd !== 'note') continue;
    notes.push([Math.round(ev.start * 1e6) / 1e6, ev.pitch, Math.round(ev.duration * 1e6) / 1e6]);
  }
  notes.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  out['track' + i] = notes;
});
console.log(JSON.stringify(out));
