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

class RetryTests(unittest.TestCase):
    def fake_time(self):
        now = [0.0]
        return (lambda: now[0]), (lambda seconds: now.__setitem__(0, now[0] + seconds))

    def test_stale_live_site_is_rechecked_until_it_matches(self):
        clock, sleep = self.fake_time()
        results = [ValueError('Live HTML differs'), ValueError('Live HTML differs'), 'Verified']
        def check():
            result = results.pop(0)
            if isinstance(result, Exception):
                raise result
            return result
        self.assertEqual(release.retry_until(check, 60, clock=clock, sleep=sleep), 'Verified')
        self.assertEqual(clock(), 20)

    def test_persistent_mismatch_still_fails_after_the_budget(self):
        clock, sleep = self.fake_time()
        calls = []
        def check():
            calls.append(clock())
            raise ValueError('Live HTML differs from the validated artifact')
        with self.assertRaisesRegex(ValueError, 'differs'):
            release.retry_until(check, 25, clock=clock, sleep=sleep)
        self.assertEqual(calls, [0, 10, 20, 25])

    def test_no_budget_means_a_single_check(self):
        calls = []
        def check():
            calls.append(1)
            raise ValueError('differs')
        with self.assertRaises(ValueError):
            release.retry_until(check, 0, sleep=lambda s: self.fail('slept'))
        self.assertEqual(calls, [1])

    def test_a_connection_dropped_mid_response_is_retried(self):
        from http.client import IncompleteRead
        clock, sleep = self.fake_time()
        results = [IncompleteRead(b'partial'), ConnectionResetError('reset'), 'Verified']
        def check():
            result = results.pop(0)
            if isinstance(result, Exception):
                raise result
            return result
        self.assertEqual(release.retry_until(check, 60, clock=clock, sleep=sleep), 'Verified')

    def test_unexpected_errors_are_not_retried(self):
        def check():
            raise KeyError('bug')
        with self.assertRaises(KeyError):
            release.retry_until(check, 60, sleep=lambda s: self.fail('slept'))


if __name__ == '__main__':
    unittest.main()
