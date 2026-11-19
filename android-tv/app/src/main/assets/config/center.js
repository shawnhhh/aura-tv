'use strict';
let pairing=location.hash.slice(1),session='',tab='sources',protocol='WebDAV',personalConnection='',directoryPage=0,sourcePoll=0,toastTimer;
history.replaceState(null,'',location.pathname);
const $=s=>document.querySelector(s);
const status=$('#status');
function toast(ok){
 const el=$('#module-toast');el.textContent=ok?'成功':'失败';el.hidden=false;
 clearTimeout(toastTimer);toastTimer=setTimeout(()=>{el.hidden=true},2400);
}
async function call(path,data={}){
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Herrin-Session':session},
  body:JSON.stringify(data),cache:'no-store',credentials:'omit'});
 const json=await r.json().catch(()=>({}));
 if(!r.ok)throw Error(json.code||('HTTP_'+r.status));
 return json;
}
function busy(button,enabled){button.disabled=enabled}
function button(parent,label,action){
 const b=document.createElement('button');b.type='button';b.textContent=label;
 b.onclick=async()=>{busy(b,true);try{await action()}catch(_){toast(false)}finally{busy(b,false)}};
 parent.append(b);return b;
}
function switchTab(target){
 tab=target;
 document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('selected',b.dataset.tab===target));
 for(const name of ['sources','nas','tmdb','live'])$('#'+name).hidden=name!==target;
 $('#module-toast').hidden=true;
 if(target==='nas')refreshPersonal().catch(()=>toast(false));
}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>switchTab(b.dataset.tab));
$('#pair').onclick=async()=>{
 if(!$('#verified').checked){status.textContent='请核对电视证书指纹';return}
 if($('#pair').disabled)return;
 const b=$('#pair');busy(b,true);
 try {
  while(!session){
   const p=await call('/pair',{pairing});session=p.session||'';
   if(!session){status.textContent='请在电视确认配对';await new Promise(r=>setTimeout(r,1500))}
  }
  pairing='';$('#trust').hidden=true;$('#manager').hidden=false;status.textContent='';
  const config=await call('/status');
  $('#sources [name=url]').value=config.tvbox||'';
  $('#live [name=m3u]').value=config.m3u||'';
  $('#live [name=epg]').value=config.epg||'';
  switchTab('sources');
 }catch(_){status.textContent='配对失败，请在电视重新扫码'}finally{busy(b,false)}
};
async function watchSource(id){
 const ticket=++sourcePoll,started=Date.now();
 while(session&&ticket===sourcePoll){
  if(Date.now()-started>50000){toast(false);return}
  const r=await call('/sources/status',{importId:id});
  if(r.state==='ACTIVE'){toast(true);return}
  if(['FAILED','SUPERSEDED'].includes(r.state)){toast(false);return}
  await new Promise(r=>setTimeout(r,1100));
 }
}
$('#sources').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');busy(b,true);
 try{const r=await call('/sources',{url:e.target.elements.url.value});await watchSource(r.importId)}
 catch(_){toast(false)}finally{busy(b,false)}
};
$('#tmdb').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');busy(b,true);
 try{await call('/tmdb',{token:e.target.elements.token.value});e.target.elements.token.value='';toast(true)}
 catch(_){toast(false)}finally{busy(b,false)}
};
$('#live').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');busy(b,true);
 try{await call('/live',{m3u:e.target.elements.m3u.value,epg:e.target.elements.epg.value});toast(true)}
 catch(_){toast(false)}finally{busy(b,false)}
};
function switchProtocol(next){
 protocol=next;document.querySelectorAll('[data-personal-tab]').forEach(b=>b.classList.toggle('selected',b.dataset.personalTab===next));
 const f=$('#personal-edit');f.reset();f.elements.id.value='';f.elements.protocol.value=next==='SMB'?'smb':'https';
 f.elements.address.placeholder=next==='SMB'?'IP 或域名':'http:// 或 https://';
 $('#smb-port').hidden=next!=='SMB';f.elements.port.disabled=next!=='SMB';
 $('#personal-browser').hidden=true;
}
document.querySelectorAll('[data-personal-tab]').forEach(b=>b.onclick=()=>{switchProtocol(b.dataset.personalTab);refreshPersonal().catch(()=>toast(false))});
$('#personal-new').onclick=()=>switchProtocol(protocol);
async function personal(path,data={}){
 const r=await call('/personal'+path,data);if(r.ok===false)throw Error('PERSONAL_FAILED');return r;
}
async function refreshPersonal(){
 const data=await personal('',{}),list=$('#personal-connections'),dirs=$('#personal-folders');
 list.replaceChildren();dirs.replaceChildren();
 for(const c of data.connections||[]){
  if((c.protocol==='smb')!==(protocol==='SMB'))continue;
  const row=document.createElement('div');row.className='connection';
  const name=document.createElement('span');name.className='name';name.textContent=c.name;row.append(name);
  const actions=document.createElement('div');actions.className='actions';row.append(actions);
  button(actions,'编辑',()=>{const f=$('#personal-edit');switchProtocol(protocol);
   for(const key of ['id','name','username','path'])f.elements[key].value=c[key]||'';
   f.elements.address.value=protocol==='SMB'?c.host:(c.address||'');
   if(protocol==='SMB')f.elements.port.value=c.port||445;
  });
  button(actions,'浏览目录',async()=>{personalConnection=c.id;directoryPage=0;$('#personal-path').value=c.path||'';
   $('#personal-browser').hidden=false;await browsePersonal()});
  button(actions,c.enabled?'停用':'启用',async()=>{await personal('/toggle',{id:c.id});await refreshPersonal();toast(true)});
  button(actions,'删除',async()=>{if(!confirm('确定删除？'))return;await personal('/delete',{id:c.id,confirmed:true});await refreshPersonal();toast(true)});
  list.append(row);
 }
 for(const f of data.folders||[]){
  const c=(data.connections||[]).find(c=>c.id===f.connection);
  if(!c||(c.protocol==='smb')!==(protocol==='SMB'))continue;
  const row=document.createElement('div');row.className='folder';
  const name=document.createElement('span');name.textContent=f.path;row.append(name);
  button(row,'移除',async()=>{if(!confirm('移除当前文件夹？'))return;await personal('/folder/delete',{id:f.id,confirmed:true});await refreshPersonal();toast(true)});
  dirs.append(row);
 }
}
$('#personal-edit').onsubmit=async e=>{
 e.preventDefault();const f=e.target,b=f.querySelector('button[type=submit]');busy(b,true);
 try{
  const data=Object.fromEntries(new FormData(f));
  if(protocol==='SMB'){data.protocol='smb';data.host=data.address;delete data.address;data.port=Number(data.port)||445}
  else {data.protocol='https';delete data.host;delete data.port}
  if(data.id&&!data.password)delete data.password;
  const result=await personal('/save',data);f.elements.id.value=result.id;f.elements.password.value='';
  personalConnection=result.id;directoryPage=0;
  $('#personal-path').value=result.path||'';$('#personal-browser').hidden=false;
  await refreshPersonal();renderDirectories(result);toast(true);
 }catch(_){toast(false)}finally{busy(b,false)}
};
function renderDirectories(r){
 const el=$('#personal-directories');el.replaceChildren();
 for(const f of r.files||[])button(el,f.name,async()=>{$('#personal-path').value=f.path;directoryPage=0;await browsePersonal()});
 $('#personal-directory-previous').disabled=directoryPage===0;
 $('#personal-directory-next').disabled=(directoryPage+1)*100>=(r.total||0);
}
async function browsePersonal(){
 const r=await personal('/browse',{id:personalConnection,path:$('#personal-path').value,offset:directoryPage*100});
 renderDirectories(r);
}
$('#personal-browse').onclick=()=>browsePersonal().catch(()=>toast(false));
$('#personal-parent').onclick=()=>{
 const v=$('#personal-path');v.value=v.value.split('/').filter(Boolean).slice(0,-1).join('/');directoryPage=0;
 browsePersonal().catch(()=>toast(false));
};
$('#personal-directory-previous').onclick=()=>{directoryPage=Math.max(0,directoryPage-1);browsePersonal().catch(()=>toast(false))};
$('#personal-directory-next').onclick=()=>{directoryPage++;browsePersonal().catch(()=>toast(false))};
$('#personal-import').onclick=async()=>{
 try{await personal('/folder',{connection:personalConnection,path:$('#personal-path').value,type:'auto'});
  await refreshPersonal();toast(true)}catch(_){toast(false)}
};
$('#end').onclick=async()=>{try{await call('/end')}finally{session='';$('#manager').hidden=true;status.textContent='会话已结束'}};
setInterval(()=>{if(session)call('/status').catch(()=>{})},45000);
switchProtocol('WebDAV');
