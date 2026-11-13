#!/usr/bin/env python3
"""Run the Kotlin PSI inventory, optionally extract its visible copy into Android resources.

Only direct visible String arguments in composable calls are changed. Resource names
and formatter arguments are stable; technical examples and dynamic data stay intact.
"""
import argparse, collections, hashlib, json, os, pathlib, re, shutil, subprocess, tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
ROOT = pathlib.Path(__file__).resolve().parents[2]

def base_resource_files(root):
    """Android permits strings and plurals in any values XML filename."""
    return sorted((root/'app/src/main/res/values').glob('*.xml'))

def android_text(value):
    if len(value) >= 2 and value[0] == value[-1] == '"': value = value[1:-1]
    escapes = {'n':'\n', 'r':'\r', 't':'\t', "'":"'", '"':'"', '\\':'\\', '@':'@', '?':'?'}
    return re.sub(r'\\([nrt\'"\\@?])', lambda m: escapes[m[1]], value)

def xml_text(value):
    value = value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t').replace('"', '\\"').replace("'", "\\'")
    return '"' + escape(value) + '"'

def inventory(output):
    cache = pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
    def jar(group, artifact, version=None):
        matches = sorted((cache/group/artifact).glob((version or '*')+'/*/*.jar'))
        if not matches: raise SystemExit('Missing cached tool dependency: '+artifact)
        return matches[0]
    paths = [jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.0'), jar('org.jetbrains.kotlin','kotlin-stdlib','2.2.0'),
             jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'), jar('com.google.code.gson','gson','2.13.2'),
             jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm')]
    java = pathlib.Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))/'bin'
    with tempfile.TemporaryDirectory(prefix='syncmd-localization-psi-') as directory:
        classpath = os.pathsep.join(map(str,paths))
        subprocess.run([str(java/'javac'),'-cp',classpath,'-d',directory,str(ROOT/'tools/localization/ExtractComposeStrings.java')],check=True)
        with output.open('w') as stream:
            subprocess.run([str(java/'java'),'-cp',directory+os.pathsep+classpath,'ExtractComposeStrings','app/src/main/kotlin'],cwd=ROOT,stdout=stream,check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory',type=pathlib.Path,default=pathlib.Path('/tmp/syncmd-compose-string-inventory.json'))
    parser.add_argument('--apply',action='store_true')
    options=parser.parse_args(); inventory(options.inventory)
    rows=json.loads(options.inventory.read_text()); names={}
    for path in base_resource_files(ROOT):
        for element in ET.parse(path).getroot().findall('string'):
            names.setdefault(android_text(''.join(element.itertext())),element.attrib['name'])
    new={}; changes=collections.defaultdict(list)
    expectations_path=ROOT/'tools/localization/english_literals.json'
    expectations={row['name']:row for row in json.loads(expectations_path.read_text())['strings']} if expectations_path.exists() else {}
    for row in rows:
        text=row['text']; name=names.get(text)
        if not name:
            slug=re.sub('[^a-z0-9]+','_',text.lower()).strip('_')[:42].rstrip('_')
            name='ui_'+(slug or 'text')+'_'+hashlib.sha256(text.encode()).hexdigest()[:8]
            names[text]=name;new[name]=text
        arguments=''.join(', ('+value+').toString()' for value in row['arguments'])
        replacement='androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.'+name+arguments+')'
        changes[ROOT/row['file']].append((row,replacement))
        expectation={'name':name,'source':text,'arguments':len(row['arguments'])}
        assert name not in expectations or all(expectations[name].get(key)==value for key,value in expectation.items()), 'Source fixture changed: '+name
        # Retain independently reviewed original-source and review annotations.
        expectations.setdefault(name,expectation)
    # Validate every range against the original source before changing any file.
    sources={path:path.read_text() for path in changes}
    for path,edits in changes.items():
        for row,_ in edits: assert sources[path][row['start']:row['end']]==row['literal'],str(path)
    print('Visible copy occurrences:',len(rows),'files:',len(changes),'new resources:',len(new))
    if not options.apply: return
    backup=pathlib.Path(tempfile.mkdtemp(prefix='syncmd-localization-before-',dir='/tmp'))
    for path,edits in changes.items():
        relative=path.relative_to(ROOT);saved=backup/relative;saved.parent.mkdir(parents=True,exist_ok=True);saved.write_text(sources[path])
        source=sources[path]
        for row,replacement in sorted(edits,key=lambda edit:edit[0]['start'],reverse=True): source=source[:row['start']]+replacement+source[row['end']:]
        path.write_text(source)
    output=ROOT/'app/src/main/res/values/ui_strings.xml'
    old={e.attrib['name']:android_text(''.join(e.itertext())) for e in ET.parse(output).getroot().findall('string')} if output.exists() else {}
    old.update(new)
    output.write_text('<resources>\n'+''.join('    <string name="'+name+'"'+(' formatted="false"' if not re.search(r'%[0-9]+\$[sdf]',text) else '')+'>'+xml_text(text)+'</string>\n' for name,text in sorted(old.items()))+'</resources>\n')
    expectations_path.write_text(json.dumps({'version':1,'strings':list(expectations.values())},ensure_ascii=False,indent=2)+'\n')
    report={'backup':str(backup),'occurrences':len(rows),'files':len(changes),'new_resources':len(new),'source_sha256':{str(path.relative_to(ROOT)):hashlib.sha256(source.encode()).hexdigest() for path,source in sources.items()}}
    (backup/'manifest.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Preserved pre-edit source:',backup)
if __name__=='__main__': main()
