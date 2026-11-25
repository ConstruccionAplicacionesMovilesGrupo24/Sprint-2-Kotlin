#!/usr/bin/env node
// S4-11: keeps the sections that start inside each run's `cm.s4-11.burst` window (the warmup and app start are
// left out) and totals them per run, so the formatter is shown next to the whole admission cost.
// Usage: node scripts/profiling/s4-11-window.js sections-all.csv sections.csv totals.csv
//   sections.csv  same columns as sections.js, only in-window cm.outbox.* rows (input of summary.js)
//   totals.csv    file,burst_ms,enqueue_count,enqueue_sum_ms,isoutc_count,isoutc_sum_ms,isoutc_share_of_enqueue
'use strict';
const fs = require('fs');
const [input, sectionsOut, totalsOut] = process.argv.slice(2);
if (!totalsOut) { console.error('usage: s4-11-window.js sections-all.csv sections.csv totals.csv'); process.exit(2); }

const header = 'file,name,ts_ns,duration_ms';
const rows = fs.readFileSync(input, 'utf8').trim().split(/\r?\n/).slice(1).map((l) => {
  const [file, name, ts, ms] = l.split(',');
  return { file, name, ts: BigInt(ts), ms: Number(ms), line: l };
});
const files = [...new Set(rows.map((r) => r.file))];
const kept = [], totals = ['file,burst_ms,enqueue_count,enqueue_sum_ms,isoutc_count,isoutc_sum_ms,isoutc_share_of_enqueue'];
for (const file of files) {
  const windows = rows.filter((r) => r.file === file && r.name === 'cm.s4-11.burst');
  if (windows.length !== 1) throw new Error(`${file}: expected one cm.s4-11.burst section, found ${windows.length}`);
  const w = windows[0], end = w.ts + BigInt(Math.round(w.ms * 1e6));
  const inside = rows.filter((r) => r.file === file && r.name.startsWith('cm.outbox.') && r.ts >= w.ts && r.ts <= end);
  kept.push(...inside.map((r) => r.line));
  const of = (name) => inside.filter((r) => r.name === name).map((r) => r.ms);
  const enq = of('cm.outbox.enqueue'), iso = of('cm.outbox.isoUtc');
  const sum = (v) => v.reduce((a, b) => a + b, 0);
  totals.push([file, w.ms.toFixed(3), enq.length, sum(enq).toFixed(3), iso.length, sum(iso).toFixed(3),
    (sum(iso) / sum(enq)).toFixed(4)].join(','));
}
fs.writeFileSync(sectionsOut, [header, ...kept].join('\n') + '\n');
fs.writeFileSync(totalsOut, totals.join('\n') + '\n');
