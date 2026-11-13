#!/usr/bin/env python3
"""Resume Android-only machine draft overrides; human linguistic review is pending.

Optional dependency: translators==6.0.4 in a temporary Python environment.
Only this explicit authoring command uses the network. Import/check/build are offline.
Existing translations and the canonical Apple catalog are never overwritten.
"""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import re
import threading

from draft_translation import mask_text, parse_batch, restore_text
from extract_compose import ROOT
from import_ios_catalog import REQUIRED_PLURAL_CATEGORIES, signature


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=1)
    parser.add_argument('--locale', action='append')
    parser.add_argument('--cache', type=Path, default=ROOT / 'tools/localization/.machine-draft-cache.json')
    args = parser.parse_args()
    import translators

    spec = importlib.util.spec_from_file_location('apple_mt_helpers', ROOT.parent / 'scripts/localization/translate_catalog.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    terms = helper.PROTECTED_TERMS + ['Google Play', 'Android', 'Material 3', 'Jetpack Compose', 'JGit',
                                     'Bitbucket', 'GitLab', 'Gitea', 'GitSync.md Connect', 'Full Access',
                                     'README.md', 'MERGE_HEAD', 'HEAD', 'UTF-8']
    path = ROOT / 'tools/localization/android_completion_overrides.json'
    data = json.loads(path.read_text())
    languages = json.loads((ROOT / 'tools/localization/coverage.json').read_text())['languages']
    locales = args.locale or languages
    if not set(locales) <= set(languages):
        parser.error('Unsupported locale')
    cache = json.loads(args.cache.read_text()) if args.cache.exists() else {}
    lock = threading.Lock()

    def write_checkpoint():
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(path)
        args.cache.write_text(json.dumps(cache, ensure_ascii=False) + '\n')

    def request(text, target):
        if len(text) > 1000:
            raise ValueError('Public provider request exceeds its 1000-character limit')
        target = {'iw': 'he', 'zh-CN': 'zh-Hans', 'zh-TW': 'zh-Hant', 'no': 'nb'}.get(target, target)
        value = translators.translate_text(text, translator='bing', from_language='en', to_language=target,
                                           http_client='httpx', timeout=20)
        if any(re.fullmatch(r'[a-z0-9]{20}', word) and len(re.findall(r'[0-9]', word)) >= 2
               for word in value.split()):
            raise RuntimeError('Provider returned a challenge response, not draft prose')
        return value

    def framed(items, target):
        if len(items) == 1:
            index, text = items[0]
            return {index: request(text, target)}
        text = '\n'.join(f'ZXQITEM{index:06d}QXZ: {source}' for index, source in items)
        translated = request(text, target)
        try:
            return parse_batch(translated, {index for index, _ in items})
        except ValueError:
            middle = len(items) // 2
            return {**framed(items[:middle], target), **framed(items[middle:], target)}

    def translate(items, locale):
        result, pending, masks, aliases, unique = {}, [], {}, {}, {}
        for index, source in items:
            if source in unique:
                aliases[index] = unique[source]
                continue
            unique[source] = index
            key = locale + '\0' + source
            if key in cache:
                result[index] = cache[key]
                continue
            masked, tokens = mask_text(source, helper.TOKEN_PATTERN, terms)
            if not re.search('[A-Za-z]{2}', re.sub(r'__GSPH\d+__', '', masked)):
                result[index] = source
                continue
            masks[index] = masked, tokens, source
            pending.append((index, masked))
        for batch in helper.batches(pending, 800):
            values = framed(batch, helper.RUNTIME_LOCALES[locale])
            for index, text in values.items():
                masked, tokens, source = masks[index]
                # Refuse to accept dropped or duplicated syntax; leave the cell missing.
                result[index] = restore_text(text, masked, tokens)
                if signature(result[index]) != signature(source):
                    raise ValueError(f'Formatter contract changed: {locale}, item {index}')
                with lock:
                    cache[locale + '\0' + source] = result[index]
        for index, original in aliases.items():
            result[index] = result[original]
        return result

    def fill(locale):
        jobs, targets = [], []
        for name, row in data['strings'].items():
            if locale not in row['localizations']:
                targets.append(('strings', name, None))
                jobs.append((len(jobs), row['source']))
        for name, row in data['plurals'].items():
            required = REQUIRED_PLURAL_CATEGORIES.get(locale, {'one', 'other'})
            for category in sorted(required - set(row['localizations'].get(locale, {}))):
                targets.append(('plurals', name, category))
                jobs.append((len(jobs), row['source'].get(category, row['source']['other'])))
        for start in range(0, len(jobs), 50):
            values = translate(jobs[start:start + 50], locale)
            with lock:
                for index, value in values.items():
                    kind, name, category = targets[index]
                    row = data[kind][name]
                    if kind == 'strings':
                        row['localizations'][locale] = value
                    else:
                        row['localizations'].setdefault(locale, {})[category] = value
                    row.setdefault('generation', {})[locale] = 'machine-generated-draft; human review pending'
                write_checkpoint()
            print(locale, min(start + 50, len(jobs)), '/', len(jobs), flush=True)
        return locale

    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for future in concurrent.futures.as_completed([pool.submit(fill, locale) for locale in locales]):
            try:
                print('Completed', future.result(), flush=True)
            except Exception as error:
                errors.append(error)
                print('Failed:', type(error).__name__, str(error), flush=True)
    if errors:
        raise SystemExit('Draft generation incomplete; checkpoints retained and no checks bypassed')


if __name__ == '__main__':
    main()
