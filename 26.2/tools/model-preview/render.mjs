import { chromium } from 'playwright';
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
const ROOT = path.dirname(new URL(import.meta.url).pathname);
const types={'.html':'text/html','.js':'text/javascript','.json':'application/json','.png':'image/png'};
const srv=http.createServer((q,r)=>{const f=path.join(ROOT,decodeURIComponent(q.url.split('?')[0]));
  fs.readFile(f,(e,d)=>{if(e){r.writeHead(404);r.end();return;} r.writeHead(200,{'content-type':types[path.extname(f)]||'application/octet-stream'}); r.end(d);});});
await new Promise(res=>srv.listen(0,res)); const port=srv.address().port;
const scenes = JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const browser = await chromium.launch({executablePath: undefined, args:['--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const page = await browser.newPage({viewport:{width:1600,height:1000}});
page.on('console', m=>{ if(m.type()==='error') console.log('console:', m.text()); });
page.on('pageerror', e=>console.log('pageerror:', e.message));
await page.goto(`http://127.0.0.1:${port}/viewer.html`); await page.waitForFunction('window.ready===true');
for(const sc of scenes){
  const url = await page.evaluate(async sc=>{ return await window.renderScene(sc); }, sc);
  fs.mkdirSync(path.dirname(sc.out),{recursive:true});
  fs.writeFileSync(sc.out, Buffer.from(url.split(',')[1],'base64')); console.log('wrote', sc.out);
}
await browser.close(); srv.close();
