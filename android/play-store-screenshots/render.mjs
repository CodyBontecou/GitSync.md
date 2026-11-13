import fs from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const require=createRequire(import.meta.url);
const dependencyRoot=process.env.CODEX_NODE_MODULES;
const resolve=name=>require(dependencyRoot?path.join(dependencyRoot,name):name);
const {chromium}=resolve('playwright');
const sharp=resolve('sharp');
const root=path.dirname(fileURLToPath(import.meta.url));
const output=path.resolve(root,'../app/src/main/play/listings/en-US/graphics');
const browser=await chromium.launch({channel:'chrome',chromiumSandbox:true,headless:true, ...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{})});
const page=await browser.newPage({viewport:{width:1080,height:1920},deviceScaleFactor:1});
const errors=[];page.on('pageerror',e=>errors.push(e.message));
const ids=['clone-real-repos','repo-health','git-control','line-by-line-diff','ready-to-push'];
const files=[];
try {
 await fs.mkdir(path.join(output,'phone-screenshots'),{recursive:true});
 for(let i=0;i<6;i++){
  const feature=i===5, width=feature?1024:1080,height=feature?500:1920;
  await page.setViewportSize({width,height});
  await page.goto('file://'+path.join(root,'index.html')+'?render='+(feature?'feature':i));
  await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(img=>img.decode()));});
  const issues=await page.evaluate(()=>{
    const asset=document.querySelector('.asset');
    const bounds=asset.getBoundingClientRect();
    const copy=asset.querySelector('.copy').getBoundingClientRect();
    const figure=asset.querySelector('.figure').getBoundingClientRect();
    const note=asset.querySelector('.note').getBoundingClientRect();
    const issues=[];
    if(copy.bottom>figure.top && !asset.classList.contains('feature'))issues.push('Copy overlaps capture');
    if(figure.bottom>note.top && !asset.classList.contains('feature'))issues.push('Capture overlaps footer');
    for(const node of asset.querySelectorAll('.headline,.subtext,.note,.caption')){
      if(node.scrollWidth>node.clientWidth+1)issues.push('Text overflow: '+node.className);
      const b=node.getBoundingClientRect();
      if(b.right>bounds.right+1||b.bottom>bounds.bottom+1)issues.push('Text outside asset');
    }
    return issues;
  });
  if(issues.length)throw new Error(issues.join('; '));
  const name=feature?'feature-graphic.png':`phone-screenshots/${String(i+1).padStart(2,'0')}-${ids[i]}-1080x1920.png`;
  const file=path.join(output,name);
  await page.locator('.asset').screenshot({path:file});
  // Google Play accepts RGB PNGs. Strip the browser's optional alpha channel.
  const rgb=await sharp(file).removeAlpha().png().toBuffer();await fs.writeFile(file,rgb);
  const metadata=await sharp(file).metadata();
  if(metadata.width!==width||metadata.height!==height||metadata.channels!==3)throw new Error('Invalid export '+name);
  files.push({name,width,height,bytes:rgb.length});
 }
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:900});
  await page.goto('file://'+path.join(root,'index.html'));
  await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(img=>img.decode()));});
  await page.waitForFunction(()=>[...document.querySelectorAll('.viewport')].every(v=>v.firstElementChild.getBoundingClientRect().width<=v.clientWidth+1));
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  if(overflow)throw new Error('Preview overflows at '+width+'px');
 }
 if(errors.length)throw new Error(errors.join('\n'));
 const thumbWidth=270,thumbHeight=480;
 const thumbs=await Promise.all(files.slice(0,5).map(async(f,i)=>({input:await sharp(path.join(output,f.name)).resize(thumbWidth,thumbHeight).toBuffer(),left:24+i*(thumbWidth+16),top:70})));
 const featureThumb=await sharp(path.join(output,'feature-graphic.png')).resize(640).toBuffer();
 await sharp({create:{width:1462,height:910,channels:3,background:'#e4e1da'}}).composite([...thumbs,{input:featureThumb,left:24,top:574}]).png().toFile(path.join(root,'preview.png'));
 const sources=await Promise.all(['vault.png','git.png','diff.png'].map(async name=>({name,sha256:createHash('sha256').update(await fs.readFile(path.join(root,'sources',name))).digest('hex')})));
 await fs.writeFile(path.join(root,'manifest.json'),JSON.stringify({styleReference:'aso-tool/.asc/aso/screenshots-editorial-v1 / en-US',locale:'en-US',source:'Fresh real Android emulator captures in light mode; rectangular crops and uniform scaling only; original UI unchanged.',sources,files},null,2)+'\n');
 const zipPath=path.join(root,'gitsync-google-play-assets.zip');
 await fs.rm(zipPath,{force:true});
 execFileSync('/usr/bin/zip',['-q',zipPath,...files.map(f=>f.name)],{cwd:output});
 console.log(JSON.stringify({files,preview:path.join(root,'preview.png')},null,2));
}finally{await browser.close();}
