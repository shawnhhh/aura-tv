'use strict';
let pairing=location.hash.slice(1), session='', polling=false;
history.replaceState(null,'',location.pathname);
const status=document.querySelector('#status');
async function call(path,value={}){
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Herrin-Session':session},body:JSON.stringify(value),cache:'no-store',credentials:'omit'});
 if(!r.ok){
   if(r.status===403)throw Error('会话已失效，请从电视重新开启');
   const detail=await r.json().catch(()=>({}));
   const messages={
     SOURCE_SAVE_FAILED:'影视源候选配置保存失败，原配置保留',
     SOURCE_NOTIFY_FAILED:'已保存候选配置，但未能通知电视；请重新打开电视配置页',
     SOURCE_URL_INVALID:'影视源地址格式不受支持；请确认输入的是完整的 HTTP(S) TVBox 配置地址',
     SOURCE_SAVE_OR_APPLY_FAILED:'电视端保存或应用影视源失败；请在电视设置中核对是否已保存',
     CONFIG_INVALID:'配置无效或保存失败'
   };
   throw Error(messages[detail.code]||'配置无效或保存失败');
 }
 return r.json();
}
document.querySelector('#pair').onclick=async()=>{
 if(!document.querySelector('#verified').checked){status.textContent='请先核对证书指纹';return;}
 if(polling)return;polling=true;
 try{while(!session){const r=await call('/pair',{pairing});session=r.session;if(!session){status.textContent='请在电视确认此配对';await new Promise(r=>setTimeout(r,1500));}}
 pairing='';document.querySelector('#trust').hidden=true;document.querySelector('#manager').hidden=false;
 const r=await call('/status');document.querySelector('#endpoint').textContent=r.endpoint+' · '+(r.tokenConfigured?'已配置':'未配置');
 document.querySelector('#sources [name=url]').value=r.tvbox;document.querySelector('#live [name=m3u]').value=r.m3u;document.querySelector('#live [name=epg]').value=r.epg;document.querySelector('#nas [name=url]').value=r.nas;document.querySelector('#cloud [name=url]').value=r.cloud||'';document.querySelector('#smb [name=url]').value=r.smb?.url||'';document.querySelector('#smb [name=encryption]').checked=!!r.smb?.encryption;
 status.textContent='已配对，各模块共用会话；最长 30 分钟，退出电视配置页立即结束';
 }catch(e){status.textContent=e.message;}finally{polling=false;}
};
const sourceErrors={
 SOURCE_HTTP_403:'源站 HTTP 403：服务器拒绝访问，原配置保留',
 SOURCE_HTTP_ERROR:'源站 HTTP 请求失败，原配置保留',
 SOURCE_BODY_INVALID:'配置正文无效，原配置保留',
 SOURCE_WAREHOUSE_PARSE_FAILED:'仓库解析失败，原配置保留',
 SOURCE_FETCH_FAILED:'配置读取失败，请在电视查看网络状态',
 SOURCE_SITE_ACTIVATION_FAILED:'影视点激活失败，原配置保留；可在电视重新选择站点'
};
let sourcePoll=0;
async function watchSource(importId){
 const ticket=++sourcePoll;
 while(session && ticket===sourcePoll){
   const r=await call('/sources/status',{importId});
   if(ticket!==sourcePoll)return;
   if(r.state==='ACTIVE'){status.textContent='影视点加载成功，已导入影视源';return;}
   if(r.state==='FAILED'){status.textContent=sourceErrors[r.code]||'影视源导入失败，原配置保留';return;}
   if(r.state==='SUPERSEDED'){status.textContent='已被后续配置替代';return;}
   status.textContent=r.state==='AWAITING_SITE'?'配置已读取，请在电视选择影视点并确认新 Spider 授权':'候选配置已保存，等待电视读取';
   await new Promise(r=>setTimeout(r,1000));
 }
}
let module='tmdb';
async function refreshEntries(){
 document.querySelector('#cloud').hidden=module!=='nas';document.querySelector('#smb').hidden=module!=='nas';
 const additional=document.querySelector('#additional');additional.hidden=module==='tmdb';document.querySelector('#catalog-manager').hidden=module!=='sources';if(additional.hidden)return;
 document.querySelector('#entry-edit').hidden=false;
 document.querySelector('#extra-epg').hidden=module!=='live';document.querySelector('#extra-login').hidden=module!=='nas';document.querySelector('#batch').hidden=module!=='sources';await refreshCatalogs();
 const r=await call('/entries',{module});const list=document.querySelector('#entries');list.replaceChildren();
 r.entries.forEach(entry=>{const row=document.createElement('p');const name=document.createElement('span');name.textContent=entry.name+' · '+(entry.enabled?'启用':'停用');row.append(name);
 const button=(label,action)=>{const b=document.createElement('button');b.type='button';b.textContent=label;b.onclick=async()=>{try{await action();}catch(e){status.textContent=e.message;}};row.append(b);};
 button('编辑',()=>{const f=document.querySelector('#entry-edit');for(const key of ['id','name','url','epg'])f.elements[key].value=entry[key]||'';f.elements.enabled.checked=entry.enabled;f.elements.password.value='';});
 button(entry.enabled?'停用':'启用',async()=>{await call('/entries/save',{...entry,module,enabled:!entry.enabled});await refreshEntries();});
 button('删除',async()=>{await call('/entries/delete',{module,id:entry.id});await refreshEntries();});
 if(module==='nas')button('测试',async()=>{status.textContent=(await call('/nas/test',{id:entry.id})).result;});list.append(row);});
}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=async()=>{module=b.dataset.tab;document.querySelectorAll('form').forEach(f=>f.hidden=f.id!==module);document.querySelector('#entry-edit').reset();try{await refreshEntries();if(module==='tmdb')await refreshTmdbStatus();}catch(e){status.textContent=e.message;}});
document.querySelectorAll('form:not(#entry-edit):not(#catalog-edit)').forEach(f=>f.onsubmit=async e=>{e.preventDefault();const button=f.querySelector('button');button.disabled=true;try{const data=Object.fromEntries(new FormData(f));if(f.id==='smb')data.encryption=f.elements.encryption.checked;const result=await call('/'+f.id,data);if(f.id==='sources'){await watchSource(result.importId);return;}f.querySelectorAll('[type=password]').forEach(i=>i.value='');status.textContent='保存成功，电视正在应用';if(f.id==='tmdb')await refreshTmdbStatus();}catch(e){status.textContent=e.message;}finally{button.disabled=false;}});
document.querySelector('#test').onclick=async()=>{try{status.textContent=(await call('/tmdb/test')).result;await refreshTmdbStatus();}catch(e){status.textContent=e.message;}};
document.querySelector('#end').onclick=async()=>{try{await call('/end');}finally{session='';document.querySelector('#manager').hidden=true;status.textContent='会话已结束，请在电视重新开启';}};
// Editing a long form is activity; refresh only while the user is actively editing.
let lastActivity=Date.now();document.oninput=()=>{lastActivity=Date.now();};
setInterval(()=>{if(session&&Date.now()-lastActivity<60000)call('/status').catch(()=>{});},45000);

document.querySelector('#entry-edit').onsubmit=async e=>{e.preventDefault();const f=e.target;const data=Object.fromEntries(new FormData(f));data.module=module;data.enabled=f.elements.enabled.checked;if(data.id&&!data.password)delete data.password;try{await call('/entries/save',data);f.reset();await refreshEntries();status.textContent='已保存并通知电视';}catch(e){status.textContent=e.message;}};
document.querySelector('#new-entry').onclick=()=>document.querySelector('#entry-edit').reset();
document.querySelector('#batch-save').onclick=async()=>{try{await call('/entries/batch',{urls:document.querySelector('#batch-urls').value});await refreshEntries();status.textContent='批量地址已保存并去重';}catch(e){status.textContent=e.message;}};

async function refreshCatalogs(){
 const section=document.querySelector('#catalog-manager');section.hidden=module!=='sources';if(section.hidden)return;
 document.querySelector('#catalog-edit').hidden=false;const data=await call('/catalogs');const list=document.querySelector('#catalog-list');list.replaceChildren();
 data.entries.forEach(entry=>{const row=document.createElement('p');row.textContent=entry.name+(data.active===entry.id?' · 已启用':'');
 const add=(label,action)=>{const b=document.createElement('button');b.type='button';b.textContent=label;b.onclick=async()=>{try{await action();}catch(e){status.textContent=e.message;}};row.append(b);};
 add('编辑',()=>{const f=document.querySelector('#catalog-edit');for(const key of ['id','name','url'])f.elements[key].value=entry[key];});
 add('启用',async()=>{await call('/catalogs/activate',{id:entry.id});await refreshCatalogs();});
 add('删除',async()=>{await call('/catalogs/delete',{id:entry.id});await refreshCatalogs();});list.append(row);});
}
document.querySelector('#catalog-edit').onsubmit=async e=>{e.preventDefault();try{await call('/catalogs/save',Object.fromEntries(new FormData(e.target)));e.target.reset();await refreshCatalogs();status.textContent='目录已保存';}catch(e){status.textContent=e.message;}};
document.querySelector('#catalog-new').onclick=()=>document.querySelector('#catalog-edit').reset();
document.querySelector('#catalog-disable').onclick=async()=>{try{await call('/catalogs/activate',{id:''});await refreshCatalogs();}catch(e){status.textContent=e.message;}};

async function refreshTmdbStatus(){const r=await call('/status');document.querySelector('#endpoint').textContent=r.endpoint+' · '+(r.tokenConfigured?'已配置':'未配置');}
document.querySelector('#nas-test').onclick=async()=>{try{status.textContent=(await call('/nas/test')).result;}catch(e){status.textContent=e.message;}};

document.querySelector('#cloud-test').onclick=async()=>{try{status.textContent=(await call('/cloud/test')).result;}catch(e){status.textContent=e.message;}};

document.querySelector('#smb-test').onclick=async()=>{try{status.textContent=(await call('/smb/test')).result;}catch(e){status.textContent=e.message;}};
