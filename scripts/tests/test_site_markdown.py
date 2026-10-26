import unittest
from scripts.site_markdown import to_markdown


class MarkdownTests(unittest.TestCase):
    def test_main_content_preserves_headings_links_and_omits_hidden_markup(self):
        html = '''<nav>Menu</nav><main><h1>Git<br>on <span>iPhone</span></h1>
        <p>Read <a href="/blog/">the guides</a>.</p><span aria-hidden="true">hidden</span>
        <script>private()</script><style>body {}</style></main><footer>Footer</footer>'''
        result = to_markdown(html, 'https://gitsync.md/')
        self.assertIn('# Git on iPhone', result)
        self.assertIn('[the guides](https://gitsync.md/blog/)', result)
        for excluded in ['Menu', 'Footer', 'hidden', 'private()', 'body {}']:
            self.assertNotIn(excluded, result)

    def test_code_keeps_blank_lines_and_whitespace(self):
        result = to_markdown('<main><pre><code>git status\n\n\n  git diff</code></pre></main>', 'https://gitsync.md/')
        self.assertIn('```\ngit status\n\n\n  git diff\n```', result)

    def test_lists_and_comparison_tables_remain_structured(self):
        result = to_markdown('<main><ol><li>Clone</li><li>Pull</li></ol><table><tr><th>App</th><th>Price</th></tr><tr><td>GitSync</td><td>$19.99</td></tr></table></main>', 'https://gitsync.md/')
        self.assertIn('1. Clone\n2. Pull', result)
        self.assertIn('| App | Price |\n| --- | --- |\n| GitSync | $19.99 |', result)

    def test_relative_links_resolve_against_the_canonical_document(self):
        result = to_markdown('<main><a href="../privacy">Privacy</a></main>', 'https://gitsync.md/blog/guide')
        self.assertIn('(https://gitsync.md/privacy)', result)


if __name__ == '__main__':
    unittest.main()
