const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../app/admin_panel.py'), 'utf8');
const match = source.match(/function renderServices\(data\)\{([\s\S]*?)vpnDirty.clear\(\)/);
assert.ok(match, 'Profile presentation function exists');
const ids = ['global_world','orange_bf_v2','telecel_bf','orange_bf','moov_bf_v2','moov_bf','moov_tiktok'];
const actual = vm.runInNewContext(
  'function ordered(data){'+match[1]+'return list.map(p=>p.network_id);} JSON.stringify(ordered(input));',
  {input:{profiles:ids.map(network_id=>({network_id}))}}
);
assert.deepEqual(JSON.parse(actual),['global_world','telecel_bf','orange_bf','orange_bf_v2','moov_bf','moov_bf_v2','moov_tiktok']);
console.log('Panel lot22: variants adjacent, original order preserved, no profiles lost.');
