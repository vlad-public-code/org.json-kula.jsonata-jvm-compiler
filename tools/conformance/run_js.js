'use strict';
// The jsonata2js voice of the three-engine harness. Same I/O contract as
// jsonata2js/_xrun_ref.js (the reference oracle) and ProbeRunner.java, so all
// three outputs diff directly.
const fs = require('fs');
const jsonata2js = require('c:/vlad-projects/js/jsonata2js/src/index.js');

function safe(o) {
    const stack = [];
    return JSON.stringify(o, function (k, v) {
        if (typeof v === 'function') return '<fn>';
        if (v === undefined) return null;
        if (v !== null && typeof v === 'object') {
            while (stack.length && !Object.is(stack[stack.length - 1], this)) stack.pop();
            if (stack.some((a) => Object.is(a, v))) return '<circular>';
            stack.push(v);
        }
        return v;
    });
}

const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const out = {};
for (const c of cases) {
    try {
        const v = jsonata2js.compile(c.expr).evaluate(c.input);
        out[c.id] = { ok: v === undefined ? null : v };
    } catch (e) {
        out[c.id] = { err: `${e.constructor.name === 'JsonataCompilationError' || e.constructor.name === 'ParseError' ? 'compile' : 'eval'}:${e.code || e.constructor.name}` };
    }
}
fs.writeFileSync(process.argv[3], safe(out));
