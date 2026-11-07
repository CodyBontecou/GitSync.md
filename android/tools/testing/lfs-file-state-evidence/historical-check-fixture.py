import pathlib,subprocess,tempfile,json,hashlib,datetime,sys
p=pathlib.Path('/tmp/syncmd-lfs-language-fixture-proof.json');d=json.loads(p.read_text());adb='/Users/codybontecou/Library/Android/sdk/platform-tools/adb';serial='2C061FDH200CJN';phone=d['phone'];assert phone=='/storage/emulated/0/Android/media/md.gitsync.md/repos/custom/lfs-language-check-20261102'
def run(*a):return subprocess.run([adb,'-s',serial,*a],capture_output=True,text=True,timeout=45)
assert run('get-state').stdout.strip()=='device'
assert run('shell','test','-L',phone).returncode==1
root=pathlib.Path(tempfile.mkdtemp(prefix='syncmd-lfs-language-readback-',dir='/tmp'))/'fixture';r=run('pull',phone,str(root));assert r.returncode==0,r.stderr
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
def working(path):return {str(f.relative_to(path)):sha(f) for f in path.rglob('*') if f.is_file() and '.git' not in f.relative_to(path).parts}
assert working(root)==d['expected_files'],'Generated working files changed'
assert sha(root/'.git/index')==d['index_sha256'],'Staged bytes changed'
assert sha(root/'.git/config')==d['config_sha256'],'Repository configuration changed'
assert (root/'.git/refs/heads/main').read_text().strip()==d['head'],'HEAD changed'
host=pathlib.Path(d['host'])
def cache(path):return {str(f.relative_to(path/'.git/lfs/objects')):sha(f) for f in (path/'.git/lfs/objects').rglob('*') if f.is_file()}
before,after=cache(host),cache(root)
assert all(after.get(name)==value for name,value in before.items()),'Original cached object changed or disappeared'
draft=d['expected_files']['modified.bin'];draft_path=f'{draft[:2]}/{draft[2:4]}/{draft}'
assert set(after)-set(before) <= {draft_path},'Unexpected new cached object'
if draft_path in after:
 assert (root/'.git/lfs/objects'/draft_path).read_bytes()==(root/'modified.bin').read_bytes(),'Added cache does not match the generated draft'
proof={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'readback':str(root),'working_files':len(d['expected_files']),'cached_objects':len(cache(root)),'head':d['head'],'exact_working_index_config_preserved':True,'original_cached_objects_preserved':True,'added_generated_draft_cache':draft_path in after and draft_path not in before,'remote_absent':d['remote_absent']}
if len(sys.argv)>1 and sys.argv[1]=='--remove':
 assert run('shell','cat',phone+'/.git/refs/heads/main').stdout.strip()==d['head']
 r=run('shell','rm','-rf','--',phone);assert r.returncode==0,r.stderr
 assert run('shell','test','-e',phone).returncode==1
 proof['owned_checkout_removed']=True;d['cleanup_pending']=False;p.write_text(json.dumps(d,indent=2)+'\n')
pathlib.Path('/tmp/syncmd-lfs-language-final-readback-proof.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(proof,indent=2))
