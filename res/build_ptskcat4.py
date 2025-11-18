
#!/usr/bin/env python3
import os, json, sys, struct, io, math
from typing import List, Dict, Tuple

STAR_RECORD_SIZE = 24   # bytes
ASTR_RECORD_SIZE = 20
CST_RECORD_SIZE  = 16
APLY_RECORD_SIZE = 12
ASTN_RECORD_SIZE = 4
ART_RECORD_SIZE  = 16

CONST_LIST = [
    ("And", "Andromeda"), ("Ant", "Antlia"), ("Aps", "Apus"), ("Aqr", "Aquarius"), ("Aql", "Aquila"),
    ("Ara", "Ara"), ("Ari", "Aries"), ("Aur", "Auriga"), ("Boo", "Boötes"), ("Cae", "Caelum"),
    ("Cam", "Camelopardalis"), ("Cnc", "Cancer"), ("CVn", "Canes Venatici"), ("CMa", "Canis Major"), ("CMi", "Canis Minor"),
    ("Cap", "Capricornus"), ("Car", "Carina"), ("Cas", "Cassiopeia"), ("Cen", "Centaurus"), ("Cep", "Cepheus"),
    ("Cet", "Cetus"), ("Cha", "Chamaeleon"), ("Cir", "Circinus"), ("Col", "Columba"), ("Com", "Coma Berenices"),
    ("CrA", "Corona Australis"), ("CrB", "Corona Borealis"), ("Crv", "Corvus"), ("Crt", "Crater"), ("Cru", "Crux"),
    ("Cyg", "Cygnus"), ("Del", "Delphinus"), ("Dor", "Dorado"), ("Dra", "Draco"), ("Equ", "Equuleus"),
    ("Eri", "Eridanus"), ("For", "Fornax"), ("Gem", "Gemini"), ("Gru", "Grus"), ("Her", "Hercules"),
    ("Hor", "Horologium"), ("Hya", "Hydra"), ("Hyi", "Hydrus"), ("Ind", "Indus"), ("Lac", "Lacerta"),
    ("Leo", "Leo"), ("LMi", "Leo Minor"), ("Lep", "Lepus"), ("Lib", "Libra"), ("Lup", "Lupus"),
    ("Lyn", "Lynx"), ("Lyr", "Lyra"), ("Men", "Mensa"), ("Mic", "Microscopium"), ("Mon", "Monoceros"),
    ("Mus", "Musca"), ("Nor", "Norma"), ("Oct", "Octans"), ("Oph", "Ophiuchus"), ("Ori", "Orion"),
    ("Pav", "Pavo"), ("Peg", "Pegasus"), ("Per", "Perseus"), ("Phe", "Phoenix"), ("Pic", "Pictor"),
    ("Psc", "Pisces"), ("PsA", "Piscis Austrinus"), ("Pup", "Puppis"), ("Pyx", "Pyxis"), ("Ret", "Reticulum"),
    ("Sge", "Sagitta"), ("Sgr", "Sagittarius"), ("Sco", "Scorpius"), ("Scl", "Sculptor"), ("Sct", "Scutum"),
    ("Ser", "Serpens"), ("Sex", "Sextans"), ("Tau", "Taurus"), ("Tel", "Telescopium"), ("Tri", "Triangulum"),
    ("TrA", "Triangulum Australe"), ("Tuc", "Tucana"), ("UMa", "Ursa Major"), ("UMi", "Ursa Minor"), ("Vel", "Vela"),
    ("Vir", "Virgo"), ("Vol", "Volans"), ("Vul", "Vulpecula"),
]

ABBR_TO_INDEX = {abbr: i for i, (abbr, _) in enumerate(CONST_LIST)}

BRIGHT   = 0x01
LINE_NODE= 0x02
NO_LABEL = 0x04
AUX_ONLY = 0x08

def hms_to_deg(h, m, s): return (h + m/60.0 + s/3600.0) * 15.0
def dms_to_deg(sign, d, m, s):
    deg = d + m/60.0 + s/3600.0
    return -deg if sign < 0 else deg

def parse_hms(s: str) -> float:
    # Accept "HH:MM:SS(.s)" or degrees as float string
    if isinstance(s, (int, float)): return float(s)
    if ":" not in s:
        return float(s)
    parts = s.strip().split(":")
    if len(parts) != 3:
        raise ValueError(f"Invalid HMS: {s}")
    h = float(parts[0]); m = float(parts[1]); sec = float(parts[2])
    return hms_to_deg(h, m, sec)

def parse_dms(s: str) -> float:
    if isinstance(s, (int, float)): return float(s)
    s = s.strip()
    sign = 1
    if s[0] == "+":
        s = s[1:]
    elif s[0] == "-":
        s = s[1:]; sign = -1
    parts = s.split(":")
    if len(parts) != 3:
        # Allow "+7.4" style
        return float(("-" if sign<0 else "") + s)
    d = float(parts[0]); m = float(parts[1]); sec = float(parts[2])
    return dms_to_deg(sign, d, m, sec)

def make_id(abbr: str, pp: int, ss: int) -> int:
    cc = ABBR_TO_INDEX[abbr]  # 0..87
    return cc*10000 + (pp%100)*100 + (ss%100)

class StringPool:
    def __init__(self):
        self.offsets: Dict[str,int] = {}
        self.blob = bytearray()
    def id(self, s: str) -> int:
        if s is None or s == "":
            return 0
        if s in self.offsets:
            return self.offsets[s]
        off = len(self.blob)
        self.blob.extend(s.encode("utf-8"))
        self.blob.append(0)
        self.offsets[s] = off
        return off
    def build(self) -> bytes:
        return bytes(self.blob)

def pack_sections(sections: List[Tuple[bytes, bytes, int]]) -> bytes:
    # sections: list of (fourcc, payload, count)
    header_size = 8 + 4 + 4 + len(sections)*16
    payload = bytearray()
    dir_entries = []
    off = 0
    for fourcc, content, count in sections:
        # align to 4
        if (off % 4) != 0:
            pad = 4 - (off % 4)
            payload.extend(b"\x00"*pad)
            off += pad
        dir_entries.append((fourcc, header_size+off, len(content), count))
        payload.extend(content)
        off += len(content)
    out = io.BytesIO()
    out.write(b"PTSKCAT4")
    out.write(struct.pack("<I", 4))
    out.write(struct.pack("<I", len(sections)))
    for fourcc, off, length, count in dir_entries:
        out.write(struct.pack("<4sIII", fourcc, off, length, count))
    out.write(payload)
    return out.getvalue()

def build_from_dir(src_dir: str, out_path: str):
    strings = StringPool()
    # preload constellation strings
    for abbr, name in CONST_LIST:
        strings.id(abbr); strings.id(name)

    # Collect sections
    cst_blob = bytearray()
    for abbr, name in CONST_LIST:
        cst_blob.extend(struct.pack("<IIII", strings.id(abbr), strings.id(name), 0, 0))

    # Read all JSONs
    files = [f for f in os.listdir(src_dir) if f.lower().endswith(".json")]
    files.sort()
    # Maps
    star_records: List[bytes] = []
    star_by_key_primary: Dict[Tuple[str,str], int] = {}  # (abbr,key) -> id (primary pp)
    star_by_key_pp: Dict[Tuple[str,str,int], int] = {}   # (abbr,key,pp) -> id
    star_props: Dict[Tuple[str,str], Tuple[float,float,float,str]] = {}  # (abbr,key) -> (ra,dec,mag,name)

    # pass 1: read, collect stars, skeleton usage
    per_const_data = {}
    for fname in files:
        with open(os.path.join(src_dir, fname), "r", encoding="utf-8") as f:
            data = json.load(f)
        abbr = data["abbr"]; name = data.get("name", abbr)
        per_const_data[abbr] = data
        # stars
        for s in data.get("stars", []):
            key = s["key"]
            ra = parse_hms(s["ra"]) if isinstance(s["ra"], str) else float(s["ra"])
            dec = parse_dms(s["dec"]) if isinstance(s["dec"], str) else float(s["dec"])
            mag = float(s.get("mag", 0.0))
            sname = s.get("name", key)
            star_props[(abbr,key)] = (ra, dec, mag, sname)

    # pass 2: create STAR records for skeleton pp groups (duplicates per pp allowed)
    for abbr, data in per_const_data.items():
        const_index = ABBR_TO_INDEX[abbr]
        # for stable SS numbering across PP groups
        for skel in data.get("skeleton", []):
            pp = int(skel["pp"])
            ss = 1
            for key in skel.get("nodes", []):
                ra, dec, mag, sname = star_props[(abbr,key)]
                sid = make_id(abbr, pp, ss)
                # flags: BRIGHT if mag<2.0, LINE_NODE for skeleton
                flags = 0
                if mag < 2.0: flags |= BRIGHT
                flags |= LINE_NODE
                # If star already has a "primary", set NO_LABEL here; otherwise this becomes primary
                primary_key = (abbr, key)
                if primary_key in star_by_key_primary:
                    flags |= NO_LABEL
                else:
                    star_by_key_primary[primary_key] = sid
                # write STAR
                star_records.append(struct.pack("<I fff H H I", sid, ra, dec, mag, const_index, flags, strings.id(sname)))
                star_by_key_pp[(abbr, key, pp)] = sid
                ss += 1

    # pass 3: for stars not used in skeleton -> add PP=00 entries
    for (abbr,key), (ra,dec,mag,sname) in star_props.items():
        if (abbr,key) not in star_by_key_primary:
            const_index = ABBR_TO_INDEX[abbr]
            sid = make_id(abbr, 0, 1)  # PP=00 -> no skeleton lines
            flags = 0
            if mag < 2.0: flags |= BRIGHT
            star_records.append(struct.pack("<I fff H H I", sid, ra, dec, mag, const_index, flags, strings.id(sname)))
            star_by_key_primary[(abbr,key)] = sid
            star_by_key_pp[(abbr,key,0)] = sid

    # Build asterisms (ASTR/APLY/ASTN)
    ASTR = bytearray()
    APLY = bytearray()
    ASTN = bytearray()
    aply_count = 0
    node_count = 0
    astr_count = 0

    def add_poly(node_ids: List[int], style:int=0):
        nonlocal aply_count, node_count, APLY, ASTN
        node_start = node_count
        for sid in node_ids:
            ASTN.extend(struct.pack("<I", sid))
        node_count += len(node_ids)
        APLY.extend(struct.pack("<I H H I", node_start, len(node_ids), style, 0))
        aply_count += 1

    for abbr, data in per_const_data.items():
        const_index = ABBR_TO_INDEX[abbr]
        for ast in data.get("asterisms", []):
            name = ast["name"]
            label_key = ast.get("label", None)
            poly_start = aply_count
            for poly in ast.get("polylines", []):
                ids = []
                for key in poly:
                    sid = star_by_key_primary[(abbr, key)]
                    ids.append(sid)
                add_poly(ids, style=0)
            poly_count = aply_count - poly_start
            label_sid = star_by_key_primary[(abbr, label_key)] if label_key else 0
            ASTR.extend(struct.pack("<H H I I H H I",
                const_index, 0, strings.id(name), poly_start, poly_count, 0, label_sid))
            astr_count += 1

    # Build ART0
    ART0 = bytearray()
    art_count = 0
    for abbr, data in per_const_data.items():
        const_index = ABBR_TO_INDEX[abbr]
        for art in data.get("art_overlays", []):
            key = strings.id(art["key"])
            a_sid = star_by_key_primary[(abbr, art["anchorA"])]
            b_sid = star_by_key_primary[(abbr, art["anchorB"])]
            ART0.extend(struct.pack("<H H I I I", const_index, 0, key, a_sid, b_sid))
            art_count += 1

    # STR0
    STR0 = strings.build()
    # CST0 already assembled
    CST0 = bytes(cst_blob := bytearray())
    for abbr, name in CONST_LIST:
        cst_blob.extend(struct.pack("<IIII", strings.id(abbr), strings.id(name), 0, 0))
    CST0 = bytes(cst_blob)
    # STAR
    STAR = b"".join(star_records)

    # Sanity checks: exact multiples
    assert (len(STAR) % STAR_RECORD_SIZE) == 0, f"STAR size {len(STAR)} not multiple of {STAR_RECORD_SIZE}"
    assert (len(ASTR) % ASTR_RECORD_SIZE) == 0, f"ASTR size {len(ASTR)} not multiple of {ASTR_RECORD_SIZE}"

    blob = pack_sections([
        (b"STR0", STR0, len(strings.offsets)),
        (b"CST0", CST0, len(CONST_LIST)),
        (b"STAR", STAR, len(STAR)//STAR_RECORD_SIZE),
        (b"ASTR", bytes(ASTR), len(ASTR)//ASTR_RECORD_SIZE),
        (b"APLY", bytes(APLY), len(APLY)//APLY_RECORD_SIZE if len(APLY)>0 else 0),
        (b"ASTN", bytes(ASTN), len(ASTN)//ASTN_RECORD_SIZE if len(ASTN)>0 else 0),
        (b"ART0", bytes(ART0), art_count),
    ])
    with open(out_path, "wb") as f:
        f.write(blob)
    return out_path

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: build_ptskcat4.py <src_dir> <out_bin_path>")
        sys.exit(1)
    src_dir = sys.argv[1]
    out_path = sys.argv[2]
    print("Building from", src_dir, "->", out_path)
    build_from_dir(src_dir, out_path)
    print("OK:", out_path)
