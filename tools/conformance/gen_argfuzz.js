'use strict';
// Argument fuzz for every built-in: 0-2 arguments drawn from an adversarial pool.
//
// This is not a differential corpus — it is a leak detector. A JSONata engine may legitimately
// answer, or raise a JSONata error, for any of these; what it must never do is let a *host*
// exception out, because that reaches the caller as an error with no JSONata code and a message
// naming an internal class. `scan_leaks.js` reads the output and flags exactly that.
//
// usage: node gen_argfuzz.js <builtins.txt> <out.json> [maxArgs]
const fs = require('fs');

const names = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/).filter(Boolean);
const maxArgs = Number(process.argv[4] || 2);

// Values chosen to sit on the edges the built-ins branch on: absent, null, the empty and
// oversized ends of every type, a function, a regex, and the picture/format strings that the
// date, number and regex families parse.
const POOL = [
  'nope',            // undefined
  'null',
  'true',
  '0',
  '-1',
  '1.5',
  '1e15',
  '-1e15',
  '""',
  '"x"',
  '"["',             // an unterminated picture / bad format
  '"[Y0001]"',
  '"[fn]"',
  '"#,##0.00"',
  '"0o17"',
  '[]',
  '[1,2]',
  '[[1],[2]]',
  '{}',
  '{"a":1}',
  'function($x){$x}',
  '/a/',
];

const out = [];
let n = 0;
for (const name of names) {
  out.push({ id: `fuzz#${n++}`, expr: `$${name}()`, input: { a: 1 } });
  for (const a of POOL) {
    out.push({ id: `fuzz#${n++}`, expr: `$${name}(${a})`, input: { a: 1 } });
    if (maxArgs >= 2) {
      for (const b of POOL) {
        out.push({ id: `fuzz#${n++}`, expr: `$${name}(${a}, ${b})`, input: { a: 1 } });
      }
    }
  }
}

fs.writeFileSync(process.argv[3], JSON.stringify(out));
console.error(`${out.length} cases over ${names.length} built-ins`);
