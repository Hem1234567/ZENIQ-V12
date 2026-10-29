import sys, re, io, os, json, copy, collections
from pptx import Presentation
from pptx.util import Emu
from analyze import walk, fsize, month_re
from PIL import Image

SRC = "/home/claude/render/"
OUT = "/home/claude/work/out/templates/ppt/"
os.makedirs(OUT, exist_ok=True)

CFG = {
 "n1": dict(name="Mono Minimal", premium=False, keep=[1,2,3,4,5,6,7,8,9,10], cover=1, closing=10, sub={1:3,10:3},
            minp={2:1,3:1,4:1,5:1,6:1,8:1,9:1}, brand=["THYNK UNLIMITED"], keepids={}, multi={2,3,4,5,6,7,8,9}),
 "n2": dict(name="Blue Geometric", premium=False, keep=[1,2,4,5,6,7,8,9,10,11,12], cover=1, closing=12, sub={1:6},
            minp={2:1,8:1,9:1,11:1}, lead={6:10}, brand=[], keepids={}, multi={2,11}),
 "n3": dict(name="Red Minimal", premium=False, keep=[1,2,3,4,6,7,8,10,13,14,15], cover=1, closing=15, sub={1:4},
            minp={3:1,8:1}, brand=["Borcelle University"], keepids={}, multi={3}),
 "p1": dict(name="Pitch Deck Dark", premium=True, keep=[1,2,3,4,6,10,12], cover=1, closing=12, sub={1:14},
            minp={2:1}, lead={6:2,10:25}, brand=["Salford & Co."], keepids={1:[18],12:[7,11]}, multi={2}, stripids={10:[7]}),
 "p2": dict(name="Investor Glass", premium=True, keep=[1,4,6,9], cover=1, closing=1, sub={},
            minp={}, lead={4:30,6:5,9:21}, label={4:{31:"Key Points"}}, brand=["THYNK UNLIMITED"], keepids={1:[25,26,27]},
            forcelist={4,6,9}, order={9:[11,18,17,19,20], 6:[9,12,16], 4:[32,33,34,35]}),
 "p3": dict(name="Midnight Gradient", premium=True, keep=[1,3,5,12,13,16], cover=1, closing=16, sub={},
            minp={3:1}, brand=["BORCELLE"], keepids={}, multi={3}),
}
FILES = {
 "n1": "n1.pptx", "n2": "n2.pptx", "n3": "n3.pptx", "p1": "p1.pptx", "p2": "p2.pptx", "p3": "p3.pptx",
}
STEPRE = re.compile(r'^(step|phase|stage)\s*\d+$', re.I)
WEBRE = re.compile(r'www\.|\.com|@|https?:|^\+?[\d\s\-()]{7,}$', re.I)

def collect(slide):
    L = []
    for sh, l, t, w, h in walk(slide.shapes):
        if sh.has_text_frame and sh.text_frame.text.strip():
            L.append(dict(id=sh.shape_id, l=l/12700, t=t/12700, w=w/12700, h=h/12700, f=fsize(sh),
                          txt=sh.text_frame.text.strip(), np=len(sh.text_frame.paragraphs)))
    return L

def token(x, cfg, has_page, keep):
    tx = x['txt']
    if x['id'] in keep: return None
    if tx.lower().startswith('lorem'): return 'BLANK'
    if month_re(tx): return 'DATE'
    if WEBRE.search(tx): return 'BLANK'
    if tx == 'Page': return None
    if re.fullmatch(r'(19|20)\d\d', tx): return 'YEAR'
    if tx in cfg.get('decor', {}): return cfg['decor'][tx]
    if re.fullmatch(r'\d{1,3}', tx): return 'PAGE' if has_page else None
    if re.fullmatch(r'\d{1,3}\.', tx): return None
    if tx in cfg['brand']: return 'TITLE'
    if STEPRE.match(tx): return None
    return 'BLANK'

def classify(L, cfg, sidx):
    """returns dict kind-specific slots"""
    keep = set(cfg.get('keepids', {}).get(sidx, []))
    has_page = any(x['txt'] == 'Page' for x in L)
    nums = [x for x in L if re.fullmatch(r'\d{1,3}\.?', x['txt'])]
    content = [x for x in L if x not in nums and not (x['txt'] == 'Page') and x['txt'] not in cfg['brand']
               and not re.fullmatch(r'(19|20)\d\d', x['txt']) and x['txt'] not in cfg.get('decor', {})
               and not WEBRE.search(x['txt']) and not STEPRE.match(x['txt']) and not month_re(x['txt'])
               and x['id'] not in keep]
    return content, nums, has_page, keep

def long_(x): return x['txt'].lower().startswith('lorem') or len(x['txt']) > 70 or x['np'] >= 1 and len(x['txt']) > 55

def build_slide(L, cfg, sidx):
    content, nums, has_page, keep = classify(L, cfg, sidx)
    title = max(content, key=lambda x: (x['f'], -x['t']))
    rest = [x for x in content if x is not title]
    lead_id = cfg.get('lead', {}).get(sidx)
    lead = next((x for x in rest if x['id'] == lead_id), None) if lead_id else None
    if lead: rest.remove(lead)
    label = cfg.get('label', {}).get(sidx, {})
    labels = [x for x in rest if x['id'] in label]
    for x in labels: rest.remove(x)
    forcelist = sidx in cfg.get('forcelist', set())
    minf = min([x['f'] for x in rest], default=0)
    bodies = [x for x in rest if len(x['txt']) > 70 or x['txt'].lower().startswith('lorem') or (x['f'] <= minf + 1 and len(x['txt']) > 20 and len(set(round(y['f']) for y in rest)) > 1)]
    if rest and not bodies and len(set(round(y['f']) for y in rest)) == 1 and all(len(x['txt']) > 45 for x in rest): bodies = list(rest)
    heads = [x for x in rest if x not in bodies]
    res = dict(title=title, lead=lead, labels=labels)
    if forcelist or not bodies:
        items = sorted(rest, key=lambda z: (round(z['t'] / 25), z['l']))
        if sidx in cfg.get('order', {}):
            byid = {x['id']: x for x in rest}
            items = [byid[i] for i in cfg['order'][sidx]]
        res['kind'] = 'LIST'; res['items'] = items
        return res, keep, has_page
    # pairs: each body gets the nearest heading above it
    bodies.sort(key=lambda z: (round(z['t'] / 30), z['l']))
    used = set(); pairs = []
    for b in bodies:
        best = None; bg = 1e9
        for h in heads:
            if h['id'] in used: continue
            if abs(h['t'] - b['t']) < max(45, 0.9 * h['h']) and h['l'] + h['w'] <= b['l'] + 40 and (b['l'] - (h['l'] + h['w'])) < 400:
                if abs(h['t'] - b['t']) < bg: bg = abs(h['t'] - b['t']); best = h
                continue
            ov = min(h['l'] + h['w'], b['l'] + b['w']) - max(h['l'], b['l'])
            if ov < 0.3 * min(h['w'], b['w']): continue
            gap = b['t'] - (h['t'] + h['h'])
            if gap < -0.5 * h['h'] or gap > 260: continue
            if abs(gap) < bg: bg = abs(gap); best = h
        if best: used.add(best['id'])
        pairs.append((best, b))
    pairs.sort(key=lambda p: (round(((p[0] or p[1])['t']) / 30), (p[0] or p[1])['l']))
    res['kind'] = 'PAIRS'; res['pairs'] = pairs
    res['extraHeads'] = [h for h in heads if h['id'] not in used]
    return res, keep, has_page

def compress(prs):
    saved = 0
    for part in list(prs.part.package.iter_parts()):
        ct = getattr(part, 'content_type', '')
        if ct not in ('image/png', 'image/jpeg'): continue
        blob = part.blob
        try:
            im = Image.open(io.BytesIO(blob)); im.load()
        except Exception:
            continue
        maxw = 1400
        has_alpha0 = im.mode in ('RGBA', 'LA') or (im.mode == 'P' and 'transparency' in im.info)
        if has_alpha0: maxw = 1000
        if im.width > maxw:
            r = maxw / im.width
            im = im.resize((maxw, max(1, int(im.height * r))), Image.LANCZOS)
        buf = io.BytesIO()
        if ct == 'image/jpeg':
            im.convert('RGB').save(buf, 'JPEG', quality=76, optimize=True, progressive=True)
        else:
            has_alpha = has_alpha0
            if has_alpha:
                im.save(buf, 'PNG', optimize=True)
            else:
                im.convert('RGB').save(buf, 'PNG', optimize=True)
                if len(buf.getvalue()) > 120000:
                    jb = io.BytesIO(); im.convert('RGB').save(jb, 'JPEG', quality=80, optimize=True, progressive=True)
                    if len(jb.getvalue()) < len(buf.getvalue()) * 0.7:
                        from pptx.opc.packuri import PackURI
                        part.partname = PackURI(str(part.partname).rsplit('.', 1)[0] + '.jpg')
                        part._content_type = 'image/jpeg'
                        buf = jb
        nb = buf.getvalue()
        if len(nb) < len(blob):
            saved += len(blob) - len(nb)
            part._blob = nb
    return saved

NSX = {'a': 'http://schemas.openxmlformats.org/drawingml/2006/main', 'p': 'http://schemas.openxmlformats.org/presentationml/2006/main',
       'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
EMBED = '{%s}embed' % NSX['r']

def strip_photos(prs, keep, cfg):
    """Removes every stock photo (JPEG pictures that are not a full-slide background) and the extra shapes listed in stripids."""
    removed = 0
    W, H = prs.slide_width, prs.slide_height
    for i, s in enumerate(prs.slides, 1):
        if i not in keep: continue
        tree = s.shapes._spTree
        kill = []
        for el in list(tree.iter('{%s}sp' % NSX['p'], '{%s}pic' % NSX['p'])):
            sid = el.find('.//p:cNvPr', NSX).get('id')
            if int(sid) in cfg.get('stripids', {}).get(i, []):
                kill.append(el); continue
            bf = el.find('p:spPr/a:blipFill/a:blip', NSX)
            if bf is None: bf = el.find('p:blipFill/a:blip', NSX)
            if bf is None or bf.get(EMBED) is None: continue
            part = s.part.related_part(bf.get(EMBED))
            if part.content_type != 'image/jpeg': continue
            x = el.find('p:spPr/a:xfrm/a:ext', NSX)
            area = (int(x.get('cx')) * int(x.get('cy')) / (W * H) * 100) if x is not None else 0
            if area >= 90: continue          # full-slide background
            kill.append(el)
        for el in kill:
            el.getparent().remove(el); removed += 1
        # drop pictures that nothing uses any more
        xml = s._element.xml
        for rId, rel in list(s.part.rels.items()):
            if rel.reltype.endswith('/image') and ('"%s"' % rId) not in xml:
                s.part.drop_rel(rId)
    return removed

def prune_and_save(tid, cfg):
    prs = Presentation(SRC + FILES[tid])
    keep = set(cfg['keep'])
    print('  stripped photos:', strip_photos(prs, keep, cfg))
    ids = prs.slides._sldIdLst
    rels = prs.part.rels
    for i, sldId in reversed(list(enumerate(list(ids), 1))):
        if i not in keep:
            prs.part.drop_rel(sldId.rId)
            ids.remove(sldId)
    saved = compress(prs)
    # drop notes
    for s in prs.slides:
        if s.has_notes_slide:
            for rId, rel in list(s.part.rels.items()):
                if rel.reltype.endswith('/notesSlide'):
                    s.part.drop_rel(rId)
    prs.save(OUT + tid + '.pptx')
    return os.path.getsize(OUT + tid + '.pptx')

def kt_box(x):
    return 'Box(%d,%.0ff,%.0ff,%.0ff,%s)' % (x['id'], x['w'], x['h'], x['f'], 'true' if x['txt'].isupper() else 'false')

def main():
    lines = []
    report = []
    for tid, cfg in CFG.items():
        src = Presentation(SRC + FILES[tid])
        srcslides = list(src.slides)
        order = sorted(cfg['keep'])
        newidx = {s: i + 1 for i, s in enumerate(order)}
        kslides = []
        for s in order:
            L = collect(srcslides[s - 1])
            ni = newidx[s]
            is_cover = (s == cfg['cover']); is_closing = (s == cfg['closing'])
            content, nums, has_page, keep = classify(L, cfg, s)
            decos = []
            if is_cover or is_closing:
                title = max(content, key=lambda x: (x['f'], -x['t']))
                subid = cfg.get('sub', {}).get(s)
                sub = next((x for x in content if x['id'] == subid), None) if subid else None
                slots = {title['id']} | ({sub['id']} if sub else set())
                for x in L:
                    if x['id'] in slots: continue
                    t = token(x, cfg, has_page, keep)
                    if t: decos.append((x['id'], t))
                kind = 'COVER' if is_cover else 'CLOSING'
                kslides.append('TSlide(%d,K.%s,title=%s,sub=%s,deco=listOf(%s))' % (
                    ni, kind, kt_box(title), kt_box(sub) if sub else 'null',
                    ','.join('Deco(%d,"%s")' % d for d in decos)))
                report.append('%s s%d(%d) %s title=%s sub=%s deco=%s' % (tid, s, ni, kind, title['txt'][:20], sub['txt'][:20] if sub else None, decos))
                continue
            res, keep, has_page = build_slide(L, cfg, s)
            slots = {res['title']['id']}
            if res.get('lead'): slots.add(res['lead']['id'])
            for x in res.get('labels', []): slots.add(x['id'])
            if res['kind'] == 'PAIRS':
                for h, b in res['pairs']:
                    if h: slots.add(h['id'])
                    slots.add(b['id'])
                for h in res.get('extraHeads', []): pass
            else:
                for x in res['items']: slots.add(x['id'])
            labelmap = cfg.get('label', {}).get(s, {})
            for x in L:
                if x['id'] in slots: continue
                if x['id'] in labelmap: continue
                t = token(x, cfg, has_page, keep)
                if t: decos.append((x['id'], t))
            for x in res.get('labels', []):
                decos.append((x['id'], 'TXT:' + labelmap[x['id']]))
            if res['kind'] == 'PAIRS':
                pairs = ','.join('PS(%s,%s)' % (kt_box(h) if h else 'null', kt_box(b)) for h, b in res['pairs'])
                kslides.append('TSlide(%d,K.PAIRS,title=%s,lead=%s,pairs=listOf(%s),minPairs=%d,multi=%s,deco=listOf(%s))' % (
                    ni, kt_box(res['title']), kt_box(res['lead']) if res.get('lead') else 'null', pairs,
                    cfg.get('minp', {}).get(s, len(res['pairs'])), 'true' if s in cfg.get('multi', set()) else 'false', ','.join('Deco(%d,"%s")' % d for d in decos)))
                report.append('%s s%d(%d) PAIRS title=%s lead=%s pairs=%s extraHeads=%s' % (
                    tid, s, ni, res['title']['txt'][:22], res['lead']['txt'][:10] if res.get('lead') else None,
                    [((h['txt'][:14] if h else None), b['txt'][:10]) for h, b in res['pairs']],
                    [h['txt'][:14] for h in res.get('extraHeads', [])]))
            else:
                items = ','.join(kt_box(x) for x in res['items'])
                kslides.append('TSlide(%d,K.LIST,title=%s,lead=%s,items=listOf(%s),deco=listOf(%s))' % (
                    ni, kt_box(res['title']), kt_box(res['lead']) if res.get('lead') else 'null', items,
                    ','.join('Deco(%d,"%s")' % d for d in decos)))
                report.append('%s s%d(%d) LIST title=%s lead=%s items=%s deco=%s' % (
                    tid, s, ni, res['title']['txt'][:22], res['lead']['txt'][:10] if res.get('lead') else None,
                    [x['txt'][:14] for x in res['items']], decos))
        size = prune_and_save(tid, cfg)
        lines.append('        TplDef("%s", "%s", %s, "templates/ppt/%s.pptx", listOf(\n            %s\n        ))' % (
            tid, cfg['name'], 'true' if cfg['premium'] else 'false', tid, ',\n            '.join(kslides)))
        report.append('== %s saved %.1f MB' % (tid, size / 1e6))
    open('catalog_body.txt', 'w').write(',\n'.join(lines))
    print('\n'.join(report))
main()
