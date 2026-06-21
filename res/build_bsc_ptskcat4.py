#!/usr/bin/env python3
"""
build_bsc_ptskcat4.py — Yale Bright Star Catalogue (BSC5) -> PTSKCAT4 v4 star.bin

Produces the SAME runtime format as res/build_ptskcat4.py (magic "PTSKCAT4", v4,
sections STR0/CST0/STAR/ASTR/APLY/ASTN/ART0) that core/astro PtskCatalogLoader reads.
Unlike build_ptskcat4.py (dir-of-curated-JSON, asterism skeletons), this ingests the
flat BSC5 JSON and emits point-stars only (empty ASTR/APLY/ASTN/ART0).

Scope A1: only BSC stars that carry a Constellation (designated/named/lettered stars).
Undesignated faint stars have no Constellation in this source and are skipped; placing
them needs an IAU-boundary RA/Dec lookup (A2, couple with const_v1.bin).

Source (public domain): Yale Bright Star Catalogue, 5th ed., Hoffleit & Warren.
JSON mirror: https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json

Usage:
  build_bsc_ptskcat4.py <out.bin> [--input=bsc5-all.json] [--mag-limit=6.5]
If --input is omitted the script fetches the mirror above.
StarId == cc*10000 + pp*100 + ss. Point stars MUST stay in the pp==0 group: mobile AR
buildConstellationSkeletonLines() treats any star with StarId.pp()!=0 as a constellation
line node. pp==0 gives 100 ss slots, so each constellation is capped at its 100 brightest
(keeps ids unique AND prevents spurious skeleton lines). Only Taurus hits the cap at <=6.5.
"""
import sys, os, io, json, struct, collections, urllib.request

MIRROR = "https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json"

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
BRIGHT = 0x01
STAR_RECORD_SIZE = 24

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

def build(stars, out_path):
    # stars: list of (con, ra, dec, mag, label)
    blob = bytearray(); off = {}
    def sid(s):
        if not s: return 0
        if s in off: return off[s]
        o = len(blob); blob.extend(s.encode("utf-8")); blob.append(0); off[s] = o; return o
    for a, n in CONST_LIST: sid(a); sid(n)
    MAX_PER_CONST = 100  # pp==0 has exactly 100 ss slots; staying in pp==0 avoids fake skeleton lines
    counter = collections.Counter(); recs = []; seen = set(); dropped = 0
    for con, ra, dec, mag, lab in sorted(stars, key=lambda x: (ABBR[x[0]], x[3])):
        cc = ABBR[con]; n = counter[cc]
        if n >= MAX_PER_CONST:  # keep the 100 brightest; drop fainter overflow
            dropped += 1; continue
        counter[cc] += 1
        ss = n % 100  # pp is always 0 here -> StarId.pp()==0 for every point star
        sidv = cc*10000 + ss
        assert (sidv // 100) % 100 == 0, "point star must have pp()==0"
        if sidv in seen: raise SystemExit(f"dup id {sidv}")
        seen.add(sidv)
        flags = BRIGHT if mag < 2.0 else 0
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
    return len(recs), len(b.getvalue()), dropped

def main(argv):
    if len(argv) < 2:
        print(__doc__); return 1
    out = argv[1]; inp = None; mag = 6.5
    for a in argv[2:]:
        if a.startswith("--input="): inp = a.split("=", 1)[1]
        elif a.startswith("--mag-limit="): mag = float(a.split("=", 1)[1])
    if inp and os.path.exists(inp):
        data = json.load(open(inp))
    else:
        print("fetching", MIRROR); data = json.loads(urllib.request.urlopen(MIRROR, timeout=60).read())
    rows = []; skipped_nocon = 0; unmapped = collections.Counter()
    for r in data:
        con = (r.get("Constellation") or "").strip()
        if not con: skipped_nocon += 1; continue
        if con not in ABBR: unmapped[con] += 1; continue
        ra, dec, m = _ra(r), _dec(r), _f(r.get("Vmag"))
        if None in (ra, dec, m) or m > mag: continue
        rows.append((con, ra, dec, m, _label(r)))
    if unmapped: print("WARNING unmapped constellation codes:", dict(unmapped))
    cnt, size, dropped = build(rows, out)
    print(f"wrote {cnt} stars ({size} bytes) -> {out}  [mag<= {mag}, skipped {skipped_nocon} w/o constellation, capped {dropped} faint overflow]")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
