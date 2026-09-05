'use strict';
// The findings the sibling ports recorded, turned into probes for this one.
//
// jsonata2py's CONFORMANCE-REVIEW closes with items it fixed, items it left open, and two
// pre-existing bugs it surfaced but did not fix. None of that transfers by assumption — a
// finding in one port is a *question* for the others — so each one is a case here and the
// reference decides. Areas: fnfield (a function held in a field), builtinstep (a $-prefixed
// built-in as a path step), regexarg (non-pattern arguments to the regex built-ins), codec,
// evalclock, reducearity.
const fs = require('fs');
const out = [];
let n = 0;
const add = (area, expr, input) =>
  out.push({ id: `${area}#${n++}`, expr, input: input === undefined ? null : input });

// ---------------------------------------------------------------- a function held in a field
// jsonata2py: `$o.g()` resolved `g` as a variable/built-in and ignored the step context.
const FN = '$o := {"g": function($x){ $exists($x) ? $x * 2 : 42 }, "n": 5}';
add('fnfield', `( ${FN}; $o.g() )`);
add('fnfield', `( ${FN}; $o.g(21) )`);
add('fnfield', `( ${FN}; ($o.g)() )`);
add('fnfield', `( ${FN}; $o.g(n) )`);
add('fnfield', `( ${FN}; $o.n() )`);
add('fnfield', `( ${FN}; $o.nope() )`);
// A field whose name is also a built-in must resolve as the FIELD.
add('fnfield', '( $o := {"count": function(){ "field" }}; $o.count() )');
add('fnfield', '( $o := {"string": function(){ "field" }}; $o.string() )');
// ...and with no such field, a built-in name is a different error from a non-built-in name.
add('fnfield', '( $o := {"a": 1}; $o.count() )');
add('fnfield', '( $o := {"a": 1}; $o.nope() )');
// A $-prefixed call in a step is the VARIABLE, even when the receiver has its own field.
add('fnfield', '( $g := function(){ "var" }; $o := {"g": function(){ "field" }}; $o.$g() )');
// Mapping, and the receiver shapes.
add('fnfield', '( $o := [{"g": function(){1}}, {"g": function(){2}}]; $o.g() )');
add('fnfield', '( $o := 5; $o.g() )');
add('fnfield', '( $o := null; $o.g() )');
add('fnfield', '( $o := nothing; $o.g() )');
add('fnfield', '( $o := []; $o.g() )');
add('fnfield', '$$.g()', { a: 1 });
add('fnfield', 'a.g()', { a: { g: 1 } });
// The arguments evaluate against the step context, not the outer one.
add('fnfield', '( $o := {"g": function($x){ $x }, "n": 7}; $o.g(n) )');
add('fnfield', '( $n := 1; $o := {"g": function($x){ $x }, "n": 7}; $o.g($n) )');

// ---------------------------------------------------------------- a built-in as a path step
// jsonata2py's two pre-existing bugs: a $-prefixed built-in used as a step with no arguments,
// where the context should become the first argument.
add('builtinstep', '[1,2].$count()');
add('builtinstep', '{"a":1}.$keys()');
add('builtinstep', '"abc".$length()');
add('builtinstep', '[1,2,3].$sum()');
add('builtinstep', '[3,1,2].$sort()');
add('builtinstep', '"abc".$uppercase()');
add('builtinstep', 'a.$string()', { a: 5 });
add('builtinstep', 'a.$length()', { a: 'abcd' });
add('builtinstep', '[1,2].$count(  )');
add('builtinstep', '$count()', { a: 1 });

// ---------------------------------------------------------------- regex built-in arguments
// jsonata2py left these open: a negative limit should be D3040, and a fractional limit is
// compared as `count < 1.5` in the reference rather than truncated.
for (const lim of ['-1', '0', '0.5', '1.5', '2.5', '1', '2', '100']) {
  add('regexarg', `$match("abcabc", /a/, ${lim})`);
  add('regexarg', `$split("a1b2c3", /[0-9]/, ${lim})`);
  add('regexarg', `$replace("abcabc", /a/, "X", ${lim})`);
}
add('regexarg', '$match("abc", /a/, null)');
add('regexarg', '$match("abc", /a/, "1")');
// $replace with a function replacer: what shape does the callback receive?
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $m.match })');
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $string($m.index) })');
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $m.groups[0] })');
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $string($keys($m)) })');
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $type($m) })');
add('regexarg', '$replace("abcabc", /a(b)/, function($m){ $count($m.groups) })');

// ---------------------------------------------------------------- codecs
// jsonata2py: "$base64decode is strict where Node's Buffer.from(s,'base64') is lenient".
for (const s of ['"YWJj"', '"YWJj="', '"YWJ"', '"YW=J"', '"!!!!"', '""', '"YWJjZA=="',
  '"YWJjZA="', '"YWJjZA"', '"Y W J j"', '"YWJj\\n"', '"-_"', '"+/"']) {
  add('codec', `$base64decode(${s})`);
}
add('codec', '$base64encode("")');
add('codec', '$base64decode($base64encode("héllo"))');

// ---------------------------------------------------------------- nested $eval and the clock
// jsonata2py: $now/$millis are frozen per top-level evaluation, and a nested $eval is part of
// that evaluation, so it must inherit the snapshot rather than take a fresh one.
add('evalclock', '$eval("$millis()") = $millis()');
add('evalclock', '$eval("$now()") = $now()');
add('evalclock', '$millis() = $millis()');
add('evalclock', '$eval("$eval(\\"$millis()\\")") = $millis()');
add('evalclock', '( $a := $millis(); $b := $eval("$millis()"); $a = $b )');

// ---------------------------------------------------------------- $reduce arity
// jsonata2py: a *variable* holding a reducer, or a built-in, reached the runtime unchecked and
// was invoked with a 4-element tuple.
add('reducearity', '$reduce([1,2,3], function($a,$b){ $a + $b })');
add('reducearity', '( $f := function($a,$b){ $a + $b }; $reduce([1,2,3], $f) )');
add('reducearity', '( $f := function($a){ $a }; $reduce([1,2,3], $f) )');
add('reducearity', '$reduce([1,2,3], function($a){ $a })');
add('reducearity', '$reduce([1,2,3], $sum)');
add('reducearity', '$reduce([1,2,3], $string)');
add('reducearity', '( $f := function($a,$b,$c){ $a }; $reduce([1,2,3], $f) )');
add('reducearity', '$reduce([1,2,3], function($a,$b,$c,$d){ $a })');
add('reducearity', '$reduce([1,2,3], function($a,$b){ $a + $b }, 10)');

fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.error(`${out.length} cases`);
