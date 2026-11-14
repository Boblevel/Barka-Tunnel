const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/admin_panel.py'),'utf8');
class E {constructor(){this.children=[];this.dataset={};this.events={}} appendChild(e){this.children.push(e)} replaceChildren(){this.children=[]} addEventListener(n,f){this.events[n]=f} setCustomValidity(){} }
const box=new E(),json=new E(),protocol={value:'VLESS'};
const bank={fp_events:{host:'first.example',verify_cert_name:'first.example',mux:true},am2_d3:{host:'second.example',verify_cert_name:'second.example',mux:false}};
json.value=JSON.stringify({...bank.fp_events,global_profiles:bank,active_global_profile:'fp_events'});
const el={dataset:{id:'global_world'},querySelector:s=>({'.protocol-fields':box,'.config':json,'.protocol':protocol}[s])};
const ctx=vm.createContext({URL,document:{createElement:()=>new E()},markServiceDirty:()=>{}});
vm.runInContext(source.slice(source.indexOf('function vlessUri('),source.indexOf('function newProtocolConfig(')),ctx);
ctx.refreshProtocolFields(el);
let host=box.children.flatMap(e=>e.children).find(e=>e.dataset.configKey==='host');host.value='edited.example';host.events.input();
assert.equal(JSON.parse(json.value).verify_cert_name,'edited.example');
let select=box.children[0].children[0];select.value='am2_d3';select.events.change();
assert.equal(JSON.parse(json.value).host,'second.example');assert.equal(JSON.parse(json.value).mux,false);
select=box.children[0].children[0];select.value='fp_events';select.events.change();
assert.equal(JSON.parse(json.value).host,'edited.example');assert.equal(JSON.parse(json.value).mux,true);
console.log('Global profile switching and independent edits passed.');
