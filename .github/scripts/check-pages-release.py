#!/usr/bin/env python3
"""Compare a validated Pages artifact with its live canonical site, without writes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
from urllib.error import HTTPError
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


def normalize_html(text):
    def decode(match):
        raw = bytes.fromhex(match[1])
        return bytes(c ^ raw[0] for c in raw[1:]).decode()
    text = re.sub(r'href="/cdn-cgi/l/email-protection#([a-fA-F0-9]+)"', lambda m: 'href="mailto:' + decode(m) + '"', text)
    text = re.sub(r'<span class="__cf_email__" data-cfemail="([a-fA-F0-9]+)">.*?</span>', decode, text)
    text = re.sub(r'<script[^>]*src="/cdn-cgi/scripts/[^"]+/cloudflare-static/email-decode.min.js"[^>]*></script>', '', text)
    return re.sub(r'<script\b(?=[^>]*\bsrc=["\x27]https://static\.cloudflareinsights\.com/beacon\.min\.js(?:/[a-zA-Z0-9_-]+)?["\x27])[^>]*>\s*</script>\s*', '', text, flags=re.I)


def local_page(root, url, origin):
    parsed = urlsplit(url)
    if (parsed.scheme, parsed.netloc) != (urlsplit(origin).scheme, urlsplit(origin).netloc):
        raise ValueError('Sitemap contains a different origin: ' + url)
    if parsed.query or parsed.fragment:
        raise ValueError('Sitemap URL contains a query or fragment: ' + url)
    path = unquote(parsed.path)
    target = root / path.lstrip('/')
    if path.endswith('/'):
        target /= 'index.html'
    elif not target.suffix:
        target = target.with_suffix('.html')
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError('Sitemap path escapes the artifact')
    if not target.is_file():
        raise ValueError('Missing artifact page: ' + url)
    return target


def fetch(url, accept='text/html'):
    request = Request(url, headers={'Accept': accept, 'User-Agent': 'Pages-release-verification/1.0'})
    with urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise ValueError('Unexpected HTTP status: ' + url)
        if urlsplit(response.geturl()).netloc != urlsplit(url).netloc:
            raise ValueError('Unexpected cross-origin redirect: ' + url)
        return response.headers, response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--private-tools', action='store_true')
    parser.add_argument('--canonical-origin', help='Production origin when checking a preview')
    parser.add_argument('--preview', action='store_true')
    args = parser.parse_args()
    origin = args.base_url.rstrip('/')
    if not origin.startswith('https://'):
        parser.error('Live release checks require verified HTTPS')
    canonical_origin = (args.canonical_origin or origin).rstrip('/')
    urls = [entry.text for entry in ET.parse(args.site / 'sitemap.xml').findall('.//{*}loc')]
    _, live_sitemap = fetch(origin + '/sitemap.xml', 'application/xml')
    live_urls = [entry.text for entry in ET.fromstring(live_sitemap).findall('.//{*}loc')]
    if sorted(urls) != sorted(live_urls):
        raise ValueError('Live sitemap differs from the validated artifact')

    def check(url):
        page = local_page(args.site, url, canonical_origin)
        headers, body = fetch(origin + url.removeprefix(canonical_origin))
        if headers.get_content_type() != 'text/html':
            raise ValueError('Expected HTML: ' + url)
        if not args.preview and re.search(r'\bnoindex\b', headers.get('X-Robots-Tag', ''), flags=re.I):
            raise ValueError('Canonical URL is noindex: ' + url)
        if normalize_html(body.decode()) != normalize_html(page.read_text()):
            raise ValueError('Live HTML differs from the validated artifact: ' + url)
        canonical = re.findall(r'<link\b[^>]*rel=["\x27]canonical["\x27][^>]*href=["\x27]([^"\x27]+)', body.decode(), re.I)
        if canonical != [url]:
            raise ValueError('Unexpected canonical: ' + url)
    with ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(check, urls))
    assets = [p for p in args.site.rglob('*') if p.is_file() and p.suffix in {'.mjs', '.js', '.css', '.svg', '.gpx', '.csv', '.geojson', '.kml', '.zip'} and p.name != '_worker.js']
    def asset(path):
        url = origin + '/' + path.relative_to(args.site).as_posix()
        _, body = fetch(url, '*/*')
        if body != path.read_bytes():
            raise ValueError('Live asset differs from the validated artifact: ' + url)
    with ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(asset, assets))
    if args.private_tools:
        for tool in ('gpx-viewer', 'google-timeline-inspector'):
            for suffix in ('', '/', '/index.html'):
                url = origin + '/tools/' + tool + suffix
                req = Request(url, headers={'Accept': 'text/html'})
                with urlopen(req, timeout=30) as r:
                    body = r.read()
                    if "connect-src 'none'" not in r.headers.get('Content-Security-Policy', ''):
                        raise ValueError('Missing private-file network boundary: ' + url)
                    if r.headers.get('Referrer-Policy') != 'no-referrer' or b'/assets/acquisition.js' in body:
                        raise ValueError('Private-file tool privacy drift: ' + url)
    print(f'Verified {len(urls)} canonical HTML pages, matching sitemap and {len(assets)} exact public assets over HTTPS.')


if __name__ == '__main__':
    main()
