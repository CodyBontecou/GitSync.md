#!/usr/bin/env python3
"""Render page-specific sharing cards; requires Pillow. Fonts are bundled with their license."""
import argparse
from html import escape, unescape
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'scripts/assets/manrope/Manrope.ttf'
SIZE = (1200, 630)


def font(size, weight=500):
    face = ImageFont.truetype(str(FONT), size)
    face.set_variation_by_axes([weight])
    return face


def wrap(text, face, width):
    lines = []
    for word in text.split():
        if face.getlength(word) > width:
            raise ValueError(f'Word is too wide for card: {word}')
        if lines and face.getlength(lines[-1] + ' ' + word) <= width:
            lines[-1] += ' ' + word
        else:
            lines.append(word)
    if len(lines) > 1 and len(lines[-1].split()) == 1:
        previous = lines[-2].split()
        if len(previous) > 1 and face.getlength(previous[-1] + ' ' + lines[-1]) <= width:
            lines[-1] = previous.pop() + ' ' + lines[-1]
            lines[-2] = ' '.join(previous)
    return lines


def render(title, category, output):
    image = Image.new('RGB', SIZE, '#ffffff')
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1199, 11), fill='#007aff')
    draw.line((64, 136, 1136, 136), fill='#050505', width=2)
    # Render the existing app icon as an asset in this new card composition.
    with Image.open(ROOT / 'site/assets/gitsync-app-icon.png') as source:
        icon = source.convert('RGBA').resize((64, 64), Image.Resampling.LANCZOS)
        image.paste(icon, (64, 48), icon)
    draw.text((144, 56), 'GitSync.md', font=font(36, 700), fill='#050505')
    label = font(24, 600)
    draw.text((1136 - label.getlength(category), 64), category, font=label, fill='#565656')
    for size in (72, 60, 48):
        face = font(size, 700)
        lines = wrap(title, face, 1072)
        if len(lines) <= 3:
            break
    else:
        raise ValueError(f'Title needs an editorial shortening: {title}')
    line_height = size if size >= 48 else 48
    top = 180 + (240 - line_height * len(lines)) // 2
    for line in lines:
        draw.text((64, top), line, font=face, fill='#050505', anchor='lt')
        top += line_height
    draw.line((64, 518, 1136, 518), fill='#050505', width=2)
    draw.text((64, 552), 'Your files. Your commits. Your remote.', font=font(24), fill='#565656')
    draw.text((1000, 552), 'gitsync.md', font=font(24, 600), fill='#007aff')
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, optimize=True)


def build(site, update_metadata=False):
    count = 0
    for page in sorted(site.rglob('*.html')):
        source = page.read_text()
        title = unescape(re.search(r'<title>(.*?)</title>', source, re.S)[1])
        title = title.removesuffix(' — GitSync.md')
        relative = page.relative_to(site)
        card = Path('social') / relative.with_suffix('.png')
        category = 'Comparison' if relative.parts[0] == 'compare' else (
            'Guide library' if relative.as_posix() == 'blog/index.html' else
            'Guide' if relative.parts[0] == 'blog' else
            {'index': 'Git on mobile', 'about': 'About', 'contact': 'Support',
             'privacy': 'Privacy', 'terms': 'Terms', '404': 'Help'}[page.stem])
        render(title, category, site / card)
        if update_metadata:
            url = 'https://gitsync.md/' + card.as_posix()
            alt = f'GitSync.md {category.lower()} card: {title}'
            for key, attribute in [('og:image', 'property'), ('twitter:image', 'name')]:
                source, changed = re.subn(r'<meta ' + attribute + r'="' + key + r'" content="[^"]+">',
                                         lambda _: f'<meta {attribute}="{key}" content="{url}">', source)
                if changed != 1:
                    raise ValueError(f'{page}: expected one {key}')
            for key in ('og:image:width', 'og:image:height', 'og:image:type', 'og:image:alt', 'twitter:image:alt'):
                source = re.sub(r'<meta (?:property|name)="' + key + r'" content="[^"]*">\n?', '', source)
            extra = '\n'.join(f'<meta property="{key}" content="{escape(value, quote=True)}">' for key, value in [
                ('og:image:width', '1200'), ('og:image:height', '630'), ('og:image:type', 'image/png'), ('og:image:alt', alt)])
            source = source.replace(f'<meta property="og:image" content="{url}">',
                                    f'<meta property="og:image" content="{url}">\n' + extra)
            source = source.replace(f'<meta name="twitter:image" content="{url}">',
                                    f'<meta name="twitter:image" content="{url}">\n<meta name="twitter:image:alt" content="{escape(alt, quote=True)}">')
            page.write_text(source)
        count += 1
    return count


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, default=ROOT / 'site')
    parser.add_argument('--update-metadata', action='store_true', help='Point HTML sharing tags at generated cards')
    args = parser.parse_args()
    print(f'Rendered {build(args.site.resolve(), args.update_metadata)} sharing cards.')
