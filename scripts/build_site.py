#!/usr/bin/env python3
"""Stage the static site with local styles inlined to avoid render-blocking trips."""
import argparse
from pathlib import Path
import re
import shutil
from urllib.parse import urlsplit
if __package__:
    from .site_markdown import to_markdown
else:
    from site_markdown import to_markdown


def build(source, destination):
    source, destination = source.resolve(), destination.resolve()
    if destination == source or source in destination.parents:
        raise ValueError('Build output must be outside the source site.')
    # Never remove an existing directory: callers choose a fresh staging path.
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns(
        '.DS_Store', '.gitignore', '.vercel'))
    for page in destination.rglob('*.html'):
        source_html = page.read_text()
        canonical = re.search(r'<link\b[^>]*rel="canonical"[^>]*href="([^"]+)"', source_html)
        if not canonical:
            raise ValueError(f'Page has no canonical URL: {page}')
        page.with_suffix('.md').write_text(to_markdown(source_html, canonical[1]))
        def inline(match):
            tag = match.group(0)
            if not re.search(r'\brel=[\'"]stylesheet[\'"]', tag):
                return tag
            href = re.search(r'\bhref=[\'"]([^\'"]+)[\'"]', tag)
            if not href:
                return tag
            url = urlsplit(href[1])
            if url.scheme or url.netloc:
                return tag
            css = ((destination / url.path.lstrip('/')) if url.path.startswith('/')
                   else (page.parent / url.path)).resolve()
            if destination not in css.parents:
                raise ValueError(f'Stylesheet escapes site root: {href[1]}')
            content = css.read_text()
            # Current stylesheets are self-contained. Fail if future CSS would
            # change meaning when moved from its stylesheet URL into the HTML.
            if re.search(r'url\s*\(|@import|</style', content, re.I):
                raise ValueError(f'Stylesheet requires URL rebasing: {css}')
            return '<style>\n' + content + '\n</style>'
        page.write_text(re.sub(r'<link\b[^>]*>', inline, source_html))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='New staging directory')
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1] / 'site')
    args = parser.parse_args()
    build(args.source, args.output)
    print(f'Built site in {args.output}')
