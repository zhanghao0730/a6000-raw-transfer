#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 ServerThread.java 里抽出内联的 CSS/JS，配合假数据生成一份本地预览页。
   目的是不装机也能看到网页端的样子（样式与真机完全同源）。"""
import os
import re
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(HERE, 'src', 'com', 'local', 'arwserver', 'ServerThread.java')
OUT = os.path.join(HERE, 'preview', 'gallery-preview.html')

src = open(JAVA, encoding='utf-8').read()


def const(name):
    m = re.search(r'String ' + name + r' =\s*(.*?);\n', src, re.S)
    if not m:
        raise SystemExit('const %s not found' % name)
    return ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))


css = const('CSS')
js = const('JS')
print('CSS %d chars / JS %d chars' % (len(css), len(js)))

SAMPLE = [
    ('DSC05231.ARW', '24.6 MB', '100MSDCF', 'RAW',  '13:02', (210, 32)),
    ('DSC05230.ARW', '24.5 MB', '100MSDCF', 'RAW',  '13:01', (195, 38)),
    ('DSC05229.JPG', '7.8 MB',  '100MSDCF', 'JPEG', '13:00', (268, 34)),
    ('DSC05228.ARW', '24.7 MB', '100MSDCF', 'RAW',  '12:59', (160, 36)),
    ('DSC05227.ARW', '24.4 MB', '101MSDCF', 'RAW',  '12:41', (340, 30)),
    ('DSC05226.JPG', '8.1 MB',  '101MSDCF', 'JPEG', '12:40', (226, 40)),
]


def placeholder(h, s):
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300">'
        '<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="hsl({h},{s}%,26%)"/>'
        '<stop offset="1" stop-color="hsl({h},20%,9%)"/></linearGradient></defs>'
        '<rect width="400" height="300" fill="url(#g)"/>'
        '<circle cx="300" cy="80" r="34" fill="hsl({h},55%,58%)" opacity=".5"/>'
        '<path d="M0 220 L120 150 L210 205 L300 140 L400 200 L400 300 L0 300 Z"'
        ' fill="hsl({h},28%,16%)"/></svg>'
    ).format(h=h, s=s)
    return 'data:image/svg+xml,' + urllib.parse.quote(svg)


CAM = ('<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#19a974"'
       ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
       '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4'
       'a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>')

out = []
out.append("<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
           "<meta name='viewport' content='width=device-width,initial-scale=1'>"
           "<title>相机相册 · 本地预览</title><style>%s</style></head><body>" % css)
out.append('<header><h1>%s相机相册 <span class="pill">DCIM</span></h1>'
           '<div class="stat">共 128 张 · RAW 96 · JPEG 32 · 2.1 GB'
           '　<span style="color:#19a974">（本地预览，样式与真机同源）</span></div></header>' % CAM)
out.append('<div class="tabs"><button class="tab on" data-f="all">全部</button>'
           '<button class="tab" data-f="raw">仅 RAW</button>'
           '<button class="tab" data-f="jpg">仅 JPEG</button>'
           '<a class="tab" href="#">刷新</a></div>')
out.append('<main class="grid">')
for i, (name, size, folder, kind, tm, hue) in enumerate(SAMPLE):
    cls = 'raw' if kind == 'RAW' else 'jpg'
    out.append(
        '<figure class="card" data-t="%s">'
        '<span class="bdg %s">%s</span>'
        '<label class="pick"><input type="checkbox" value="%s/%s"></label>'
        '<a class="thumb" href="#"><img src="%s" alt=""></a>'
        '<figcaption class="cap"><span class="nm">%s</span>'
        '<span class="mt">%s · 10-13 %s · %s</span>'
        '<a class="dl" href="#">下载原文件</a></figcaption></figure>'
        % (cls, cls, kind, folder, name, placeholder(hue[0], hue[1]),
           name, size, tm, folder))
out.append('</main>')
out.append('<div class="dock"><button class="btn" id="selall">全选</button>'
           '<button class="btn" id="selnone">清空</button>'
           '<span class="cnt" id="cnt">未选择</span>'
           '<button class="btn p" id="dl" disabled>打包下载</button></div>')
out.append('<footer>ARW Server v19 · 相机热点直连 · 浏览器直接下载 RAW</footer>')
out.append('<script>%s</script></body></html>' % js)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, 'w', encoding='utf-8').write('\n'.join(out))
print('written %s (%d bytes)' % (OUT, os.path.getsize(OUT)))
