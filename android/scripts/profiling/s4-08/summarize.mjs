import fs from 'node:fs';
import path from 'node:path';
const args=process.argv.slice(2), variant=args.pop(), mode=args.pop();
if(!['before','after'].includes(variant)||!['small','large'].includes(mode)||!args.length) throw Error('Usage: summarize.mjs CSV... small|large before|after');
const median=a=>{const x=[...a].sort((a,b)=>a-b),m=Math.floor(x.length/2);return x.length%2?x[m]:(x[m-1]+x[m])/2};
const all={search:[],detail:[]};
let payload=null;
for(const file of args){
  const rows=fs.readFileSync(file,'utf8').trim().split(/\r?\n/).slice(1).map(l=>{const [file,name,ts,dur]=l.split(',');return {name,ts:BigInt(ts),dur:Number(dur)}});
  const log=fs.readFileSync(file.replace(/\.csv$/,'.log'),'utf8');
  const line=log.split(/\r?\n/).find(l=>l.includes(`DONE mode=${mode} `)&&l.includes('apiCalls=0 PASS'));
  if(!line) throw Error('Missing successful no-network log: '+file);
  const signature=line.match(/searchBytes=(\d+) detailBytes=(\d+) searchSha=([a-f0-9]+) detailSha=([a-f0-9]+)/)?.[0];
  if(!signature|| (payload&&payload!==signature)) throw Error('Payload mismatch: '+file);
  payload=signature;
  for(const kind of ['search','detail']){
    const bursts=rows.filter(r=>r.name===`cm.s4-08.${kind}.${mode}`);
    if(bursts.length!==1) throw Error(`${file}: ${kind} burst count ${bursts.length}`);
    const b=bursts[0],end=b.ts+BigInt(Math.round(b.dur*1e6));
    const inside=rows.filter(r=>r.ts>=b.ts&&r.ts<end);
    const reads=inside.filter(r=>r.name==='cm.restaurant.cache.read');
    const decodes=inside.filter(r=>r.name==='cm.restaurant.cache.decode');
    if(reads.length!==(variant==='before'?200:100)||decodes.length!==100) throw Error(`${file}: ${kind} reads=${reads.length} decodes=${decodes.length}`);
    const sum=[...reads,...decodes].reduce((s,r)=>s+r.dur,0);
    all[kind].push({burst:b.dur,sections:sum});
    console.log(`${path.basename(file)} ${kind} reads=${reads.length} decodes=${decodes.length} burstMs=${b.dur.toFixed(4)} sumReadDecodeMs=${sum.toFixed(4)}`);
  }
}
console.log(payload);
for(const [kind,rs] of Object.entries(all))for(const k of ['burst','sections']){
  const x=rs.map(r=>r[k]),m=median(x),lo=Math.min(...x),hi=Math.max(...x);
  console.log(`${kind} ${k}: n=${x.length} median=${m.toFixed(4)} range=${lo.toFixed(4)}-${hi.toFixed(4)} spread=${((hi-lo)/m*100).toFixed(2)}%`);
}
