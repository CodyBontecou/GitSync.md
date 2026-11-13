"""Fails when app Kotlin introduces English copy in a user-facing position.

Positions checked: Compose Text/labels, named copy parameters, UI error/notice
state assignments and app-authored exception messages. Each remaining match was
reviewed (previews, Git/commit data, product names, caught-and-replaced
diagnostics) and is pinned by file and text in literal_guard_baseline.json.

    python3 tools/localization/literal_guard.py            # check
    python3 tools/localization/literal_guard.py --update   # re-pin after review
"""
import argparse, collections, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'app/src/main/kotlin'
BASELINE = pathlib.Path(__file__).with_name('literal_guard_baseline.json')
LITERAL = r'"((?:[^"\\\n]|\\.)*[A-Za-z]{2,}(?:[^"\\\n]|\\.)*)"'
POSITIONS = [
    re.compile(r'\bText\(\s*' + LITERAL),
    re.compile(r'\b(?:title|subtitle|label|text|contentDescription|placeholder|message|error|warning|notice|body|'
               r'confirmText|confirmLabel|dismissLabel|backLabel|actionLabel|supportingText|description)\s*[=:]\s*(?:String\s*=\s*)?' + LITERAL),
    re.compile(r'\b\w*(?:Exception|Error)\(\s*' + LITERAL),
    re.compile(r'\b(?:require|requireNotNull|check|checkNotNull|error)\b[^{\n]*\{\s*' + LITERAL),
    re.compile(r'\berror\(\s*' + LITERAL),
    re.compile(r'\b_?\w*(?:[Ee]rror|[Nn]otice|[Mm]essage|[Ww]arning)\w*\.value\s*=\s*' + LITERAL),
]
IGNORED_LINE = re.compile(r'^\s*(?://|\*|/\*\*|import |package )|DebugLogger|Log\.[dewiv]\(|@Preview')

def occurrences():
    found = collections.Counter()
    for path in sorted(SOURCES.rglob('*.kt')):
        relative = str(path.relative_to(ROOT))
        for line in path.read_text(encoding='utf-8').splitlines():
            if IGNORED_LINE.search(line): continue
            for pattern in POSITIONS:
                for match in pattern.finditer(line):
                    text = match.group(1)
                    if re.fullmatch(r'[a-z0-9_.:/-]+', text): continue  # keys, ids, codes
                    found[(relative, text)] += 1
    return found

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--update', action='store_true')
    options = parser.parse_args()
    current = occurrences()
    if options.update:
        rows = [{'file': f, 'text': t, 'count': n} for (f, t), n in sorted(current.items())]
        BASELINE.write_text(json.dumps({'reviewed': rows}, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
        print('Pinned', len(rows), 'reviewed literals'); return 0
    pinned = {(r['file'], r['text']): r['count'] for r in json.loads(BASELINE.read_text(encoding='utf-8'))['reviewed']}
    new = [(f, t, n - pinned.get((f, t), 0)) for (f, t), n in sorted(current.items()) if n > pinned.get((f, t), 0)]
    for f, t, n in new: print(f'{f}: unlocalized English literal ({n}x): "{t}"')
    print('Literal guard:', len(new), 'new', '/', len(pinned), 'reviewed')
    return 1 if new else 0

if __name__ == '__main__': sys.exit(main())
