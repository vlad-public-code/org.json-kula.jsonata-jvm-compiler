'use strict';
// Arbitrates three engine outputs against the reference.
//   node arbitrate.js cases.json java.json ref.json [js.json] [--all] [--limit N]
// Reading two implementations side by side finds candidate differences but
// cannot say which is right; the reference is the arbiter.
const fs = require('fs');
const rd = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

const cases = rd(process.argv[2]);
const java = rd(process.argv[3]);
const ref = rd(process.argv[4]);
const js = process.argv[5] && !process.argv[5].startsWith('--') ? rd(process.argv[5]) : null;
const showAll = process.argv.includes('--all');
const li = process.argv.indexOf('--limit');
const limit = li >= 0 ? Number(process.argv[li + 1]) : 40;

const S = (v) => JSON.stringify(v);
const byId = new Map(cases.map((c) => [c.id, c]));

let agree = 0;
const diffs = [];
for (const id of Object.keys(ref)) {
    const j = S(java[id]), r = S(ref[id]);
    if (j === r) { agree++; continue; }
    diffs.push({ id, expr: byId.get(id).expr, java: j, ref: r, js: js ? S(js[id]) : null });
}

// Where java and the reference differ, does the JS port agree with the reference?
let jsRight = 0, jsWrong = 0;
for (const d of diffs) if (d.js !== null) (d.js === d.ref ? jsRight++ : jsWrong++);

console.log(`cases ${Object.keys(ref).length}  agree ${agree}  DIVERGENCES ${diffs.length}`);
if (js) console.log(`  of those, reference agrees with jsonata2js on ${jsRight}, with neither on ${jsWrong}`);
for (const d of diffs.slice(0, showAll ? diffs.length : limit)) {
    console.log(`\n[${d.id}] ${d.expr}`);
    console.log(`   java ${d.java}`);
    console.log(`   ref  ${d.ref}`);
    if (d.js !== null && d.js !== d.ref) console.log(`   js   ${d.js}`);
}
if (!showAll && diffs.length > limit) console.log(`\n... ${diffs.length - limit} more`);
