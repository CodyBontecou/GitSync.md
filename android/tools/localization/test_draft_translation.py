import re
import unittest
from draft_translation import parse_batch, mask_text, restore_text


class DraftTranslationTest(unittest.TestCase):
    def test_provider_whitespace_and_fullwidth_colon_preserve_exact_ids(self):
        self.assertEqual({1: 'Compte', 7: 'Paramètres'}, parse_batch(
            'ZXQITEM000001QXZ : Compte\nZXQITEM000007QXZ： Paramètres', {1, 7}))
        self.assertEqual({7: 'الإعدادات', 1: 'الحساب'}, parse_batch(
            '\u200fZXQITEM000007QXZ: الإعدادات\nZXQITEM000001QXZ: الحساب', {1, 7}))

    def test_ambiguous_or_shifted_batches_are_rejected(self):
        for text in ['ZXQITEM000001QXZ: one',
                     'ZXQITEM000001QXZ: one\nZXQITEM000001QXZ: duplicate',
                     'Prose\nZXQITEM000001QXZ: one\nZXQITEM000007QXZ: seven',
                     'ZXQITEM000001QXZ: \nZXQITEM000007QXZ: seven']:
            with self.assertRaises(ValueError):
                parse_batch(text, {1, 7})

    def test_technical_terms_do_not_mask_substrings_of_ordinary_words(self):
        source = 'Check the original folder on origin for %1$d files\nGitHub'
        masked, tokens = mask_text(source, re.compile(r'%\d+\$d'), ['Git', 'GitHub', 'origin'])
        self.assertIn('original', masked)
        self.assertNotIn(' on origin ', masked)
        self.assertEqual(source, restore_text(masked, masked, tokens))
        token = next(iter(tokens))
        for bad in [masked.replace(token, ''), masked + token, masked + ' __GSPH999__']:
            with self.assertRaises(ValueError):
                restore_text(bad, masked, tokens)

    def test_protected_leading_and_trailing_newlines_are_not_trimmed(self):
        source = '\nRead %1$d files\n'
        masked, tokens = mask_text(source, re.compile(r'%\d+\$d'), [])
        self.assertEqual(source, restore_text(masked, masked, tokens))
