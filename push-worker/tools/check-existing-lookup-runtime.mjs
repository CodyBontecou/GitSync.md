import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { build } = require('esbuild');
const { Miniflare, convertV4MiniflareOptions } = require('miniflare');
const pair = await webcrypto.subtle.generateKey({ name:'RSASSA-PKCS1-v1_5', modulusLength:2048, publicExponent:new Uint8Array([1,0,1]), hash:'SHA-256' },true,['sign','verify']);
const der = await webcrypto.subtle.exportKey('pkcs8',pair.privateKey);
const pem = '-----BEGIN PRIVATE KEY-----\n'+Buffer.from(der).toString('base64').match(/.{1,64}/g).join('\n')+'\n-----END PRIVATE KEY-----';
const bundled = await build({stdin:{contents:`import { findExistingInstallation } from './src/github-app.ts';
export default { async fetch(request,env) { try { return Response.json({id:await findExistingInstallation(env,'octocat')}); } catch(error) { return Response.json({error:error.name,kind:String(error.message).split(':')[0]},{status:502}); } } };`,resolveDir:process.cwd(),sourcefile:'runtime-harness.ts'},bundle:true,format:'esm',platform:'browser',write:false});
const seen=[];let redirect=false;
const mf=new Miniflare(convertV4MiniflareOptions({modules:true,script:bundled.outputFiles[0].text,compatibilityDate:'2026-09-08',host:'127.0.0.1',port:0,
 bindings:{GITHUB_APP_ID:'123456',GITHUB_APP_CLIENT_ID:'Iv1.runtime-test',GITHUB_APP_PRIVATE_KEY_PKCS8:pem},
 outboundService:async(request)=>{seen.push(new URL(request.url).hostname);return redirect?new Response(null,{status:302,headers:{location:'https://unexpected.example/redirect'}}):Response.json({id:123,app_id:123456,account:{id:42,login:'octocat',type:'User'},suspended_at:null});}}));
try {
 const response=await mf.dispatchFetch('https://runtime.test/lookup');const body=await response.json();
 assert.equal(body.id,123,JSON.stringify(body));assert.deepEqual(seen,['api.github.com']);
 console.log('PASS: existing-installation lookup executes in workerd with a synthetic key and mocked GitHub.');
 redirect=true;seen.length=0;
 const rejected=await mf.dispatchFetch('https://runtime.test/lookup');const failure=await rejected.json();
 assert.equal(rejected.status,502);assert.equal(failure.kind,'GitHubInstallationLookupFailed');assert.deepEqual(seen,['api.github.com']);
 console.log('PASS: workerd refuses the redirect without forwarding the app JWT.');
} finally {await mf.dispose();}
