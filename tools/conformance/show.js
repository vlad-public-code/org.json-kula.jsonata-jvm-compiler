'use strict';
const fs=require('fs');
const rd=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const [,,cf,jf,rf,area,limStr]=process.argv;
const cases=rd(cf),java=rd(jf),ref=rd(rf);
const lim=Number(limStr||25);
const byId=new Map(cases.map(c=>[c.id,c]));
const S=v=>JSON.stringify(v);
let shown=0,total=0;
for(const id of Object.keys(ref)){
  if(!id.startsWith(area+'#')) continue;
  if(S(java[id])===S(ref[id])) continue;
  total++;
  if(shown++>=lim) continue;
  console.log(`${byId.get(id).expr}\n   java ${S(java[id])}\n   ref  ${S(ref[id])}`);
}
console.log(`\n(${total} divergences in ${area})`);
