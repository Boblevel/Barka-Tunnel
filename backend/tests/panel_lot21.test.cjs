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
  assert.doesNotMatch(source, /<option value="24h">/);
  context.renderQuota({...quota,used:0,remaining:30});
  assert.equal(elements.get('quotaBox').classList.active,false);
  assert.equal(elements.get('quotaBox').classList.empty,false);
  context.renderQuota(quota);
  assert.equal(elements.get('quotaBox').classList.active,true);
  assert.equal(elements.get('quotaNumber').textContent,1);
  assert.equal(elements.get('quotaBox').classList.empty,false);
  await Promise.all([context.generateSubscription(),context.generateSubscription()]);
  assert.equal(calls.length,1,'Double click must issue a single request');
  assert.equal(elements.get('generateSubscriptionButton').disabled,false,'History loading must not lock generation');
  context.renderQuota({...quota,used:30,remaining:0});
  assert.equal(elements.get('quotaBox').classList.empty,true);
  assert.equal(elements.get('quotaBox').classList.active,false);
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
  context.renderQuota({...quota,used:0,remaining:45,limit:45,revision:2});
  assert.equal(elements.get('quotaNumber').textContent,45,'Admin reset must update an open panel');
  assert.equal(elements.get('quotaBox').classList.active,false);
  assert.equal(elements.get('quotaBox').classList.empty,false);
  assert.equal(elements.get('generateSubscriptionButton').disabled,false);
  context.renderQuota({...quota,used:30,remaining:0,revision:1});
  assert.equal(elements.get('quotaNumber').textContent,45,'Pre-reset response must not undo reset');
  // Older mobile browsers expose secure random bytes but not randomUUID.
  context.crypto={getRandomValues:require('node:crypto').webcrypto.getRandomValues.bind(require('node:crypto').webcrypto)};
  await context.generateSubscription();
  assert.match(calls.at(-1).request_id,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(elements.get('generateSubscriptionButton').disabled,false);
  // A lost response must be recovered even after selecting another duration.
  fail=true;
  await context.generateSubscription();
  const interrupted=calls.at(-1);
  elements.get('plan').value='2w';
  fail=false;
  await context.generateSubscription();
  assert.equal(calls.at(-1).request_id,interrupted.request_id);
  assert.equal(calls.at(-1).plan_id,interrupted.plan_id);
  assert.equal(elements.get('plan').value,interrupted.plan_id);
  assert.equal(storage.size,0);
  elements.get('plan').value='2w';
  await context.generateSubscription();
  assert.notEqual(calls.at(-1).request_id,interrupted.request_id);
  assert.equal(calls.at(-1).plan_id,'2w');
  context.renderQuota({...quota,used:30,remaining:0,limit:30,revision:3});
  context.renderQuota({...quota,used:29,remaining:1,limit:30,revision:4});
  assert.equal(elements.get('quotaNumber').textContent,1);
  console.log('Panel: quota colors, stale responses, double click, tests and retry passed.');
})().catch(error=>{console.error(error);process.exitCode=1});
