import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { build } = require('esbuild');
const { Miniflare, convertV4MiniflareOptions } = require('miniflare');
const pair = await webcrypto.subtle.generateKey({ name:'RSASSA-PKCS1-v1_5', modulusLength:2048, publicExponent:new Uint8Array([1,0,1]), hash:'SHA-256' },true,['sign','verify']);
const der = await webcrypto.subtle.exportKey('pkcs8',pair.privateKey);
const pem = '-----BEGIN PRIVATE KEY-----\n'+Buffer.from(der).toString('base64').match(/.{1,64}/g).join('\n')+'\n-----END PRIVATE KEY-----';

const bundled=await build({stdin:{contents:`import {FcmSender} from './src/fcm.ts'; import {collapseID} from './src/index.ts'; export default {async fetch(request,env){try{return Response.json(await new FcmSender(env.ACCOUNT).send({token:'synthetic-token-'.repeat(4),environment:'production',collapseId:await collapseID('acme/notes','main'),title:'Generated fixture',body:'Update',repo:'acme/notes',branch:'main',hint:'fixture'}));}catch(e){return Response.json({kind:e.name,message:e.message},{status:502});}}};`,resolveDir:process.cwd(),sourcefile:'fcm-runtime-harness.ts'},bundle:true,format:'esm',platform:'browser',write:false});
const seen=[];let mode='success';
const mf=new Miniflare(convertV4MiniflareOptions({modules:true,script:bundled.outputFiles[0].text,compatibilityDate:'2026-09-08',host:'127.0.0.1',port:0,
 bindings:{ACCOUNT:JSON.stringify({project_id:'gitsync-android-test',client_email:'push@gitsync-android-test.iam.gserviceaccount.com',private_key:pem})},
 outboundService:async(request)=>{const host=new URL(request.url).hostname;seen.push(host);if((mode==='oauth-redirect'&&host==='oauth2.googleapis.com')||(mode==='fcm-redirect'&&host==='fcm.googleapis.com'))return new Response(null,{status:302,headers:{location:'https://unexpected.example/credentials'}});return new URL(request.url).hostname==='oauth2.googleapis.com'?Response.json({access_token:'synthetic-OAuth-access-token',token_type:'Bearer',expires_in:3600}):Response.json({name:'projects/gitsync-android-test/messages/fixture'});}}));
try {
 const response=await mf.dispatchFetch('https://runtime.test/fcm');const body=await response.json();
 assert.equal(response.status,200,JSON.stringify(body));assert.equal(body.ok,true);
 assert.deepEqual(seen,['oauth2.googleapis.com','fcm.googleapis.com']);
 console.log('PASS: FCM default fetch completes OAuth and delivery in workerd.');
 for(const [scenario,expectedHosts,expectedError] of [
  ['oauth-redirect',['oauth2.googleapis.com'],'FcmAuthorizationFailed'],
  ['fcm-redirect',['oauth2.googleapis.com','fcm.googleapis.com'],'FcmRedirectRejected'],
 ]){
  mode=scenario;seen.length=0;
  const rejected=await mf.dispatchFetch('https://runtime.test/fcm');const failure=await rejected.json();
  assert.equal(rejected.status,502);assert.equal(failure.message,expectedError);assert.deepEqual(seen,expectedHosts);
  console.log('PASS: workerd rejects '+scenario+' without forwarding credentials.');
 }
} finally {await mf.dispose();}
