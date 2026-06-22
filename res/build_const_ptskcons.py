#!/usr/bin/env python3
"""
build_const_ptskcons.py — IAU constellation boundaries -> PTSKCONS v1 const_v1.bin

Emits the polygon format produced by tools BinaryConstellationWriter and read by
mobile ConstellationOutlineLoader (sky-map outlines). NOTE: the runtime findByEq reader
(core/catalog binary/BinaryConstellationBoundaries) currently expects a DIFFERENT (AABB)
layout and must be switched to read THIS polygon file with point-in-polygon — see the
companion agent spec. The deployed file path is catalog/const_v1.bin for both mobile+wear.

Header (26B): "PTSKCONS"(8) + version:u16=1 + reserved:u16=0 + constellationCount:u16=88
              + polygonCount:i32 + vertexCount:i32 + crc32:u32(LE, over payload).
Payload: 88 directory entries [code(3+NUL) polyStart:i32 polyCount:i32 AABB(4×f32)],
         then polygonCount polygon entries [vertexStart:i32 vertexCount:i32 AABB(4×f32)],
         then vertexCount vertices [ra:f32 dec:f32]. RA/Dec in degrees, J2000.

Source (boundary polygons): d3-celestial, GeoJSON, lon=RA in [-180,180] -> ra = lon % 360.
  https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.bounds.json

Usage: build_const_ptskcons.py <out.bin> [--input=constellations.bounds.json]
"""
import sys, os, io, json, struct, collections, urllib.request, zlib

MIRROR = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.bounds.json"
CONST = ["And","Ant","Aps","Aqr","Aql","Ara","Ari","Aur","Boo","Cae","Cam","Cnc","CVn","CMa","CMi",
 "Cap","Car","Cas","Cen","Cep","Cet","Cha","Cir","Col","Com","CrA","CrB","Crv","Crt","Cru","Cyg",
 "Del","Dor","Dra","Equ","Eri","For","Gem","Gru","Her","Hor","Hya","Hyi","Ind","Lac","Leo","LMi",
 "Lep","Lib","Lup","Lyn","Lyr","Men","Mic","Mon","Mus","Nor","Oct","Oph","Ori","Pav","Peg","Per",
 "Phe","Pic","Psc","PsA","Pup","Pyx","Ret","Sge","Sgr","Sco","Scl","Sct","Ser","Sex","Tau","Tel",
 "Tri","TrA","Tuc","UMa","UMi","Vel","Vir","Vol","Vul"]

def rings_of(g):
    if g["type"] == "Polygon": return list(g["coordinates"])
    if g["type"] == "MultiPolygon": return [r for p in g["coordinates"] for r in p]
    return []

def circ_aabb(verts):
    ras = sorted(v[0] for v in verts); decs = [v[1] for v in verts]
    gap = -1.0; gi = 0
    for i in range(len(ras)):
        nxt = ras[(i + 1) % len(ras)]; cur = ras[i]
        d = (nxt + 360 - cur) if i == len(ras) - 1 else (nxt - cur)
        if d > gap: gap = d; gi = i
    return ras[(gi + 1) % len(ras)], ras[gi], min(decs), max(decs)

def build(rings_by, out_path):
    dir_b = io.BytesIO(); poly_b = io.BytesIO(); vert_b = io.BytesIO()
    poly_i = 0; vert_i = 0
    for c in CONST:
        rs = rings_by[c]
        if not rs: raise SystemExit(f"missing rings for {c}")
        allv = [v for r in rs for v in r]
        ar0, ar1, ad0, ad1 = circ_aabb(allv)
        dir_b.write(c.upper().ljust(3)[:3].encode("ascii")); dir_b.write(b"\x00")
        dir_b.write(struct.pack("<ii", poly_i, len(rs)))
        dir_b.write(struct.pack("<ffff", ar0, ar1, ad0, ad1))
        for r in rs:
            pr0, pr1, pd0, pd1 = circ_aabb(r)
            poly_b.write(struct.pack("<ii", vert_i, len(r)))
            poly_b.write(struct.pack("<ffff", pr0, pr1, pd0, pd1))
            for ra, dec in r: vert_b.write(struct.pack("<ff", ra, dec))
            poly_i += 1; vert_i += len(r)
    data = dir_b.getvalue() + poly_b.getvalue() + vert_b.getvalue()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    hdr = io.BytesIO()
    hdr.write(b"PTSKCONS")
    hdr.write(struct.pack("<HHH", 1, 0, len(CONST)))
    hdr.write(struct.pack("<ii", poly_i, vert_i))
    hdr.write(struct.pack("<I", crc))
    open(out_path, "wb").write(hdr.getvalue() + data)
    return poly_i, vert_i, len(hdr.getvalue()) + len(data)

def main(argv):
    if len(argv) < 2: print(__doc__); return 1
    out = argv[1]; inp = None
    for a in argv[2:]:
        if a.startswith("--input="): inp = a.split("=", 1)[1]
    if inp and os.path.exists(inp): data = json.load(open(inp))
    else:
        print("fetching", MIRROR); data = json.loads(urllib.request.urlopen(MIRROR, timeout=60).read())
    rings_by = collections.defaultdict(list)
    for f in data["features"]:
        code = f["id"]
        if code not in CONST: raise SystemExit(f"unknown constellation code {code}")
        for ring in rings_of(f["geometry"]):
            rings_by[code].append([(lon % 360.0, lat) for lon, lat in ring])
    pc, vc, sz = build(rings_by, out)
    print(f"wrote const_v1.bin: {len(CONST)} constellations, {pc} polygons, {vc} vertices, {sz} bytes -> {out}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
