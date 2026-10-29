// npm install --no-save playwright@1.56.1; npx playwright install chromium
// Start the real app first. Requires ffmpeg/ffprobe.
const {chromium}=require('playwright');const fs=require('fs');const path=require('path');const {spawnSync}=require('child_process');
const repo=path.resolve(__dirname,'..'),raw=path.join(repo,'.demo-recording');fs.mkdirSync(raw,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1200,height:850},recordVideo:{dir:raw,size:{width:1200,height:850}}});const page=await context.newPage();
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));await page.goto('http://127.0.0.1:7860');await page.waitForFunction(()=>document.querySelector('#output').src);
 await page.locator('#prompt').fill('a beautiful alpine mountain lake at sunset, pine forest, snow capped peaks, golden sunlight');await page.locator('#seed').fill('42');await page.locator('#seed').dispatchEvent('change');
 const start=Date.now();await page.waitForTimeout(400);const box=await page.locator('#sketch-stage').boundingBox();const pt=(x,y)=>[box.x+x*box.width,box.y+y*box.height];
 async function brush(color,size){await page.locator('#color').fill(color);await page.locator('#size').fill(String(size));}
 async function stroke(points){await page.mouse.move(...pt(...points[0]));await page.mouse.down();for(const p of points.slice(1))await page.mouse.move(...pt(...p),{steps:8});await page.mouse.up();await page.waitForTimeout(220);}
 await brush('#38bdf8',14);await page.locator('#fill').click();await brush('#1e3a8a',65);await stroke([[.03,.64],[.28,.35],[.48,.61],[.70,.28],[.97,.61]]);
 await brush('#16a34a',80);await stroke([[.02,.76],[.30,.70],[.55,.77],[.80,.69],[.98,.76]]);
 await brush('#38bdf8',90);await stroke([[.02,.93],[.35,.87],[.65,.94],[.98,.87]]);
 await brush('#facc15',65);await stroke([[.2,.2],[.201,.201]]);
 await brush('#ffffff',8);await stroke([[.18,.43],[.28,.35],[.36,.44]]);await stroke([[.61,.39],[.70,.28],[.79,.4]]);
 await page.waitForTimeout(700);await page.locator('#enhance').click();await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('enhanced'),{timeout:90000});
 await page.screenshot({path:path.join(raw,'final.png')});await page.waitForTimeout(2300);const video=page.video();await context.close();const source=await video.path();await browser.close();if(errors.length)throw Error(errors.join('\n'));
 const duration=+JSON.parse(spawnSync('ffprobe',['-v','error','-show_entries','format=duration','-of','json',source],{encoding:'utf8'}).stdout).format.duration;const speed=duration/9;
 const filter=`[0:v]setpts=PTS/${speed},fps=12,scale=800:-1:flags=lanczos,drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='Actual RTX 3090 run - playback condensed':x=12:y=h-22:fontsize=13:fontcolor=white:box=1:boxcolor=black@0.7,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a`;
 const dest=path.join(repo,'media/demo.gif');const r=spawnSync('ffmpeg',['-y','-i',source,'-filter_complex',filter,'-loop','0',dest],{encoding:'utf8'});if(r.status)throw Error(r.stderr.slice(-1200));
 fs.writeFileSync(path.join(repo,'media/DEMO.md'),`# Real SketchLive capture\n\nFresh Playwright recording of actual SD-Turbo CUDA inference on an RTX 3090, seed 42. Mouse strokes draw the landscape, real preview frames follow, and Enhance runs the full VAE. No mock generator, substituted results or UI mockups. Original recording ${duration.toFixed(2)} seconds, condensed to about 9 seconds (${speed.toFixed(2)}Ã— playback), as the burned-in caption states. This is not an inference speed benchmark.\n`);console.log({duration,speed,bytes:fs.statSync(dest).size});
})().catch(e=>{console.error(e);process.exitCode=1});
