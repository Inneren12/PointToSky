#!/usr/bin/env python3
"""
build_figures_from_d3.py — generate res/<abbr>.json skeleton figures for the constellations from
d3-celestial's constellations.lines.json (BSD-3-Clause), matched to the BSC bulk.

VERIFIED in the planning sandbox: 85 files written (d3's split Serpens merged), builder accepts all
88 with zero collisions, match max offset 0.50' (none > 0.02 deg), 12/829 vertices unmatched across
7 constellations (figure endpoints fainter than the mag-6.5 bulk; those single edges are dropped).

How it works:
  * Imports build_catalog_variant_b (same dir) and reuses its constellation assignment + field
    helpers, so every emitted star is assigned to the SAME abbr the builder's match_bulk searches.
    Without this the build fails ("no bulk match within 0.10 deg").
  * Emits each star's exact BSC H:M:S / D:M:S strings -> the builder re-matches at 0.0 deg.
  * Merges multiple d3 features that share an id (Serpens is two pieces).
  * Skips hand-curated Ori/Lyr/UMa so their asterisms/keys/art are preserved.

Run from the repo root (script lives in res/):
  python3 res/build_figures_from_d3.py            # writes into res/
then rebuild the catalog with build_catalog_variant_b.py.
"""
import sys, os, json, collections, tempfile, urllib.request

RES = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, RES)
import build_catalog_variant_b as B  # no side effects unless run as __main__

LINES_URL = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.lines.json"
SKIP = {"Ori", "Lyr", "UMa"}     # hand-curated; do not overwrite
MAG_LIMIT = 6.5                  # must match the catalog build mag limit
TOL = 0.10                       # accept vertex->star match within this (deg); matches MATCH_TOL_DEG
NEAR = 0.02                      # reporting-only "clean" threshold

_TMP = tempfile.gettempdir()
_CACHE = {"bsc": os.path.join(_TMP, "pts_bsc5.json"),
          "bnd": os.path.join(_TMP, "pts_bounds.json"),
          "lin": os.path.join(_TMP, "pts_lines.json")}


def _get(url, cache):
    if os.path.exists(cache):
        return json.load(open(cache))
    raw = urllib.request.urlopen(url, timeout=180).read()
    open(cache, "wb").write(raw)
    return json.loads(raw)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else RES
    bsc = _get(B.BSC_MIRROR, _CACHE["bsc"])
    bnd = _get(B.BND_MIRROR, _CACHE["bnd"])
    lines = _get(LINES_URL, _CACHE["lin"])
    boundaries = B.load_boundaries(bnd)
    name_of = dict(B.CONST_LIST)

    # per-abbr star index, using the builder's exact assignment + mag filter
    sba = collections.defaultdict(list)
    for r in bsc:
        ra, dec, m = B._ra(r), B._dec(r), B._f(r.get("Vmag"))
        if None in (ra, dec, m) or m > MAG_LIMIT:
            continue
        con = (r.get("Constellation") or "").strip()
        abbr = con if con in B.ABBR else B.pip_assign(boundaries, ra, dec)
        if abbr is None:
            continue
        sign = "-" if (r.get("DE-") or "").strip() == "-" else "+"
        sba[abbr].append({
            "ra": ra, "dec": dec, "mag": m, "label": B._label(r),
            "ra_str": f"{r.get('RAh')}:{r.get('RAm')}:{r.get('RAs')}",
            "dec_str": f"{sign}{r.get('DEd')}:{r.get('DEm')}:{r.get('DEs')}",
        })

    def nearest(abbr, qra, qdec):
        best, bs = None, 999.0
        for s in sba.get(abbr, []):
            d = B.ang_sep_deg(qra, qdec, s["ra"], s["dec"])
            if d < bs:
                bs, best = d, s
        return best, bs

    acc = collections.OrderedDict()          # abbr -> {sk, stars, skel}
    dup, seps, un = [], [], collections.Counter()
    skipped = 0
    for feat in lines["features"]:
        abbr = feat["id"]
        if abbr in SKIP:
            skipped += 1
            continue
        if abbr not in B.ABBR:
            print("  ?? unknown abbr from d3:", abbr)
            continue
        g = feat["geometry"]
        if g["type"] == "MultiLineString":
            polys = g["coordinates"]
        elif g["type"] == "LineString":
            polys = [g["coordinates"]]
        else:
            continue
        if abbr in acc:
            dup.append(abbr)
        a = acc.setdefault(abbr, {"sk": {}, "stars": [], "skel": []})
        for poly in polys:
            # Split the polyline into runs of consecutive MATCHED vertices. An unmatched
            # vertex breaks the run so we never bridge across the gap (A-B-C with B missing
            # must NOT become an A-C segment).
            runs = [[]]
            for pt in poly:
                qra = B._nra(pt[0])
                s, sep = nearest(abbr, qra, pt[1])
                if s is None or sep > TOL:
                    un[abbr] += 1
                    if runs[-1]:
                        runs.append([])
                    continue
                seps.append(sep)
                ident = (s["ra_str"], s["dec_str"])
                if ident not in a["sk"]:
                    k = f"s{len(a['stars']) + 1}"
                    a["sk"][ident] = k
                    a["stars"].append({"key": k, "name": s["label"],
                                       "ra": s["ra_str"], "dec": s["dec_str"], "mag": s["mag"]})
                runs[-1].append(a["sk"][ident])
            for run in runs:
                dd = [k for i, k in enumerate(run) if i == 0 or k != run[i - 1]]
                if len(dd) >= 2 and len(set(dd)) >= 2:
                    a["skel"].append({"pp": len(a["skel"]) + 1, "nodes": dd})

    written = ts = tp = tg = empty = 0
    empties = []
    for abbr, a in acc.items():
        if not a["skel"]:
            empty += 1
            empties.append(abbr)
            continue
        # drop stars left unreferenced after splitting (orphans from dropped short runs)
        used = {n for s in a["skel"] for n in s["nodes"]}
        stars = [s for s in a["stars"] if s["key"] in used]
        out = {"abbr": abbr, "name": name_of.get(abbr, abbr),
               "stars": stars, "skeleton": a["skel"],
               "asterisms": [], "art_overlays": []}
        json.dump(out, open(os.path.join(out_dir, f"{abbr.lower()}.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)
        written += 1
        ts += len(stars)
        tp += len(a["skel"])
        tg += sum(len(s["nodes"]) - 1 for s in a["skel"])

    seps.sort()
    mx = seps[-1] if seps else 0.0
    print("=== build_figures_from_d3 ===")
    print(f"d3 features: {len(lines['features'])}  written: {written}  "
          f"skipped(curated): {skipped} {sorted(SKIP)}  merged-dup: {sorted(set(dup))}  "
          f"empty: {empty} {empties}")
    print(f"stars: {ts}  polylines: {tp}  segments: {tg}  "
          f"matched-verts: {len(seps)}  unmatched(dropped): {sum(un.values())}")
    print(f"match sep: max {mx*60:.2f}'  >{NEAR}deg: {sum(1 for x in seps if x>NEAR)}  "
          f">0.05deg: {sum(1 for x in seps if x>0.05)}")
    if un:
        print("unmatched by const:", dict(un))


if __name__ == "__main__":
    main()
