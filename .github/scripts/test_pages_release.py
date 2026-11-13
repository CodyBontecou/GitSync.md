import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('check-pages-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseBoundaryTests(unittest.TestCase):
    def test_only_managed_beacon_is_ignored(self):
        beacon = '<script defer src="https://static.cloudflareinsights.com/beacon.min.js/test"></script>'
        self.assertEqual(release.normalize_html('<main>Body</main>' + beacon), '<main>Body</main>')
        other = '<script src="https://example.org/changed.js"></script>'
        self.assertIn(other, release.normalize_html(other))

    def test_sitemap_cannot_escape_origin_or_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'index.html').write_text('site')
            self.assertEqual(release.local_page(root, 'https://site.test/', 'https://site.test'), root / 'index.html')
            for url in ['https://other.test/', 'https://site.test/../outside', 'https://site.test/?q=secret']:
                with self.subTest(url=url), self.assertRaises(ValueError):
                    release.local_page(root, url, 'https://site.test')

if __name__ == '__main__':
    unittest.main()
