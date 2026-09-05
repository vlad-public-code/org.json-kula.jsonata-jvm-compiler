'use strict';
// Generates the differential corpus for SequenceScanFusion: blocks that bind a sequence and
// then interrogate it several times, which is exactly the shape the pass rewrites.
//
// Every case is run twice, once as written and once with a `$z := 0;` statement wedged between
// the operations, because the pass declines or reorders on statement structure rather than on
// the values — a same-answer test that only ever sees one arrangement passes by luck.
const fs = require('fs');
const out = [];
let n = 0;
const add = (area, expr, input) =>
  out.push({ id: `${area}#${n++}`, expr, input: input === undefined ? null : input });

// ---------------------------------------------------------------- documents
const CLEAN = {
  e: [
    { name: 'a', salary: 10, level: 'senior', rating: 4.5, remote: true },
    { name: 'b', salary: 20, level: 'lead', rating: 3.0, remote: false },
    { name: 'c', salary: 30, level: 'senior', rating: 5.0, remote: true },
    { name: 'd', salary: 5, level: 'junior', rating: 2.5, remote: false }
  ]
};
// A field that is sometimes absent, sometimes an array, sometimes the wrong type.
const RAGGED = {
  e: [
    { name: 'a', salary: 10, level: 'senior' },
    { name: 'b', level: 'lead' },
    { name: 'c', salary: [1, 2, 3], level: 'senior' },
    { name: 'd', salary: 4, level: null },
    { name: 'e', salary: 7 }
  ]
};
const BAD = { e: [{ salary: 10, level: 'senior' }, { salary: 'oops', level: 'lead' }] };
const SINGLE = { e: { salary: 10, level: 'senior', rating: 4.5 } };
const EMPTY = { e: [] };
const ABSENT = { other: 1 };
// Elements that are not objects, including one that is itself an array: a predicate maps the
// field step over it and can match, an aggregate skips it.
const MIXED = { e: [{ salary: 10, level: 'senior' }, 7, 'x', null, [{ salary: 3, level: 'senior' }]] };
const STRINGY = { e: [{ level: 'senior', salary: 'x' }, { level: 'lead', salary: 'y' }] };

const DOCS = { clean: CLEAN, ragged: RAGGED, bad: BAD, single: SINGLE, empty: EMPTY,
  absent: ABSENT, mixed: MIXED, stringy: STRINGY };

/** Runs one block body against every document, plainly and with an inert statement wedged in. */
function everywhere(area, head, ops, tail) {
  for (const [docName, doc] of Object.entries(DOCS)) {
    add(`${area}-${docName}`, `( ${head}; ${ops.join('; ')}; ${tail} )`, doc);
    add(`${area}-${docName}-wedged`,
      `( ${head}; ${ops.join('; $z := 0; ')}; ${tail} )`, doc);
  }
}

const SEQ = '$e := e';

// ---------------------------------------------------------------- aggregates sharing a field
everywhere('agg4', SEQ,
  ['$a := $sum($e.salary)', '$b := $average($e.salary)', '$c := $max($e.salary)',
    '$d := $min($e.salary)'],
  '[$a, $b, $c, $d]');

// The four aggregates in every pairwise order: which one is bound first decides which error wins.
const AGGS = ['sum', 'average', 'max', 'min'];
for (const x of AGGS) {
  for (const y of AGGS) {
    if (x === y) continue;
    for (const [docName, doc] of Object.entries({ bad: BAD, ragged: RAGGED, stringy: STRINGY })) {
      add(`aggorder-${docName}`,
        `( ${SEQ}; $a := $${x}($e.salary); $b := $${y}($e.salary); [$a, $b] )`, doc);
    }
  }
}

// An aggregate's error must not outrun a statement bound between the absorbed ones.
for (const [docName, doc] of Object.entries({ bad: BAD, stringy: STRINGY, clean: CLEAN })) {
  add(`aggdefer-${docName}`,
    `( ${SEQ}; $a := $count($e[level = "senior"]); $z := $error("boom"); $b := $sum($e.salary); $b )`,
    doc);
  add(`aggdefer-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary); $z := $error("boom"); $b := $max($e.salary); $b )`, doc);
  // The aggregate is bound but never read: it still runs, and still fails, at its binding.
  add(`aggdefer-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary); $b := $count($e[level = "senior"]); $c := $max($e.salary); $b )`,
    doc);
}

// ---------------------------------------------------------------- equality predicates
everywhere('eq', SEQ,
  ['$a := $count($e[level = "senior"])', '$b := $count($e[level = "lead"])',
    '$c := $e[level = "senior"]'],
  '[$a, $b, $c]');

everywhere('eqtypes', SEQ,
  ['$a := $count($e[salary = 10])', '$b := $count($e[remote = true])',
    '$c := $count($e[level = null])', '$d := $count($e[salary = 10])'],
  '[$a, $b, $c, $d]');

// ---------------------------------------------------------------- != , and , or
everywhere('ne', SEQ,
  ['$a := $count($e[level != "senior"])', '$b := $count($e[level != "lead"])',
    '$c := $e[level != "senior"]'],
  '[$a, $b, $c]');

everywhere('junction', SEQ,
  ['$a := $count($e[level = "senior" and salary = 10])',
    '$b := $count($e[level = "lead" or salary = 30])',
    '$c := $e[level = "senior" and salary != 10]',
    '$d := $count($e[level != "senior" or level != "lead"])'],
  '[$a, $b, $c, $d]');

// A junction whose two sides read different fields: the scan must read both, once each.
everywhere('junction2', SEQ,
  ['$a := $count($e[level = "senior" and remote = true])',
    '$b := $count($e[level = "senior" or remote = false])',
    '$c := $sum($e.salary)'],
  '[$a, $b, $c]');

// Nested junctions.
everywhere('junction3', SEQ,
  ['$a := $count($e[(level = "senior" and salary = 10) or level = "lead"])',
    '$b := $count($e[level = "senior" and (salary = 10 or salary = 30)])',
    '$c := $sum($e.salary)'],
  '[$a, $b, $c]');

// ---------------------------------------------------------------- ordering comparisons
everywhere('ord', SEQ,
  ['$a := $count($e[salary >= 10])', '$b := $count($e[salary > 10])',
    '$c := $count($e[salary < 10])', '$d := $count($e[salary <= 10])'],
  '[$a, $b, $c, $d]');

everywhere('ordmix', SEQ,
  ['$a := $count($e[rating >= 4.5])', '$b := $e[salary > 9]', '$c := $sum($e.salary)'],
  '[$a, $b, $c]');

// An ordering comparison against a string literal, and one that cannot be compared at all.
everywhere('ordstr', SEQ,
  ['$a := $count($e[level >= "l"])', '$b := $count($e[level < "s"])', '$c := $sum($e.salary)'],
  '[$a, $b, $c]');

// The ordering error must be deferred exactly as an aggregate's is.
for (const [docName, doc] of Object.entries(DOCS)) {
  add(`orddefer-${docName}`,
    `( ${SEQ}; $a := $count($e[salary >= 10]); $z := $error("boom"); $b := $count($e[salary > 1]); $b )`,
    doc);
  add(`orddefer-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary); $b := $count($e[level >= 3]); $c := $count($e[level >= 4]); $a )`,
    doc);
  // The failing comparison is bound after a sound one: the sound one still answers.
  add(`orddefer-${docName}`,
    `( ${SEQ}; $a := $count($e[level = "senior"]); $b := $count($e[salary >= "x"]); $a )`, doc);
}

// ---------------------------------------------------------------- shapes the pass must decline
for (const [docName, doc] of Object.entries(DOCS)) {
  // Rebound sequence.
  add(`decline-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary); $e := []; $b := $max($e.salary); [$a, $b] )`, doc);
  // Shadowed built-in.
  add(`decline-${docName}`,
    `( ${SEQ}; $sum := function($x) { 99 }; $a := $sum($e.salary); $b := $max($e.salary); [$a, $b] )`,
    doc);
  // Under a conditional branch and under the right of and/or.
  add(`decline-${docName}`,
    `( ${SEQ}; $a := $count($e) > 0 ? $sum($e.salary) : 0; $b := $max($e.salary); [$a, $b] )`, doc);
  add(`decline-${docName}`,
    `( ${SEQ}; $a := false and $sum($e.salary) > 0; $b := $max($e.salary); [$a, $b] )`, doc);
  // Inside a lambda.
  add(`decline-${docName}`,
    `( ${SEQ}; $f := function() { $sum($e.salary) }; $a := $f(); $b := $max($e.salary); [$a, $b] )`,
    doc);
  // A predicate shape outside the grammar: a function call, and a two-sided field comparison.
  add(`decline-${docName}`,
    `( ${SEQ}; $a := $count($e[$length(level) = 6]); $b := $sum($e.salary); [$a, $b] )`, doc);
  add(`decline-${docName}`,
    `( ${SEQ}; $a := $count($e[salary = rating]); $b := $sum($e.salary); [$a, $b] )`, doc);
  // A positional predicate is an index, not a test.
  add(`decline-${docName}`,
    `( ${SEQ}; $a := $e[0]; $b := $sum($e.salary); $c := $max($e.salary); [$a, $b, $c] )`, doc);
}

// ---------------------------------------------------------------- absorbed inside other syntax
for (const [docName, doc] of Object.entries(DOCS)) {
  add(`nested-${docName}`,
    `( ${SEQ}; $a := $round($average($e.salary), 2); $b := $max($e.salary); $c := $sum($e.salary); [$a, $b, $c] )`,
    doc);
  add(`nested-${docName}`,
    `( ${SEQ}; { "t": $sum($e.salary), "m": $max($e.salary), "n": $count($e[level = "senior"]) } )`,
    doc);
  add(`nested-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary) + $max($e.salary) + $min($e.salary); $a )`, doc);
  add(`nested-${docName}`,
    `( ${SEQ}; $a := [$sum($e.salary), $count($e[level = "senior"]), $count($e[level = "lead"])]; $a )`,
    doc);
  // The same expression text twice: two separate operations, two separate slots.
  add(`nested-${docName}`,
    `( ${SEQ}; $a := $sum($e.salary); $b := $sum($e.salary); $c := $max($e.salary); [$a, $b, $c] )`,
    doc);
}

// ---------------------------------------------------------------- filter result shapes
for (const [docName, doc] of Object.entries(DOCS)) {
  for (const lit of ['"senior"', '"lead"', '"junior"', '"nobody"', 'null']) {
    add(`shape-${docName}`,
      `( ${SEQ}; $a := $e[level = ${lit}]; $b := $count($e[level = ${lit}]); $c := $sum($e.salary); [$a, $b, $c] )`,
      doc);
  }
}

fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.error(`${out.length} cases`);
