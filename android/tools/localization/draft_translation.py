"""Protected public-copy MT helpers. Output is draft text, never human-reviewed.

Providers may insert whitespace or fullwidth punctuation around framing markers.
Parse by exact item identity and reject duplicate, missing or shifted markers.
The canonical Apple catalog is read-only; Android owns any resulting overrides.
"""
import re

MARKER = re.compile(r'ZXQITEM(\d{6})QXZ\s*[:：]\s*', re.IGNORECASE)


def scope_identifiers(text):
    """GitHub PAT scope names are identifiers, not translatable prose."""
    if 'user:email' not in text:
        return ()
    return tuple(term for term in ('repo', 'user:email')
                 if re.search(r'(?<![A-Za-z0-9_:])' + re.escape(term) + r'(?![A-Za-z0-9_:])', text))


def validate_scope_identifiers(source, value):
    for term in scope_identifiers(source):
        pattern = r'(?<![A-Za-z0-9_:])' + re.escape(term) + r'(?![A-Za-z0-9_:])'
        if len(re.findall(pattern, source)) != len(re.findall(pattern, value)):
            raise ValueError('GitHub scope identifier changed: ' + term)


def parse_batch(text, expected):
    matches = list(MARKER.finditer(text))
    ids = [int(match[1]) for match in matches]
    if len(ids) != len(expected) or set(ids) != set(expected):
        raise ValueError('Missing, duplicate or unexpected translation markers')
    if text[:matches[0].start()].strip(' \t\n\r\u200e\u200f\u2066\u2067\u2068\u2069'):
        raise ValueError('Prose moved before its translation marker')
    values = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        value = text[match.end():end].strip()
        if not value:
            raise ValueError('Empty draft translation')
        values[int(match[1])] = value
    return values


def mask_text(text, token_pattern, protected_terms):
    replacements = {}

    def token(value):
        key = f'__GSPH{len(replacements):03d}__'
        replacements[key] = value
        return key

    masked = token_pattern.sub(lambda match: token(match.group()), text)
    for term in sorted(set(protected_terms) | set(scope_identifiers(text)), key=len, reverse=True):
        masked = re.sub(r'(?<![A-Za-z0-9_])' + re.escape(term) + r'(?![A-Za-z0-9_])',
                        lambda match: token(match.group()), masked)
    if '\n' in masked:
        masked = masked.replace('\n', token('\n'))
    return masked, replacements


def restore_text(text, masked, replacements):
    text = text.strip()
    for token, value in replacements.items():
        if text.count(token) != masked.count(token):
            raise ValueError('Protected token multiplicity changed')
        text = text.replace(token, value)
    if re.search(r'__GSPH\d+__|ZXQITEM\d+QXZ', text):
        raise ValueError('Unexpected draft marker left in translated prose')
    return text
