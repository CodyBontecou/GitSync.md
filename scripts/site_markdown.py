"""Render public HTML content as Markdown during the static-site build."""
from dataclasses import dataclass, field
from html.parser import HTMLParser
import re
from urllib.parse import urljoin


@dataclass
class Node:
    tag: str
    attrs: dict = field(default_factory=dict)
    children: list = field(default_factory=list)


class Document(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.root = Node('document')
        self.stack = [self.root]
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        node = Node(tag, dict(attrs))
        self.stack[-1].children.append(node)
        if tag not in {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}:
            self.stack.append(node)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def find(node, tag):
    if isinstance(node, str):
        return None
    if node.tag == tag:
        return node
    for child in node.children:
        match = find(child, tag)
        if match:
            return match
    return None


def plain(node):
    return node if isinstance(node, str) else ''.join(plain(c) for c in node.children)


def render(node, base, in_pre=False):
    if isinstance(node, str):
        return node if in_pre else re.sub(r'\s+', ' ', node)
    tag, attrs = node.tag, node.attrs
    if tag in {'script', 'style', 'svg', 'nav', 'button', 'noscript'} or attrs.get('aria-hidden') == 'true' or 'hidden' in attrs:
        return ''
    body = ''.join(render(c, base, in_pre or tag == 'pre') for c in node.children)
    if tag == 'pre':
        code = plain(node).strip('\n')
        fence = '`' * max(3, max((len(x) + 1 for x in re.findall(r'`+', code)), default=3))
        return f'\n\n{fence}\n{code}\n{fence}\n\n'
    if tag == 'code' and not in_pre:
        fence = '`' * max(1, max((len(x) + 1 for x in re.findall(r'`+', body)), default=1))
        return f'{fence}{body}{fence}'
    if tag in {'h1', 'h2', 'h3', 'h4', 'h5', 'h6'}:
        return '\n\n' + '#' * int(tag[1]) + ' ' + re.sub(r'\s+', ' ', body).strip() + '\n\n'
    if tag in {'span', 'time'}:
        return body + ' '
    if tag == 'a' and attrs.get('href') and body.strip():
        url = urljoin(base, attrs['href']).replace(' ', '%20').replace(')', '%29')
        return f'[{body.strip()}]({url})'
    if tag == 'img' and attrs.get('alt'):
        return f'![{attrs["alt"]}]({urljoin(base, attrs.get("src", ""))})'
    if tag in {'ul', 'ol'}:
        items = [c for c in node.children if isinstance(c, Node) and c.tag == 'li']
        lines = []
        for i, item in enumerate(items, 1):
            content = render(item, base).strip()
            prefix = f'{i}. ' if tag == 'ol' else '- '
            lines.append(prefix + content.replace('\n', '\n' + ' ' * len(prefix)))
        return '\n\n' + '\n'.join(lines) + '\n\n'
    if tag in {'strong', 'b'}:
        return f'**{body.strip()}**'
    if tag in {'em', 'i'}:
        return f'*{body.strip()}*'
    if tag == 'br':
        return '\n'
    if tag == 'hr':
        return '\n\n---\n\n'
    if tag == 'table':
        def rows(n):
            if not isinstance(n, Node):
                return []
            if n.tag == 'tr':
                return [[render(c, base).strip().replace('|', '\\|').replace('\n', ' ') for c in n.children if isinstance(c, Node) and c.tag in {'th', 'td'}]]
            return [row for c in n.children for row in rows(c)]
        data = rows(node)
        if not data:
            return body
        width = max(map(len, data))
        data = [row + [''] * (width - len(row)) for row in data]
        data.insert(1, ['---'] * width)
        caption = find(node, 'caption')
        return '\n\n' + (render(caption, base).strip() + '\n\n' if caption else '') + '\n'.join('| ' + ' | '.join(row) + ' |' for row in data) + '\n\n'
    if tag in {'p', 'section', 'article', 'div', 'header', 'footer', 'aside', 'figure', 'details', 'summary', 'blockquote'}:
        return '\n\n' + body.strip() + '\n\n'
    return body


def to_markdown(source, canonical):
    document = Document(source).root
    main = find(document, 'main')
    if not main:
        raise ValueError(f'Page has no main content: {canonical}')
    result = render(main, canonical).strip()
    # Normalize blank lines outside fenced code blocks only.
    chunks = re.split(r'(^`{3,}[^\n]*\n.*?^`{3,}\s*$)', result, flags=re.M | re.S)
    result = ''.join(chunk if i % 2 else re.sub(r'\n[ \t]*\n(?:[ \t]*\n)+', '\n\n', chunk) for i, chunk in enumerate(chunks))
    return f'Canonical: {canonical}\n\n{result}\n'
