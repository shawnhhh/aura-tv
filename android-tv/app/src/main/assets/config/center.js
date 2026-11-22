'use strict';
let pairing=location.hash.slice(1),session='',tab='sources',protocol='WebDAV',personalConnection='',directoryPage=0,sourcePoll=0,toastTimer,personalRefreshToken=0,directoryBrowseToken=0,moduleEpoch=0,formRevision=0,sourceFormRevision=0;
history.replaceState(null,'',location.pathname);
const $=s=>document.querySelector(s);
const status=$('#status');
const busyOwners=new Map(),inFlightRequests=new Set();
function notice(message,owner){
 // A background request must never display a result in a different module or ended session.
 if(!session||$('#manager').hidden||owner!==tab)return;
 const el=$('#module-toast');el.textContent=message;el.hidden=false;
 clearTimeout(toastTimer);toastTimer=setTimeout(()=>{el.hidden=true},2400);
}
function toast(ok,owner){notice(ok?'成功':'失败',owner)}
function releaseBusy(button){if(busyOwners.delete(button))button.disabled=false}
function beginBusy(button){
 const owner=Symbol(),epoch=moduleEpoch,authorizedSession=session;busyOwners.set(button,owner);button.disabled=true;
 return ()=>{if(busyOwners.get(button)!==owner)return;busyOwners.delete(button);
  if(epoch===moduleEpoch&&authorizedSession===session)button.disabled=false};
}
function advanceModuleScope(ending=false){
 moduleEpoch++;sourcePoll++;
 for(const button of [...busyOwners.keys()])releaseBusy(button);
 for(const request of inFlightRequests){
  if(request.path!=='/pair'&&request.path!=='/end'&&(ending||request.path!=='/personal/save'))request.controller.abort();
 }
}
async function call(path,data={}){
 const requestedSession=session,requestedModule=moduleEpoch;
 const stale=()=>path!=='/pair'&&(requestedSession!==session||(path!=='/personal/save'&&requestedModule!==moduleEpoch));
 const request={path,controller:new AbortController()};inFlightRequests.add(request);
 const deadline=setTimeout(()=>request.controller.abort(),30000);
 try{
  const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Herrin-Session':session},
   body:JSON.stringify(data),cache:'no-store',credentials:'omit',signal:request.controller.signal});
  const json=await r.json();
  if(stale())throw Object.assign(Error('STALE_SESSION'),{stale:true});
  if(!json||typeof json!=='object'||Array.isArray(json))throw Error('INVALID_JSON_RESPONSE');
  if(!r.ok)throw Error(json.code||('HTTP_'+r.status));
  return json;
 }catch(error){
  if(stale())error.stale=true;
  if(!error.stale)console.warn('HERRIN request failed',path,error.name);
  throw error;
 }finally{clearTimeout(deadline);inFlightRequests.delete(request)}
}
function busy(button,enabled){button.disabled=enabled}
function button(parent,label,action){
 const b=document.createElement('button'),owner=parent.closest('#nas')?'nas':tab;b.type='button';b.textContent=label;
 b.onclick=async()=>{if(b.disabled)return;const finish=beginBusy(b);try{await action()}catch(error){if(!error.stale)toast(false,owner)}finally{finish()}};
 parent.append(b);return b;
}
function switchTab(target){
 advanceModuleScope();tab=target;
 document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('selected',b.dataset.tab===target));
 for(const name of ['sources','nas','tmdb','live'])$('#'+name).hidden=name!==target;
 clearTimeout(toastTimer);$('#module-toast').hidden=true;
 if(target==='nas')refreshPersonal().catch(error=>{if(!error.stale)toast(false,'nas')});
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
  pairing='';status.textContent='';
  const config=await call('/status');
  $('#sources [name=url]').value=config.tvbox||'';
  $('#live [name=m3u]').value=config.m3u||'';
  $('#live [name=epg]').value=config.epg||'';
  $('#trust').hidden=true;$('#manager').hidden=false;
  switchTab('sources');
 }catch(error){if(!error.stale)status.textContent=session?'连接失败，请重试':'配对失败，请在电视重新扫码'}finally{busy(b,false)}
};
async function watchSource(id,ownerModule,ownerForm){
 const ticket=++sourcePoll,started=Date.now();
 const current=()=>session&&ticket===sourcePoll&&ownerModule===moduleEpoch&&ownerForm===sourceFormRevision&&tab==='sources';
 while(current()){
  if(Date.now()-started>50000){notice('仍在处理，请稍后查看','sources');return}
  const r=await call('/sources/status',{importId:id});
  if(!current())return;
  if(r.state==='ACTIVE'){toast(true,'sources');return}
  if(['FAILED','SUPERSEDED'].includes(r.state)){toast(false,'sources');return}
  await new Promise(r=>setTimeout(r,1100));
 }
}
$('#sources').addEventListener('input',()=>{
 sourceFormRevision++;sourcePoll++;releaseBusy($('#sources button[type=submit]'));
 for(const request of inFlightRequests)if(request.path==='/sources/status')request.controller.abort();
 if(tab==='sources'){clearTimeout(toastTimer);$('#module-toast').hidden=true}
});
$('#sources').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');if(b.disabled)return;
 const ownerModule=moduleEpoch,ownerForm=sourceFormRevision,finish=beginBusy(b);
 try{const r=await call('/sources',{url:e.target.elements.url.value});if(!r.importId)throw Error('MISSING_IMPORT_ID');
  if(ownerModule===moduleEpoch&&ownerForm===sourceFormRevision)await watchSource(r.importId,ownerModule,ownerForm)}
 catch(error){if(!error.stale&&ownerModule===moduleEpoch&&ownerForm===sourceFormRevision)toast(false,'sources')}finally{finish()}
};
$('#tmdb').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');if(b.disabled)return;const finish=beginBusy(b);
 const token=e.target.elements.token,submitted=token.value;
 try{await call('/tmdb',{token:submitted});if(token.value===submitted)token.value='';toast(true,'tmdb')}
 catch(error){if(!error.stale)toast(false,'tmdb')}finally{finish()}
};
$('#live').onsubmit=async e=>{
 e.preventDefault();const b=e.target.querySelector('button[type=submit]');if(b.disabled)return;const finish=beginBusy(b);
 try{await call('/live',{m3u:e.target.elements.m3u.value,epg:e.target.elements.epg.value});toast(true,'live')}
 catch(error){if(!error.stale)toast(false,'live')}finally{finish()}
};
function switchProtocol(next){
 advanceModuleScope();formRevision++;protocol=next;document.querySelectorAll('[data-personal-tab]').forEach(b=>b.classList.toggle('selected',b.dataset.personalTab===next));
 const f=$('#personal-edit');f.reset();f.elements.id.value='';f.elements.protocol.value=next==='SMB'?'smb':'https';
 f.elements.address.placeholder=next==='SMB'?'IP 或域名':'http:// 或 https://';
 $('#smb-port').hidden=false;f.elements.port.disabled=false;f.elements.port.value=next==='SMB'?'445':'';
 directoryBrowseToken++;$('#personal-directories').replaceChildren();$('#personal-browser').hidden=true;
}
document.querySelectorAll('[data-personal-tab]').forEach(b=>b.onclick=()=>{switchProtocol(b.dataset.personalTab);refreshPersonal().catch(error=>{if(!error.stale)toast(false,'nas')})});
$('#personal-new').onclick=()=>switchProtocol(protocol);
async function personal(path,data={}){
 const r=await call('/personal'+path,data);if(r.ok===false)throw Error('PERSONAL_FAILED');return r;
}
async function refreshPersonal(){
 const ticket=++personalRefreshToken,requestedProtocol=protocol;
 const data=await personal('',{});
 if(!session||ticket!==personalRefreshToken||requestedProtocol!==protocol||tab!=='nas')return;
 const list=$('#personal-connections'),dirs=$('#personal-folders');
 list.replaceChildren();dirs.replaceChildren();
 for(const c of data.connections||[]){
  if((c.protocol==='smb')!==(protocol==='SMB'))continue;
  const row=document.createElement('div');row.className='connection';
  const name=document.createElement('span');name.className='name';name.textContent=c.name;row.append(name);
  const actions=document.createElement('div');actions.className='actions';row.append(actions);
  button(actions,'编辑',()=>{const f=$('#personal-edit');switchProtocol(protocol);
   for(const key of ['id','name','username','path'])f.elements[key].value=c[key]||'';
   f.elements.address.value=protocol==='SMB'?c.host:(c.address||'');
   f.elements.port.value=c.port||'';
  });
  button(actions,'浏览目录',async()=>{personalConnection=c.id;directoryPage=0;$('#personal-path').value=c.path||'';
   $('#personal-browser').hidden=false;await browsePersonal()});
  button(actions,c.enabled?'停用':'启用',async()=>{await personal('/toggle',{id:c.id});await refreshPersonal();toast(true,'nas')});
  button(actions,'删除',async()=>{if(!confirm('确定删除？'))return;await personal('/delete',{id:c.id,confirmed:true});await refreshPersonal();toast(true,'nas')});
  list.append(row);
 }
 for(const f of data.folders||[]){
  const c=(data.connections||[]).find(c=>c.id===f.connection);
  if(!c||(c.protocol==='smb')!==(protocol==='SMB'))continue;
  const row=document.createElement('div');row.className='folder';
  const name=document.createElement('span');name.textContent=f.path||c.basePath||c.name;row.append(name);
  button(row,'移除',async()=>{if(!confirm('移除当前文件夹？'))return;await personal('/folder/delete',{id:f.id,confirmed:true});await refreshPersonal();toast(true,'nas')});
  dirs.append(row);
 }
}
$('#personal-edit').addEventListener('input',()=>{formRevision++});
$('#personal-edit').onsubmit=async e=>{
 e.preventDefault();const f=e.target,b=f.querySelector('button[type=submit]'),requestedProtocol=protocol,requestedModule=moduleEpoch,requestedForm=formRevision;if(b.disabled)return;const finish=beginBusy(b);
 try{
  const data=Object.fromEntries(new FormData(f));
  if(requestedProtocol==='SMB'){data.protocol='smb';data.host=data.address;delete data.address;data.port=Number(data.port)||445}
  else {data.protocol='https';delete data.host;data.port=Number(data.port)||''}
  if(data.id&&!data.password)delete data.password;
  const result=await personal('/save',data);
  // Only the original form may clear its submitted secret, even when a new form reuses the same text.
  if(requestedForm===formRevision&&requestedProtocol===protocol&&f.elements.password.value===data.password)f.elements.password.value='';
  // Commit is complete, but a late response must not overwrite a newly selected form.
  if(!session||requestedModule!==moduleEpoch||requestedForm!==formRevision||requestedProtocol!==protocol||tab!=='nas')return;
  f.elements.id.value=result.id;
  personalConnection=result.id;directoryPage=0;
  $('#personal-path').value=result.path||'';$('#personal-browser').hidden=false;
  await refreshPersonal();
  if(!session||requestedModule!==moduleEpoch||requestedForm!==formRevision||requestedProtocol!==protocol||tab!=='nas')return;
  renderDirectories(result);toast(true,'nas');
 }catch(error){if(!error.stale&&requestedModule===moduleEpoch)toast(false,'nas')}finally{finish()}
};
function renderDirectories(r){
 const el=$('#personal-directories');el.replaceChildren();
 for(const f of r.files||[])button(el,f.name,async()=>{$('#personal-path').value=f.path;directoryPage=0;await browsePersonal()});
 $('#personal-directory-previous').disabled=directoryPage===0;
 $('#personal-directory-next').disabled=(directoryPage+1)*100>=(r.total||0);
}
async function browsePersonal(){
 const ticket=++directoryBrowseToken,connection=personalConnection,path=$('#personal-path').value,offset=directoryPage*100;
 const r=await personal('/browse',{id:connection,path,offset});
 if(ticket!==directoryBrowseToken||connection!==personalConnection||path!==$('#personal-path').value||tab!=='nas')return;
 renderDirectories(r);
}
$('#personal-browse').onclick=()=>browsePersonal().catch(error=>{if(!error.stale)toast(false,'nas')});
$('#personal-parent').onclick=()=>{
 const v=$('#personal-path');v.value=v.value.split('/').filter(Boolean).slice(0,-1).join('/');directoryPage=0;
 browsePersonal().catch(error=>{if(!error.stale)toast(false,'nas')});
};
$('#personal-directory-previous').onclick=()=>{directoryPage=Math.max(0,directoryPage-1);browsePersonal().catch(error=>{if(!error.stale)toast(false,'nas')})};
$('#personal-directory-next').onclick=()=>{directoryPage++;browsePersonal().catch(error=>{if(!error.stale)toast(false,'nas')})};
$('#personal-import').onclick=async()=>{
 try{await personal('/folder',{connection:personalConnection,path:$('#personal-path').value,type:'auto'});
  await refreshPersonal();toast(true,'nas')}catch(error){if(!error.stale)toast(false,'nas')}
};
$('#end').onclick=async()=>{
 directoryBrowseToken++;personalRefreshToken++;
 const ending=call('/end');session='';advanceModuleScope(true);
 clearTimeout(toastTimer);$('#module-toast').hidden=true;$('#manager').hidden=true;status.textContent='会话已结束';
 // The old logout response has no authority to close a subsequently paired session.
 try{await ending}catch(_){/* The local session is closed even if the TV is unreachable. */}
};
setInterval(()=>{if(session)call('/status').catch(()=>{})},45000);
switchProtocol('WebDAV');
