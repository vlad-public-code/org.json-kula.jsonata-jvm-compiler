'use strict';
// Wide per-area sweeps, generated. `node gen_sweep.js <area> <out.json>`.
const fs = require('fs');
const area = process.argv[2];
const out = [];
let n = 0;
const add = (expr, input) => out.push({ id: `${area}#${n++}`, expr, input: input === undefined ? null : input });

function numbers() {
  // A spread of doubles: integers either side of 2^53, subnormals, the notation
  // boundaries, and pseudo-random mantissas.
  const vs = [];
  for (let e = -330; e <= 308; e += 7) { vs.push(`1e${e}`, `-1e${e}`, `9.87654321e${e}`); }
  for (let i = 0; i < 300; i++) {
    const m = (Math.sin(i * 7.13) * 1e6).toFixed(6);
    vs.push(String(m), `${m}e${(i % 40) - 20}`);
  }
  for (const b of [52, 53, 54, 62, 63, 64, 70]) vs.push(`$power(2,${b})`, `$power(2,${b})+1`);
  vs.push('0', '-0', '1/3', '2/3', '1/7', '0.1+0.2');
  for (const v of vs) { add(`$string(${v})`); add(`$floor(${v})`); add(`$ceil(${v})`); }
}

function formatNumber() {
  const pics = ['#', '0', '#.##', '0.00', '#,###.00', '###0.0###', '0.0e0', '#0.0#e0',
    '00%', '0.0%', '#;#', '0.0;(0.0)', '###,##0.00', '#.#####', '0000', '#0',
    '#,##,##0', '0.###;-0.###', '0e0', '#e0', '#.0', '0.#', '0,0', '#,#0.0#',
    '0.00e00', '###.###', '00.00', '#####', '0.0000', '#,##0.###'];
  const vals = ['0', '-0', '1', '-1', '0.5', '-0.5', '1234.5678', '-1234.5678', '1e21',
    '1e-7', '0.000012', '123456789', '-0.0001', '1000000', '0.999', '-0.999',
    '99999.99999', '1/3', '2.675', '1e15', '-1e15', '0.045'];
  for (const p of pics) for (const v of vals) add(`$formatNumber(${v}, ${JSON.stringify(p)})`);
}

function integers() {
  const pics = ['0', '00', '000', '0000', '#', '###', '1', '01', '0001', 'I', 'i', 'A', 'a',
    'w', 'W', 'Ww', '1;o', '01;o', '0001;o', '#,##0', '#,##,##0', '0,0,0', 'N', 'n',
    '000;o', 'w;o', 'W;o', 'Ww;o', 'I;o', 'a;o'];
  const vals = ['0', '1', '-1', '7', '-7', '12', '-12', '13', '21', '22', '23', '100', '101',
    '111', '112', '113', '999', '1000', '3999', '4000', '12345', '-12345', '25', '26',
    '27', '52', '702', '703', '1000000', '-1000000', '12.6', '-12.6', '1e6'];
  for (const p of pics) for (const v of vals) add(`$formatInteger(${v}, ${JSON.stringify(p)})`);
  const inputs = ['1', '007', '0012', 'MCMXCIV', 'xiv', 'AB', 'ab', 'one hundred',
    'twenty-first', 'twelfth', '1,234', '-7', '', 'forty-second', 'ONE THOUSAND',
    '12th', '21st', 'nonsense'];
  for (const p of pics) for (const s of inputs) add(`$parseInteger(${JSON.stringify(s)}, ${JSON.stringify(p)})`);
}

function datetime() {
  const ms = ['0', '-1', '1', '-2208988800000', '1521801216617', '253402300799999',
    '-62135596800000', '946684800000', '1704067200000', '1709251200000', '1735689600000',
    '86399999', '-86400000', '1104537600000', '1104451200000', '1136073600000',
    '1451606400000', '4102444800000', '-1', '999', '-999'];
  const comps = 'YMDdFWwXxHhmsfPZzCE'.split('');
  const mods = ['', '1', '01', '001', '0001', 'i', 'I', 'a', 'A', 'w', 'W', 'Ww', 'n', 'N',
    'Nn', '1;o', ',2', ',1-2', ',2-2', '0000', '01:01', 't'];
  for (const c of comps) for (const m of mods) {
    const pic = `[${c}${m}]`;
    for (const t of ms.slice(0, 6)) add(`$fromMillis(${t}, ${JSON.stringify(pic)})`);
  }
  for (const t of ms) {
    add(`$fromMillis(${t})`);
    add(`$toMillis($fromMillis(${t}))`);
    add(`$fromMillis(${t}, "[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t]")`);
    add(`$fromMillis(${t}, "[FNn], [D1o] [MNn] [Y]")`);
    add(`$fromMillis(${t}, "[W01]/[X0001]")`);
    add(`$fromMillis(${t}, "[w] [x01]")`);
  }
}

function regex() {
  const subjects = ['abc', '', 'aaa', 'Hello World', 'a\nb', 'a\rb', 'a\r\nb', 'tail\n',
    'line1\nline2\rline3', '  padded  ', 'a.b.c', 'ABC', ' nbsp ', 'héllo',
    'x'.repeat(30), '123-456', 'aXbXc'];
  const pats = ['/b/', '/x*/', '/^/', '/$/', '/a*/', '/(a)(b)?/', '/./', '/\\s/', '/\\S/',
    '/[a-z]+/', '/^a/m', '/c$/m', '/^/m', '/$/m', '/A/i', '/(?:)/', '/\\d+/', '/X/',
    '/(\\d)-(\\d)/', '/l+/', '/[^a]/', '/\\b/', '/a|b/'];
  for (const s of subjects) for (const p of pats) {
    const q = JSON.stringify(s);
    add(`$match(${q}, ${p})`);
    add(`$split(${q}, ${p})`);
    add(`$replace(${q}, ${p}, "-")`);
    add(`$contains(${q}, ${p})`);
  }
  for (const lim of ['0', '1', '2', '3', '-1', '1.5', '2.7']) {
    for (const p of ['/a/', '/x*/', '/./']) {
      add(`$match("aaa", ${p}, ${lim})`);
      add(`$split("aaa", ${p}, ${lim})`);
      add(`$replace("aaa", ${p}, "-", ${lim})`);
    }
  }
  for (const r of ['"[$1]"', '"$0"', '"$$"', '"$9"', '"a$1b$2c"', '"$01"']) {
    add(`$replace("abc123", /([a-z])(\\d)?/, ${r})`);
  }
}

function strings() {
  const subjects = ['abc', '', 'Hello, World!', '  pad  ', 'éüñ',
    'a\tb\nc\rd', 'x'.repeat(40), '0123456789', 'MiXeD cAsE', ' nbsp '];
  for (const s of subjects) {
    const q = JSON.stringify(s);
    for (const f of ['$length', '$uppercase', '$lowercase', '$trim', '$string',
      '$base64encode', '$encodeUrl', '$encodeUrlComponent']) add(`${f}(${q})`);
    add(`$base64decode($base64encode(${q}))`);
    add(`$decodeUrlComponent($encodeUrlComponent(${q}))`);
    for (const a of ['0', '1', '-2', '100']) {
      add(`$substring(${q}, ${a})`);
      add(`$substring(${q}, ${a}, 3)`);
    }
    for (const w of ['0', '5', '20', '-5', '-20']) add(`$pad(${q}, ${w}, "*")`);
    add(`$split(${q}, "")`);
  }
}

({ numbers, formatNumber, integers, datetime, regex, strings })[area]();
fs.writeFileSync(process.argv[3], JSON.stringify(out));
console.log(`${area}: ${out.length} cases`);
