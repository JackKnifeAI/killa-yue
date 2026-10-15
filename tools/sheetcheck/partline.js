// node partline.js ABCJS_FILE LIST_FILE : the first voice's sounding notes of each listed score, one JSON line each
const fs = require('fs');
const ABCJS = require(process.argv[2]);
for (const f of fs.readFileSync(process.argv[3], 'utf8').split('\n').filter(Boolean)) {
  const tune = ABCJS.parseOnly(fs.readFileSync(f, 'utf8'))[0];
  const w = (tune.warnings || []).length;
  const notes = [];
  for (const ev of tune.setUpAudio({}).tracks[0] || []) {
    if (ev.cmd === 'note') notes.push([Math.round(ev.start * 1e6) / 1e6, ev.pitch, Math.round(ev.duration * 1e6) / 1e6]);
  }
  notes.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  console.log(JSON.stringify({file: f, warnings: w, notes}));
}
