#!/usr/bin/env python3
"""
build_bsc_ptskcat4_a2.py — Yale BSC5 -> PTSKCAT4 v4 star.bin  (Scope A2: dense, all stars constellation-assigned)

Same runtime format as res/build_ptskcat4.py / build_bsc_ptskcat4.py (magic "PTSKCAT4", v4,
sections STR0/CST0/STAR/ASTR/APLY/ASTN/ART0) read by core/astro PtskCatalogLoader.

Difference vs A1 (build_bsc_ptskcat4.py):
  * A1 kept only BSC stars that carry a Constellation field, and capped each constellation at 100
    (so every point star stayed in StarId.pp()==0, dodging the old pp()!=0 skeleton trigger).
  * A2 keeps EVERY star <= mag-limit. Designated stars use their BSC Constellation field; the ~5953
    undesignated faint stars are assigned a constellation by build-time point-in-polygon against the
    IAU boundaries (same meridian-ray + pole-flip used to build const_v1.bin). The 100-cap is removed:
    ids span pp=0,1,2,... within a constellation (id = cc*10000 + n, n = 0..count-1, brightest first).
  * REQUIRES the LINE_NODE skeleton gate to be merged first (phase2-line-node-gate.md): bulk stars here
    carry flags WITHOUT LINE_NODE (0x02), so with that gate they never draw skeleton lines despite pp>0.
    (Wear renders no skeletons at all, so its pp values are irrelevant either way.)

Sources (public domain / open):
  BSC5 JSON  : https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json
  IAU bounds : https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.bounds.json

Usage:
  build_bsc_ptskcat4_a2.py <out.bin> [--input=bsc5-all.json] [--bounds=constellations.bounds.json] [--mag-limit=6.5]
"""
import sys, os, io, json, struct, collections, urllib.request

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
BRIGHT = 0x01            # LINE_NODE (0x02) is intentionally NEVER set on bulk stars
STAR_RECORD_SIZE = 24

# ---------- BSC field helpers ----------
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

# ---------- validated meridian-ray point-in-polygon (same as const_v1.bin) ----------
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
    # ring: list of (ra_deg, dec_deg) already lon%360
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
    # abbr -> list of runtime polygons, kept in CONST_LIST order for deterministic first-match
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
    for abbr in ABBRS:                       # CONST_LIST order == runtime directory order
        for poly in boundaries.get(abbr, ()):
            if _contains(poly, ra, dec): return abbr
    return None

# ---------- PTSKCAT4 v4 writer (identical layout to A1) ----------
def build(stars, out_path):
    # stars: list of (abbr, ra, dec, mag, label)
    blob = bytearray(); off = {}
    def sid(s):
        if not s: return 0
        if s in off: return off[s]
        o = len(blob); blob.extend(s.encode("utf-8")); blob.append(0); off[s] = o; return o
    for a, n in CONST_LIST: sid(a); sid(n)
    counter = collections.Counter(); recs = []; seen = set()
    for abbr, ra, dec, mag, lab in sorted(stars, key=lambda x: (ABBR[x[0]], x[3])):
        cc = ABBR[abbr]; n = counter[cc]; counter[cc] += 1
        if n >= 10000: raise SystemExit(f"constellation {abbr} exceeds 10000 stars (pp overflow)")
        sidv = cc*10000 + n                  # pp = n//100, ss = n%100
        if (sidv // 10000) != cc: raise SystemExit("cc invariant broken")
        if sidv in seen: raise SystemExit(f"dup id {sidv}")
        seen.add(sidv)
        flags = BRIGHT if mag < 2.0 else 0   # never LINE_NODE
        recs.append(struct.pack("<IfffHHI", sidv, ra, dec, mag, cc, flags, sid(lab)))
    STR0 = bytes(blob)
    cst = bytearray()
    for a, n in CONST_LIST: cst.extend(struct.pack("<IIII", off[a], off[n], 0, 0))
    STAR = b"".join(recs)
    secs = [(b"STR0", STR0, len(off)), (b"CST0", bytes(cst), len(CONST_LIST)),
            (b"STAR", STAR, len(STAR)//STAR_RECORD_SIZE),
            (b"ASTR", b"", 0), (b"APLY", b"", 0), (b"ASTN", b"", 0), (b"ART0", b"", 0)]
    hs = 8 + 4 + 4 + len(secs)*16; payload = bytearray(); dirs = []; o = 0
    for fc, ct, cnt in secs:
        if o % 4: pad = 4 - (o % 4); payload.extend(b"\x00"*pad); o += pad
        dirs.append((fc, hs+o, len(ct), cnt)); payload.extend(ct); o += len(ct)
    b = io.BytesIO(); b.write(b"PTSKCAT4"); b.write(struct.pack("<I", 4)); b.write(struct.pack("<I", len(secs)))
    for fc, oo, ln, cnt in dirs: b.write(struct.pack("<4sIII", fc, oo, ln, cnt))
    b.write(payload)
    open(out_path, "wb").write(b.getvalue())
    return len(recs), len(b.getvalue())

def main(argv):
    if len(argv) < 2:
        print(__doc__); return 1
    out = argv[1]; inp = None; bnd = None; mag = 6.5
    for a in argv[2:]:
        if a.startswith("--input="): inp = a.split("=", 1)[1]
        elif a.startswith("--bounds="): bnd = a.split("=", 1)[1]
        elif a.startswith("--mag-limit="): mag = float(a.split("=", 1)[1])
    data = json.load(open(inp)) if (inp and os.path.exists(inp)) else \
        json.loads(urllib.request.urlopen(BSC_MIRROR, timeout=60).read())
    bdata = json.load(open(bnd)) if (bnd and os.path.exists(bnd)) else \
        json.loads(urllib.request.urlopen(BND_MIRROR, timeout=60).read())
    boundaries = load_boundaries(bdata)
    rows = []; from_field = 0; from_pip = 0; unassigned = 0
    for r in data:
        ra, dec, m = _ra(r), _dec(r), _f(r.get("Vmag"))
        if None in (ra, dec, m) or m > mag: continue
        con = (r.get("Constellation") or "").strip()
        if con in ABBR:
            abbr = con; from_field += 1
        else:
            abbr = pip_assign(boundaries, ra, dec)
            if abbr is None: unassigned += 1; continue
            from_pip += 1
        rows.append((abbr, ra, dec, m, _label(r)))
    cnt, size = build(rows, out)
    print(f"wrote {cnt} stars ({size} bytes) -> {out}  [mag<= {mag}]")
    print(f"  constellation source: {from_field} from BSC field, {from_pip} via PIP, {unassigned} unassigned(dropped)")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
