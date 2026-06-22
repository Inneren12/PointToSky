#!/usr/bin/env python3
"""
build_catalog_variant_b.py — PTSKCAT4 v4 star.bin = A2 BSC bulk + curated skeleton/asterisms/art.

PHONE catalog producer for Variant B. Layers curated constellation content (Orion, Lyra, Ursa Major)
on top of the dense A2 BSC catalog, read by core/astro PtskCatalogLoader.

Design (decided after recon, "Option 1"):
  * BSC bulk is built EXACTLY as build_bsc_ptskcat4_a2.py (same sorted order, same ids, same string
    interning order) -> the bulk STAR records are byte-identical to A2. Bulk stars are the catalog's
    points + identify/search/skymap targets + asterism/art anchors.
  * Curated SKELETON nodes are added as SEPARATE STAR records flagged LINE_NODE | AUX_ONLY, with pp in
    a RESERVED high range (SKEL_PP_BASE=50; bulk pp is <=2 globally, so no id collision). Each curated
    skeleton polyline maps to one reserved pp; nodes carry ss = node order. Their geometry (ra/dec/mag)
    is taken from the position-matched bulk star so skeleton segment endpoints land exactly on the dots.
    AUX_ONLY means: drawn as skeleton geometry (the LINE_NODE gate keeps them) but excluded from the
    rendered point set AND from identify/search/skymap (a tiny app code change reads AUX_ONLY there).
    This is what lets a star in N skeleton polylines (betelgeuse, bellatrix, dubhe, merak) exist as N
    node records without showing up as N points / N identify hits.
  * ASTR/APLY/ASTN (named asterisms) and ART0 (art overlays) reference the BSC BULK star ids, resolved
    by position match (curated key -> nearest bulk star). No star duplication on this path.
  * STR0 offset 0 is reserved for "" (loader maps empty name -> null); skeleton nodes use nameId 0.

Wear renders no skeleton/asterism/art, so the wear catalog stays the A2 output (build_bsc_ptskcat4_a2.py);
this generator is phone-only.

Sources (public domain / open):
  BSC5 JSON  : https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json
  IAU bounds : https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.bounds.json

Usage:
  build_catalog_variant_b.py <out.bin> [--input=bsc5-all.json] [--bounds=constellations.bounds.json]
                                       [--curated-dir=res] [--mag-limit=6.5]
"""
import sys, os, io, json, struct, collections, math, urllib.request

BSC_MIRROR = "https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json"
BND_MIRROR = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.bounds.json"

CONST_LIST = [
 ("And","Andromeda"),("Ant","Antlia"),("Aps","Apus"),("Aqr","Aquarius"),("Aql","Aquila"),
 ("Ara","Ara"),("Ari","Aries"),("Aur","Auriga"),("Boo","Boötes"),("Cae","Caelum"),
 ("Cam","Camelopardalis"),("Cnc","Cancer"),("CVn","Canes Venatici"),("CMa","Canis Major"),("CMi","Canis Minor"),
 ("Cap","Capricornus"),("Car","Carina"),("Cas","Cassiopeia"),("Cen","Centaurus"),("Cep","Cepheus"),
 ("Cet","Cetus"),("Cha","Chamaeleon"),("Cir","Circinus"),("Col","Columba"),("Com","Coma Berenices"),
 ("CrA","Corona Australis"),("CrB","Corona Borealis"),("Crv","Corvus"),("Crt","Crater"),("Cru","Crux"),
 ("Cyg","Cygnus"),("Del","Delphinus"),("Dor","Dorado"),("Dra","Draco"),("Equ","Equuleus"),
 ("Eri","Eridanus"),("For","Fornax"),("Gem","Gemini"),("Gru","Grus"),("Her","Hercules"),
 ("Hor","Horologium"),("Hya","Hydra"),("Hyi","Hydrus"),("Ind","Indus"),("Lac","Lacerta"),
 ("Leo","Leo"),("LMi","Leo Minor"),("Lep","Lepus"),("Lib","Libra"),("Lup","Lupus"),
 ("Lyn","Lynx"),("Lyr","Lyra"),("Men","Mensa"),("Mic","Microscopium"),("Mon","Monoceros"),
 ("Mus","Musca"),("Nor","Norma"),("Oct","Octans"),("Oph","Ophiuchus"),("Ori","Orion"),
 ("Pav","Pavo"),("Peg","Pegasus"),("Per","Perseus"),("Phe","Phoenix"),("Pic","Pictor"),
 ("Psc","Pisces"),("PsA","Piscis Austrinus"),("Pup","Puppis"),("Pyx","Pyxis"),("Ret","Reticulum"),
 ("Sge","Sagitta"),("Sgr","Sagittarius"),("Sco","Scorpius"),("Scl","Sculptor"),("Sct","Scutum"),
 ("Ser","Serpens"),("Sex","Sextans"),("Tau","Taurus"),("Tel","Telescopium"),("Tri","Triangulum"),
 ("TrA","Triangulum Australe"),("Tuc","Tucana"),("UMa","Ursa Major"),("UMi","Ursa Minor"),("Vel","Vela"),
 ("Vir","Virgo"),("Vol","Volans"),("Vul","Vulpecula"),
]
ABBR = {a: i for i, (a, _) in enumerate(CONST_LIST)}
ABBRS = [a for a, _ in CONST_LIST]

BRIGHT    = 0x01
LINE_NODE = 0x02
AUX_ONLY  = 0x08
STAR_RECORD_SIZE = 24
ASTR_RECORD_SIZE = 20
APLY_RECORD_SIZE = 12
ASTN_RECORD_SIZE = 4
ART0_RECORD_SIZE = 16

SKEL_PP_BASE = 50         # curated skeleton polylines occupy pp >= 50 (bulk pp is <= 2)
MATCH_TOL_DEG = 0.10      # curated star must match a bulk star within this angular separation
MATCH_WARN_DEG = 0.02     # warn if the match is farther than this (curated vs BSC should agree closely)

# ---------- BSC field helpers (identical to A2) ----------
def _f(x):
    try: return float(str(x).strip())
    except Exception: return None
def _ra(r):
    h, m, s = _f(r.get("RAh")), _f(r.get("RAm")), _f(r.get("RAs"))
    return None if None in (h, m, s) else (h + m/60 + s/3600) * 15.0
def _dec(r):
    d, m, s = _f(r.get("DEd")), _f(r.get("DEm")), _f(r.get("DEs"))
    if None in (d, m, s): return None
    sign = -1 if (r.get("DE-") or "").strip() == "-" else 1
    return sign * (d + m/60 + s/3600)
def _label(r):
    for k in ("Common", "BayerF", "FlamsteedF"):
        v = (r.get(k) or "").strip()
        if v: return v
    return ""

# ---------- curated HMS/DMS parsing ----------
def parse_hms(s):
    if isinstance(s, (int, float)): return float(s)
    p = s.strip().split(":")
    if len(p) != 3: return float(s)
    return (float(p[0]) + float(p[1])/60 + float(p[2])/3600) * 15.0
def parse_dms(s):
    if isinstance(s, (int, float)): return float(s)
    t = s.strip(); sign = -1.0 if t.startswith("-") else 1.0
    t = t.lstrip("+-"); p = t.split(":")
    if len(p) != 3: return float(s)
    return sign * (float(p[0]) + float(p[1])/60 + float(p[2])/3600)

# ---------- validated meridian-ray point-in-polygon (same as const_v1.bin / A2) ----------
def _nra(v):
    r = v % 360.0
    if r < 0: r += 360.0
    return 0.0 if r == 360.0 else r
def _sdiff(a, b):
    d = (a - b) % 360.0
    if d < -180.0: d += 360.0
    if d >= 180.0: d -= 360.0
    return d
def _build_poly(ring):
    ra = [_nra(p[0]) for p in ring]; dec = [p[1] for p in ring]; n = len(ra)
    if n >= 2 and abs(ra[0]-ra[n-1]) < 1e-6 and abs(dec[0]-dec[n-1]) < 1e-6:
        ra = ra[:n-1]; dec = dec[:n-1]; n -= 1
    if n == 0: return None
    wind = sum(_sdiff(ra[(i+1) % n], ra[i]) for i in range(n)); md = sum(dec)/n
    return (ra, dec, abs(wind) > 180.0 and md > 0, abs(wind) > 180.0 and md < 0)
def _contains(poly, qra, qdec):
    ra, dec, eN, eS = poly; n = len(ra); up = qdec <= 0.0; cr = 0
    for i in range(n):
        j = (i+1) % n
        x1 = _sdiff(ra[i], qra); x2 = x1 + _sdiff(ra[j], ra[i]); y1 = dec[i]-qdec; y2 = dec[j]-qdec
        if (x1 <= 0.0 < x2) or (x2 <= 0.0 < x1):
            yc = y1 + (y2-y1) * ((-x1)/(x2-x1))
            if (up and yc > 0.0) or ((not up) and yc < 0.0): cr += 1
    ins = cr % 2 == 1
    if up and eN: ins = not ins
    if (not up) and eS: ins = not ins
    return ins
def _rings_of(g):
    if g["type"] == "Polygon": return list(g["coordinates"])
    if g["type"] == "MultiPolygon": return [r for p in g["coordinates"] for r in p]
    return []
def load_boundaries(bounds_data):
    by = collections.defaultdict(list)
    for feat in bounds_data["features"]:
        code = feat["id"]
        if code not in ABBR: raise SystemExit(f"unknown boundary id {code}")
        for ring in _rings_of(feat["geometry"]):
            poly = _build_poly([(lon % 360.0, lat) for lon, lat in ring])
            if poly: by[code].append(poly)
    return by
def pip_assign(boundaries, ra, dec):
    ra = _nra(ra)
    for abbr in ABBRS:
        for poly in boundaries.get(abbr, ()):
            if _contains(poly, ra, dec): return abbr
    return None

def ang_sep_deg(ra1, dec1, ra2, dec2):
    r1, d1, r2, d2 = map(math.radians, (ra1, dec1, ra2, dec2))
    v = math.sin(d1)*math.sin(d2) + math.cos(d1)*math.cos(d2)*math.cos(r1-r2)
    return math.degrees(math.acos(max(-1.0, min(1.0, v))))

def make_id(cc, pp, ss):
    return cc*10000 + (pp % 100)*100 + (ss % 100)

# ---------- build ----------
def build(bulk_rows, curated_dir, out_path):
    # bulk_rows: list of (abbr, ra, dec, mag, label), already filtered to <= mag-limit
    # ---- STR0: reserve offset 0 = "" (loader maps empty name -> null) ----
    blob = bytearray(b"\x00"); off = {"": 0}
    def sid(s):
        if not s: return 0
        if s in off: return off[s]
        o = len(blob); blob.extend(s.encode("utf-8")); blob.append(0); off[s] = o; return o
    # interning order MUST match A2 for byte-identical bulk: const strings first, then bulk labels.
    for a, n in CONST_LIST: sid(a); sid(n)

    # ---- bulk STAR records (identical construction to A2) ----
    counter = collections.Counter(); recs = []; seen = set()
    bulk_by_abbr = collections.defaultdict(list)   # abbr -> list of (id, ra, dec, mag) for matching
    for abbr, ra, dec, mag, lab in sorted(bulk_rows, key=lambda x: (ABBR[x[0]], x[3])):
        cc = ABBR[abbr]; n = counter[cc]; counter[cc] += 1
        if n >= 10000: raise SystemExit(f"constellation {abbr} exceeds 10000 stars")
        sidv = cc*10000 + n
        if (sidv // 10000) != cc: raise SystemExit("cc invariant broken (bulk)")
        if sidv in seen: raise SystemExit(f"dup id {sidv}")
        seen.add(sidv)
        flags = BRIGHT if mag < 2.0 else 0
        recs.append(struct.pack("<IfffHHI", sidv, ra, dec, mag, cc, flags, sid(lab)))
        bulk_by_abbr[abbr].append((sidv, ra, dec, mag))
    bulk_count = len(recs)

    # global flat list for nearest-match (id, ra, dec)
    bulk_flat = [(sidv, ra, dec) for abbr in bulk_by_abbr for (sidv, ra, dec, _) in bulk_by_abbr[abbr]]

    def match_bulk(qra, qdec, want_abbr):
        best = None; bestsep = 999.0
        for sidv, ra, dec in bulk_flat:
            s = ang_sep_deg(qra, qdec, ra, dec)
            if s < bestsep: bestsep = s; best = (sidv, ra, dec)
        if best is None or bestsep > MATCH_TOL_DEG:
            raise SystemExit(f"curated star in {want_abbr} at ({qra:.4f},{qdec:.4f}) "
                             f"has no bulk match within {MATCH_TOL_DEG} deg (nearest {bestsep:.4f})")
        if bestsep > MATCH_WARN_DEG:
            print(f"  WARN: {want_abbr} curated ({qra:.4f},{qdec:.4f}) matched bulk id {best[0]} "
                  f"at sep {bestsep*60:.2f}'")
        return best  # (id, ra, dec)

    # ---- load curated JSONs ----
    files = sorted(f for f in os.listdir(curated_dir) if f.lower().endswith(".json"))
    per_const = []
    for fn in files:
        with open(os.path.join(curated_dir, fn), "r", encoding="utf-8") as fh:
            per_const.append(json.load(fh))

    # resolve every curated star key -> matched bulk (id, ra, dec, mag)
    key2bulk = {}   # (abbr, key) -> (id, ra, dec, mag)
    star_mag = {}   # (abbr, key) -> curated mag (for BRIGHT decision on skeleton nodes; use bulk-matched)
    for data in per_const:
        abbr = data["abbr"]
        if abbr not in ABBR: raise SystemExit(f"curated unknown abbr {abbr}")
        for s in data.get("stars", []):
            key = s["key"]
            qra = parse_hms(s["ra"]); qdec = parse_dms(s["dec"])
            mid, mra, mdec = match_bulk(qra, qdec, abbr)
            # find the matched bulk mag
            mmag = next(m for (i, r, d, m) in bulk_by_abbr[abbr] if i == mid) \
                   if any(i == mid for (i, r, d, m) in bulk_by_abbr[abbr]) \
                   else float(s.get("mag", 9.0))
            key2bulk[(abbr, key)] = (mid, mra, mdec, mmag)

    # ---- curated skeleton nodes: LINE_NODE | AUX_ONLY, reserved pp, geometry from matched bulk ----
    skel_segments_expected = 0
    for data in per_const:
        abbr = data["abbr"]; cc = ABBR[abbr]
        for j, skel in enumerate(data.get("skeleton", [])):
            pp = SKEL_PP_BASE + j
            if pp > 99 or (pp*100 + len(skel["nodes"])) >= 10000:
                raise SystemExit(f"{abbr} skeleton pp {pp} would break cc invariant")
            nodes = skel["nodes"]
            if len(nodes) >= 2: skel_segments_expected += len(nodes) - 1
            for ss, key in enumerate(nodes, start=1):
                mid, mra, mdec, mmag = key2bulk[(abbr, key)]
                nid = make_id(cc, pp, ss)
                if (nid // 10000) != cc: raise SystemExit("cc invariant broken (node)")
                if nid in seen: raise SystemExit(f"skeleton node id {nid} collides")
                seen.add(nid)
                flags = LINE_NODE | AUX_ONLY | (BRIGHT if mmag < 2.0 else 0)
                recs.append(struct.pack("<IfffHHI", nid, mra, mdec, mmag, cc, flags, 0))
    node_count = len(recs) - bulk_count

    # ---- asterisms (ASTR/APLY/ASTN) referencing bulk ids ----
    ASTR = bytearray(); APLY = bytearray(); ASTN = bytearray()
    aply_count = 0; node_total = 0; astr_count = 0
    def add_poly(ids, style=0):
        nonlocal aply_count, node_total
        start = node_total
        for sidv in ids: ASTN.extend(struct.pack("<I", sidv))
        node_total += len(ids)
        APLY.extend(struct.pack("<IHHI", start, len(ids), style, 0)); aply_count += 1
    for data in per_const:
        abbr = data["abbr"]; cc = ABBR[abbr]
        for ast in data.get("asterisms", []):
            poly_start = aply_count
            for poly in ast.get("polylines", []):
                ids = [key2bulk[(abbr, k)][0] for k in poly]
                add_poly(ids, style=0)
            poly_count = aply_count - poly_start
            label_key = ast.get("label")
            label_id = key2bulk[(abbr, label_key)][0] if label_key else 0
            ASTR.extend(struct.pack("<HHIIHHI", cc, 0, sid(ast["name"]), poly_start, poly_count, 0, label_id))
            astr_count += 1

    # ---- art overlays (ART0) referencing bulk ids ----
    ART0 = bytearray(); art_count = 0
    for data in per_const:
        abbr = data["abbr"]; cc = ABBR[abbr]
        for art in data.get("art_overlays", []):
            a_id = key2bulk[(abbr, art["anchorA"])][0]
            b_id = key2bulk[(abbr, art["anchorB"])][0]
            ART0.extend(struct.pack("<HHIII", cc, 0, sid(art["key"]), a_id, b_id)); art_count += 1

    # ---- CST0 (after all strings interned; abbr/name offsets are stable) ----
    cst = bytearray()
    for a, n in CONST_LIST: cst.extend(struct.pack("<IIII", off[a], off[n], 0, 0))

    STR0 = bytes(blob); STAR = b"".join(recs)
    assert len(STAR) % STAR_RECORD_SIZE == 0
    assert len(ASTR) % ASTR_RECORD_SIZE == 0
    assert len(APLY) % APLY_RECORD_SIZE == 0
    assert len(ASTN) % ASTN_RECORD_SIZE == 0
    assert len(ART0) % ART0_RECORD_SIZE == 0

    secs = [(b"STR0", STR0, len(off)), (b"CST0", bytes(cst), len(CONST_LIST)),
            (b"STAR", STAR, len(STAR)//STAR_RECORD_SIZE),
            (b"ASTR", bytes(ASTR), astr_count), (b"APLY", bytes(APLY), aply_count),
            (b"ASTN", bytes(ASTN), node_total), (b"ART0", bytes(ART0), art_count)]
    hs = 8 + 4 + 4 + len(secs)*16; payload = bytearray(); dirs = []; o = 0
    for fc, ct, cnt in secs:
        if o % 4: pad = 4 - (o % 4); payload.extend(b"\x00"*pad); o += pad
        dirs.append((fc, hs+o, len(ct), cnt)); payload.extend(ct); o += len(ct)
    b = io.BytesIO(); b.write(b"PTSKCAT4"); b.write(struct.pack("<I", 4)); b.write(struct.pack("<I", len(secs)))
    for fc, oo, ln, cnt in dirs: b.write(struct.pack("<4sIII", fc, oo, ln, cnt))
    b.write(payload)
    open(out_path, "wb").write(b.getvalue())
    return dict(bulk=bulk_count, nodes=node_count, astr=astr_count, aply=aply_count,
                astn=node_total, art=art_count, segs=skel_segments_expected, size=len(b.getvalue()))

def main(argv):
    if len(argv) < 2:
        print(__doc__); return 1
    out = argv[1]; inp = None; bnd = None; cur = "res"; mag = 6.5
    for a in argv[2:]:
        if a.startswith("--input="): inp = a.split("=", 1)[1]
        elif a.startswith("--bounds="): bnd = a.split("=", 1)[1]
        elif a.startswith("--curated-dir="): cur = a.split("=", 1)[1]
        elif a.startswith("--mag-limit="): mag = float(a.split("=", 1)[1])
    data = json.load(open(inp)) if (inp and os.path.exists(inp)) else \
        json.loads(urllib.request.urlopen(BSC_MIRROR, timeout=60).read())
    bdata = json.load(open(bnd)) if (bnd and os.path.exists(bnd)) else \
        json.loads(urllib.request.urlopen(BND_MIRROR, timeout=60).read())
    boundaries = load_boundaries(bdata)
    rows = []; from_field = 0; from_pip = 0; dropped = 0
    for r in data:
        ra, dec, m = _ra(r), _dec(r), _f(r.get("Vmag"))
        if None in (ra, dec, m) or m > mag: continue
        con = (r.get("Constellation") or "").strip()
        if con in ABBR: abbr = con; from_field += 1
        else:
            abbr = pip_assign(boundaries, ra, dec)
            if abbr is None: dropped += 1; continue
            from_pip += 1
        rows.append((abbr, ra, dec, m, _label(r)))
    st = build(rows, cur, out)
    print(f"wrote {out}  [mag<= {mag}]  {st['size']} bytes")
    print(f"  bulk stars     : {st['bulk']}  ({from_field} field, {from_pip} PIP, {dropped} dropped)")
    print(f"  skeleton nodes : {st['nodes']}  (LINE_NODE|AUX_ONLY, pp>= {SKEL_PP_BASE})")
    print(f"  asterisms      : {st['astr']} (polylines {st['aply']}, nodes {st['astn']})")
    print(f"  art overlays   : {st['art']}")
    print(f"  expected skeleton segments: {st['segs']}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
