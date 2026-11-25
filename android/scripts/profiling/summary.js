#!/usr/bin/env node
// Median and range of a sections.js CSV, per section name: all evaluations, and the per-run medians.
// Usage: node scripts/profiling/summary.js before-stress.csv
'use strict';
const fs = require('fs');
const median = (v) => { const s = [...v].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const fmt = (x) => x.toFixed(3);
for (const file of process.argv.slice(2)) {
  const rows = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).slice(1).map((l) => l.split(','));
  const byName = new Map();
  for (const [run, name, , ms] of rows) {
    if (!byName.has(name)) byName.set(name, new Map());
    const runs = byName.get(name);
    if (!runs.has(run)) runs.set(run, []);
    runs.get(run).push(Number(ms));
  }
  for (const [name, runs] of byName) {
    const all = [...runs.values()].flat();
    const perRun = [...runs.entries()].map(([run, v]) => [run, median(v)]);
    const runMedians = perRun.map(([, m]) => m);
    const med = median(runMedians), lo = Math.min(...runMedians), hi = Math.max(...runMedians);
    console.log(`${file} ${name}: ${all.length} evaluations in ${runs.size} runs`);
    console.log(`  all evaluations  median ${fmt(median(all))} ms  min ${fmt(Math.min(...all))}  max ${fmt(Math.max(...all))}`);
    console.log(`  per-run medians  ${perRun.map(([r, m]) => `${r.replace(/\.perfetto-trace$/, '')}=${fmt(m)}`).join('  ')}`);
    console.log(`  median of runs   ${fmt(med)} ms  range ${fmt(lo)}–${fmt(hi)}  (spread ${fmt(((hi - lo) / med) * 100)} % of median)`);
  }
}
