from __future__ import annotations


RESELLER_PANEL_HTML = r"""<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#0d63e7">
  <title>Barka Tunnel — Revendeur</title>
  <style>
    :root{--blue:#0d63e7;--blue2:#0957ce;--ink:#101828;--muted:#667085;--line:#dce4f0;--bg:#f5f8fd;--card:#fff;--bad:#dc2626;--ok:#16a34a}
    *{box-sizing:border-box}body{margin:0;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif;background:var(--bg);color:var(--ink)}button,input,select{font:inherit}.hidden{display:none!important}
    .top{background:linear-gradient(135deg,var(--blue),#3386f3);color:#fff;padding:18px 18px 28px}.brand{max-width:980px;margin:auto;display:flex;align-items:center;gap:12px}.logo{width:54px;height:54px;border-radius:16px;background:#fff;padding:5px;box-shadow:0 8px 24px #002a6b33}.logo svg{width:100%;height:100%}.brand strong{display:block;font-size:20px}.brand span{opacity:.86;font-size:13px}
    main{max-width:980px;margin:-14px auto 32px;padding:0 14px}.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:18px;box-shadow:0 10px 34px #173b6d12;margin-bottom:14px}.login{max-width:430px;margin:60px auto}.title{font-size:18px;font-weight:850;margin-bottom:5px}.small{font-size:12px;color:var(--muted)}label{display:block;font-size:13px;font-weight:750;margin:13px 0 6px}input,select{width:100%;border:1px solid var(--line);border-radius:12px;padding:12px;background:#fff;color:var(--ink);outline:none}input:focus,select:focus{border-color:var(--blue);box-shadow:0 0 0 3px #0d63e719}
    button{border:0;border-radius:12px;padding:12px 15px;background:var(--blue);color:#fff;font-weight:850;cursor:pointer}button:hover{background:var(--blue2)}button.ghost{background:#edf4ff;color:var(--blue)}button.danger{background:#fff1f2;color:#be123c;border:1px solid #fecdd3}.row{display:flex;align-items:center;justify-content:space-between;gap:12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.status{padding:12px;border-radius:12px;background:#f3f6fa;color:var(--muted);margin-top:12px}.bad{color:var(--bad)}.ok{color:var(--ok)}.result{margin-top:12px;padding:14px;border:1px solid #86efac;background:#f0fdf4;border-radius:14px}.code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-weight:850;overflow-wrap:anywhere}.code-row{border-top:1px solid var(--line);padding:13px 0}.code-row:first-child{border-top:0}.code-row.expired{border-left:4px solid var(--bad);padding-left:10px;background:#fff7f7}.expired-badge{display:inline-block;color:var(--bad);background:#fee2e2;border-radius:99px;padding:3px 7px;font-size:10px;font-weight:900}
    @media(max-width:680px){.grid{grid-template-columns:1fr}.row.mobile{align-items:flex-start;flex-direction:column}.top{padding-top:max(18px,env(safe-area-inset-top))}main{padding-bottom:env(safe-area-inset-bottom)}}
  </style>
</head>
<body>
  <header class="top"><div class="brand"><div class="logo"><svg viewBox="0 0 72 72" role="img" aria-label="Logo Barka Tunnel"><path d="M62 9C47 0 24 2 11 16C-1 29 1 50 15 62C29 74 51 70 63 56" fill="none" stroke="#126ee8" stroke-width="5" stroke-linecap="round"/><path d="M22 16V58M22 16H40C51 16 57 22 57 30C57 36 52 40 47 41C54 42 59 47 59 54C59 63 51 67 40 67H22" fill="none" stroke="#111827" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/></svg></div><div><strong>Barka Tunnel</strong><span>Sous-panel revendeur</span></div></div></header>
  <main>
    <section id="loginCard" class="card login"><div class="title">Connexion revendeur</div><div class="small">Utilisez les coordonnées remises par l'administrateur.</div><label>Nom d'utilisateur</label><input id="username" autocomplete="username" maxlength="40"><label>Mot de passe</label><input id="password" type="password" autocomplete="current-password" maxlength="200"><div style="height:14px"></div><button style="width:100%" onclick="login()">SE CONNECTER</button><div id="loginStatus"></div></section>
    <div id="app" class="hidden">
      <section class="card"><div class="row mobile"><div><div class="title">Bienvenue, <span id="accountName">—</span></div><div class="small">Expiration du sous-panel : <span id="accountExpiry">—</span></div></div><button class="ghost" onclick="logout()">SE DÉCONNECTER</button></div></section>
      <div class="grid">
        <section class="card"><div class="title">Code d'abonnement</div><div class="small">Un code individuel pour un seul utilisateur.</div><label>Durée</label><select id="plan"><option value="24h">24 HEURES</option><option value="1w">1 SEMAINE</option><option value="2w">2 SEMAINES</option><option value="1m">1 MOIS</option></select><div style="height:12px"></div><button onclick="generateSubscription()">GÉNÉRER LE CODE</button></section>
        <section class="card"><div class="title">Test de 2 heures</div><div class="small">Un test individuel limité exactement à 2 heures.</div><div style="height:38px"></div><button onclick="generateTest()">GÉNÉRER LE TEST 2H</button></section>
      </div>
      <section id="result" class="card hidden"></section>
      <section class="card"><div class="row"><div><div class="title">Mes codes générés</div><div class="small">Les codes arrivés à zéro apparaissent en rouge avec le statut EXPIRÉ.</div></div><button class="ghost" onclick="loadCodes()">ACTUALISER</button></div><div id="codes" class="status">Chargement…</div></section>
    </div>
  </main>
<script>
const $=id=>document.getElementById(id);let TOKEN=sessionStorage.getItem('barka_reseller_token')||'';
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date=v=>v?new Date(v).toLocaleString('fr-FR'):'—';
async function api(path,opt={}){const headers={...(opt.headers||{})};if(TOKEN)headers.Authorization='Bearer '+TOKEN;if(opt.body)headers['Content-Type']='application/json';const r=await fetch(path,{...opt,headers});let d={};try{d=await r.json()}catch{}if(!r.ok)throw new Error(d.detail||('Erreur HTTP '+r.status));return d}
function showLoggedIn(on){$('loginCard').classList.toggle('hidden',on);$('app').classList.toggle('hidden',!on)}
async function login(){const username=$('username').value.trim(),password=$('password').value;$('loginStatus').innerHTML='<div class="status">Connexion…</div>';try{const d=await api('/v1/reseller/login',{method:'POST',body:JSON.stringify({username,password})});TOKEN=d.token;sessionStorage.setItem('barka_reseller_token',TOKEN);$('password').value='';showLoggedIn(true);await refresh()}catch(e){$('loginStatus').innerHTML='<div class="status bad">'+esc(e.message)+'</div>'}}
async function logout(){try{if(TOKEN)await api('/v1/reseller/logout',{method:'POST'})}catch{}TOKEN='';sessionStorage.removeItem('barka_reseller_token');showLoggedIn(false)}
async function refresh(){const account=await api('/v1/reseller/account');$('accountName').textContent=account.username;$('accountExpiry').textContent=date(account.expires_at);await loadCodes()}
function showResult(label,code){$('result').classList.remove('hidden');$('result').innerHTML='<div class="title">'+esc(label)+'</div><div class="result"><div class="code">'+esc(code)+'</div><div style="height:8px"></div><button class="ghost" onclick="copyCode()">COPIER</button></div>';$('result').dataset.code=code}
async function copyCode(){const code=$('result').dataset.code||'';try{await navigator.clipboard.writeText(code)}catch{const t=document.createElement('textarea');t.value=code;document.body.appendChild(t);t.select();document.execCommand('copy');t.remove()}}
async function generateSubscription(){try{const d=await api('/v1/reseller/codes/subscription',{method:'POST',body:JSON.stringify({plan_id:$('plan').value})});showResult('Code d’abonnement créé',d.code);await loadCodes()}catch(e){alert(e.message)}}
async function generateTest(){try{const d=await api('/v1/reseller/codes/test',{method:'POST'});showResult('Test de 2 heures créé',d.code);await loadCodes()}catch(e){alert(e.message)}}
function status(v){return v==='issued'?'Disponible':v==='redeemed'?'Actif / utilisé':v==='expired'?'EXPIRÉ':v==='revoked'?'Désactivé':v}
async function loadCodes(){const list=await api('/v1/reseller/codes?limit=200');$('codes').className='';$('codes').innerHTML=list.length?list.map(c=>'<div class="code-row '+(c.status==='expired'?'expired':'')+'"><div class="row mobile"><div><div class="code">'+esc(c.code)+'</div><div class="small">'+esc(c.code_type==='test'?'TEST 2H':c.plan_id)+' • '+(c.status==='expired'?'<span class="expired-badge">EXPIRÉ</span>':esc(status(c.status)))+'</div><div class="small">Créé : '+date(c.created_at)+' • Expire : '+date(c.expires_at)+'</div></div><button class="danger" onclick="deleteCode(\''+c.code+'\')">SUPPRIMER</button></div></div>').join(''):'<div class="status">Aucun code généré.</div>'}
async function deleteCode(code){if(!confirm('Supprimer ce code ? S’il est déjà utilisé, son temps restant sera retiré.'))return;try{const d=await api('/v1/reseller/codes/delete',{method:'POST',body:JSON.stringify({code})});if(!d.success)throw new Error(d.message);if(($('result').dataset.code||'')===code){$('result').classList.add('hidden');$('result').dataset.code=''}await loadCodes()}catch(e){alert(e.message)}}
async function restore(){if(!TOKEN)return;showLoggedIn(true);try{await refresh()}catch{TOKEN='';sessionStorage.removeItem('barka_reseller_token');showLoggedIn(false)}}
setInterval(()=>{if(TOKEN)loadCodes().catch(()=>{})},60000);restore();
</script>
</body>
</html>"""
