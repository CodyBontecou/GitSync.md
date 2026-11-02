#!/usr/bin/env python3
"""Generate native expectations from original PSI literals and unescaped source translations.

This does not read generated locale XML; runtime tests independently check AAPT
escaping, placeholder adaptation, script/legacy-language qualifiers and fallback.
"""
import argparse,json,pathlib,xml.etree.ElementTree as ET
from extract_compose import ROOT,android_text,base_resource_files
from import_ios_catalog import load_overrides,signature
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true')
parser.add_argument('--catalog',type=pathlib.Path,default=ROOT.parent/'gitsync.md/Sync.md/Localizable.xcstrings')
options=parser.parse_args()
base_sources={}
for p in base_resource_files(ROOT):
 for element in ET.parse(p).getroot().findall('string'):
  value=android_text(''.join(element.itertext()))
  base_sources[element.attrib['name']]=value
english={row['name']:row for row in json.loads((ROOT/'tools/localization/english_literals.json').read_text())['strings']}
for row in english.values(): assert base_sources.get(row['name'])==row['source'], 'English source changed; review its independent native expectation: '+row['name']
coverage=json.loads((ROOT/'tools/localization/coverage.json').read_text())
catalog=json.loads(options.catalog.read_text())['strings']
overrides,plural_overrides=load_overrides()
aliases=json.loads((ROOT/'tools/localization/ios_aliases.json').read_text())['strings']
def source(name,key,locale):
 if key=='android_overrides':return overrides[name]['localizations'][locale]
 value=catalog[key]['localizations'][locale]['stringUnit']['value']
 if name in aliases:
  item=aliases[name]
  def part(kind):
   value=item.get(kind,'')
   return value.get(locale,value['default']) if isinstance(value,dict) else value
  value=part('prefix')+value+part('suffix')
 return value
locales={locale:[{'name':name,'source':source(name,key,locale)} for name,key in coverage['mapped'].items()] for locale in coverage['languages']}
additional=[{'name':name,'source':item['source'],'arguments':len(signature(item['source']))} for name,item in overrides.items() if name not in english]
reviewed=json.loads((ROOT/'tools/localization/reviewed_native_sources.json').read_text())
for name,value in reviewed.items():
 assert name not in english and name not in overrides, 'Duplicate independent source expectation: '+name
 assert base_sources.get(name)==value, 'Reviewed native source changed: '+name
 additional.append({'name':name,'source':value,'arguments':len(signature(value))})
known=set(english)|{row['name'] for row in additional}
for rows in locales.values():
 for row in rows:
  assert not signature(row['source']) or row['name'] in known, 'Missing independent argument types: '+row['name']
output={'ios_catalog_sha256':coverage['ios_catalog_sha256'],'english':list(english.values()),'additional_english':additional,'locales':locales,'missing':coverage['missing'],
 'plurals':{'english':{name:item['source'] for name,item in plural_overrides.items()},
 'locales':{locale:{name:item['localizations'][locale] for name,item in plural_overrides.items()} for locale in coverage['languages']}}}
path=ROOT/'app/src/androidTest/assets/localization_expected.json';text=json.dumps(output,ensure_ascii=False,indent=2)+'\n'
if options.check: assert path.read_text()==text, 'Native localization expectations drifted'
else:path.write_text(text)
print('Native source expectations:',len(english),'English strings,',len(locales),'locales')
