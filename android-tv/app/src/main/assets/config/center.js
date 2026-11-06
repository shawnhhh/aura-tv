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
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{document.querySelectorAll('form').forEach(f=>f.hidden=f.id!==b.dataset.tab);});
document.querySelectorAll('form').forEach(f=>f.onsubmit=async e=>{e.preventDefault();const button=f.querySelector('button');button.disabled=true;try{await call('/'+f.id,Object.fromEntries(new FormData(f)));f.querySelectorAll('[type=password]').forEach(i=>i.value='');status.textContent='保存成功，电视正在应用';}catch(e){status.textContent=e.message;}finally{button.disabled=false;}});
document.querySelector('#test').onclick=async()=>{try{status.textContent=(await call('/tmdb/test')).result;}catch(e){status.textContent=e.message;}};
document.querySelector('#end').onclick=async()=>{try{await call('/end');}finally{session='';document.querySelector('#manager').hidden=true;status.textContent='会话已结束，请在电视重新开启';}};
// Editing a long form is activity; refresh only while the user is actively editing.
let lastActivity=Date.now();document.oninput=()=>{lastActivity=Date.now();};
setInterval(()=>{if(session&&Date.now()-lastActivity<60000)call('/status').catch(()=>{});},45000);
