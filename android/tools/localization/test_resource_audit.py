import json
from pathlib import Path
import tempfile
import unittest
from audit_resources import audit, translation_errors


class ResourceAuditTest(unittest.TestCase):
    def test_xml_audit_rejects_translated_pat_scope_identifiers(self):
        self.assertEqual(['GitHub scope identifier changed: repo'], translation_errors(
            'Scopes: repo and user:email.', 'Ámbitos: repositorio y usuario: correo electrónico.'))
        self.assertEqual([], translation_errors(
            'Scopes: repo and user:email.', 'Ámbitos: repo y user:email.'))

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.res = self.root / 'app/src/main/res'
        (self.res / 'values').mkdir(parents=True)
        (self.res / 'values-es').mkdir()
        (self.root / 'tools/localization').mkdir(parents=True)
        (self.root / 'tools/localization/coverage.json').write_text(json.dumps({'languages': ['es']}))
        (self.res / 'values/base.xml').write_text('<resources><string name="count">%1$d changed</string><plurals name="files"><item quantity="one">%1$d file</item><item quantity="other">%1$d files</item></plurals></resources>')
        self.locale = self.res / 'values-es/labels.xml'
        self.valid = '<resources><string name="count">%1$d modificados</string><plurals name="files"><item quantity="one">%1$d archivo</item><item quantity="many">%1$d archivos</item><item quantity="other">%1$d archivos</item></plurals></resources>'

    def test_complete_contracts_pass_and_arbitrary_resource_filenames_are_scanned(self):
        self.locale.write_text(self.valid)
        result = audit(self.root)['locales']['es']
        self.assertEqual({'missing_strings': [], 'missing_plurals': [], 'contract_errors': []}, result)

    def test_missing_icu_many_and_formatter_type_changes_fail(self):
        self.locale.write_text(self.valid.replace('%1$d modificados', '%1$s modificados').replace('<item quantity="many">%1$d archivos</item>', ''))
        self.assertEqual(['count: formatter signature', 'files: missing ICU category'], audit(self.root)['locales']['es']['contract_errors'])

    def test_coverage_manifest_cannot_hide_missing_actual_xml(self):
        self.locale.write_text('<resources/>')
        result = audit(self.root)['locales']['es']
        self.assertEqual(['count'], result['missing_strings'])
        self.assertEqual(['files'], result['missing_plurals'])

    def test_provider_challenges_cannot_pass_as_unformatted_translation(self):
        (self.res / 'values/base.xml').write_text('<resources><string name="label">Read</string></resources>')
        self.locale.write_text('<resources><string name="label">d5g5gyaulidnn9sz3l99</string></resources>')
        self.assertEqual(['label: provider challenge response'], audit(self.root)['locales']['es']['contract_errors'])
