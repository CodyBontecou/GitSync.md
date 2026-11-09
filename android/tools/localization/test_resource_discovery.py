import contextlib
import io
import json
import pathlib
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import extract_compose as extractor
import import_ios_catalog as importer


class ResourceDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='syncmd-resource-discovery-')
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name) / 'android'
        self.root.mkdir()
        self.values = self.root / 'app/src/main/res/values'
        self.values.mkdir(parents=True)
        (self.values / 'strings.xml').write_text('<resources><string name="fallback">Unmatched fixture</string></resources>')
        (self.values / 'strings_feature.xml').write_text('<resources><string name="read">Read %1$s</string><string name="alternate_ready">Ready</string></resources>')
        (self.values / 'labels.xml').write_text('<resources><string name="ready">Ready</string></resources>')
        (self.values / 'quantities.xml').write_text('<resources><plurals name="items"><item quantity="other">%d items</item></plurals></resources>')
        (self.values / 'styles.xml').write_text('<resources><style name="Fixture"><item name="android:windowLightStatusBar">true</item></style></resources>')
        (self.root / 'app/src/main/res/xml').mkdir()
        self.tools = self.root / 'tools/localization'
        self.tools.mkdir(parents=True)
        for name in ('android_overrides.json', 'android_workflow_overrides.json'):
            (self.tools / name).write_text(json.dumps({'strings': {}, 'plurals': {}}))
        (self.tools / 'ios_aliases.json').write_text(json.dumps({'strings': {}}))
        (self.tools / 'reviewed_native_sources.json').write_text('{}')
        (self.tools / 'english_literals.json').write_text(json.dumps({'version': 1, 'strings': [
            {'name': 'read', 'source': 'Read %1$s', 'arguments': 1},
            {'name': 'ready', 'source': 'Ready', 'arguments': 0},
            {'name': 'alternate_ready', 'source': 'Ready', 'arguments': 0},
            {'name': 'fallback', 'source': 'Unmatched fixture', 'arguments': 0}]}))
        self.catalog = self.root / 'catalog.json'
        self.catalog.write_text(json.dumps({'strings': {
            key: {'localizations': {'en': {'stringUnit': {'value': key}}, 'es': {'stringUnit': {'value': value, 'state': 'translated'}}}}
            for key, value in [('Read %@', 'Leer %@'), ('Ready', 'Listo')]} }))
        native_catalog = self.root.parent / 'Sync.md/Localizable.xcstrings'
        native_catalog.parent.mkdir(parents=True)
        native_catalog.write_bytes(self.catalog.read_bytes())
        (self.root / 'app/src/androidTest/assets').mkdir(parents=True)

    def test_import_and_native_expectations_cover_all_resource_filenames(self):
        with patch.object(importer, 'ROOT', self.root), patch.object(sys, 'argv', ['import', '--apply', '--catalog', str(self.catalog)]), contextlib.redirect_stdout(io.StringIO()):
            importer.main()
        coverage = json.loads((self.tools / 'coverage.json').read_text())
        self.assertEqual(4, coverage['base_strings'])
        self.assertEqual({'read', 'ready', 'alternate_ready'}, set(coverage['mapped']))
        self.assertEqual(['fallback'], coverage['missing'])
        self.assertEqual(['items'], coverage['unmatched_plurals'])
        translated = {e.attrib['name']: extractor.android_text(e.text) for e in ET.parse(self.root / 'app/src/main/res/values-es/ios_catalog_strings.xml').getroot().findall('string')}
        self.assertEqual({'read': 'Leer %1$s', 'ready': 'Listo', 'alternate_ready': 'Listo'}, translated)
        with patch.object(extractor, 'ROOT', self.root), patch.object(importer, 'ROOT', self.root), patch.object(sys, 'argv', ['native']), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(pathlib.Path(__file__).with_name('native_expectations.py')), run_name='__main__')
        native = json.loads((self.root / 'app/src/androidTest/assets/localization_expected.json').read_text())
        self.assertEqual({'read', 'ready', 'alternate_ready'}, {r['name'] for r in native['locales']['es']})
        self.assertEqual('Read %1$s', next(r['source'] for r in native['english'] if r['name'] == 'read'))
        (self.values / 'strings_feature.xml').write_text('<resources><string name="read">Changed %1$s</string><string name="alternate_ready">Ready</string></resources>')
        with patch.object(extractor, 'ROOT', self.root), patch.object(importer, 'ROOT', self.root), patch.object(sys, 'argv', ['native']), self.assertRaisesRegex(AssertionError, 'English source changed.*read'):
            runpy.run_path(str(pathlib.Path(__file__).with_name('native_expectations.py')), run_name='__main__')

    def test_import_defaults_to_catalog_in_same_repository(self):
        with patch.object(importer, 'ROOT', self.root), patch.object(sys, 'argv', ['import', '--apply']), contextlib.redirect_stdout(io.StringIO()):
            importer.main()
        coverage = json.loads((self.tools / 'coverage.json').read_text())
        self.assertEqual({'read', 'ready', 'alternate_ready'}, set(coverage['mapped']))

    def test_extractor_reuses_existing_key_from_arbitrary_xml_filename(self):
        source = self.root / 'app/src/main/kotlin/Fixture.kt'
        source.parent.mkdir(parents=True)
        source.write_text('Text("Ready")')
        output = self.root / 'inventory.json'
        def inventory(path):
            path.write_text(json.dumps([{'text': 'Ready', 'arguments': [], 'file': 'app/src/main/kotlin/Fixture.kt', 'start': 5, 'end': 12, 'literal': '"Ready"'}]))
        captured = io.StringIO()
        with patch.object(extractor, 'ROOT', self.root), patch.object(extractor, 'inventory', inventory), patch.object(sys, 'argv', ['extract', '--inventory', str(output)]), contextlib.redirect_stdout(captured):
            extractor.main()
        self.assertIn('new resources: 0', captured.getvalue())
        self.assertEqual('Text("Ready")', source.read_text())


    def test_extractor_preserves_review_metadata_on_reextraction(self):
        fixture = json.loads((self.tools / 'english_literals.json').read_text())
        ready = next(r for r in fixture['strings'] if r['name'] == 'ready')
        ready.update(original_source='READY', source_review='Reviewed capitalization only.')
        (self.tools / 'english_literals.json').write_text(json.dumps(fixture))
        source = self.root / 'app/src/main/kotlin/Fixture.kt'
        source.parent.mkdir(parents=True)
        source.write_text('Text("Ready")')
        output = self.root / 'inventory.json'
        def inventory(path):
            path.write_text(json.dumps([{'text': 'Ready', 'arguments': [], 'file': 'app/src/main/kotlin/Fixture.kt', 'start': 5, 'end': 12, 'literal': '"Ready"'}]))
        with patch.object(extractor, 'ROOT', self.root), patch.object(extractor, 'inventory', inventory), patch.object(extractor.tempfile, 'mkdtemp', return_value=str(self.root / 'backup')), patch.object(sys, 'argv', ['extract', '--inventory', str(output), '--apply']), contextlib.redirect_stdout(io.StringIO()):
            extractor.main()
        retained = next(r for r in json.loads((self.tools / 'english_literals.json').read_text())['strings'] if r['name'] == 'ready')
        self.assertEqual(ready, retained)
        self.assertIn('R.string.ready', source.read_text())


if __name__ == '__main__':
    unittest.main()
