"""Rebuild fonts/xiaowei-subset.woff2 with every character the page can show.
Needs: pip install fonttools brotli; npm i @fontsource/zcool-xiaowei
Usage: python3 subset_font.py <index.html> <app.js source> <out.woff2> [node_modules dir]"""
import re, sys, os
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.merge import Merger
html, js, out = sys.argv[1:4]
base = os.path.join(sys.argv[4] if len(sys.argv) > 4 else 'node_modules', '@fontsource/zcool-xiaowei')
text = open(html, encoding='utf8').read() + open(js, encoding='utf8').read() + '0123456789%'
chars = sorted({c for c in text if ord(c) > 0x2E7F or c.isalnum() or c in '%“”，。？：（）'})
css = open(base + '/400.css').read()
blocks = re.findall(r'src:\s*url\(\./files/([^)]+?\.woff2)\).*?unicode-range:\s*([^;]+);', css, flags=re.S)
def ranges(rng):
    for part in rng.split(','):
        part = part.strip().upper().replace('U+', '')
        if '-' in part: a, b = part.split('-'); yield int(a, 16), int(b, 16)
        elif '?' in part: yield int(part.replace('?', '0'), 16), int(part.replace('?', 'F'), 16)
        else: yield int(part, 16), int(part, 16)
need = {}
for c in chars:
    for f, rng in blocks:
        if any(a <= ord(c) <= b for a, b in ranges(rng)):
            need.setdefault(f, []).append(ord(c)); break
parts = []
for i, (f, us) in enumerate(need.items()):
    font = TTFont(f'{base}/files/{f}')
    opt = subset.Options(); opt.layout_features = []; opt.notdef_outline = True
    s = subset.Subsetter(opt); s.populate(unicodes=us); s.subset(font)
    p = f'/tmp/xw-part{i}.ttf'; font.flavor = None; font.save(p); parts.append(p)
merged = Merger().merge(parts); merged.flavor = 'woff2'; merged.save(out)
print(len(merged.getBestCmap()), 'characters,', os.path.getsize(out), 'bytes')
