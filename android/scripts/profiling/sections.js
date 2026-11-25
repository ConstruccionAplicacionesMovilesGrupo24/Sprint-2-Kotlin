#!/usr/bin/env node
// Offline equivalent of sections.sql for the Sprint 4 pairs (S4-05): reads Perfetto traces recorded with
// campusmeal.pbtxt and prints every synchronous `cm.*` section (atrace B/E markers in ftrace print events).
// Usage: node scripts/profiling/sections.js run-01.perfetto-trace [run-02…]  ->  CSV: file,name,ts_ns,duration_ms
// Async sections (traceAsyncSection, S|/F| markers) are matched by name and cookie.
// No dependency: minimal protobuf reader for Trace(1: packet) > TracePacket(1: ftrace_events) >
// FtraceEventBundle(2: event) > FtraceEvent(1: timestamp, 2: pid, 3: print) > PrintFtraceEvent(2: buf).
'use strict';
const fs = require('fs');

function* fields(buf, start = 0, end = buf.length) {
  let i = start;
  while (i < end) {
    const [key, k1] = varint(buf, i); i = k1;
    const field = Number(key >> 3n), wire = Number(key & 7n);
    if (wire === 0) { const [v, n] = varint(buf, i); i = n; yield { field, wire, value: v }; }
    else if (wire === 1) { yield { field, wire, value: buf.readBigUInt64LE(i) }; i += 8; }
    else if (wire === 2) { const [len, n] = varint(buf, i); const s = n, e = n + Number(len); i = e; yield { field, wire, start: s, end: e }; }
    else if (wire === 5) { yield { field, wire, value: BigInt(buf.readUInt32LE(i)) }; i += 4; }
    else throw new Error(`unsupported wire type ${wire} at ${i}`);
  }
}

function varint(buf, i) {
  let result = 0n, shift = 0n;
  for (;;) {
    const b = buf[i++];
    result |= BigInt(b & 0x7f) << shift;
    if ((b & 0x80) === 0) return [result, i];
    shift += 7n;
  }
}

function sections(file) {
  const buf = fs.readFileSync(file);
  const events = [];
  for (const packet of fields(buf)) {
    if (packet.field !== 1 || packet.wire !== 2) continue;
    for (const pf of fields(buf, packet.start, packet.end)) {
      if (pf.field !== 1 || pf.wire !== 2) continue; // ftrace_events bundle
      for (const bf of fields(buf, pf.start, pf.end)) {
        if (bf.field !== 2 || bf.wire !== 2) continue; // FtraceEvent
        let ts = null, tid = null, text = null;
        for (const ef of fields(buf, bf.start, bf.end)) {
          if (ef.field === 1 && ef.wire === 0) ts = ef.value;
          else if (ef.field === 2 && ef.wire === 0) tid = Number(ef.value);
          else if (ef.field === 3 && ef.wire === 2) {
            for (const p of fields(buf, ef.start, ef.end)) if (p.field === 2 && p.wire === 2) text = buf.toString('utf8', p.start, p.end);
          }
        }
        if (ts !== null && text !== null) events.push({ ts, tid, text: text.replace(/\n$/, '') });
      }
    }
  }
  events.sort((a, b) => (a.ts < b.ts ? -1 : a.ts > b.ts ? 1 : 0));
  const stacks = new Map(), async = new Map(), out = [];
  for (const e of events) {
    const parts = e.text.split('|');
    if (parts[0] === 'B') {
      if (!stacks.has(e.tid)) stacks.set(e.tid, []);
      stacks.get(e.tid).push({ name: parts.slice(2).join('|'), ts: e.ts });
    } else if (parts[0] === 'E') {
      const open = stacks.get(e.tid)?.pop();
      if (open && open.name.startsWith('cm.')) out.push({ name: open.name, ts: open.ts, dur: e.ts - open.ts });
    } else if (parts[0] === 'S' || parts[0] === 'F') {
      const key = parts[2] + '#' + parts[3];
      if (parts[0] === 'S') async.set(key, e.ts);
      else if (async.has(key) && parts[2].startsWith('cm.')) { out.push({ name: parts[2], ts: async.get(key), dur: e.ts - async.get(key) }); async.delete(key); }
    }
  }
  return out;
}

console.log('file,name,ts_ns,duration_ms');
for (const file of process.argv.slice(2)) {
  for (const s of sections(file)) console.log(`${file.split(/[\\/]/).pop()},${s.name},${s.ts},${(Number(s.dur) / 1e6).toFixed(4)}`);
}
