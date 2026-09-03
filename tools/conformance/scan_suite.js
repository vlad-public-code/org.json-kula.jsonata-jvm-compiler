'use strict';
// Does the official JSONata test suite exercise a path whose FIRST step is an array
// constructor? That is exactly the `consarray` flag the reference sets, so the AST answers
// it directly rather than by guessing at the expression text.
const fs = require('fs');
const path = require('path');
const jsonata = require('c:/vlad-projects/js/jsonata2js/node_modules/jsonata');

const root = process.argv[2];

function walkFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkFiles(full, out);
    else if (entry.name.endsWith('.json')) out.push(full);
  }
  return out;
}

/** True if any path node in the AST has an array constructor as its first step. */
function hasLeadingConsarray(node, seen = new Set()) {
  if (node === null || typeof node !== 'object' || seen.has(node)) return false;
  seen.add(node);
  if (node.type === 'path' && Array.isArray(node.steps) && node.steps.length > 1
      && node.steps[0] && node.steps[0].consarray === true) {
    return true;
  }
  for (const key of Object.keys(node)) {
    const child = node[key];
    if (Array.isArray(child)) {
      for (const c of child) if (hasLeadingConsarray(c, seen)) return true;
    } else if (hasLeadingConsarray(child, seen)) return true;
  }
  return false;
}

const files = walkFiles(root, []);
let cases = 0, parsed = 0, unparseable = 0;
const hits = [];

for (const file of files) {
  let content;
  try { content = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { continue; }
  for (const testCase of Array.isArray(content) ? content : [content]) {
    const expr = testCase.expr;
    if (typeof expr !== 'string') continue;
    cases++;
    let ast;
    try { ast = jsonata(expr).ast(); parsed++; } catch { unparseable++; continue; }
    if (hasLeadingConsarray(ast)) {
      hits.push({ file: path.relative(root, file), expr });
    }
  }
}

console.log(`suite case files scanned : ${files.length}`);
console.log(`expressions              : ${cases}  (parsed ${parsed}, expected-parse-error ${unparseable})`);
console.log(`paths led by an array constructor: ${hits.length}`);
for (const h of hits) console.log(`   ${h.file}\n      ${h.expr.replace(/\s+/g, ' ')}`);
