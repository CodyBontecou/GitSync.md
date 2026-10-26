#!/usr/bin/env python3
"""Check static-site SEO and links; optionally verify an uploaded Pages deployment."""
import argparse
from collections import Counter
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urljoin, urlsplit
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ORIGIN = 'https://gitsync.md'
NS = {'s': 'http://www.sitemaps.org/schemas/sitemap/0.9'}


class Page(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.ids, self.references, self.anchors = [], [], []
        self.canonical, self.meta, self.title, self.h1 = [], {}, '', 0
        self.in_title = False
        self.schema = []
        self.feed(source)
        for raw in re.findall(r'<script\b[^>]*type=[\'"]application/ld\+json[\'"][^>]*>(.*?)</script>', source, re.S | re.I):
            self.schema.append(json.loads(raw))

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if 'id' in a:
            self.ids.append(a['id'])
        if tag == 'h1':
            self.h1 += 1
        if tag == 'title':
            self.in_title = True
        if tag == 'link' and a.get('rel') == 'canonical':
            self.canonical.append(a.get('href', ''))
        if tag == 'meta':
            key = a.get('name', a.get('property'))
            if key:
                self.meta.setdefault(key, []).append(a.get('content', ''))
                if key in ('og:image', 'twitter:image') and a.get('content'):
                    self.references.append(a['content'])
        for key in ('href', 'src', 'poster'):
            if a.get(key):
                self.references.append(a[key])
        if tag == 'a' and a.get('href'):
            self.anchors.append(a['href'])

    def handle_endtag(self, tag):
        if tag == 'title':
            self.in_title = False

    def handle_data(self, value):
        if self.in_title:
            self.title += value

    @property
    def indexable(self):
        return not any('noindex' in value.lower() for value in self.meta.get('robots', []))


def public_path(file, site):
    path = '/' + file.relative_to(site).as_posix()
    return path.removesuffix('index.html') if path.endswith('/index.html') else path.removesuffix('.html')


def resolve(path, site):
    candidate = (site / unquote(path).lstrip('/')).resolve()
    if not candidate.is_relative_to(site):
        return None
    # Pages serves slug.html even when slug/ also contains article images.
    if not path.endswith('/') and Path(str(candidate) + '.html').is_file():
        return Path(str(candidate) + '.html')
    return candidate / 'index.html' if candidate.is_dir() else candidate



def normalized_html(content):
    """Allow only Cloudflare's managed beacon injection in otherwise exact HTML."""
    beacon = rb'<script\b(?=[^>]*\bsrc=["\']https://static\.cloudflareinsights\.com/beacon\.min\.js(?:/[a-zA-Z0-9_-]+)?["\'])[^>]*>\s*</script>\s*'
    return re.sub(beacon, b'', content, flags=re.I)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, default=Path(__file__).resolve().parents[1] / 'site')
    parser.add_argument('--live-base-url', help='Optional HTTPS deployment origin to verify against local files')
    args = parser.parse_args()
    site = args.site.resolve()
    errors, pages = [], {}
    for file in sorted(site.rglob('*.html')):
        try:
            page = Page(file.read_text())
        except (ValueError, OSError) as exc:
            errors.append(f'{file.name}: cannot parse page: {exc}')
            continue
        pages[file] = page
        expected = ORIGIN + public_path(file, site)
        if page.canonical != [expected]:
            errors.append(f'{file.name}: canonical must be {expected}')
        if not page.title.strip() or page.h1 != 1:
            errors.append(f'{file.name}: needs a title and exactly one H1')
        for key in ('description', 'og:title', 'og:description', 'og:url', 'og:image', 'twitter:card'):
            values = page.meta.get(key, [])
            if len(values) != 1 or not values[0].strip():
                errors.append(f'{file.name}: missing/duplicate {key}')
        if page.meta.get('og:url') != [expected]:
            errors.append(f'{file.name}: og:url must match canonical')
        if len(page.ids) != len(set(page.ids)):
            errors.append(f'{file.name}: duplicate element IDs')
        for obj in page.schema:
            if isinstance(obj, dict) and obj.get('@type') in ('BlogPosting', 'Article'):
                if obj.get('mainEntityOfPage') != expected:
                    errors.append(f'{file.name}: article schema identity must match canonical')
    for file, page in pages.items():
        base = ORIGIN + public_path(file, site)
        for raw in page.references:
            u = urlsplit(urljoin(base, raw))
            if u.netloc != urlsplit(ORIGIN).netloc or u.path.startswith('/v/'):
                continue
            target = resolve(u.path, site)
            if target is None or not target.is_file():
                errors.append(f'{file.name}: missing local reference {raw}')
            elif u.fragment and target in pages and unquote(u.fragment) not in pages[target].ids:
                errors.append(f'{file.name}: missing anchor {raw}')
    for file in site.rglob('*.css'):
        for raw in re.findall(r'url\([\'"]?([^\)\'"\s]+)', file.read_text()):
            u = urlsplit(urljoin(ORIGIN + '/' + file.relative_to(site).as_posix(), raw))
            if u.netloc == urlsplit(ORIGIN).netloc:
                target = resolve(u.path, site)
                if target is None or not target.is_file():
                    errors.append(f'{file.name}: missing CSS asset {raw}')
    try:
        urls = [node.text for node in ET.parse(site / 'sitemap.xml').findall('.//s:loc', NS)]
    except (ET.ParseError, OSError) as exc:
        errors.append(f'sitemap.xml: {exc}')
        urls = []
    canonicals = [page.canonical[0] for page in pages.values() if page.indexable and len(page.canonical) == 1]
    if Counter(urls) != Counter(canonicals):
        errors.append('sitemap URLs must match indexable canonical pages exactly, without duplicates')
    if f'Sitemap: {ORIGIN}/sitemap.xml' not in (site / 'robots.txt').read_text():
        errors.append('robots.txt must declare the canonical sitemap')
    not_found = pages.get(site / '404.html')
    if not_found is None or not_found.indexable:
        errors.append('404.html must exist and declare noindex')
    for title, count in Counter(p.title for p in pages.values() if p.indexable).items():
        if count > 1:
            errors.append(f'duplicate indexable title: {title}')
    # Ensure newly added pages are reachable through real navigation, not just schema/sitemap.
    visited, queue = set(), [site / 'index.html']
    while queue:
        file = queue.pop()
        if file in visited or file not in pages:
            continue
        visited.add(file)
        for raw in pages[file].anchors:
            u = urlsplit(urljoin(ORIGIN + public_path(file, site), raw))
            if u.netloc == urlsplit(ORIGIN).netloc:
                target = resolve(u.path, site)
                if target in pages and target not in visited:
                    queue.append(target)
    for file, page in pages.items():
        if page.indexable and file not in visited:
            errors.append(f'{file.name}: orphaned indexable page')
    if not errors and args.live_base_url:
        live = args.live_base_url.rstrip('/')
        u = urlsplit(live)
        if u.scheme != 'https' or not u.netloc or u.path:
            errors.append('--live-base-url must be an HTTPS origin')
        else:
            def fetch(path):
                try:
                    with urlopen(Request(live + path, headers={'User-Agent': 'GitSync.md-SEO-Check/1.0'}), timeout=25) as response:
                        return response.status, response.read(), response.geturl()
                except HTTPError as exc:
                    return exc.code, exc.read(), exc.url
            try:
                for file, page in pages.items():
                    if not page.indexable:
                        continue
                    path = public_path(file, site)
                    status, content, final_url = fetch(path)
                    if status != 200 or normalized_html(content) != normalized_html(file.read_bytes()):
                        errors.append(f'live {path}: status/content differs from validated local HTML ({status})')
                    if urlsplit(final_url).path != path:
                        errors.append(f'live {path}: canonical path redirects to {final_url}')
                for name in ('sitemap.xml', 'robots.txt'):
                    status, content, _ = fetch('/' + name)
                    if status != 200 or content != (site / name).read_bytes():
                        errors.append(f'live {name}: status/content mismatch ({status})')
                status, content, _ = fetch('/__seo_missing_page_check__')
                if status != 404 or b'noindex' not in content:
                    errors.append(f'live missing URL must return a noindex 404 (got {status})')
            except (OSError, URLError) as exc:
                errors.append(f'live check failed: {exc}')
    print(f'Checked {len(pages)} HTML pages, {len(urls)} sitemap URLs, metadata, JSON-LD, links, anchors, CSS assets and navigation reachability.')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('All SEO checks passed.' + (' Live deployment content matches.' if args.live_base_url else ''))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
