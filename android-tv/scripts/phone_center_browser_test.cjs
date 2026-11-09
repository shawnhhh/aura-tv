const {chromium}=require('playwright');
const fs=require('fs');
const assert=require('assert');
const path=require('path');
const evidence=process.env.HERRIN_BROWSER_EVIDENCE_DIR||path.resolve(__dirname,'../build/phone-center-browser');
fs.mkdirSync(evidence,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.HERRIN_CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:393,height:852},deviceScaleFactor:1});
 const requests=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('https://herrin.test/**',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(req.method()==='GET'){
   const file=url.pathname==='/'?'center.html':url.pathname.slice(1);
   await route.fulfill({status:200,contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html',body:fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/config',file))});return;
  }
  const body=JSON.parse(req.postData());requests.push({path:url.pathname,body,headers:req.headers()});
  let response={ok:true};
  if(url.pathname==='/pair')response={session:'mock-management',pending:false};
  if(url.pathname==='/status')response={endpoint:'https://api.tmdb.org/3',tokenConfigured:false,tvbox:'',m3u:'',epg:'',nas:'',nasProtocol:'WebDAV'};
  if(url.pathname==='/entries')response={entries:[]};
  if(url.pathname==='/catalogs')response={entries:[],active:''};
  if(url.pathname==='/tmdb/test'||url.pathname==='/nas/test')response={result:'Mock connection result'};
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(response)});
 });
 await page.goto('https://herrin.test/#mock-pairing');assert.strictEqual(await page.getByText('局域网快速配置').count(),0);
 await page.locator('#pair').click();assert.strictEqual(requests.length,0,'must require explicit certificate check');
 await page.locator('#verified').check();await page.locator('#pair').click();await page.locator('#manager').waitFor({state:'visible'});
 assert(!page.url().includes('#'),'pairing capability must leave address bar');
 for(const tab of ['sources','live','nas','tmdb']){await page.locator('[data-tab='+tab+']').click();await page.locator('form#'+tab).waitFor({state:'visible'});}
 await page.locator('#tmdb [name=token]').fill('A'.repeat(120));await page.locator('#tmdb button').first().click();await page.waitForTimeout(100);
 assert.strictEqual(await page.locator('#tmdb [name=token]').inputValue(),'');
 await page.locator('[data-tab=nas]').click();
 await page.locator('#nas [name=url]').fill('https://nas.example/dav/');await page.locator('#nas [name=username]').fill('test');await page.locator('#nas [name=password]').fill('mock-only-password');await page.locator('#nas button').first().click();await page.locator('#nas-test').click();
 await page.locator('#cloud [name=url]').fill('https://cloud.example/dav/');await page.locator('#cloud [name=username]').fill('cloud-account');await page.locator('#cloud [name=password]').fill('mock-cloud-password');await page.locator('#cloud button').first().click();await page.waitForTimeout(100);assert.strictEqual(await page.locator('#cloud [name=password]').inputValue(),'');assert(requests.some(r=>r.path==='/cloud'&&r.body.username==='cloud-account'));
 await page.locator('#smb [name=url]').fill('smb://nas/media');await page.locator('#smb [name=username]').fill('smb-account');await page.locator('#smb [name=domain]').fill('workgroup');await page.locator('#smb [name=password]').fill('mock-smb-password');await page.locator('#smb [name=encryption]').check();await page.locator('#smb button').first().click();await page.waitForTimeout(100);assert.strictEqual(await page.locator('#smb [name=password]').inputValue(),'');assert(requests.some(r=>r.path==='/smb'&&r.body.encryption===true&&r.body.domain==='workgroup'));
 await page.locator('#entry-edit [name=name]').fill('Second NAS');await page.locator('#entry-edit [name=url]').fill('https://nas-two.example/dav/');await page.locator('#entry-edit [name=password]').fill('mock-password');await page.locator('#entry-edit button').first().click();
 await page.locator('[data-tab=sources]').click();await page.locator('#sources [name=url]').fill('http://饭太硬.net/tv');await page.locator('#sources button').click();await page.waitForTimeout(100);assert(requests.some(r=>r.path==='/sources'&&r.body.url==='http://饭太硬.net/tv'));
 await page.locator('#batch-urls').fill('http://饭太硬.net/a\nhttps://two.example/config');await page.locator('#batch-save').click();await page.waitForTimeout(100);
 assert(requests.some(r=>r.path==='/entries/save'&&r.body.module==='nas'));
 assert(requests.some(r=>r.path==='/entries/batch'));
 assert.strictEqual(requests.filter(r=>r.path==='/pair').length,1,'all four tabs share one pairing');
 assert(requests.filter(r=>r.path!=='/pair').every(r=>r.headers['x-herrin-session']==='mock-management'));
 assert.deepStrictEqual(errors,[]);
 await page.screenshot({path:path.join(evidence,'phone-center-mobile.png'),fullPage:true});
 await page.locator('#end').click();await page.locator('#manager').waitFor({state:'hidden'});
 fs.writeFileSync(path.join(evidence,'phone-center-result.json'),JSON.stringify({test:'Chromium mobile frontend with mocked API',passed:true,pairings:1,modules:4,requests:requests.length,real_android_server:false,real_mobile_safari:false},null,2));
 console.log('PASS: Chromium mobile frontend, four modules, one pairing, credential clearing, batch and NAS posts, session end (mock API only)');await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
