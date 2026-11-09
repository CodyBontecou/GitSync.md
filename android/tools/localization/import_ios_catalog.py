#!/usr/bin/env python3
"""Import English-equivalent iOS translations, with exact format-argument validation.

No fuzzy semantic match is used. Unmatched Android text remains explicit in coverage.json
and falls back to English until a reviewed Android-specific translation is supplied.
"""
import argparse, collections, hashlib, json, pathlib, re, xml.etree.ElementTree as ET
from extract_compose import ROOT, android_text, xml_text, base_resource_files
SPEC=re.compile(r'%%|%(?:(\d+)\$)?(lld|ld|@|s|d|f)')
PREVIEW_PATH=pathlib.Path('/tmp/syncmd-localization-import-preview.json')

def translation_loss(previous, current, active_strings, active_plurals, previous_forms, current_forms):
    """Compare identities, not totals: newly translated labels must not hide lost ones."""
    if previous is None: return {}
    lost={}
    strings=sorted((set(previous['mapped']) & set(active_strings)) - set(current['mapped']))
    languages=sorted(set(previous['languages']) - set(current['languages']))
    old_plurals={name for forms in previous_forms.values() for name in forms}
    new_plurals={name for forms in current_forms.values() for name in forms}
    plurals=sorted((old_plurals & set(active_plurals)) - new_plurals)
    forms={}
    for locale in sorted(set(previous_forms) & set(current_forms)):
        for name in sorted(set(previous_forms[locale]) & set(active_plurals) & new_plurals):
            removed=sorted(set(previous_forms[locale][name]) - set(current_forms[locale].get(name, {})))
            if removed: forms.setdefault(locale,{})[name]=removed
    for name,value in [('strings',strings),('languages',languages),('plurals',plurals),('plural_forms',forms)]:
        if value: lost[name]=value
    return lost

def normalized(value):
    value=SPEC.sub(lambda m: '%' if m[0]=='%%' else '{}',value).replace('’',"'").replace('…','...')
    return ' '.join(value.casefold().split())

def signature(value):
    result={};sequence=0
    for match in SPEC.finditer(value):
        if match[0]=='%%': continue
        sequence+=1;position=int(match[1]) if match[1] else sequence
        if position in result and result[position]!=match[2]: raise ValueError('Mixed placeholder types')
        result[position]=match[2]
    return result

def adapt(value, source, target):
    expected=signature(target);original=signature(source)
    if set(original)!=set(expected): raise ValueError('Source argument positions differ')
    sequence=0;positions=[]
    def replacement(match):
        nonlocal sequence
        if match[0]=='%%': return '%%'
        sequence+=1;position=int(match[1]) if match[1] else sequence;positions.append(position)
        if position not in expected: raise ValueError('Translation introduced an argument')
        return '%'+str(position)+'$'+expected[position]
    converted=SPEC.sub(replacement,value)
    if set(positions)!=set(expected): raise ValueError('Translation lost an argument')
    # Every percent in formatted text must be a formatter token or an escaped literal.
    if expected and re.search(r'%(?!%|[0-9]+\$[sdf])',converted.replace('%%','')): raise ValueError('Invalid format percent')
    return converted

def qualifier(locale):
    return {'pt-BR':'pt-rBR','zh-Hans':'b+zh+Hans','zh-Hant':'b+zh+Hant','he':'iw','id':'in'}.get(locale,locale)

def load_overrides():
    strings={};plurals={}
    for name in ['android_overrides.json','android_workflow_overrides.json','android_completion_overrides.json']:
        document=json.loads((ROOT/'tools/localization'/name).read_text())
        for kind,target in [('strings',strings),('plurals',plurals)]:
            rows=document.get(kind,{})
            assert not set(rows)&set(target), 'Duplicate Android translation override'
            target.update(rows)
    return strings,plurals

def checked_translations(item, source, languages):
    if item['source']!=source: raise ValueError('Android translation source changed')
    if set(item['localizations'])!=set(languages): raise ValueError('Android translation locales differ')
    return {locale:adapt(value,source,source) for locale,value in item['localizations'].items()}

def alias_translations(item, source, catalog, languages):
    if item['source']!=source: raise ValueError('Reviewed alias source changed')
    entry=catalog[item['ios_key']]
    english=entry.get('localizations',{}).get('en',{}).get('stringUnit',{}).get('value',item['ios_key'])
    if item['ios_source']!=english: raise ValueError('Reviewed iOS alias source changed')
    def decoration(kind,locale):
        value=item.get(kind,'')
        if isinstance(value,dict):
            if 'default' not in value or not set(value)<=set(languages)|{'default'}: raise ValueError('Invalid alias decoration locales')
            value=value.get(locale,value['default'])
        if not isinstance(value,str) or '%' in value: raise ValueError('Invalid alias decoration')
        return value
    values={}
    for locale in languages:
        unit=entry.get('localizations',{}).get(locale,{}).get('stringUnit',{})
        if unit.get('state')!='translated' or not isinstance(unit.get('value'),str): raise ValueError('Alias is not translated in every locale')
        values[locale]=adapt(decoration('prefix',locale)+unit['value']+decoration('suffix',locale),english,source)
    return values

# Union of categories selected by supported Android ICU versions (Hebrew many exists in CLDR 32).
# Every selected category needs a form (`other` alone would be used silently otherwise).
REQUIRED_PLURAL_CATEGORIES={'ar':{'zero','one','two','few','many','other'},'he':{'one','two','many','other'},
    'pl':{'one','few','many','other'},'ru':{'one','few','many','other'},'uk':{'one','few','many','other'},
    'es':{'one','many','other'},'fr':{'one','many','other'},'it':{'one','many','other'},'pt-BR':{'one','many','other'},
    **{locale:{'other'} for locale in ('id','ja','ko','th','vi','zh-Hans','zh-Hant')}}

def checked_plurals(item, source, languages):
    if item['source']!=source: raise ValueError('Android plural source changed')
    if set(item['localizations'])!=set(languages): raise ValueError('Android plural locales differ')
    values={}
    for locale,forms in item['localizations'].items():
        if 'other' not in forms or not set(forms)<={'zero','one','two','few','many','other'}: raise ValueError('Invalid plural categories')
        missing=REQUIRED_PLURAL_CATEGORIES.get(locale,{'one','other'})-set(forms)
        if missing: raise ValueError(f'Plural {locale} lacks ICU categories {sorted(missing)}')
        values[locale]={quantity:adapt(value,source.get(quantity,source['other']),source.get(quantity,source['other'])) for quantity,value in forms.items()}
    return values

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--require-complete',action='store_true',help='Fail if any translatable string or plural resource lacks every locale.')
    parser.add_argument('--catalog',type=pathlib.Path,default=ROOT.parent/'Sync.md/Localizable.xcstrings')
    parser.add_argument('--allow-translation-loss',action='store_true',
        help='With --apply only: allow an intentional loss of translated labels, locales or plural forms after inspecting the preview.')
    mode=parser.add_mutually_exclusive_group();mode.add_argument('--apply',action='store_true');mode.add_argument('--check',action='store_true');options=parser.parse_args()
    if options.allow_translation_loss and not options.apply:
        parser.error('--allow-translation-loss requires --apply; preview without it to inspect losses first.')
    raw=options.catalog.read_bytes();catalog=json.loads(raw)['strings']
    languages=sorted({locale for entry in catalog.values() for locale in entry.get('localizations',{}) if locale!='en'})
    index=collections.defaultdict(list)
    for key,entry in catalog.items():
        english=entry.get('localizations',{}).get('en',{}).get('stringUnit',{}).get('value',key)
        for text in {key,english}: index[normalized(text)].append((key,english,entry))
    base={};plural_base={}
    for path in base_resource_files(ROOT):
        for element in ET.parse(path).getroot().findall('string'):
            if element.attrib.get('translatable')=='false' or element.attrib['name']=='app_name': continue
            name=element.attrib['name'];assert name not in base,name
            base[name]=android_text(''.join(element.itertext()))
        for element in ET.parse(path).getroot().findall('plurals'):
            name=element.attrib['name'];assert name not in plural_base,name
            plural_base[name]={item.attrib['quantity']:android_text(''.join(item.itertext())) for item in element.findall('item')}
    translations={locale:{} for locale in languages};mapped={};rejected={};missing=[]
    overrides,plural_overrides=load_overrides()
    aliases=json.loads((ROOT/'tools/localization/ios_aliases.json').read_text())['strings']
    assert set(overrides) <= set(base), 'An Android translation resource was removed'
    assert set(aliases)<=set(base) and not set(aliases)&set(overrides), 'Reviewed alias removed or overridden'
    assert set(plural_overrides)<=set(plural_base), 'An Android plural resource was removed'
    for name,text in sorted(base.items()):
        if name in overrides:
            for locale,value in checked_translations(overrides[name],text,languages).items(): translations[locale][name]=value
            mapped[name]='android_overrides';continue
        if name in aliases:
            for locale,value in alias_translations(aliases[name],text,catalog,languages).items(): translations[locale][name]=value
            mapped[name]=aliases[name]['ios_key'];continue
        choices=sorted(index.get(normalized(text),[]),key=lambda row:(row[1]!=text,row[0]!=text,row[0]))
        imported=None;reason=None
        for key,english,entry in choices:
            try:
                values={}
                for locale in languages:
                    unit=entry.get('localizations',{}).get(locale,{}).get('stringUnit',{})
                    if unit.get('state')!='translated' or not isinstance(unit.get('value'),str): raise ValueError('Not translated in every iOS locale')
                    values[locale]=adapt(unit['value'],english,text)
                imported=(key,values);break
            except ValueError as error: reason=str(error)
        if imported:
            key,values=imported;mapped[name]=key
            for locale,value in values.items(): translations[locale][name]=value
        else:
            missing.append(name)
            if reason:rejected[name]=reason
    plural_translations={locale:{} for locale in languages}
    for name,item in plural_overrides.items():
        for locale,forms in checked_plurals(item,plural_base[name],languages).items(): plural_translations[locale][name]=forms
    report={'ios_catalog_sha256':hashlib.sha256(raw).hexdigest(),'languages':languages,'base_strings':len(base),'translated_per_language':len(mapped),
        'mapped':mapped,'missing':missing,'rejected':rejected,'reviewed_aliases':{name:item['ios_key'] for name,item in aliases.items()},
        'translated_plurals_per_language':len(plural_overrides),'unmatched_plurals':sorted(set(plural_base)-set(plural_overrides))}
    print('Languages:',len(languages),'mapped Android strings:',len(mapped),'of',len(base),'rejected candidates:',len(rejected))
    if options.require_complete and (missing or report['unmatched_plurals']):
        parser.error(f"Incomplete localization: {len(missing)} strings and {len(report['unmatched_plurals'])} plurals lack complete locale definitions.")
    previous=None;previous_forms={}
    coverage_path=ROOT/'tools/localization/coverage.json'
    if coverage_path.exists():
        try:
            previous=json.loads(coverage_path.read_text())
            if not isinstance(previous['mapped'],dict) or not isinstance(previous['languages'],list):
                raise ValueError('mapped must be an object and languages must be a list')
            for locale in previous['languages']:
                path=ROOT/('app/src/main/res/values-'+qualifier(locale))/'ios_catalog_strings.xml'
                if path.exists():
                    previous_forms[locale]={element.attrib['name']:{item.attrib['quantity'] for item in element.findall('item')}
                        for element in ET.parse(path).getroot().findall('plurals')}
        except (ValueError,KeyError,TypeError,ET.ParseError) as error:
            parser.error('Cannot read the previous localization baseline: '+str(error))
    loss=translation_loss(previous,report,base,plural_base,previous_forms,plural_translations)
    if not options.apply and not options.check:
        preview=dict(report)
        if loss: preview['translation_loss']=loss
        PREVIEW_PATH.write_text(json.dumps(preview,indent=2,ensure_ascii=False)+'\n');return
    if loss and not options.allow_translation_loss:
        parser.error('Translation loss would overwrite existing coverage: '+json.dumps(loss,ensure_ascii=False,sort_keys=True)+
            '. Preserve these translations, or inspect the preview and use --apply --allow-translation-loss for an intentional removal. No generated files were changed.')
    def emit(path,text):
        if options.check: assert path.exists() and path.read_text()==text, 'Generated localization drift: '+str(path.relative_to(ROOT))
        else: path.parent.mkdir(exist_ok=True);path.write_text(text)
    for locale,entries in translations.items():
        path=ROOT/('app/src/main/res/values-'+qualifier(locale))/'ios_catalog_strings.xml'
        emit(path,'<!-- Generated from the canonical Apple catalog and Android overrides by tools/localization/import_ios_catalog.py. -->\n<resources>\n'+
            ''.join('    <string name="'+name+'"'+(' formatted="false"' if not signature(base[name]) else '')+'>'+xml_text(value)+'</string>\n' for name,value in sorted(entries.items()))+
            ''.join('    <plurals name="'+name+'">\n'+''.join('        <item quantity="'+quantity+'">'+xml_text(value)+'</item>\n' for quantity,value in forms.items())+'    </plurals>\n' for name,forms in sorted(plural_translations[locale].items()))+'</resources>\n')
    emit(ROOT/'tools/localization/coverage.json',json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    config=ROOT/'app/src/main/res/xml/locales_config.xml'
    emit(config,'<?xml version="1.0" encoding="utf-8"?>\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n'+
        ''.join('    <locale android:name="'+locale+'" />\n' for locale in ['en']+languages)+'</locale-config>\n')
if __name__=='__main__':main()
