'use strict';
let pairing=location.hash.slice(1), session='', polling=false;
history.replaceState(null,'',location.pathname);
const status=document.querySelector('#status');
async function call(path,value={}){
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Herrin-Session':session},body:JSON.stringify(value),cache:'no-store',credentials:'omit'});
 if(!r.ok)throw Error(r.status===403?'会话已失效，请从电视重新开启':'配置无效或保存失败');return r.json();
}
document.querySelector('#pair').onclick=async()=>{
 if(!document.querySelector('#verified').checked){status.textContent='请先核对证书指纹';return;}
 if(polling)return;polling=true;
 try{while(!session){const r=await call('/pair',{pairing});session=r.session;if(!session){status.textContent='请在电视确认此配对';await new Promise(r=>setTimeout(r,1500));}}
 pairing='';document.querySelector('#trust').hidden=true;document.querySelector('#manager').hidden=false;
 const r=await call('/status');document.querySelector('#endpoint').textContent=r.endpoint+' · '+(r.tokenConfigured?'已配置':'未配置');
 document.querySelector('#sources [name=url]').value=r.tvbox;document.querySelector('#live [name=m3u]').value=r.m3u;document.querySelector('#live [name=epg]').value=r.epg;document.querySelector('#nas [name=url]').value=r.nas;
 status.textContent='已配对，四个模块共用会话；闲置 30 分钟后关闭';
 }catch(e){status.textContent=e.message;}finally{polling=false;}
};
let module='tmdb';
async function refreshEntries(){
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
document.querySelectorAll('form:not(#entry-edit):not(#catalog-edit)').forEach(f=>f.onsubmit=async e=>{e.preventDefault();const button=f.querySelector('button');button.disabled=true;try{await call('/'+f.id,Object.fromEntries(new FormData(f)));f.querySelectorAll('[type=password]').forEach(i=>i.value='');status.textContent='保存成功，电视正在应用';if(f.id==='tmdb')await refreshTmdbStatus();}catch(e){status.textContent=e.message;}finally{button.disabled=false;}});
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
