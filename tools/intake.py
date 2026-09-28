"""Device -> analysed source tree, in one command.

    python tools/intake.py --package np.com.nepalipatro
    python tools/intake.py --package com.hamropatro --census-only
    python tools/intake.py --package com.hamropatro --skip-pull

Does, in order:
  1. reads package metadata from `dumpsys package`
  2. pulls every split APK via `pm path` (the on-device split filenames are obfuscated
     and differ per install, so they cannot be guessed)
  3. runs apktool on the base APK
  4. stages libapp.so / libflutter.so out of the ABI split
  5. locates the Flutter AOT snapshot magic
  6. counts methods, and prints an ad-SDK census

jadx is not run: for the ad work in this repo apktool smali is the source of truth, and jadx
adds a multi-minute decompile of a 100MB+ APK for little gain. Reach for it by hand only when
you need to read Kotlin/Java logic rather than locate methods.

Output layout (all gitignored):
    apks/<key>/                      the pulled splits
    apks/extracted/<key>/            apktool tree
    apks/extracted/<key>-analysis/   staged natives + blutter output
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (
    APKTOOL_JAR,
    APKS_DIR,
    EXTRACTED_DIR,
    Adb,
    ToolError,
    aot_magic_offset,
    fail,
    head,
    human,
    info,
    ok,
    rel,
    run,
    smali_roots,
    warn,
    zip_entries,
)

# Directory presence, not string search: an earlier keyword grep counted 9933 false
# positives on a directory listing alone. The census is only a starting map anyway.
AD_SDK_DIRS = {
    "applovin": "com/applovin",
    "appLovin-adview": "com/applovin/adview",
    "unity-ads": "com/unity3d/ads",
    "unity-mediation": "com/unity3d/mediation",
    "facebook-audience": "com/facebook/ads",
    "google-ads": "com/google/ads",
    "google-gms-ads": "com/google/android/gms/ads",
    "ironsource": "com/ironsource",
    "pangle-bytedance": "com/bytedance/sdk/openadsdk",
    "inmobi": "com/inmobi",
    "vungle": "com/vungle",
    "chartboost": "com/chartboost/sdk",
    "adcolony": "com/adcolony",
    "smaato": "com/smaato",
    "mintegral": "com/mintegral",
    "pubsense-pubnative": "net/pubnative",
    "safedk": "com/safedk",
    "bidmachine": "io/bidmachine",
    "mopub": "com/mopub",
    "yandex-ads": "com/yandex/mobile/ads",
    "huawei-ads": "com/huawei/hms/ads",
    "mytarget": "com/my/target",
    "vungle-legacy": "com/vungle/warren",
    "bigo-ads": "sg/bigo/ads",
    "hamropatro-ads": "com/hamropatro/library/ads",
    "hamropatro-nativeads": "com/hamropatro/library/nativeads",
}

FLUTTER_NATIVES = ("libapp.so", "libflutter.so")


def census(extracted: Path) -> None:
    head("ad SDK census (directory presence)")
    present: dict[str, str] = {}
    for label, relpath in sorted(AD_SDK_DIRS.items()):
        for root in smali_roots(extracted):
            d = root / Path(relpath)
            if d.is_dir():
                count = sum(1 for _ in d.rglob("*.smali"))
                present[label] = f"{count} files in {root.name}"
                break
    for label, where in present.items():
        ok(f"{label:<22} {where}")
    absent = [l for l in AD_SDK_DIRS if l not in present]
    if absent:
        info(f"absent: {', '.join(sorted(absent))}")
    if not present:
        warn("no known ad SDK directories found - check the package or the mapping table")


def pull_splits(adb: Adb, package: str, dest: Path) -> list[Path]:
    head(f"pulling splits for {package}")
    dest.mkdir(parents=True, exist_ok=True)
    paths = adb.apk_paths(package)
    if not paths:
        raise ToolError(f"no APKs reported for {package} - is it installed?")
    out: list[Path] = []
    for remote in paths:
        name = remote.rsplit("/", 1)[-1]
        local = dest / name
        if local.exists() and local.stat().st_size > 0:
            ok(f"{name} (already present, {human(local.stat().st_size)} bytes)")
        else:
            adb.pull(remote, local)
            ok(f"{name}  {human(local.stat().st_size)} bytes")
        out.append(local)
    return out


def apktool(apk: Path, out: Path) -> None:
    head(f"apktool {apk.name} -> {rel(out)}")
    if not APKTOOL_JAR.exists():
        raise ToolError(f"apktool jar not found at {APKTOOL_JAR}")
    if out.exists():
        shutil.rmtree(out)
    java = shutil.which("java")
    if not java:
        raise ToolError("java not on PATH")
    res = run([java, "-jar", str(APKTOOL_JAR), "d", "-f", "-o", str(out), str(apk)], timeout=3600)
    m = re.search(r"^I: Using(?: apkanalyzer| resources\.arsc)?.*", res.stdout, re.M)
    for line in res.stdout.splitlines()[-3:]:
        info(line)
    ok(f"extracted to {rel(out)}")


def stage_natives(splits: list[Path], analysis: Path) -> dict[str, int]:
    head("staging Flutter natives")
    analysis.mkdir(parents=True, exist_ok=True)
    found: dict[str, int] = {}
    for apk in splits:
        try:
            names = zip_entries(apk)
        except zipfile.BadZipFile:
            continue
        libs = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        if not libs:
            continue
        info(f"{apk.name}: {len(libs)} .so")
        with zipfile.ZipFile(apk) as z:
            for lib in libs:
                base = lib.rsplit("/", 1)[-1]
                if base not in FLUTTER_NATIVES:
                    continue
                data = z.read(lib)
                dest = analysis / base
                dest.write_bytes(data)
                found[base] = len(data)
                ok(f"{base:<16} {human(len(data))} bytes   (from {lib})")
    for name in FLUTTER_NATIVES:
        if name not in found:
            info(f"{name:<16} not present - not a Flutter app?")
    if "libapp.so" in found:
        data = (analysis / "libapp.so").read_bytes()
        off = aot_magic_offset(data)
        if off is None:
            warn("libapp.so has no 0xdcdcf5f5 AOT magic - not a Flutter release snapshot")
        else:
            ok(f"AOT snapshot magic 0xdcdcf5f5 at file offset {hex(off)}")
            info("blutter's per-function 'addr' equals the libapp.so file offset - no vaddr "
                 "translation. Confirm once per snapshot on a function prologue.")
    return found


def count_methods(extracted: Path) -> int:
    head("method count")
    total = 0
    files = 0
    for path in extracted.rglob("*.smali"):
        files += 1
        try:
            with path.open("r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if line.startswith(".method"):
                        total += 1
        except OSError:
            pass
    ok(f"{human(files)} smali files, {human(total)} methods")
    return total


def resolve_key(package: str, explicit: str | None = None) -> str:
    """Directory key for a package.

    Prefers the key already used by tools/appdata/<key>.yml, so `--package com.hamropatro`
    lands in apks/hamropatro/ and lines up with the existing analysis dirs. Falls back to a
    derived slug for a package with no app data yet.
    """
    if explicit:
        return explicit
    try:
        from fingerprints import available_apps, load_spec

        for key in available_apps():
            if load_spec(key).package == package:
                return key
    except Exception:  # a broken app data file must not block intake
        pass
    return package.rsplit(".", 1)[-1].lower()


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--package", required=True, help="package name, e.g. np.com.nepalipatro")
    ap.add_argument("--key", help="directory key (default: taken from tools/appdata)")
    ap.add_argument("--serial", help="device serial")
    ap.add_argument("--skip-apktool", action="store_true")
    ap.add_argument("--skip-pull", action="store_true", help="reuse apks/<key>/ if present")
    ap.add_argument("--census-only", action="store_true", help="only run the ad census")
    args = ap.parse_args(argv[1:])

    key = resolve_key(args.package, args.key)
    apk_dir = APKS_DIR / key
    extracted = EXTRACTED_DIR / key
    analysis = EXTRACTED_DIR / f"{key}-analysis"

    # The device is only required when we actually have to pull something, so the
    # offline passes (census, re-count) work with nothing attached.
    adb: Adb | None = None
    if not args.census_only and not args.skip_pull:
        adb = Adb(args.serial)
        info(f"device {adb.serial}")

    if not args.census_only:
        if adb is not None:
            info_pkg = adb.package_info(args.package)
            for k, v in info_pkg.items():
                info(f"{k}: {v}")
            if info_pkg.get("versionName"):
                ok(f"{args.package} {info_pkg['versionName']} "
                   f"(versionCode {info_pkg.get('versionCode', '?')}, "
                   f"abi {info_pkg.get('abi', '?')})")

    splits: list[Path] = []
    if not args.census_only:
        if args.skip_pull and apk_dir.exists():
            splits = sorted(apk_dir.glob("*.apk"))
            info(f"reusing {len(splits)} apk(s) in {rel(apk_dir)}")
        else:
            splits = pull_splits(adb, args.package, apk_dir)
        if not args.skip_apktool:
            base = next((p for p in splits if p.name == "base.apk"), splits[0])
            apktool(base, extracted)
        if not extracted.exists():
            raise ToolError(f"no extracted tree at {extracted}")
        stage_natives(splits, analysis)
        count_methods(extracted)

    if extracted.exists():
        census(extracted)
    else:
        warn(f"no extracted tree at {extracted}; skipping census")

    head("next")
    info("blutter:       python tools/blutter_stage.py --package " + args.package)
    info("verify:        python tools/verify.py --all")
    print()
    ok("intake complete")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
