const {chromium}=require('playwright');
const http=require('http'),fs=require('fs'),assert=require('assert');
const root=require('path').resolve(__dirname,'../app/src/main/assets/config')+'/';
(async()=>{
 const calls=[]; const server=http.createServer((req,res)=>{
  if(req.method==='GET') {const file=req.url==='/'?'quick.html':req.url==='/center.js'?'quick.js':'center.css';res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html; charset=utf-8');return res.end(fs.readFileSync(root+file));}
  let raw='';req.on('data',b=>raw+=b);req.on('end',()=>{calls.push({path:req.url,input:JSON.parse(raw),session:req.headers['x-herrin-session']});res.setHeader('Content-Type','application/json');res.end(JSON.stringify(req.url==='/pair'?{session:'fixture-session'}:req.url==='/entries'?{entries:[{id:'fixture',name:'公开样本',url:'http://example.org/config',enabled:true}]}:{ok:true}));});
 }); await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 try {const page=await browser.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('http://127.0.0.1:'+server.address().port+'/#fixture-pair');await page.click('#pair');await page.waitForSelector('#manager:visible');assert.strictEqual(await page.locator('[name=password],[name=token],[name=username]').count(),0);
 await page.fill('#primary [name=url]','http://饭太硬.net/tv');await page.click('#primary button');await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('保存成功'));assert(calls.some(c=>c.path==='/sources'&&c.input.url==='http://饭太硬.net/tv'));
 await page.fill('#batch textarea','http://example.org/a\nhttps://example.org/b');await page.click('#batch button');await page.waitForTimeout(300);assert(calls.some(c=>c.path==='/entries/batch'));
 await page.click('#entries button');assert.strictEqual(await page.inputValue('#edit [name=id]'),'fixture');await page.fill('#edit [name=url]','http://example.org/edited');await page.click('#edit button[type=submit], #edit button:not([type])');await page.waitForTimeout(150);assert(calls.some(c=>c.path==='/entries/save'&&c.input.url==='http://example.org/edited'));await page.click('#end');await page.waitForSelector('#manager',{state:'hidden'});assert.deepStrictEqual(errors,[]);
 const result={browser:'Chromium',viewport:'390x844',checks:['page loads','no credential inputs','pairing flow','HTTP IDN source submission','mixed batch submission','edit source','end hides manager','no JS errors'],backend:'controlled mock API; real Android API is covered by instrumentation',result:'passed'};fs.writeFileSync(process.env.LAN_BROWSER_REPORT||'/tmp/lan-browser-smoke.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exit(1)});
