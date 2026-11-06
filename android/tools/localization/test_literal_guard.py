import pathlib, sys, tempfile, unittest
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import literal_guard

class LiteralGuardTest(unittest.TestCase):
    def test_no_unreviewed_user_facing_literals(self):
        argv, sys.argv = sys.argv, ['literal_guard.py']
        try: self.assertEqual(literal_guard.main(), 0)
        finally: sys.argv = argv

    def test_detects_new_copy_in_user_facing_positions(self):
        source = '\n'.join([
            'Text("Pull finished")',
            '_errorMessage.value = "Could not pull"',
            'throw IllegalStateException("Choose a folder")',
            'require(ok) { "Enter a name" }',
            'TideBanner(title = "Push rejected")',
            'Log.d(TAG, "Ignored log text")',
            'Text(stringResource(R.string.ok))',
            'Text("route_key")',
        ])
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'app/src/main/kotlin').mkdir(parents=True)
            (root / 'app/src/main/kotlin/Sample.kt').write_text(source)
            original = literal_guard.ROOT, literal_guard.SOURCES
            literal_guard.ROOT, literal_guard.SOURCES = root, root / 'app/src/main/kotlin'
            try: found = {text for _, text in literal_guard.occurrences()}
            finally: literal_guard.ROOT, literal_guard.SOURCES = original
        self.assertEqual(found, {'Pull finished', 'Could not pull', 'Choose a folder', 'Enter a name', 'Push rejected'})

if __name__ == '__main__': unittest.main()
