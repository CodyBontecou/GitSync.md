"""Exercise failures that would otherwise silently publish broken SEO pages."""
from pathlib import Path
import subprocess
import sys
import tempfile
import importlib.util
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'check_site_seo.py'
ORIGIN = 'https://gitsync.md'
spec = importlib.util.spec_from_file_location('site_seo', CHECKER)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def page(path, title, body='', robots='index, follow'):
    return f'''<!doctype html><html><head><title>{title}</title>
<link rel="canonical" href="{ORIGIN}{path}">
<meta name="robots" content="{robots}">
<meta name="description" content="A descriptive page.">
<meta property="og:title" content="{title}">
<meta property="og:description" content="A descriptive page.">
<meta property="og:url" content="{ORIGIN}{path}">
<meta property="og:image" content="{ORIGIN}/icon.png">
<meta name="twitter:card" content="summary">
</head><body><h1>{title}</h1>{body}</body></html>'''


class SiteChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.site = Path(self.temp.name)
        (self.site / 'blog' / 'guide').mkdir(parents=True)
        (self.site / 'icon.png').write_bytes(b'fixture image')
        (self.site / 'index.html').write_text(page('/', 'Home', '<a href="/blog/guide#steps">Read guide</a>'))
        (self.site / 'blog' / 'guide.html').write_text(page('/blog/guide', 'Guide', '<h2 id="steps">Steps</h2>'))
        (self.site / '404.html').write_text(page('/404', 'Missing page', '<a href="/">Home</a>', 'noindex, follow'))
        (self.site / 'robots.txt').write_text(f'User-agent: *\nAllow: /\nSitemap: {ORIGIN}/sitemap.xml\n')
        (self.site / 'sitemap.xml').write_text(f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>{ORIGIN}/</loc></url><url><loc>{ORIGIN}/blog/guide</loc></url></urlset>')

    def check(self):
        return subprocess.run([sys.executable, str(CHECKER), '--site', str(self.site)], capture_output=True, text=True)

    def replace(self, name, before, after):
        p = self.site / name
        p.write_text(p.read_text().replace(before, after))

    def test_clean_urls_win_over_same_named_asset_directories(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_only_managed_beacon_is_normalized(self):
        expected = b'<body>\n</body>'
        beacon = b'<body>\n<script type="module" src="https://static.cloudflareinsights.com/beacon.min.js/v123"></script>\n</body>'
        self.assertEqual(checker.normalized_html(beacon), expected)
        unexpected = b'<body>\n<script src="https://example.com/extra.js"></script>\n</body>'
        self.assertNotEqual(checker.normalized_html(unexpected), expected)
        text_change = b'<body>Changed content</body>'
        self.assertNotEqual(checker.normalized_html(text_change), expected)

    def test_missing_anchor_fails(self):
        self.replace('index.html', '#steps', '#missing')
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('missing anchor', result.stderr)

    def test_old_html_canonical_fails(self):
        self.replace('blog/guide.html', f'href="{ORIGIN}/blog/guide"', f'href="{ORIGIN}/blog/guide.html"')
        self.assertNotEqual(self.check().returncode, 0)

    def test_orphaned_page_fails_even_when_in_sitemap(self):
        self.replace('index.html', '<a href="/blog/guide#steps">Read guide</a>', '')
        self.assertIn('orphaned indexable page', self.check().stderr)

    def test_bad_json_ld_fails(self):
        self.replace('blog/guide.html', '</head>', '<script type="application/ld+json">{invalid}</script></head>')
        self.assertNotEqual(self.check().returncode, 0)

    def test_indexable_error_page_fails(self):
        self.replace('404.html', 'noindex, follow', 'index, follow')
        self.assertIn('404.html must exist and declare noindex', self.check().stderr)

    def test_missing_local_asset_fails(self):
        self.replace('blog/guide.html', '</body>', '<img src="/missing.png" alt="Missing"></body>')
        self.assertIn('missing local reference', self.check().stderr)


if __name__ == '__main__':
    unittest.main()
