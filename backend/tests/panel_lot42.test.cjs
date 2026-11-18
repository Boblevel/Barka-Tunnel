const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/admin_panel.py'), 'utf8');
const elements = {};
const element = id => elements[id] ||= {checked:false, disabled:false, value:'unchanged', textContent:''};
const calls = [];
let failure = false;
const context = vm.createContext({
  $:element, loadShareUrl:()=>{}, APKPURE_URL:'https://example.org/app.apk',
  api:async (url, options) => {
    calls.push({url, options});
    if (failure) throw new Error('network');
    return options ? JSON.parse(options.body) : {orange_ip_indicator_enabled:false, enabled:false,
      mandatory:false, download_in_app:true, latest_version_code:145, latest_version_name:'2.4.1.1',
      apk_url:'https://example.org/app.apk', message:'Update', updated_at:'date'};
  }
});
vm.runInContext(source.slice(source.indexOf('async function saveOrangeIpIndicator()'),
    source.indexOf('async function saveUpdate()')), context);
(async () => {
  await context.loadUpdate();
  assert.equal(element('orangeIpIndicator').checked, false);
  assert.equal(element('orangeIpSave').disabled, false);
  const publication = ['updEnabled','updMandatory','updCode','updName','updUrl','updMessage']
    .map(id => JSON.stringify(element(id)));
  calls.length = 0;
  for (const enabled of [true, false]) {
    element('orangeIpIndicator').checked = enabled;
    await context.saveOrangeIpIndicator();
    assert.equal(calls.at(-1).url, '/v1/admin/app-update/orange-ip-indicator');
    assert.deepEqual(JSON.parse(calls.at(-1).options.body), {enabled});
    assert.equal(element('orangeIpSave').disabled, false);
    assert.equal(element('orangeIpIndicator').disabled, false);
  }
  assert.deepEqual(['updEnabled','updMandatory','updCode','updName','updUrl','updMessage']
    .map(id => JSON.stringify(element(id))), publication);
  failure = true;
  await context.saveOrangeIpIndicator();
  assert.match(element('orangeIpStatus').textContent, /Non enregistré/);
  assert.equal(element('orangeIpSave').disabled, false);
  console.log('LOT42 : chargement, activation/désactivation indépendante et erreur réseau validés.');
})().catch(error => { console.error(error); process.exitCode = 1; });
