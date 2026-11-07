import pathlib,subprocess,tempfile,hashlib,shutil,json
source=pathlib.Path('/tmp/syncmd-live-webhook-pixel-checkout')
def git(root,*args,input=None):
 return subprocess.run(['git','-C',str(root),*args],input=input,capture_output=True,check=True).stdout
assert git(source,'rev-parse','HEAD').strip()==b'353bd21bb14a19dac1441e3b0ef04c45f74e4ffe'
parent=pathlib.Path(tempfile.mkdtemp(prefix='syncmd-lfs-language-',dir='/tmp'));root=parent/'lfs-language-check-20261102';shutil.copytree(source,root)
git(root,'remote','remove','origin')
(root/'.gitattributes').write_text('*.bin filter=lfs diff=lfs merge=lfs -text\n')
git(root,'add','--','.gitattributes')
def add_pointer(name,data,size=None,working=None,cached=False):
 oid=hashlib.sha256(data).hexdigest();size=len(data) if size is None else size
 pointer=f'version https://git-lfs.github.com/spec/v1\noid sha256:{oid}\nsize {size}\n'.encode()
 blob=git(root,'hash-object','-w','--stdin',input=pointer).decode().strip()
 git(root,'update-index','--add','--cacheinfo',f'100644,{blob},{name}')
 (root/name).write_bytes(pointer if working is None else working)
 if cached:
  cache=root/'.git/lfs/objects'/oid[:2]/oid[2:4]/oid;cache.parent.mkdir(parents=True,exist_ok=True);cache.write_bytes(data)
contents=(bytes(range(251))*5000)[:1234567]
add_pointer('available.bin',contents,working=contents,cached=True)
add_pointer('pointer.bin',b'Uncached generated data',size=9223372036854775807)
add_pointer('modified.bin',b'Original generated LFS contents\n',working=b'Generated local draft: keep this text.\n',cached=True)
git(root,'-c','core.hooksPath=/dev/null','-c','commit.gpgSign=false','-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-m','Generated local LFS language fixture')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files={str(p.relative_to(root)):sha(p) for p in root.iterdir() if p.is_file()}
proof={'host':str(root),'phone':'/storage/emulated/0/Android/media/md.gitsync.md/repos/custom/lfs-language-check-20261102','head':git(root,'rev-parse','HEAD').decode().strip(),'expected_files':files,'index_sha256':sha(root/'.git/index'),'config_sha256':sha(root/'.git/config'),'remote_absent':git(root,'remote').strip()==b'','published':False,'cleanup_pending':True}
pathlib.Path('/tmp/syncmd-lfs-language-fixture-proof.json').write_text(json.dumps(proof,indent=2)+'\n');print('Prepared six-file local-only fixture:',root,'with available/cached, missing/pointer and retained-edit states; no remote or publication')
