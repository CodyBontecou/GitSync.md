"""Isolated macOS loopback Git/SSH/LFS fixture for Android transport tests.

Run from the repository root with python3. Metadata is written to
/tmp/syncmd-lfs-device-fixture.json; no real account or repository is used.
SIGTERM stops both servers. The temporary fixture is retained for evidence.
"""
import os,sys,tempfile,subprocess,socket,json,signal,pwd,ssl,threading,http.server,base64,hashlib,shlex,time,re
root=tempfile.mkdtemp(prefix='syncmd-lfs-device-',dir='/private/tmp')
env=dict(os.environ,GIT_CONFIG_GLOBAL='/dev/null',GIT_CONFIG_SYSTEM='/dev/null')
def run(*args): subprocess.run(args,check=True,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
run('/usr/bin/ssh-keygen','-q','-t','ed25519','-N','','-f',root+'/host')
run('/usr/bin/openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1','-keyout',root+'/tls-key.pem','-out',root+'/tls-cert.pem')
remote=root+'/repo.git';source=root+'/source'
run('/usr/bin/git','init','--bare','--initial-branch=main',remote)
run('/usr/bin/git','--git-dir='+remote,'config','http.receivepack','true')
run('/usr/bin/git','init','--initial-branch=main',source)
initial=bytes(i%251 for i in range(16384));oid=hashlib.sha256(initial).hexdigest()
os.mkdir(root+'/objects');open(root+'/objects/'+oid,'wb').write(initial)
http_lfs='--http-lfs' in sys.argv[1:]
scheme='http' if http_lfs else 'https'
foreign_lock='--foreign-lock' in sys.argv[1:]
open(source+'/.gitattributes','w').write('*.bin filter=lfs diff=lfs merge=lfs -text'+(' lockable' if foreign_lock else '')+'\n')
open(source+'/asset.bin','w').write(f'version https://git-lfs.github.com/spec/v1\noid sha256:{oid}\nsize {len(initial)}\n')
run('/usr/bin/git','-C',source,'add','.')
run('/usr/bin/git','-C',source,'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-m','LFS fixture')
run('/usr/bin/git','-C',source,'push',remote,'main')
counts={'uploads':0,'verifies':0,'downloads':0,'gitCredentialsEscaped':0,'sshAuth':[],'customDownloads':0,'customUploads':0,'customGitCredentialsEscaped':0,'injectedUploadDrops':0}
counts.update(lockChecks=0,unlocks=0,creates=0)
active_locks=[{'id':'asset-lock','path':'asset.bin','locked_at':'2026-10-27T12:00:00Z','owner':{'name':'Fixture teammate'}}] if foreign_lock else []
drop_first_upload='--drop-first-upload' in sys.argv[1:]
basic='Basic '+base64.b64encode(b'fixture:fixture-token').decode()
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a): pass
 def event(self,kind,**details):
  from urllib.parse import urlsplit
  # Deliberately omit authorization, query strings, bodies and exception messages.
  entry={'event':kind,'time':time.monotonic(),'connection':self.client_address[1],'method':getattr(self,'command',None),'path':urlsplit(getattr(self,'path','')).path}
  entry.update(details)
  with open(root+'/requests.jsonl','a') as output:output.write(json.dumps(entry)+'\n')
 def finish(self):
  try:super().finish()
  finally:self.event('connection-finished')
 def send_error(self,code,message=None,explain=None):
  self.event('protocol-error',status=code)
  super().send_error(code,message,explain)
 def respond(self,code,data=b'',headers=None):
  self.event('response',status=code,bytes=len(data))
  self.send_response(code)
  for k,v in (headers or {}).items(): self.send_header(k,v)
  self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
 def do_GET(self): self.serve()
 def do_POST(self): self.serve()
 def do_PUT(self): self.serve()
 def serve(self):
  try:
   from urllib.parse import urlsplit
   path=urlsplit(self.path).path;auth=self.headers.get('Authorization')
   policy=self.headers.get('Connection','').lower()
   self.event('request',length=self.headers.get('Content-Length'),protocol=self.request_version,tls=self.connection.version() if isinstance(self.connection,ssl.SSLSocket) else None,sessionReused=getattr(self.connection,'session_reused',False),connectionPolicy=policy if policy in ('close','keep-alive','') else 'other')
   body=self.rfile.read(int(self.headers.get('Content-Length','0')))
   if path=='/fixture/state':
    if auth!=basic:return self.respond(401)
    return self.respond(200,json.dumps(counts).encode(),{'Content-Type':'application/json'})
   if path.startswith('/repo.git/info/lfs/locks'):
    if auth not in (basic,'RemoteAuth fixture-lfs-access'):return self.respond(401)
    request=json.loads(body) if body else {}
    if path.endswith('/verify'):
     counts['lockChecks']+=1
     if active_locks and request.get('cursor')!='page-two': result={'ours':[],'theirs':[],'next_cursor':'page-two'}
     else:result={'ours':[lock for lock in active_locks if lock['owner']['name']=='Fixture connection'],'theirs':[lock for lock in active_locks if lock['owner']['name']!='Fixture connection']}
    elif path.endswith('/unlock'):
     lock_id=path.rsplit('/',2)[1]
     selected=next((lock for lock in active_locks if lock['id']==lock_id),None)
     if selected is None:return self.respond(404)
     if selected['owner']['name']!='Fixture connection' and not request.get('force'):return self.respond(403)
     active_locks.remove(selected);counts['unlocks']+=1;result={'lock':selected}
    elif self.command=='GET':result={'locks':active_locks}
    elif self.command=='POST' and path.endswith('/locks'):
     file=request.get('path')
     if file!='asset.bin':return self.respond(422)
     if any(lock['path']==file for lock in active_locks):return self.respond(409)
     counts['creates']+=1
     selected={'id':'owned-lock-'+str(counts['creates']),'path':file,'locked_at':'2026-10-27T12:00:00Z','owner':{'name':'Fixture connection'}}
     active_locks.append(selected);result={'lock':selected}
    else:return self.respond(404)
    return self.respond(200,json.dumps(result).encode(),{'Content-Type':'application/vnd.git-lfs+json'})
   if path.startswith('/objects/') or path.startswith('/verify/'):
    if auth==basic: counts['gitCredentialsEscaped']+=1
    if auth!='Bearer fixture-object-access': return self.respond(401)
    oid=path.rsplit('/',1)[1]
    if len(oid)!=64 or any(c not in '0123456789abcdef' for c in oid): return self.respond(400)
    file=root+'/objects/'+oid
    if path.startswith('/verify/'):
     request=json.loads(body);data=open(file,'rb').read()
     if hashlib.sha256(data).hexdigest()!=oid or len(data)!=request['size']: return self.respond(422)
     counts['verifies']+=1;return self.respond(200)
    if self.command=='PUT':
     if hashlib.sha256(body).hexdigest()!=oid:return self.respond(422)
     if drop_first_upload and counts['injectedUploadDrops']==0:
      counts['injectedUploadDrops']+=1;self.event('injected-upload-drop');self.close_connection=True
      self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
     open(file,'wb').write(body);counts['uploads']+=1;return self.respond(200)
    counts['downloads']+=1;return self.respond(200,open(file,'rb').read())
   if path in ('/repo.git/info/lfs/objects/batch','/custom-lfs/objects/batch'):
    request=json.loads(body);objects=[]
    if path.startswith('/custom-lfs/'):
     if auth==basic: counts['customGitCredentialsEscaped']+=1
     operation=request['operation']
     expected='Basic '+base64.b64encode(('endpoint-reader:fixture-read-token' if operation=='download' else 'endpoint-writer:fixture-write-token').encode()).decode()
     if auth!=expected:return self.respond(401)
     counts['customDownloads' if operation=='download' else 'customUploads']+=1
    elif auth not in (basic,'RemoteAuth fixture-lfs-access'):return self.respond(401)
    for item in request['objects']:
     oid=item['oid'];result={'oid':oid,'size':item['size']}
     if request['operation']=='download' or not os.path.isfile(root+'/objects/'+oid):
      def action(route):return {'href':f'{scheme}://127.0.0.1:{self.server.server_port}/{route}/{oid}?fixtureSignature=1','header':{'Authorization':'Bearer fixture-object-access'},'expires_in':600}
      result['actions']={request['operation']:action('objects')}
      if request['operation']=='upload':result['actions']['verify']=action('verify')
     objects.append(result)
    return self.respond(200,json.dumps({'transfer':'basic','objects':objects}).encode(),{'Content-Type':'application/vnd.git-lfs+json'})
   if path not in ('/repo.git/info/refs','/repo.git/git-upload-pack','/repo.git/git-receive-pack'):return self.respond(404)
   if auth!=basic:return self.respond(401,headers={'WWW-Authenticate':'Basic realm=fixture'})
   git_env=dict(env,GIT_PROJECT_ROOT=root,GIT_HTTP_EXPORT_ALL='1',PATH_INFO=path,REQUEST_METHOD=self.command,QUERY_STRING=urlsplit(self.path).query,CONTENT_TYPE=self.headers.get('Content-Type',''),CONTENT_LENGTH=str(len(body)),REMOTE_USER='fixture')
   if self.headers.get('Content-Encoding')=='gzip':
    import gzip;body=gzip.decompress(body);git_env['CONTENT_LENGTH']=str(len(body))
   response=subprocess.run(['/usr/bin/git','http-backend'],input=body,env=git_env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True).stdout
   headers,payload=response.split(b'\r\n\r\n',1);out={};code=200
   for line in headers.decode('latin1').split('\r\n'):
    k,v=line.split(':',1)
    if k=='Status':code=int(v.strip().split()[0])
    else:out[k]=v.strip()
   return self.respond(code,payload,out)
  except Exception as failure:
   self.event('handler-error',type=type(failure).__name__)
   self.respond(500)
  finally:open(root+'/counts.json','w').write(json.dumps(counts))
class FixtureServer(http.server.ThreadingHTTPServer):
 def get_request(self):
  try:return super().get_request()
  except OSError as failure:
   # BaseServer normally suppresses accept/handshake failures before a handler exists.
   reason=getattr(failure,'reason','UNKNOWN')
   if not isinstance(reason,str) or not re.fullmatch('[A-Z0-9_]+',reason):reason='OTHER'
   with open(root+'/requests.jsonl','a') as output:output.write(json.dumps({'event':'tls-accept-error','time':time.monotonic(),'type':type(failure).__name__,'reason':reason})+'\n')
   raise
server=FixtureServer(('127.0.0.1',0),Handler)
if not http_lfs:
 tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(root+'/tls-cert.pem',root+'/tls-key.pem');server.socket=tls.wrap_socket(server.socket,server_side=True)
threading.Thread(target=server.serve_forever,daemon=True).start()
s=socket.socket();s.bind(('127.0.0.1',0));sshport=s.getsockname()[1];s.close()
user=pwd.getpwuid(os.getuid()).pw_name
script=root+'/git-command.py'
auth={'href':f'{scheme}://127.0.0.1:{server.server_port}/repo.git/info/lfs','header':{'Authorization':'RemoteAuth fixture-lfs-access'},'expires_in':600}
open(script,'w').write("import os,shlex,json\na=shlex.split(os.environ.get('SSH_ORIGINAL_COMMAND',''))\nif len(a)==3 and a[0]=='git-lfs-authenticate' and a[1]=="+repr(remote.lstrip('/'))+" and a[2] in ('download','upload'):\n with open("+repr(root+'/ssh-lfs-operations')+",'a') as f:f.write(a[2]+'\\n')\n print("+repr(json.dumps(auth))+")\n raise SystemExit(0)\nif len(a)!=2 or a[0] not in ('git-upload-pack','git-receive-pack') or a[1]!="+repr(remote)+":raise SystemExit(1)\ne=dict(os.environ,GIT_CONFIG_GLOBAL='/dev/null',GIT_CONFIG_SYSTEM='/dev/null')\nos.execve('/usr/bin/git',['git',a[0][4:],a[1]],e)\n")
key=os.path.abspath('app/src/androidTest/assets/ssh-fixture/identity.pub')
config=f'ListenAddress 127.0.0.1\nPort {sshport}\nHostKey {root}/host\nPidFile {root}/pid\nAuthorizedKeysFile {key}\nStrictModes no\nUsePAM no\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nPubkeyAuthentication yes\nAllowUsers {user}\nAllowTcpForwarding no\nAllowAgentForwarding no\nX11Forwarding no\nPermitUserRC no\nForceCommand /usr/bin/python3 {script}\n'
open(root+'/config','w').write(config)
sshd=subprocess.Popen(['/usr/sbin/sshd','-D','-e','-f',root+'/config'],stdout=open(root+'/sshd.log','w'),stderr=subprocess.STDOUT)
meta={'pid':os.getpid(),'root':root,'sshPort':sshport,'httpPort':server.server_port,'scheme':scheme,'sshUrl':f'ssh://{user}@127.0.0.1:{sshport}{remote}','httpsUrl':f'{scheme}://127.0.0.1:{server.server_port}/repo.git','hostKey':open(root+'/host.pub').read().split()[1],'cert':base64.b64encode(open(root+'/tls-cert.pem','rb').read()).decode()}
metadata_path=sys.argv[sys.argv.index('--metadata')+1] if '--metadata' in sys.argv else '/tmp/syncmd-lfs-device-fixture.json'
open(metadata_path,'w').write(json.dumps(meta));print(json.dumps({k:v for k,v in meta.items() if k not in ('cert',)}),flush=True)
def stop(*_):
 # Return to the existing Popen.wait instead of recursively acquiring its wait lock.
 sshd.terminate()
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
try:sshd.wait()
finally:server.shutdown();server.server_close()
