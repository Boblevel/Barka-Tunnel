const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/reseller_panel.py'), 'utf8');
const code = source.slice(source.indexOf('let generationBusy='), source.indexOf('async function deleteCode('));
const elements = new Map();
const storage = new Map();
const errors = [];
let calls = [], fail = false;
const quota = {limit:30,used:29,remaining:1,starts_at:100,ends_at:200};
const context = vm.createContext({
  TOKEN:'test-session', crypto:require('node:crypto').webcrypto,
  $: id => {
    if (!elements.has(id)) elements.set(id,{style:{},disabled:false,value:id==='plan'?'1m':'2',
      classList:{toggle(name,value){this[name]=value}}});
    return elements.get(id);
  },
  sessionStorage:{getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
  formatDate:String, alert:message=>errors.push(message), setInterval:()=>{},
  document:{hidden:false,addEventListener:()=>{}}, logout:async()=>{},
  showResult:()=>{}, loadCodesAndStats:()=>new Promise(()=>{}),
  api:async(url,options)=>{
    if(url.endsWith('/quota'))return quota;
    calls.push(JSON.parse(options.body));
    if(fail)throw new Error('Network interrupted after server confirmation');
    return {code:'BARKA-TEST-CODE',quota};
  }
});
vm.runInContext(code,context);
(async()=>{
  context.renderQuota(quota);
  assert.equal(elements.get('quotaNumber').textContent,1);
  assert.equal(elements.get('quotaBox').classList.empty,false);
  await Promise.all([context.generateSubscription(),context.generateSubscription()]);
  assert.equal(calls.length,1,'Double click must issue a single request');
  assert.equal(elements.get('generateSubscriptionButton').disabled,false,'History loading must not lock generation');
  context.renderQuota({...quota,used:30,remaining:0});
  assert.equal(elements.get('quotaBox').classList.empty,true);
  assert.equal(elements.get('generateSubscriptionButton').disabled,true);
  context.renderQuota({...quota,used:20,remaining:10});
  assert.equal(elements.get('quotaNumber').textContent,0,'Older response must not refund quota');
  await context.generateTest();
  assert.equal(calls.at(-1).duration_hours,2);
  assert.equal(elements.get('generateTestButton').disabled,false);
  fail=true;
  await context.generateTest();
  const request=calls.at(-1).request_id;
  fail=false;
  await context.generateTest();
  assert.equal(calls.at(-1).request_id,request,'Retry must reuse the same request');
  assert.equal(storage.size,0);
  assert.equal(errors.length,1);
  console.log('Panel: quota colors, stale responses, double click, tests and retry passed.');
})().catch(error=>{console.error(error);process.exitCode=1});
