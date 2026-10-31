"""Build a STOCK (unpatched) signed split APK set for Marriage, for the runtime A/B.

Same contract as tools/make_marriage_apk.py, but grafts the pristine bundle instead of
the patched one, so the only difference between the two builds is the bytecode patch.
"""
from __future__ import annotations
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import patch as P
from common import APKS_DIR, rebundle_apk

SRC_DIR = APKS_DIR / "marriage"
OUT_DIR = APKS_DIR / "marriage-stock"
STOCK_BUNDLE = APKS_DIR / "extracted" / "marriage" / "assets" / "index.android.bundle.orig"


def main() -> int:
    if not STOCK_BUNDLE.exists():
        raise SystemExit("missing stock bundle")

    splits = sorted(SRC_DIR.glob("*.apk"))
    base = next((p for p in splits if p.name == "base.apk"), None)
    if base is None:
        raise SystemExit("base.apk not found in split set")
    others = [p for p in splits if p is not base]

    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    work = OUT_DIR / "work"
    work.mkdir(parents=True, exist_ok=True)

    signing = P.ensure_keystore()
    print(f"signing key: {signing.path.name} (alias {signing.alias})")

    stock_bytes = STOCK_BUNDLE.read_bytes()
    base_unsigned = work / "base.stock-unsigned.apk"
    changed = rebundle_apk(base, base_unsigned, {"assets/index.android.bundle": stock_bytes})
    print(f"base: grafted {changed}")

    outputs = []
    out_base = OUT_DIR / "base.apk"
    P.align_and_sign(base_unsigned, out_base, signing, work)
    print(f"signed {out_base.name}  {out_base.stat().st_size:,} bytes  [{P.verify_signature(out_base)}]")
    outputs.append(out_base)

    for split in others:
        out = OUT_DIR / split.name
        P.align_and_sign(split, out, signing, work)
        print(f"signed {out.name}  {out.stat().st_size:,} bytes  [{P.verify_signature(out)}]")
        outputs.append(out)

    print(f"\ninstall set: {OUT_DIR}")
    for o in outputs:
        print(f"  {o.name}  {o.stat().st_size:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
