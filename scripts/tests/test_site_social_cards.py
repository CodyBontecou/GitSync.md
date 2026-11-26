"""Every published page must map to a sharing-card category, so new pages can't crash the generator."""
from pathlib import Path
import importlib.util
import sys
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/build_social_cards.py'

# Category logic doesn't need Pillow; stub it where CI hasn't installed it.
if importlib.util.find_spec('PIL') is None:
    pil = types.ModuleType('PIL')
    for name in ('Image', 'ImageDraw', 'ImageFont'):
        setattr(pil, name, types.ModuleType('PIL.' + name))
    sys.modules['PIL'] = pil
spec = importlib.util.spec_from_file_location('social_cards', SCRIPT)
cards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cards)


class SocialCardTests(unittest.TestCase):
    def test_every_english_site_page_has_a_category(self):
        site = ROOT / 'site'
        for page in sorted(site.rglob('*.html')):
            if cards.is_english(page.read_text()):
                with self.subTest(page=page.relative_to(site).as_posix()):
                    self.assertTrue(cards.category_for(page.relative_to(site)))

    def test_android_privacy_page_is_a_privacy_card(self):
        self.assertEqual(cards.category_for(Path('privacy-android.html')), 'Privacy')

    def test_tool_pages_are_free_tool_cards(self):
        self.assertEqual(cards.category_for(Path('tools/obsidian-sync-cost-calculator.html')), 'Free tool')

    def test_unknown_top_level_page_names_the_fix(self):
        with self.assertRaisesRegex(ValueError, 'PAGE_CATEGORIES'):
            cards.category_for(Path('new-page.html'))
        with self.assertRaisesRegex(ValueError, 'PAGE_CATEGORIES'):
            cards.category_for(Path('zh-hans/about.html'))

    def test_translated_pages_keep_their_existing_card(self):
        self.assertFalse(cards.is_english('<!doctype html>\n<html lang="zh-Hans">'))
        self.assertTrue(cards.is_english('<!DOCTYPE html>\n<html lang="en">'))
        self.assertTrue(cards.is_english('<html lang="en-US">'))


if __name__ == '__main__':
    unittest.main()
