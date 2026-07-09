import numpy as np
from res.skyglow.build_bortle_bin import encode_v3, _decode_v3, SQM_MIN, SQM_STEP, MAGIC


def test_v3_header_and_roundtrip():
    sqm = np.array([[16.0, 18.8, 21.9], [17.25, 20.0, 22.0]], dtype=float)
    raw = encode_v3(sqm, lat_top=60.0, lon_left=-120.0, deg=0.0125)
    meta, grid = _decode_v3(raw)
    assert meta["magic"] == MAGIC and meta["version"] == 3
    assert meta["rows"] == 2 and meta["cols"] == 3
    assert abs(meta["deg"] - 0.0125) < 1e-12
    dec_sqm = SQM_MIN + (grid.astype(float) - 1) * SQM_STEP
    assert np.max(np.abs(dec_sqm - sqm)) <= SQM_STEP / 2 + 1e-9


def test_byte_encoding_matches_decoder_contract():
    # byte = round((sqm-16)/0.1)+1 ; decode = 16 + (byte-1)*0.1
    sqm = np.array([[18.80]], dtype=float)          # Acme marker
    _, grid = _decode_v3(encode_v3(sqm, 60.0, -120.0, 0.0125))
    assert int(grid[0, 0]) == 29                    # 18.80 -> byte 29
