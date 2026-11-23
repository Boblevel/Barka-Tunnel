const assert = require('node:assert/strict');
const vm = require('node:vm');
const path = require('node:path');
const fs = require('node:fs');
const src = fs.readFileSync(path.join(__dirname,'../app/admin_panel.py'),'utf8');
const a = src.indexOf('const guideFields=');
const b = src.indexOf('\n}',src.indexOf('finally{videoSaving=false;',a))+2;
const controls = new Map();
const field = id => {
  if(!controls.has(id)) controls.set(id,{addEventListener(){},focus(){}});
  return controls.get(id);
};
const checked = {};
const $ = field;
let data = {guide:'https://www.youtube.com/watch?v=abcdefghijk',ip_finder:'',guide_source:'mp4',ip_finder_source:'mp4'};
let saves = 0;
const api = async (_,opts) => {
  if(opts){saves++;data=JSON.parse(opts.body);}
  return {...data};
};
const document = { querySelector(selector){
  const name=selector.match(/name="(.*?)"/)[1],value=selector.match(/value="(.*?)"/)?.[1];
  if(!value)return {value:checked[name]};
  assert.ok(src.includes('name="'+name+'" value="'+value+'"'));
  return {set checked(v){if(v)checked[name]=value;}};
}};
const ctx = vm.createContext({$,document,api});
vm.runInContext(src.slice(a,b),ctx);
(async()=>{
  await ctx.loadVideoGuides();
  assert.deepEqual(checked,{guide_source:'mp4',ip_finder_source:'mp4'});
  checked.ip_finder_source='youtube';
  await ctx.saveVideoGuides();
  assert.equal(saves,0);
  $('videoIp').value=data.guide;
  for(const [guide,ip] of [['mp4','youtube'],['youtube','mp4']]){
    checked.guide_source=guide;checked.ip_finder_source=ip;
    await ctx.saveVideoGuides();
    assert.equal(data.guide_source,guide);assert.equal(data.ip_finder_source,ip);
    await ctx.loadVideoGuides();
    assert.equal(checked.guide_source,guide);assert.equal(checked.ip_finder_source,ip);
  }
  assert.equal(saves,2);
  console.log('Choix et relecture des deux sources vérifiés.');
})().catch(e=>{console.error(e);process.exitCode=1;});
