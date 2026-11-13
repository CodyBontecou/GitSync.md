#!/usr/bin/env python3
"""Audit actual locale XML coverage and formatter/plural contracts without a device.

This proves resource structure, not linguistic quality or Android runtime rendering.
"""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from extract_compose import ROOT, android_text
from import_ios_catalog import signature, qualifier, REQUIRED_PLURAL_CATEGORIES
from draft_translation import validate_scope_identifiers


def resources(directory):
    strings, plurals = {}, {}
    for path in sorted(directory.glob('*.xml')):
        for element in ET.parse(path).getroot():
            name = element.attrib.get('name')
            if element.tag not in ('string', 'plurals') or element.attrib.get('translatable') == 'false' or name == 'app_name':
                continue
            target = strings if element.tag == 'string' else plurals
            if name in target:
                raise ValueError(f'Duplicate resource {name} in {directory}')
            target[name] = (android_text(''.join(element.itertext())) if element.tag == 'string' else
                            {item.attrib['quantity']: android_text(''.join(item.itertext())) for item in element.findall('item')})
    return strings, plurals


def translation_errors(source, value):
    problems = []
    try:
        validate_scope_identifiers(source, value)
    except ValueError as error:
        problems.append(str(error))
    if source.strip() and not value.strip():
        problems.append('empty translation')
    if re.search(r'__GSPH\d+__|ZXQITEM\d+QXZ', value):
        problems.append('unresolved draft marker')
    if value != source and any(re.fullmatch(r'[a-z0-9]{20}', word) and len(re.findall(r'[0-9]', word)) >= 2 for word in value.split()):
        problems.append('provider challenge response')
    if signature(value) != signature(source):
        problems.append('formatter signature')
    return problems


def audit(root=ROOT):
    res = root / 'app/src/main/res'
    base, plural_base = resources(res / 'values')
    coverage = json.loads((root / 'tools/localization/coverage.json').read_text())
    result = {'base_strings': len(base), 'base_plurals': len(plural_base), 'locales': {}}
    for locale in coverage['languages']:
        strings, plurals = resources(res / ('values-' + qualifier(locale)))
        missing = sorted(set(base) - set(strings))
        missing_plurals = sorted(set(plural_base) - set(plurals))
        problems = []
        for name in set(base) & set(strings):
            problems.extend(f'{name}: {issue}' for issue in translation_errors(base[name], strings[name]))
        for name in set(plural_base) & set(plurals):
            required = REQUIRED_PLURAL_CATEGORIES.get(locale, {'one', 'other'})
            if not required <= set(plurals[name]):
                problems.append(f'{name}: missing ICU category')
            for category, value in plurals[name].items():
                source = plural_base[name].get(category, plural_base[name]['other'])
                problems.extend(f'{name}/{category}: {issue}' for issue in translation_errors(source, value))
        result['locales'][locale] = {'missing_strings': missing, 'missing_plurals': missing_plurals,
                                     'contract_errors': sorted(problems)}
    return result


if __name__ == '__main__':
    result = audit()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if any(row['missing_strings'] or row['missing_plurals'] or row['contract_errors'] for row in result['locales'].values()):
        raise SystemExit(1)
