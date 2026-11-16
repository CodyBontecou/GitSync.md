import hashlib,json,threading,time,select,socket,os
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
DATA=b'PR44 synthetic LFS object\n'*128
OID=hashlib.sha256(DATA).hexdigest()
LOG=Path(os.environ.get('PR44_LFS_LEDGER','/tmp/pr44-lfs-server-ledger.jsonl'))
RELEASE=threading.Event()
def log(event,**kw):
 with LOG.open('a') as f:f.write(json.dumps({'time':time.time(),'event':event,**kw})+'\n')
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def respond(self,code,data,typ='application/vnd.git-lfs+json'):
  self.send_response(code);self.send_header('Content-Type',typ);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_POST(self):
  if self.path=='/control/release' and self.client_address[0]=='127.0.0.1':
   RELEASE.set();log('release');self.respond(200,b'{}');return
  if self.path!='/info/lfs/objects/batch':self.respond(404,b'{}');return
  body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
  if body.get('operation')!='download' or body.get('objects')!=[{'oid':OID,'size':len(DATA)}]:self.respond(400,b'{}');return
  log('batch',oid=OID,size=len(DATA))
  self.respond(200,json.dumps({'transfer':'basic','objects':[{'oid':OID,'size':len(DATA),'actions':{'download':{'href':'http://192.168.1.252:8773/objects/'+OID}}}]}).encode())
 def do_GET(self):
  if self.path!='/objects/'+OID:self.respond(404,b'{}');return
  log('download_started',oid=OID)
  try:
   self.send_response(200);self.send_header('Content-Length',str(len(DATA)));self.end_headers();self.wfile.write(DATA[:1]);self.wfile.flush()
   deadline=time.monotonic()+180
   while not RELEASE.wait(.1):
    if time.monotonic()>deadline:log('hold_deadline');return
    if select.select([self.connection],[],[],0)[0] and not self.connection.recv(1,socket.MSG_PEEK):log('client_disconnected');return
   self.wfile.write(DATA[1:]);self.wfile.flush();log('download_sent',oid=OID)
  except (BrokenPipeError,ConnectionResetError):log('client_disconnected')
if __name__=='__main__':
 log('server_started',oid=OID,size=len(DATA));ThreadingHTTPServer(('0.0.0.0',8773),Handler).serve_forever()
