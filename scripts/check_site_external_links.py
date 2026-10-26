#!/usr/bin/env python3
"""Audit public article/support links with credential-free curl HEAD requests."""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
from urllib.parse import urldefrag, urlsplit


class Links(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.urls = set()

    def handle_starttag(self, tag, attrs):
        href = dict(attrs).get('href', '')
        url = urlsplit(href)
        if tag == 'a' and url.scheme in ('https', 'http') and url.hostname != 'gitsync.md':
            if url.username or url.password:
                raise ValueError('Do not audit a URL containing credentials.')
            self.urls.add(urldefrag(href)[0])


def collect(site):
    links = defaultdict(list)
    for page in sorted(site.rglob('*.html')):
        parser = Links()
        parser.feed(page.read_text())
        for url in sorted(parser.urls):
            links[url].append(page.relative_to(site).as_posix())
    return links


def check(url):
    # -q ignores user curl configuration (including cookies and credentials).
    result = subprocess.run([
        'curl', '-q', '--silent', '--show-error', '--head', '--location',
        '--proto', '=https,http', '--proto-redir', '=https,http',
        '--max-redirs', '5', '--max-time', '25', '--output', '/dev/null',
        '--write-out', '%{http_code}\t%{url_effective}', url,
    ], capture_output=True, text=True)
    status, _, destination = result.stdout.partition('\t')
    status = int(status or 0)
    error = result.stderr.strip() if result.returncode else None
    classification = 'broken' if status in (404, 410) else (
        'unverified' if error or status >= 400 or not status else
        'reachable' if status == 200 else 'review')
    return {'url': url, 'status': status, 'final_url': destination,
            'result': classification, 'error': error}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, default=Path(__file__).resolve().parents[1] / 'site')
    parser.add_argument('--output', type=Path, help='Optional JSON evidence file')
    args = parser.parse_args()
    links = collect(args.site.resolve())
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(check, sorted(links)))
    for result in results:
        result['pages'] = links[result['url']]
    report = {'checked_at': datetime.now(timezone.utc).isoformat(), 'method': 'HEAD', 'links': results}
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Checked {len(results)} public destinations: {dict(Counter(r["result"] for r in results))}')
    for result in results:
        if result['result'] != 'reachable' or result['url'].rstrip('/') != result['final_url'].rstrip('/'):
            print(f'{result["result"]}: {result["status"]} {result["url"]} -> {result["final_url"]}')
    print('HTTP reachability does not prove page content, anchors, video availability, or signed-in access.')
    return 1 if any(r['result'] == 'broken' for r in results) else (
        2 if any(r['result'] != 'reachable' for r in results) else 0)


if __name__ == '__main__':
    raise SystemExit(main())
