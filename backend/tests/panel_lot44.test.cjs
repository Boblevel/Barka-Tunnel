const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/admin_panel.py'), 'utf8');
const code = source.slice(source.indexOf('function promotionTimestamp'), source.indexOf("$('resellerExpiry').value=localDateTime().slice"));
const fields = {};
function field(id) {return fields[id] ??= {value:'', labels:[{textContent:id}],focus(){this.focused=true;}};}
function offer(key, base, amount, start, end) {
  field('price-'+key).value = base;
  const inputs = {'.promo-enabled':{checked:true},'.promo-amount':{value:amount},'.promo-start':{value:start},'.promo-end':{value:end,focus(){this.focused=true;}}};
  field('promo-'+key).querySelector = selector=>inputs[selector];
}
offer('reseller_1m','6000','5000','2026-11-23T16:11','');
offer('reseller_2m','13000','12000','2026-11-23T16:11','2026-11-23T16:14');
let sent;
const ctx = vm.createContext({$, pricingBusy:false, pricingRevision:2, pricingKeys:['reseller_1m','reseller_2m'],
  savedPricing:{base_prices:{reseller_1m:5000,reseller_2m:10000},promotions:{},subscription_payments_enabled:true,reseller_payments_enabled:false},
  document:{querySelectorAll:()=>[]},api:async (_,request)=>{sent=JSON.parse(request.body);return {...ctx.savedPricing,...sent,base_prices:sent.prices,revision:3};}});
function $(id){return field(id);}
vm.runInContext(code,ctx);
(async ()=>{
  await ctx.savePricing('reseller_2m');
  assert.equal(sent.prices.reseller_2m,13000);
  assert.equal(sent.promotions.reseller_2m.amount,12000);
  assert.equal(sent.prices.reseller_1m,5000);
  assert.equal(sent.reseller_payments_enabled,false);
  assert.equal(sent.promotions.reseller_2m.ends_at-sent.promotions.reseller_2m.starts_at,180);
  sent=null;
  await ctx.savePricing();
  assert.equal(sent,null);
  assert.match(field('pricingStatus').textContent,/reseller_1m.*fin/i);
  console.log('Promotion isolée et validation de la bonne offre vérifiées.');
})().catch(e=>{console.error(e);process.exitCode=1;});
