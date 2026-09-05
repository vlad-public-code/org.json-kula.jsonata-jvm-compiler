'use strict';
// Reads a ProbeRunner output and reports every result whose error is not a JSONata error.
//
// ProbeRunner writes "eval:<code>" / "compile:<code>", falling back to the exception's class
// name when there is no code — so a host exception that escaped the runtime shows up here as
// "eval:NullPointerException", or as "eval:JsonataEvaluationException" when it was wrapped
// without a code. Both are bugs: the caller gets an error it cannot match on, carrying an
// internal class name.
//
// usage: node scan_leaks.js <cases.json> <out.json>
const fs = require('fs');

const cases = new Map(JSON.parse(fs.readFileSync(process.argv[2], 'utf8')).map(c => [c.id, c.expr]));
const results = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

const JSONATA_ERROR = /^(eval|compile):[A-Z]\d{4}$/;
const ALLOWED = new Set(['eval:timeout']);

const leaks = [];
let errors = 0;
for (const [id, r] of Object.entries(results)) {
  const err = r && r.err;
  if (!err) continue;
  errors++;
  if (JSONATA_ERROR.test(err) || ALLOWED.has(err)) continue;
  leaks.push({ id, expr: cases.get(id), err });
}

const byKind = new Map();
for (const l of leaks) byKind.set(l.err, (byKind.get(l.err) || 0) + 1);

console.log(`${Object.keys(results).length} cases, ${errors} raised, ${leaks.length} LEAKED`);
for (const [kind, count] of [...byKind].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${count.toString().padStart(5)}  ${kind}`);
}
for (const l of leaks.slice(0, 40)) console.log(`  [${l.id}] ${l.expr}\n      ${l.err}`);
process.exitCode = leaks.length ? 1 : 0;
