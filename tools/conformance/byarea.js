'use strict';
const fs=require('fs');
const rd=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const cases=rd(process.argv[2]),java=rd(process.argv[3]),ref=rd(process.argv[4]);
const js=process.argv[5]?rd(process.argv[5]):null;
const S=v=>JSON.stringify(v);
const byId=new Map(cases.map(c=>[c.id,c]));
const tot={},bad={},jsWins={};
for(const id of Object.keys(ref)){
  const a=id.split('#')[0];
  tot[a]=(tot[a]||0)+1;
  if(S(java[id])!==S(ref[id])){
    bad[a]=(bad[a]||0)+1;
    if(js&&S(js[id])===S(ref[id])) jsWins[a]=(jsWins[a]||0)+1;
  }
}
const rows=Object.keys(tot).sort((x,y)=>(bad[y]||0)-(bad[x]||0));
console.log('area           cases  diverge  js-right');
for(const a of rows) console.log(a.padEnd(15)+String(tot[a]).padStart(5)+String(bad[a]||0).padStart(9)+String(jsWins[a]||0).padStart(10));
console.log('TOTAL'.padEnd(15)+String(Object.keys(ref).length).padStart(5)+String(Object.values(bad).reduce((s,v)=>s+v,0)).padStart(9)+String(Object.values(jsWins).reduce((s,v)=>s+v,0)).padStart(10));
