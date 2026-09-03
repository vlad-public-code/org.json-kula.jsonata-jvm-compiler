'use strict';
// Semantics probe for calling a function from a path step:
//   a.g(...)   invokes the FIELD g of the step context
//   a.$g(...)  invokes the VARIABLE $g
//   a.$count() invokes the BUILT-IN with the step context as its first argument
const fs = require('fs');
const out = [];
let n = 0;
const add = (expr, input) => out.push({ id: `pc#${n++}`, expr, input: input === undefined ? null : input });

// ---------------------------------------------------------------- field calls
const OBJ = '$o := {"g": function(){42}, "h": function($x){$x * 2}, "count": function(){"field!"}, "n": 5}';
for (const tail of [
  '$o.g()', '$o.g(21)', '$o.h(21)', '$o.count()', '$o.n()', '$o.nope()',
  '($o.g)()', '$o.$g()', '$o."g"()',
]) {
  add(`(${OBJ}; ${tail})`);
}
// argument evaluated against the step context
add(`($o := {"g": function($x){$x}, "v": 7}; $o.g(v))`);
add(`($o := {"g": function($x){$x}, "v": 7}; $o.g(v * 2))`);
// $-prefixed variable wins over a same-named field
add(`($g := function(){"variable"}; $o := {"g": function(){"field"}}; $o.g())`);
add(`($g := function(){"variable"}; $o := {"g": function(){"field"}}; $o.$g())`);
// mapping over a sequence
add('($a := [{"g": function(){1}}, {"g": function(){2}}]; $a.g())');
// receivers of other shapes
add('({"g": function(){42}}).g()');
add('($f := function(){9}; {"g": $f}.g())');
add('(nothing.g())');
add('([].g())');
add('(null.g())');
add('((5).g())');
add('("str".g())');
// nested steps
add('($o := {"a": {"g": function(){"deep"}}}; $o.a.g())');
// data-driven receivers
const DATA = { o: { g: 1 }, arr: [{ n: 1 }, { n: 2 }], s: "hi", nums: [1, 2, 3] };
add('o.g()', DATA);

// ------------------------------------------- builtins as a step with context
for (const e of [
  'nums.$count()', '$count(nums)', 'nums.$sum()', 'nums.$reverse()',
  's.$uppercase()', 's.$length()', 's.$substring(1)', 'arr.$count()',
  'o.$keys()', '$keys(o)', 'nums.$max()', 'nums.$string()',
  's.$contains("h")', 'nums.$sort()', 'o.$type()',
]) {
  add(e, DATA);
}
for (const e of [
  '[1,2].$count()', '{"a":1}.$keys()', '"abc".$uppercase()', '"abc".$length()',
  '[1,2,3].$sum()', '[3,1,2].$sort()', '(5).$string()', '[1,2].$reverse()',
  '"abc".$substring(1)', '"abc".$substringBefore("b")', '(2).$power(3)',
  '(2.6).$round()', '(2.567).$round(2)', '[1,2].$append(3)',
  '"a,b".$split(",")', '1521801216617.$fromMillis()', '"2018-03-23".$toMillis()',
  '(-5).$abs()', '"abc".$match(/b/)', '"abc".$replace("b","X")',
  '[1,2].$map(function($v){$v*2})', '{"a":1}.$each(function($v,$k){$k})',
  '[1,2].$filter(function($v){$v>1})', '"abc".$uppercase("zzz")',
  '(5).$uppercase()', '"abc".$floor()',
]) {
  add(e);
}
// a built-in name used bare at the top level is still an error
for (const e of ['count([1,2])', 'uppercase("a")']) add(e);

fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.log(`${out.length} cases`);
