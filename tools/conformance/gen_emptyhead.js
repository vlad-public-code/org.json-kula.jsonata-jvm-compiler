'use strict';
// Characterises the "array constructor heading a path" divergence across engines.
const fs = require('fs');
const data = { nums: [1, 2, 3], empty: [], objs: [{ x: 1 }], nested: { e: [] } };
const exprs = [
  // --- the constructor is step 0 and yields empty --------------------------
  '[].x',
  '[].x.y',
  '[].$count()',
  '[].*',
  '[].**',
  '[].{}',
  '[].[1]',
  '[nope].x',
  '[nope, nope].x',
  '[nums[false]].x',
  '[empty].x',
  // --- observable consequences --------------------------------------------
  '$exists([].x)',
  '[].x = []',
  '$string([].x)',
  '$type([].x)',
  '$count([].x)',
  '$boolean([].x)',
  '[].x ? "yes" : "no"',
  '$append([].x, 1)',
  // --- the constructor is step 0 but NOT empty -----------------------------
  '["a"].x',
  '[1].x',
  '[[]].x',
  '[{"x":1}].x',
  // --- the constructor is not step 0 --------------------------------------
  'nums.[].x',
  'objs.[].x',
  '$.[].x',
  // --- other routes to an empty sequence ----------------------------------
  'empty.x',
  'nested.e.x',
  'nums[false].x',
  'objs[x>99].y',
  '$filter(nums, function($v){false}).x',
  '$map([], function($v){$v}).x',
  '(nums[false]).x',
  '([]).x',
  '($e := []; $e.x)',
  '$each({}, function($v){$v}).x',
  // --- neighbouring constructor forms -------------------------------------
  '{}.x',
  '({}).x',
  '[][0].x',
  '[][0]',
  '[]^(x).y',
  '[] ~> $count()',
];

const out = exprs.map((expr, i) => ({ id: 'eh#' + i, expr, input: data }));
fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.log(`${out.length} cases`);
