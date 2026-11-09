import unittest
from import_ios_catalog import normalized, signature, adapt, qualifier, checked_translations, alias_translations, checked_plurals
from extract_compose import xml_text, android_text
import xml.etree.ElementTree as ET

class CatalogTest(unittest.TestCase):
    def test_reordered_swift_arguments_keep_android_argument_types(self):
        self.assertEqual('%2$s / %1$s',adapt('%2$lld / %1$@','%@ : %lld','%1$s : %2$s'))
        self.assertEqual('%1$d',adapt('%lld','%lld','%1$d'))
    def test_missing_extra_or_different_argument_positions_are_rejected(self):
        for translated in ['missing','%2$@','%1$@ %3$@']:
            with self.assertRaises(ValueError):adapt(translated,'%@','%1$s')
    def test_literal_percent_does_not_become_an_argument(self):
        self.assertEqual({1:'@'},signature('%%%@'))
        self.assertEqual('%%%1$s',adapt('%%%@','%%%@','%%%1$s'))
        self.assertEqual('%1$s %%',adapt('%1$@ %%','%% %@','%% %1$s'))
    def test_unescaped_formatter_percent_is_rejected(self):
        with self.assertRaises(ValueError):adapt('%@ 100%','%@','%1$s')
    def test_normalization_handles_typography_without_fuzzy_semantic_matching(self):
        self.assertEqual(normalized('Delete “%@”…'),normalized('DELETE “%1$s”...'))
        self.assertNotEqual(normalized('Push'),normalized('Pull'))
        self.assertNotEqual(normalized('Sync'),normalized('Sync Repository'))
    def test_xml_and_android_escaping_round_trip_special_copy(self):
        value='  O\'Brien says "東京" & <files>\\next\nsecond\tline  '
        xml='<string>'+xml_text(value)+'</string>'
        self.assertEqual(value,android_text(ET.fromstring(xml).text))
    def test_locale_qualifiers_preserve_scripts_regions_and_android_legacy_aliases(self):
        self.assertEqual('b+zh+Hans',qualifier('zh-Hans'));self.assertEqual('b+zh+Hant',qualifier('zh-Hant'))
        self.assertEqual('pt-rBR',qualifier('pt-BR'));self.assertEqual('iw',qualifier('he'));self.assertEqual('in',qualifier('id'))
    def test_reviewed_alias_keeps_argument_positions_and_rtl_adornment(self):
        entry={'localizations':{'en':{'stringUnit':{'value':'Back %@'}},'ar':{'stringUnit':{'state':'translated','value':'رجوع %@'}}}}
        alias={'source':'← Back %1$s','ios_key':'Back %@','ios_source':'Back %@','prefix':{'default':'← ','ar':'→ '}}
        self.assertEqual({'ar':'→ رجوع %1$s'},alias_translations(alias,alias['source'],{'Back %@':entry},['ar']))
        with self.assertRaises(ValueError):alias_translations(alias,'Changed',{'Back %@':entry},['ar'])
        alias['ios_source']='Outdated'
        with self.assertRaises(ValueError):alias_translations(alias,alias['source'],{'Back %@':entry},['ar'])
    def test_android_override_requires_exact_source_and_complete_locales(self):
        row={'source':'Apply rules','localizations':{'es':'Aplicar reglas'}}
        self.assertEqual({'es':'Aplicar reglas'},checked_translations(row,'Apply rules',['es']))
        with self.assertRaises(ValueError):checked_translations(row,'Apply',['es'])
        with self.assertRaises(ValueError):checked_translations(row,'Apply rules',['es','ar'])
    def test_plural_forms_retain_count_and_reject_missing_other_or_unknown_category(self):
        source={'one':'%1$d file','other':'%1$d files'}
        row={'source':source,'localizations':{'de':{'one':'%1$d Datei','other':'%1$d Dateien'}}}
        self.assertEqual(row['localizations'],checked_plurals(row,source,['de']))
        for forms in [{'one':'%1$d Datei'},{'one':'Datei','other':'Dateien'},{'one':'%1$d Datei','other':'%2$d Dateien'},{'one':'%1$d Datei','other':'%1$d Dateien','invalid':'%1$d'}]:
            with self.assertRaises(ValueError):checked_plurals({'source':source,'localizations':{'de':forms}},source,['de'])
    def test_hebrew_requires_legacy_many_category_for_supported_android_versions(self):
        source = {'one': '%1$d file', 'other': '%1$d files'}
        row = {'source': source, 'localizations': {'he': {'one': '%1$d קובץ', 'two': '%1$d קבצים', 'other': '%1$d קבצים'}}}
        with self.assertRaisesRegex(ValueError, 'lacks ICU categories'):
            checked_plurals(row, source, ['he'])
        row['localizations']['he']['many'] = '%1$d קבצים'
        self.assertEqual(row['localizations'], checked_plurals(row, source, ['he']))

    def test_plurals_need_every_icu_category_android_selects(self):
        source={'one':'%1$d file','other':'%1$d files'}
        arabic={q:'ملفات %1$d' for q in ['zero','one','two','few','many','other']}
        self.assertEqual(arabic,checked_plurals({'source':source,'localizations':{'ar':arabic}},source,['ar'])['ar'])
        self.assertEqual({'other':'%1$d 件'},checked_plurals({'source':source,'localizations':{'ja':{'other':'%1$d 件'}}},source,['ja'])['ja'])
        for locale,forms in [('ar',{'one':'ملف %1$d','other':'ملفات %1$d'}),('es',{'one':'%1$d archivo','other':'%1$d archivos'}),
                             ('ru',{'one':'%1$d файл','few':'%1$d файла','other':'%1$d файлов'})]:
            with self.assertRaises(ValueError):checked_plurals({'source':source,'localizations':{locale:forms}},source,[locale])

if __name__=='__main__':unittest.main()
