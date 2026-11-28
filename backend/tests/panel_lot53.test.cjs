const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const src=fs.readFileSync(require('node:path').join(__dirname,'../app/admin_panel.py'),'utf8');
new vm.Script(src.slice(src.indexOf('<script>')+8,src.indexOf('</script>')));
const fields={};const $=id=>fields[id]??={checked:false,disabled:false,textContent:''};
let calls=[],fail=false;
const ctx=vm.createContext({$,esc:String,creationDate:String,statusLabel:String,formatDurationSeconds:String,redeemActionButtons:()=>'',pricingBusy:false,pricingRevision:4,
 savedPricing:{base_prices:{'24h':350},promotions:{},subscription_payments_enabled:true,reseller_payments_enabled:true},
 api:async(url,opts)=>{calls.push(JSON.parse(opts.body));if(fail)throw Error('Conflit : rechargez');return {...ctx.savedPricing,...calls.at(-1),revision:5}}});
vm.runInContext(src.split('\n').find(l=>l.startsWith('function renderRedeemRows(')),ctx);
vm.runInContext(src.slice(src.indexOf('async function savePaymentControls('),src.indexOf('async function loadPricing(')),ctx);
const c={code:'test',status:'active',duration_seconds:86400,usage_count:1,max_users:1,unfinished_usage_count:1,activation_dates:['2026-11-27T23:02:25Z'],created_at:'2026-11-27T22:52:25Z'};
const full=ctx.renderRedeemRows([c],'');assert.match(full,/COMPLET — TEMPS EN COURS/);assert.doesNotMatch(full,/code-row expired/);assert.match(full,/Utilisateur 1 : 2026-11-27T23:02:25Z/);assert.doesNotMatch(full,/expiration attribuée/);
assert.match(ctx.renderRedeemRows([{...c,status:'expired',unfinished_usage_count:0,all_users_finished:true}],''),/code-row expired/);
(async()=>{
 $('resellerPayments').checked=true;await ctx.savePaymentControls();
 assert.deepEqual(calls[0].prices,{'24h':350});assert.equal(calls[0].subscription_payments_enabled,false);assert.equal(calls[0].reseller_payments_enabled,true);assert.match($('paymentControlsStatus').textContent,/BLOQUÉS/);
 $('subscriptionPayments').checked=true;await ctx.savePaymentControls();assert.match($('paymentControlsStatus').textContent,/AUTORISÉS/);
 fail=true;await ctx.savePaymentControls();assert.match($('paymentControlsStatus').textContent,/Conflit/);assert.equal($('paymentControlsSave').disabled,false);
 ctx.pricingBusy=true;const count=calls.length;await ctx.savePaymentControls();assert.equal(count,calls.length);
 console.log('LOT53 : Redeem et commande des achats vérifiés.');
})().catch(e=>{console.error(e);process.exitCode=1});
// Credentials remain plain text, individually copyable, with an explicit group link.
const node=()=>({children:[],style:{},append(...items){this.children.push(...items)},appendChild(item){this.children.push(item)},replaceChildren(...items){this.children=items},scrollIntoView(){}});
const target=node();let copiedCredential;
const credentials=vm.createContext({$:()=>target,document:{createElement:node},copyText:v=>{copiedCredential=v},credentialsRequest:0});
vm.runInContext(src.split('\n').find(l=>l.startsWith('function showResellerCredentials(')),credentials);
const data={id:1,panel_url:'https://example.org/reseller',username:'<test-user>',password:'aB3_x7Z9',expires_at:'2026-12-28T08:00:00Z'};
credentials.showResellerCredentials(data);
const card=target.children[0],grid=card.children[2];
assert.equal(grid.children[1].children[0].children[1].textContent,data.username);
for(const [i,key] of ['panel_url','username','password'].entries()){grid.children[i].children[1].onclick();assert.equal(copiedCredential,data[key]);}
const group=card.children[3];assert.equal(group.href,'https://chat.whatsapp.com/CmsqtcjJKF08hdslcUgoc5?s=cl&p=a&iam=2');assert.equal(group.rel,'noopener noreferrer');
card.children[4].onclick();assert.ok(copiedCredential.includes(':\n'+data.password+'\n\n'));assert.ok(copiedCredential.includes(group.href));
