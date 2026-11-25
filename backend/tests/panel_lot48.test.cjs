const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/admin_panel.py'),'utf8');
new vm.Script(source.slice(source.indexOf('<script>')+8,source.indexOf('</script>')));
const fields=new Map();
const $=id=>{if(!fields.has(id))fields.set(id,{dataset:{},value:'',textContent:'',innerHTML:'',classList:{toggle(){},add(){}},querySelectorAll(){return []}});return fields.get(id)};
let sent=[],confirmation=true,failDelete=false,toasts=[];
const ctx=vm.createContext({$,esc:String,Date,Set,Map,Number,Array,alert:()=>{},confirm:()=>confirmation,prompt:()=> '3',toast:message=>toasts.push(message),
 closeResellerDetails(){},credentialsRequest:0,loadCodes:async()=>{},loadStats:async()=>{},creationDate:String,fullDate:String,
 api:async(url,options)=>{sent.push({url,body:JSON.parse(options.body)});if(url.endsWith('filter-preview'))return {count:2,fingerprint:'f'.repeat(64)};if(failDelete)throw Error('La liste a changé');return {message:'ok',quota:{remaining:30,limit:30}};}});
vm.runInContext('let latestResellers=[{id:1,username:"Alpha",status:"expired"},{id:2,username:"Beta",status:"active"}];'+source.slice(source.indexOf('function resellerMatches('),source.indexOf('function resellerStatus('))+source.split('\n').find(l=>l.startsWith('function resellerStatus('))+ '\nasync function loadResellers(){}',ctx);
(async()=>{
 assert(ctx.resellerMatches({id:1,username:'Alpha',status:'expired'},'alpha','expired'));
 assert(ctx.resellerMatches({id:1,username:'Alpha',status:'expired'},'expiré','all'));
 assert(!ctx.resellerMatches({id:1,username:'Alpha',status:'active'},'alpha','expired'));
 $('resellerStatus').value='expired';$('resellerSearch').value='Alpha';
 confirmation=false;await ctx.deleteFilteredResellers();assert.equal(sent.length,1);
 sent=[];confirmation=true;await ctx.deleteFilteredResellers();assert.equal(sent.length,2);
 assert.equal(sent[1].body.fingerprint,'f'.repeat(64));assert.equal(sent[1].body.status,'expired');assert.equal(sent[1].body.query,'Alpha');
 sent=[];$('resellerStatus').value='all';$('resellerSearch').value='';await ctx.deleteFilteredResellers();assert.equal(sent.length,0);
 await ctx.extendReseller(2);assert.equal(sent[0].body.months,3);assert.equal(sent[0].body.confirmation,'oui');assert.match(toasts[0],/30 \/ 30/);
 // Full Redeem: red once the final grant has ended; queued grants stay visible.
 const line=source.split('\n').find(l=>l.startsWith('function renderRedeemRows('));
 Object.assign(ctx,{statusLabel:String,formatDurationSeconds:String,redeemActionButtons:()=>''});vm.runInContext(line,ctx);
 const code={code:'TEST',status:'expired',duration_seconds:86400,usage_count:25,max_users:25,unfinished_usage_count:0,waiting_usage_count:0,all_users_finished:true};
 assert.match(ctx.renderRedeemRows([code],''),/code-row expired/);
 const queued=ctx.renderRedeemRows([{...code,status:'active',all_users_finished:false,unfinished_usage_count:1,waiting_usage_count:1}],'');
 assert.match(queued,/24 terminé\(s\)/);assert.match(queued,/1 en attente/);assert.doesNotMatch(queued,/code-row expired/);
 console.log('LOT48 : filtres, confirmation, prolongation et compteurs Redeem vérifiés.');
})().catch(e=>{console.error(e);process.exitCode=1});
