'use strict';
// Generates the cross-port probe corpus. Every case is tagged with an area so a
// sweep can be re-run and counted on its own.
const fs = require('fs');
const out = [];
let n = 0;
const add = (area, expr, input) =>
  out.push({ id: `${area}#${n++}`, expr, input: input === undefined ? null : input });

// ---------------------------------------------------------------- numbers
const NUMS = ['0', '-0', '1', '-1', '0.1', '1/3', '1e21', '1e20', '1e-6', '1e-7', '1e-320',
  '5e-324', '1e16', '1e15', '12345678901234567890', '$power(2,70)', '$power(2,53)',
  '$power(2,53)+2', '0.00001', '0.0001', '100000000000000000000', '1.5e300', '-1.5e-300',
  '123456789012345678', '2.675', '1e6', '1e7', '0.000001', '0.0000001', '9007199254740993',
  '1.7976931348623157e308'];
for (const v of NUMS) {
  add('num', `$string(${v})`);
  add('num', `${v} & ""`);
  add('num', `$floor(${v})`);
  add('num', `$ceil(${v})`);
  add('num', `$abs(${v})`);
  add('num', `$sqrt($abs(${v}))`);
}

// $round: the tie-window family
for (const v of ['2.5', '3.5', '-2.5', '2.675', '1.005', '0.5', '1.5', '-0.5', '2.345',
  '1234.5678', '-36435.03133177965', '0.615', '1.0049999999999999', '8.835', '1e15',
  '123456789.987654321', '-1234.5678', '0.045', '1.45', '2.45']) {
  for (const p of ['', ',0', ',1', ',2', ',3', ',10', ',-1', ',-2', ',15']) {
    add('round', `$round(${v}${p})`);
  }
}

// $number parsing grammar
for (const s of ['"1"', '"1.5"', '"-1"', '"0x1A"', '"0x1A "', '" 1 "', '"0o17"', '"0b101"',
  '"1e3"', '"1E3"', '"x0o17y"', '"Infinity"', '"NaN"', '""', '"."', '"1."', '".5"', '"+1"',
  '"1_000"', 'true', 'false', 'null']) {
  add('number', `$number(${s})`);
}
for (const b of ['2', '8', '16', '36', '1', '37']) {
  add('formatBase', `$formatBase(255, ${b})`);
  add('formatBase', `$formatBase(-255, ${b})`);
}

// $formatNumber -- the F&O picture engine
const FN_PICS = ['"#"', '"0"', '"#.##"', '"0.00"', '"#,###.00"', '"###0.0###"', '"0.0e0"',
  '"#0.0#e0"', '"00%"', '"0.0%"', '"#;#"', '"0.0;(0.0)"', '"###,##0.00"', '"#.#####"',
  '"0000"', '"#0"', '"#,##,##0"', '"0.###;-0.###"', '"0e0"', '"#e0"', '"#.0"', '"0.#"'];
for (const p of FN_PICS) {
  for (const v of ['0', '1', '-1', '0.5', '-0.5', '1234.5678', '-1234.5678', '1e21',
    '0.000012', '123456789', '-0.0001', '1000000', '0.999', '-0.999']) {
    add('formatNumber', `$formatNumber(${v}, ${p})`);
  }
}
add('formatNumber', '$formatNumber(0.5, "#", {"percent":"p"})');
add('formatNumber', '$formatNumber(0.5, "0.0p", {"percent":"p"})');
add('formatNumber', '$formatNumber(1234.5, "#,###.00", {"grouping-separator":" ","decimal-separator":","})');
add('formatNumber', '$formatNumber(-5, "0;POS0")');
add('formatNumber', '$formatNumber(1234.5, "0.0", {"zero-digit":"٠"})');

// $formatInteger / $parseInteger
const FI_PICS = ['"0"', '"000"', '"#"', '"###"', '"1"', '"01"', '"0001"', '"I"', '"i"', '"A"',
  '"a"', '"w"', '"W"', '"Ww"', '"1;o"', '"01;o"', '"#,##0"', '"#,##,##0"', '"0000;o"',
  '"N"', '"n"'];
for (const p of FI_PICS) {
  for (const v of ['0', '1', '-1', '7', '-7', '12', '-12.6', '12.6', '3999', '4000',
    '1000000', '-1000000', '25', '26', '27', '702', '703', '-3999', '21', '112']) {
    add('formatInteger', `$formatInteger(${v}, ${p})`);
  }
}
for (const p of ['"0"', '"000"', '"I"', '"w"', '"#,##0"']) {
  for (const s of ['"1"', '"007"', '"MCMXCIV"', '"one hundred"', '"1,234"', '"-7"']) {
    add('parseInteger', `$parseInteger(${s}, ${p})`);
  }
}

// ---------------------------------------------------------------- datetime
const MS = ['0', '-1', '-2208988800000', '1521801216617', '1e12', '253402300799999',
  '-62135596800000', '946684800000', '1704067200000', '1709251200000', '1735689600000',
  '86399999', '-86400000'];
const DT_PICS = ['"[Y0001]-[M01]-[D01]"', '"[Y]"', '"[Ya]"', '"[YA]"', '"[Yi]"', '"[YI]"',
  '"[Yw]"', '"[YW]"', '"[YWw]"', '"[M01]"', '"[MI]"', '"[Mw]"', '"[MA]"', '"[Mn]"', '"[MN]"',
  '"[MNn]"', '"[D01]"', '"[D1o]"', '"[DW]"', '"[Dw]"', '"[Di]"', '"[d]"', '"[d001]"', '"[F]"',
  '"[FNn]"', '"[Fn]"', '"[F0]"', '"[W]"', '"[W01]"', '"[w]"', '"[X0001]"', '"[x01]"',
  '"[H01]"', '"[h]"', '"[h01][P]"', '"[m01]"', '"[s01]"', '"[f]"', '"[f001]"', '"[f0001]"',
  '"[f1]"', '"[Z]"', '"[Z0000]"', '"[Z01:01]"', '"[Z0]"', '"[Zt]"', '"[z]"', '"[z0000]"',
  '"[z01:01]"', '"[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01]"',
  '"[D1] [MNn] [Y0001]"', '"[FNn], [D1o] [MNn] [Y]"', '"[E]"', '"[Y0001]"', '"[M]"', '"[D]"'];
for (const ms of MS) {
  for (const p of DT_PICS) add('fromMillis', `$fromMillis(${ms}, ${p})`);
  add('fromMillisIso', `$fromMillis(${ms})`);
}
for (const ms of ['0', '1521801216617', '-2208988800000']) {
  for (const tz of ['"-0500"', '"+0530"', '"0000"', '"Z"', '"+1400"', '"-1200"']) {
    add('fromMillisTz',
      `$fromMillis(${ms}, "[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01]", ${tz})`);
  }
}
for (const s of ['"2023-01-01"', '"2023-13-01"', '"2023-00-01"', '"2023-01-00"', '"2023-01-32"',
  '"2023-02-29"', '"2023-01-01T12:00:00Z"', '"2023-01-01T12:00:00.123+05:30"',
  '"1900-01-01T00:00:00.000Z"', '"2023-W01-1"', '"2023"', '"2023-01"',
  '"1970-01-01T00:00:00Z"']) {
  add('toMillis', `$toMillis(${s})`);
}
for (const p of ['"[Y0001]-[M01]-[D01]"', '"[D1] [MNn] [Y]"', '"[Y]"', '"[H01]:[m01]"']) {
  for (const s of ['"2023-01-01"', '"1 January 2023"', '"2023"', '"12:30"']) {
    add('toMillisPic', `$toMillis(${s}, ${p})`);
  }
}
// round-trip: the sweep that catches formatter/parser drift as a pair
for (const ms of MS) {
  add('dtRound', `$toMillis($fromMillis(${ms}))`);
}

// ---------------------------------------------------------------- strings
const SUBJ = ['"abc"', '"Hello World"', '""', '"a\\nb"', '"a\\tb"', '"  padded  "',
  '"\\u00a0nbsp\\u00a0"', '"ABC"', '"aaa"', '"a.b.c"', '"\\ud83d\\ude00a"', '"h\\u00e9llo"',
  '"line1\\nline2\\rline3"', '"tail\\n"'];
for (const s of SUBJ) {
  add('str', `$length(${s})`);
  add('str', `$uppercase(${s})`);
  add('str', `$lowercase(${s})`);
  add('str', `$trim(${s})`);
  add('str', `$string(${s})`);
  add('str', `$base64encode(${s})`);
  add('str', `$base64decode($base64encode(${s}))`);
  add('str', `$encodeUrlComponent(${s})`);
  add('str', `$encodeUrl(${s})`);
  add('str', `$substring(${s}, 1)`);
  add('str', `$substring(${s}, 1, 2)`);
  add('str', `$substring(${s}, -2)`);
  add('str', `$pad(${s}, 10)`);
  add('str', `$pad(${s}, -10, "*")`);
  add('str', `$substringBefore(${s}, "l")`);
  add('str', `$substringAfter(${s}, "l")`);
  add('str', `$split(${s}, "")`);
}
for (const s of ['"6Q=="', '"aGVsbG8="', '"eQ"', '"!!!!"', '"YQ==="', '"w6k="']) {
  add('b64', `$base64decode(${s})`);
}

// regex: the zero-length/D1004 family, anchoring, and the line-break set
const PATS = ['/b/', '/x*/', '/^/', '/$/', '/a*/', '/(a)(b)?/', '/./', '/\\s/', '/[a-z]+/',
  '/^a/m', '/c$/m', '/A/i', '/(?:)/', '/\\d/', '/l/'];
for (const s of SUBJ) {
  for (const p of PATS) {
    add('match', `$match(${s}, ${p})`);
    add('split', `$split(${s}, ${p})`);
    add('replace', `$replace(${s}, ${p}, "-")`);
    add('contains', `$contains(${s}, ${p})`);
  }
}
for (const lim of ['0', '1', '2', '-1', '1.5', '2.7']) {
  for (const p of ['/a/', '/x*/']) {
    add('limit', `$match("aaa", ${p}, ${lim})`);
    add('limit', `$split("aaa", ${p}, ${lim})`);
    add('limit', `$replace("aaa", ${p}, "-", ${lim})`);
  }
}
add('replFn', '$replace("abc", /b/, function($m) { $m.match & "!" })');
add('replFn', '$replace("abc", /(b)/, function($m) { $string($m) })');
add('replFn', '$replace("abc", /b/, function($m) { $string($keys($m)) })');
add('replStr', '$replace("abc", /(b)/, "[$1]")');
add('replStr', '$replace("abc", /b/, "$$")');
add('replStr', '$replace("abc", /b/, "$9")');

// ---------------------------------------------------------------- sequences/objects
const DATA = { a: 1, b: [1, 2, 3], c: { d: 4 }, e: [{ f: 1 }, { f: 2 }], g: [[{ h: 1 }]] };
for (const e of ['$keys($)', '$keys(e)', '$keys(g)', '$keys(b)', '$lookup(e, "f")',
  '$lookup(g, "h")', '$lookup($, "a")', '$spread($)', '$spread(e)', '$spread(c)',
  '$merge([c, {"z":9}])', '$each(c, function($v,$k){$k})',
  '$sift($, function($v){$v=1})', '$type($)', '$type(b)']) {
  add('obj', e, DATA);
}
for (const e of ['$reverse(1)', '$reverse([1,2])', '$sort([3,1,2])', '$sort([{"a":1}])',
  '$sort([1,"a"])', '$distinct([1,1,2])', '$distinct([{"a":1},{"a":1}])',
  '$reduce(nope, function($a,$b){$a+$b}, 5)', '$reduce([1,2,3], function($a,$b){$a+$b})',
  '$reduce([], function($a,$b){$a+$b}, 5)', '$reduce([1,2], $sum)', '$clone($)', '$clone(b)',
  '$clone("x")', '$clone(nope)', '$append(1,2)', '$zip([1,2],[3,4])',
  '$single([1],function($v){true})', '$count(nope)', '$sum([])', '$average([])']) {
  add('seq', e, DATA);
}
for (const e of ['($o := {"g": function(){42}}; $o.g())', '$eval("1+1")',
  '$eval("$millis()") = $millis()', '[1,2].$count()', '{"a":1}.$keys()',
  '$string($millis() > 0)']) {
  add('misc', e, DATA);
}

fs.writeFileSync(process.argv[2], JSON.stringify(out));
console.log(`${out.length} cases`);
