'use strict';
let pairing=location.hash.slice(1),session='',polling=false;
history.replaceState(null,'',location.pathname);
const status=document.querySelector('#status');
async function call(path,value={}){
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Herrin-Session':session},body:JSON.stringify(value),cache:'no-store',credentials:'omit'});
 if(!r.ok)throw Error('配置无效、包含敏感信息或会话已结束');return r.json();
}
async function refresh(){
 const r=await call('/entries',{module:'sources'}),list=document.querySelector('#entries');list.replaceChildren();
 r.entries.forEach(entry=>{const b=document.createElement('button');b.textContent=entry.name+' · 编辑';b.onclick=()=>{const f=document.querySelector('#edit');for(const key of ['id','name','url'])f.elements[key].value=entry[key];f.elements.enabled.checked=entry.enabled;};list.append(b);});
}
document.querySelector('#pair').onclick=async()=>{
 if(polling)return;polling=true;
 try{while(!session){const r=await call('/pair',{pairing});session=r.session;if(!session){status.textContent='请在电视确认此手机配对';await new Promise(r=>setTimeout(r,1500));}}
 pairing='';document.querySelector('#pair').hidden=true;document.querySelector('#manager').hidden=false;status.textContent='已配对；仅可提交公开影视源';await refresh();
 }catch(e){status.textContent=e.message;}finally{polling=false;}
};
document.querySelectorAll('form').forEach(f=>f.onsubmit=async e=>{
 e.preventDefault();const data=Object.fromEntries(new FormData(f));
 let path='/sources';
 if(f.getAttribute('id')==='edit'){path='/entries/save';data.module='sources';data.enabled=f.elements.enabled.checked;}
 if(f.getAttribute('id')==='batch')path='/entries/batch';
 try{await call(path,data);status.textContent='保存成功，已通知电视刷新；Spider 授权保持独立';f.reset();await refresh();}catch(e){status.textContent=e.message;}
});
document.querySelector('#end').onclick=async()=>{try{await call('/end');}catch(e){status.textContent=e.message;}finally{session='';document.querySelector('#manager').hidden=true;status.textContent='会话已结束';}};
