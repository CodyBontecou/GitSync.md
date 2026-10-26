"""Exercise failures that would otherwise silently publish broken SEO pages."""
from pathlib import Path
import subprocess
import sys
import tempfile
import importlib.util
import json
import struct
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
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:type" content="image/png">
<meta property="og:image:alt" content="GitSync.md guide card">
<meta name="twitter:card" content="summary">
<meta name="twitter:image" content="{ORIGIN}/icon.png">
<meta name="twitter:image:alt" content="GitSync.md guide card">
</head><body><h1>{title}</h1>{body}</body></html>'''


class SiteChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.site = Path(self.temp.name)
        (self.site / 'blog' / 'guide').mkdir(parents=True)
        # Only the PNG signature/IHDR dimensions are inspected by this checker.
        (self.site / 'icon.png').write_bytes(b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 1200, 630))
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

    def test_portrait_sharing_image_fails_even_with_landscape_metadata(self):
        image = self.site / 'icon.png'
        image.write_bytes(image.read_bytes()[:16] + struct.pack('>II', 1320, 2868))
        self.assertIn('PNG dimensions 1200x630', self.check().stderr)

    def test_mismatched_sharing_images_and_missing_alt_fail(self):
        self.replace('blog/guide.html', f'name="twitter:image" content="{ORIGIN}/icon.png"',
                     f'name="twitter:image" content="{ORIGIN}/other.png"')
        self.replace('blog/guide.html', '<meta property="og:image:alt" content="GitSync.md guide card">', '')
        result = self.check()
        self.assertIn('images must match', result.stderr)
        self.assertIn('missing/duplicate og:image:alt', result.stderr)

    def test_article_breadcrumbs_and_author_identity(self):
        canonical = ORIGIN + '/blog/guide'
        article = {'@type': 'BlogPosting', 'author': {'@type': 'Person', 'url': ORIGIN + '/about'}}
        breadcrumb = {'@type': 'BreadcrumbList', 'itemListElement': [
            {'@type': 'ListItem', 'position': position, 'name': name, 'item': url}
            for position, name, url in [(1, 'Home', ORIGIN + '/'), (2, 'Guides', ORIGIN + '/blog/'), (3, 'Guide', canonical)]
        ]}
        def parsed():
            return checker.Page('<nav aria-label="Breadcrumb"><a href="/">Home</a></nav>'
                                '<a href="/about" rel="author">Cody Bontecou</a>'
                                + ''.join('<script type="application/ld+json">' + json.dumps(obj) + '</script>'
                                          for obj in (article, breadcrumb)))
        self.assertEqual(checker.article_metadata_errors(parsed(), canonical), [])
        breadcrumb['itemListElement'][-1]['item'] += '.html'
        self.assertIn('canonical hierarchy', ' '.join(checker.article_metadata_errors(parsed(), canonical)))
        breadcrumb['itemListElement'][-1]['item'] = canonical
        breadcrumb['itemListElement'][1]['position'] = 3
        self.assertIn('positions', ' '.join(checker.article_metadata_errors(parsed(), canonical)))
        article['author']['url'] = ORIGIN + '/'
        self.assertIn('author URL', ' '.join(checker.article_metadata_errors(parsed(), canonical)))

    def test_article_requires_visible_navigation_and_author(self):
        parsed = checker.Page('<script type="application/ld+json">'
                              '{"@type":"Article","author":{"url":"https://gitsync.md/about"}}'
                              '</script>')
        errors = checker.article_metadata_errors(parsed, ORIGIN + '/blog/guide')
        self.assertIn('visible author', ' '.join(errors))
        self.assertIn('visible breadcrumbs', ' '.join(errors))


if __name__ == '__main__':
    unittest.main()
