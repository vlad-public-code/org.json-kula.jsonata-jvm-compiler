'use strict';
// Edge semantics for a field-function call in a path step.
const fs = require('fs');
const out = [];
let n = 0;
const add = (expr, input) => out.push({ id: `pc2#${n++}`, expr, input: input === undefined ? null : input });

// what the receiver may be
add('($o := {"x": null}; $o.x.g())');                 // JSON null context
add('($o := {"x": 5}; $o.x.g())');                    // scalar context
add('($o := {"x": []}; $o.x.g())');                   // empty sequence context
add('($o := {"x": [1,2]}; $o.x.g())');                // array of scalars
add('($o := {"x": {}}; $o.x.g())');                   // empty object
add('($o := {}; $o.nope.g())');                       // missing context

// missing callee whose name is also a built-in
add('($o := {"a": 1}; $o.count())');
add('($o := {"a": 1}; $o.nope())');
add('($o := {"a": 1}; $o.uppercase())');
add('($o := {"a": 1}; $o.sum())');

// quoted name in a step, followed by a call
add('($o := {"g": function(){42}}; $o."g"())');
add('($o := {"g h": function(){42}}; $o."g h"())');

// the callee resolves per element
add('($a := [{"g": function(){1}}, {"n": 2}]; $a.g())');
add('($a := [{"g": function(){1}}, {"g": 5}]; $a.g())');

// arguments see the step context, and the outer scope
add('($k := 10; $o := {"g": function($x){$x}, "v": 7}; $o.g(v + $k))');
add('($o := {"g": function($x,$y){$x + $y}, "v": 7}; $o.g(v, 3))');

// nested / chained
add('($o := {"g": function(){ {"h": function(){"deep"}} }}; $o.g().h())');
add('($o := {"a": [{"g": function(){1}},{"g": function(){2}}]}; $o.a.g())');

// a built-in call inside the step's own arguments must stay a built-in call
add('($o := {"g": function($x){$x}, "v": "hi"}; $o.g($uppercase(v)))');
add('($o := {"g": function($x){$x}}; $o.g($count([1,2,3])))');

// predicates and sorting around it
add('($a := [{"g": function(){1}},{"g": function(){2}}]; $a.g()[$ > 1])');

// a bare built-in name called at top level is still T1005
add('count([1,2])');
// but as a step it is a field
add('($o := {"count": function(){"mine"}}; $o.count())');

fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.log(`${out.length} cases`);
