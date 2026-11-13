import contextlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import import_ios_catalog as importer


class TranslationLossTest(unittest.TestCase):
    def test_new_labels_cannot_mask_lost_active_labels_at_the_same_total(self):
        previous={'mapped':{'keep':'Keep','lost':'Lost','retired':'Retired'},'languages':['es']}
        current={'mapped':{'keep':'Keep','gain':'Gain','another':'Another'},'languages':['es']}
        self.assertEqual({'strings':['lost']},importer.translation_loss(previous,current,
            {'keep','lost','gain','another'},set(),{},{}))

    def test_locale_replacement_is_loss_even_when_the_locale_count_is_unchanged(self):
        previous={'mapped':{},'languages':['ar','es']}
        current={'mapped':{},'languages':['ar','de']}
        self.assertEqual({'languages':['es']},importer.translation_loss(previous,current,set(),set(),{},{}))

    def test_replacing_plural_names_cannot_hide_a_lost_active_plural(self):
        previous={'mapped':{},'languages':['es']};current=dict(previous)
        old={'es':{'files':{'one','other'},'retired':{'other'}}}
        new={'es':{'items':{'one':'uno','other':'otros'}}}
        self.assertEqual({'plurals':['files']},importer.translation_loss(previous,current,
            set(),{'files','items'},old,new))

    def test_plural_category_loss_is_detected_without_losing_the_plural_name(self):
        previous={'mapped':{},'languages':['ar']};current=dict(previous)
        old={'ar':{'files':{'zero','one','two','few','many','other'}}}
        new={'ar':{'files':{'one':'one','other':'other'}}}
        self.assertEqual({'plural_forms':{'ar':{'files':['few','many','two','zero']}}},
            importer.translation_loss(previous,current,set(),{'files'},old,new))

    def test_first_import_and_additions_do_not_require_an_override(self):
        current={'mapped':{'new':'New'},'languages':['es','ar']}
        self.assertEqual({},importer.translation_loss(None,current,{'new'},set(),{},{}))
        previous={'mapped':{},'languages':['es']}
        self.assertEqual({},importer.translation_loss(previous,current,{'new'},{'files'},
            {'es':{'files':{'other'}}},{'es':{'files':{'one':'uno','other':'otros'}},'ar':{}}))


class CatalogWriteGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='syncmd-catalog-loss-')
        self.addCleanup(self.temp.cleanup)
        self.root=pathlib.Path(self.temp.name)
        values=self.root/'app/src/main/res/values';values.mkdir(parents=True)
        (values/'strings.xml').write_text('<resources><string name="keep">Keep</string>'
            '<string name="lost">Lost</string><string name="gain">Gain</string></resources>')
        self.tools=self.root/'tools/localization';self.tools.mkdir(parents=True)
        for name in ['android_overrides.json','android_workflow_overrides.json','android_completion_overrides.json']:
            (self.tools/name).write_text(json.dumps({'strings':{},'plurals':{}}))
        (self.tools/'ios_aliases.json').write_text(json.dumps({'strings':{}}))
        self.coverage=self.tools/'coverage.json'
        self.coverage.write_text(json.dumps({'mapped':{'keep':'Keep','lost':'Lost','retired':'Retired'},'languages':['es']}))
        locale=self.root/'app/src/main/res/values-es';locale.mkdir()
        self.xml=locale/'ios_catalog_strings.xml'
        self.xml.write_text('<resources><string name="keep">Mantener</string>'
            '<string name="lost">Perdido</string><string name="retired">Retirado</string></resources>')
        config=self.root/'app/src/main/res/xml';config.mkdir()
        self.config=config/'locales_config.xml';self.config.write_text('existing locale configuration\n')
        self.catalog=self.root/'catalog.json';self.write_catalog(include_lost=False)

    def write_catalog(self,include_lost):
        words={'Keep':'Mantener','Gain':'Nuevo'}
        if include_lost: words['Lost']='Perdido'
        self.catalog.write_text(json.dumps({'strings':{key:{'localizations':{'es':{
            'stringUnit':{'state':'translated','value':value}}}} for key,value in words.items()}}))

    def generated_bytes(self):
        return {str(p):p.read_bytes() for p in [self.coverage,self.xml,self.config]}

    def run_import(self,*options):
        self.stdout=io.StringIO();self.stderr=io.StringIO()
        with patch.object(importer,'ROOT',self.root),patch.object(importer,'PREVIEW_PATH',self.root/'preview.json'),\
            patch.object(sys,'argv',['import_ios_catalog.py','--catalog',str(self.catalog),*options]),\
            contextlib.redirect_stdout(self.stdout),contextlib.redirect_stderr(self.stderr):
            importer.main()

    def test_apply_and_check_reject_loss_before_changing_any_generated_file(self):
        before=self.generated_bytes()
        for option in ['--apply','--check']:
            with self.subTest(option=option),self.assertRaises(SystemExit) as failure:
                self.run_import(option)
            self.assertEqual(2,failure.exception.code)
            self.assertIn('"strings": ["lost"]',self.stderr.getvalue())
            self.assertNotIn('"retired"',self.stderr.getvalue())
            self.assertEqual(before,self.generated_bytes())

    def test_preview_reports_loss_without_overwriting_resources_or_coverage(self):
        before=self.generated_bytes();self.run_import()
        report=json.loads((self.root/'preview.json').read_text())
        self.assertEqual({'strings':['lost']},report['translation_loss'])
        self.assertEqual({'keep','gain'},set(report['mapped']))
        self.assertEqual(before,self.generated_bytes())

    def test_explicit_apply_override_allows_an_intentional_removal(self):
        self.run_import('--apply','--allow-translation-loss')
        report=json.loads(self.coverage.read_text())
        self.assertEqual({'keep','gain'},set(report['mapped']))
        self.assertEqual(['lost'],report['missing'])
        self.assertNotIn('translation_loss',report)
        self.assertEqual({'keep','gain'},{item.attrib['name'] for item in ET.parse(self.xml).getroot().findall('string')})

    def test_normal_apply_allows_new_translations_and_retirement_of_an_unused_resource(self):
        self.write_catalog(include_lost=True);self.run_import('--apply')
        report=json.loads(self.coverage.read_text())
        self.assertEqual({'keep','lost','gain'},set(report['mapped']))
        self.assertEqual([],report['missing'])
        self.assertNotIn('translation_loss',report)

    def test_loss_override_requires_apply_and_cannot_disable_check(self):
        before=self.generated_bytes()
        for options in [('--allow-translation-loss',),('--check','--allow-translation-loss')]:
            with self.subTest(options=options),self.assertRaises(SystemExit): self.run_import(*options)
            self.assertEqual(before,self.generated_bytes())

    def test_malformed_previous_baseline_is_not_silently_replaced(self):
        self.coverage.write_text('{broken');before=self.generated_bytes()
        with self.assertRaises(SystemExit): self.run_import('--apply')
        self.assertIn('Cannot read the previous localization baseline',self.stderr.getvalue())
        self.assertEqual(before,self.generated_bytes())

    def test_plural_only_loss_is_read_from_existing_locale_xml_before_any_write(self):
        self.write_catalog(include_lost=True)
        base=self.root/'app/src/main/res/values/plural_strings.xml'
        base.write_text('<resources><plurals name="files"><item quantity="one">%1$d file</item>'
            '<item quantity="other">%1$d files</item></plurals></resources>')
        (self.tools/'android_workflow_overrides.json').write_text(json.dumps({'strings':{},'plurals':{'files':{
            'source':{'one':'%1$d file','other':'%1$d files'},'localizations':{'es':{q:'%1$d archivos' for q in ['one','many','other']}}}}}))
        tree=ET.parse(self.xml);element=ET.SubElement(tree.getroot(),'plurals',{'name':'files'})
        for quantity in ['one','two','many','other']: ET.SubElement(element,'item',{'quantity':quantity}).text='%1$d archivos'
        tree.write(self.xml,encoding='unicode');before=self.generated_bytes()
        with self.assertRaises(SystemExit): self.run_import('--apply')
        self.assertIn('"plural_forms": {"es": {"files": ["two"]}}',self.stderr.getvalue())
        self.assertEqual(before,self.generated_bytes())


if __name__=='__main__': unittest.main()
