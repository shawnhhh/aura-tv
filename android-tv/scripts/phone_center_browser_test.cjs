'use strict';
const {chromium}=require('playwright');
const fs=require('fs');
const assert=require('assert');
const path=require('path');

const evidence=process.env.HERRIN_BROWSER_EVIDENCE_DIR||path.resolve(__dirname,'../build/phone-center-browser');
fs.mkdirSync(evidence,{recursive:true});

(async()=>{
 const browser=await chromium.launch({executablePath:process.env.HERRIN_CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 try{
  const page=await browser.newPage({viewport:{width:393,height:852},deviceScaleFactor:1});
  const requests=[],errors=[],connections=[],folders=[];
  let nextId=0,sourceState='ACTIVE',holdSourceStatus=false,releaseSourceStatus=()=>{},notifySourceStatus=()=>{};
  let holdBrowse=false,releaseBrowse=()=>{},notifyBrowse=()=>{};
  let holdTmdb=false,releaseTmdb=()=>{},notifyTmdb=()=>{};
  let holdSave=false,releaseSave=()=>{},notifySave=()=>{};
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://herrin.test/**',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(req.method()==='GET'){
    const file=url.pathname==='/'?'center.html':url.pathname.slice(1);
    await route.fulfill({status:200,contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html',body:fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/config',file))});
    return;
   }
   const body=JSON.parse(req.postData());requests.push({path:url.pathname,body,headers:req.headers()});
   let response={ok:true};
   if(url.pathname==='/pair')response={session:'mock-management',pending:false};
   if(url.pathname==='/status')response={tvbox:'',m3u:'',epg:''};
   if(url.pathname==='/tmdb'&&holdTmdb){notifyTmdb();await new Promise(resolve=>{releaseTmdb=resolve});holdTmdb=false}
   if(url.pathname==='/personal')response={ok:true,connections,folders,media:[],progress:''};
   if(url.pathname==='/personal/save'){
    if(holdSave){notifySave();await new Promise(resolve=>{releaseSave=resolve});holdSave=false}
    const id=body.id||'fixture-'+(++nextId);
    const scheme=body.protocol==='smb'?'smb':new URL(body.address).protocol.slice(0,-1);
    const item={id,name:body.name,protocol:scheme,address:body.address||body.host,host:body.host||new URL(body.address).hostname,
      port:body.port|| (scheme==='https'?443:80),username:body.username||'',domain:body.domain||'',
      encryption:body.encryption==='true'||body.encryption===true,path:body.path||'',enabled:true};
    const i=connections.findIndex(c=>c.id===id);if(i<0)connections.push(item);else connections[i]=item;
    response={ok:true,id,path:item.path,files:[{name:'Movies',path:'Movies'}],total:1};
   }
   if(url.pathname==='/personal/browse'){
    if(holdBrowse){notifyBrowse();await new Promise(resolve=>{releaseBrowse=resolve});holdBrowse=false}
    response={ok:true,files:[{name:'Movies',path:'Movies'}],total:1};
   }
   if(url.pathname==='/personal/folder'){
    folders.push({id:'folder-'+folders.length,connection:body.connection,path:body.path,type:body.type,enabled:true});
    response={ok:true};
   }
   if(url.pathname==='/personal/folder/delete'){
    const i=folders.findIndex(f=>f.id===body.id);if(i>=0)folders.splice(i,1);
   }
   if(url.pathname==='/sources')response={ok:true,importId:'mock-import'};
   if(url.pathname==='/sources/status'){
    if(holdSourceStatus){
     notifySourceStatus();
     await new Promise(resolve=>{releaseSourceStatus=resolve});
     holdSourceStatus=false;
    }
    response={state:sourceState};
   }
   if(url.pathname==='/end'){
    await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'TV_UNREACHABLE'})});
    return;
   }
   await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(response)});
  });

  await page.goto('https://herrin.test/#mock-pairing');
  assert.strictEqual(await page.getByText('局域网快速配置').count(),0);
  await page.locator('#pair').click();
  assert.strictEqual(requests.length,0,'pairing requires explicit certificate acknowledgement');
  await page.locator('#verified').check();await page.locator('#pair').click();
  await page.locator('#manager').waitFor({state:'visible'});
  assert(!page.url().includes('#'),'pairing capability must leave address bar');
  assert.strictEqual(await page.locator('#module-content').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(16, 16, 16)');
  for(const tab of ['sources','live','nas','tmdb']){
   await page.locator('[data-tab='+tab+']').click();await page.locator('#'+tab).waitFor({state:'visible'});
  }
  await page.locator('#tmdb [name=token]').fill('A'.repeat(120));
  await page.locator('#tmdb button').first().click();
  await page.waitForFunction(()=>document.querySelector('#tmdb [name=token]').value==='');

  await page.locator('[data-tab=nas]').click();
  assert.strictEqual(await page.locator('#cloud,#smb,#batch-urls,#batch-save').count(),0,'old cloud/batch forms must remain removed');
  const f=page.locator('#personal-edit');
  await f.locator('[name=name]').fill('My HTTP DAV');
  await f.locator('[name=address]').fill('http://nas.example:8080/dav');
  await f.locator('[name=username]').fill('test');
  await f.locator('[name=password]').fill('mock-only-password');
  await f.locator('button[type=submit]').click();await page.locator('#personal-browser').waitFor({state:'visible'});
  assert.strictEqual(await f.locator('[name=password]').inputValue(),'');
  assert.strictEqual(await f.locator('[name=id]').inputValue(),'fixture-1');
  assert(requests.some(r=>r.path==='/personal/save'&&r.body.address==='http://nas.example:8080/dav'));
  await page.locator('#personal-directories button').first().click();
  await page.waitForFunction(()=>document.querySelector('#personal-path').value==='Movies');
  await page.locator('#personal-import').click();
  await page.locator('#personal-folders .folder').first().waitFor({state:'visible'});
  assert.strictEqual(await page.locator('#personal-folders .folder').count(),1);
  assert.strictEqual(await page.locator('#personal-folders .folder').first().locator('span').innerText(),'Movies');
  assert(requests.some(r=>r.path==='/personal/folder'&&r.body.path==='Movies'&&r.body.type==='auto'));

  // A delayed directory response must not repopulate the picker after a protocol switch.
  holdBrowse=true;
  const browseRequested=new Promise(resolve=>{notifyBrowse=resolve});
  await page.locator('#personal-browse').click();await browseRequested;
  await page.locator('[data-personal-tab=SMB]').click();
  const browseResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/personal/browse');
  releaseBrowse();await browseResponse;await page.waitForTimeout(50);
  assert(await page.locator('#personal-browser').isHidden());
  assert.strictEqual(await page.locator('#personal-directories button').count(),0);
  await page.locator('[data-personal-tab=WebDAV]').click();

  await page.locator('#personal-new').click();
  await f.locator('[name=name]').fill('My HTTPS DAV');
  await f.locator('[name=address]').fill('https://nas.example/webdav');
  await f.locator('button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelector('#personal-edit [name=id]').value==='fixture-2');
  assert(requests.some(r=>r.path==='/personal/save'&&r.body.address==='https://nas.example/webdav'));

  await page.locator('[data-personal-tab=SMB]').click();
  await f.locator('[name=name]').fill('My SMB');
  await f.locator('[name=address]').fill('192.168.1.15');
  await f.locator('[name=path]').fill('Movies');
  await f.locator('[name=username]').fill('smb-account');
  await f.locator('[name=password]').fill('mock-smb-password');
  assert.strictEqual(await f.locator('[name=port]').inputValue(),'445');
  await f.locator('button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelector('#personal-edit [name=id]').value==='fixture-3');
  assert(requests.some(r=>r.path==='/personal/save'&&r.body.protocol==='smb'&&r.body.host==='192.168.1.15'&&r.body.port===445));
  assert(!requests.some(r=>r.path==='/cloud'||r.path==='/cloud/test'));
  holdSave=true;
  const saveRequested=new Promise(resolve=>{notifySave=resolve});
  await f.locator('[name=password]').fill('submitted-secret');
  await f.locator('button[type=submit]').click();await saveRequested;
  await f.locator('[name=name]').fill('New unsaved connection name');
  await f.locator('[name=password]').fill('new-secret');
  releaseSave();
  await page.waitForFunction(()=>!document.querySelector('#personal-edit button[type=submit]').disabled);
  assert.strictEqual(await f.locator('[name=name]').inputValue(),'New unsaved connection name');
  assert.strictEqual(await f.locator('[name=password]').inputValue(),'new-secret');


  await page.locator('[data-tab=sources]').click();
  await page.locator('#sources [name=url]').fill('http://饭太硬.net/tv');
  await page.locator('#sources button').click();
  await page.locator('#module-toast').waitFor({state:'visible'});
  assert.strictEqual(await page.locator('#module-toast').innerText(),'成功');
  assert(requests.some(r=>r.path==='/sources'&&r.body.url==='http://饭太硬.net/tv'));
  await page.locator('#sources [name=url]').fill('https://example.invalid/tv');
  sourceState='FAILED';
  await page.locator('#sources button').click();
  await page.waitForFunction(()=>document.querySelector('#module-toast').textContent==='失败');
  assert(requests.some(r=>r.path==='/sources'&&r.body.url==='https://example.invalid/tv'));

  sourceState='ACTIVE';holdSourceStatus=true;
  const statusRequested=new Promise(resolve=>{notifySourceStatus=resolve});
  await page.locator('#sources button').click();
  await statusRequested;
  await page.locator('[data-tab=live]').click();
  await page.locator('[data-tab=sources]').click();
  releaseSourceStatus();
  await page.waitForFunction(()=>!document.querySelector('#sources button').disabled);
  assert(await page.locator('#module-toast').isHidden(),'source result must not leak into live tab');
  await page.locator('[data-tab=live]').click();
  await page.locator('#live [name=m3u]').fill('https://example.invalid/live.m3u');
  await page.locator('#live button').click();
  await page.waitForFunction(()=>document.querySelector('#module-toast').textContent==='成功'&&!document.querySelector('#module-toast').hidden);
  assert.strictEqual(requests.filter(r=>r.path==='/pair').length,1,'all modules share one pairing');
  assert(requests.filter(r=>r.path!=='/pair').every(r=>r.headers['x-herrin-session']==='mock-management'));
  assert.deepStrictEqual(errors,[]);
  await page.screenshot({path:path.join(evidence,'phone-center-mobile.png'),fullPage:true});
  await page.locator('[data-tab=tmdb]').click();
  holdTmdb=true;
  const tmdbRequested=new Promise(resolve=>{notifyTmdb=resolve});
  await page.locator('#tmdb [name=token]').fill('mock-pending-token');
  await page.locator('#tmdb button[type=submit]').click();
  await tmdbRequested;
  await page.locator('#end').click();
  await page.locator('#manager').waitFor({state:'hidden'});
  assert.strictEqual(await page.locator('#module-toast').isHidden(),true,'ending the session clears notifications');
  releaseTmdb();
  await page.waitForFunction(()=>!document.querySelector('#tmdb button[type=submit]').disabled);
  assert.strictEqual(await page.locator('#tmdb [name=token]').inputValue(),'mock-pending-token','ended-session response must not mutate the form');
  assert(await page.locator('#module-toast').isHidden(),'ended-session response must not notify');
  assert.deepStrictEqual(errors,[],'failed end request must not reject an unhandled promise');
  fs.writeFileSync(path.join(evidence,'phone-center-result.json'),JSON.stringify({
   test:'Chromium mobile frontend with mocked API',passed:true,pairings:1,modules:4,requests:requests.length,
   imports:['http','https','smb'],sourceResults:['ACTIVE','FAILED','background ACTIVE'],real_android_server:false,real_mobile_safari:false
  },null,2));
  console.log('PASS: mobile UI, HTTP/HTTPS + SMB forms, folders, scoped import feedback, session close (mock API only)');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
