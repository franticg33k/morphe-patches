"""Apply a built patch bundle to an APK - single, or a Play split set - and get something
you can install.

    # a split set pulled into apks/<key>/ (see tools/intake.py), then installed
    python tools/patch.py --package np.com.nepalipatro --install --serial emulator-5554

    # one APK, no device
    python tools/patch.py --apk ~/Downloads/app.apk

    # what would apply, without patching
    python tools/patch.py --package np.com.nepalipatro --list

Why this is more than `java -jar morphe-desktop.jar patch`:

  * **Play ships split APKs.** Only `base.apk` carries the manifest and the versionName;
    only the ABI split carries `libapp.so`. The patcher needs a *complete* app to resolve
    the version (point it at a config split and it dies with `getVersionName(...) must not
    be null`), but what you install is the *original split set* with the patched entries
    grafted back. So the flow merges once to give the patcher a whole app, then grafts
    entry-by-entry back onto the splits that own them.

  * **A changed entry cannot be edited in place.** The CRC-32 lives in the zip central
    directory; an in-place byte edit makes the installer reject the whole file with
    `BadZipFile: Bad CRC-32`. The archive is rewritten (order + compression preserved),
    re-aligned, and re-signed.

  * **Every split must be signed with one key**, or `install-multiple` refuses the set -
    including the splits nothing patched. So all of them are re-aligned and re-signed.

The split merge exists only to feed the patcher. The *result* is the original set, so the
app's split structure, on-device layout and license behaviour are the ones the developer
shipped.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import desktop  # noqa: E402
from common import (  # noqa: E402
    APKS_DIR,
    JBR,
    Adb,
    ToolError,
    apk_identity,
    apksigner,
    built_bundle,
    exe,
    fail,
    head,
    human,
    info,
    jbr_env,
    ok,
    rebundle_apk,
    run,
    sdk_tool,
    warn,
    zip_entries,
    zip_entry_blob,
    zipalign,
)

# --------------------------------------------------------------------------------------
# Signing
# --------------------------------------------------------------------------------------

KEYSTORE_DIR = desktop.DESKTOP_DIR  # tools/.morphe/ - already gitignored
KEYSTORE_PATH = KEYSTORE_DIR / "signing.keystore"
KEYSTORE_META = KEYSTORE_DIR / "signing.json"

DEFAULT_ALIAS = "morphe"
DEFAULT_PASS = "morphe"


@dataclass
class Signing:
    path: Path
    alias: str = DEFAULT_ALIAS
    store_pass: str = DEFAULT_PASS
    key_pass: str = DEFAULT_PASS


def _keytool(*argv: str) -> None:
    """Run keytool, preferring the JBR's copy.

    A standalone JRE (as opposed to a JDK) does not ship keytool, so falling back to PATH
    is not always enough - the error has to say what to do instead.
    """
    candidate = JBR / "bin" / exe("keytool")
    tool = str(candidate) if candidate.exists() else shutil.which("keytool")
    if not tool:
        raise ToolError(
            "keytool not found (looked in the JBR and on PATH).\n"
            "  install a JDK, or pass --keystore <path> to use an existing key."
        )
    run([tool, *argv], timeout=300)


def ensure_keystore(explicit: Path | None = None, *, alias: str | None = None,
                    store_pass: str | None = None, key_pass: str | None = None) -> Signing:
    """Resolve the signing key, creating a stable local one if there is none.

    A *stable* key matters: `install-multiple -r` keeps app data only while the signature
    is unchanged, so a per-run throwaway key would wipe the app's state on every iteration
    and hide exactly the persistence bugs the device tests look for. tools/.morphe/ is
    already gitignored, so a generated key is local and never committed.
    """
    if explicit is not None:
        if not explicit.exists():
            raise ToolError(f"keystore not found: {explicit}")
        return Signing(explicit, alias or DEFAULT_ALIAS, store_pass or DEFAULT_PASS,
                       key_pass or (store_pass or DEFAULT_PASS))

    if KEYSTORE_META.exists():
        meta = json.loads(KEYSTORE_META.read_text(encoding="utf-8"))
        path = KEYSTORE_DIR / meta["file"]
        if path.exists():
            return Signing(
                path,
                alias or meta.get("alias", DEFAULT_ALIAS),
                store_pass or meta.get("storePass", DEFAULT_PASS),
                key_pass or meta.get("keyPass", meta.get("storePass", DEFAULT_PASS)),
            )

    if KEYSTORE_PATH.exists():
        return Signing(KEYSTORE_PATH, alias or DEFAULT_ALIAS,
                       store_pass or DEFAULT_PASS, key_pass or (store_pass or DEFAULT_PASS))

    head("no signing key - generating one")
    KEYSTORE_DIR.mkdir(parents=True, exist_ok=True)
    if KEYSTORE_PATH.exists():
        KEYSTORE_PATH.unlink()
    _keytool(
        "-genkeypair", "-v",
        "-keystore", str(KEYSTORE_PATH),
        "-alias", DEFAULT_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
        "-storepass", DEFAULT_PASS, "-keypass", DEFAULT_PASS,
        "-dname", "CN=Morphe Patches, OU=tools, O=morphe-patches, C=NP",
    )
    KEYSTORE_META.write_text(
        json.dumps(
            {"file": KEYSTORE_PATH.name, "alias": DEFAULT_ALIAS,
             "storePass": DEFAULT_PASS, "keyPass": DEFAULT_PASS},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    ok(f"{KEYSTORE_PATH}  (alias {DEFAULT_ALIAS!r}, store+key pass {DEFAULT_PASS!r})")
    info("this key is local to tools/.morphe/ and gitignored; keep it, so re-installs keep app data")
    return Signing(KEYSTORE_PATH)


def align_and_sign(apk_in: Path, apk_out: Path, signing: Signing, tmp_dir: Path) -> Path:
    """zipalign then sign, with the aligned intermediate kept out of the source tree.

    The aligned file goes in `tmp_dir`, never next to `apk_in`: for an unchanged split
    `apk_in` is the original in apks/<key>/, and dropping a `*.aligned.apk` there would
    both pollute the pulled splits and be picked up by the next `split_set()` glob.
    """
    tmp_dir.mkdir(parents=True, exist_ok=True)
    aligned = tmp_dir / (apk_in.stem + ".aligned.apk")
    zipalign("-f", "-p", "4", str(apk_in), str(aligned))
    apksigner(
        "sign",
        "--ks", str(signing.path),
        "--ks-key-alias", signing.alias,
        "--ks-pass", f"pass:{signing.store_pass}",
        "--key-pass", f"pass:{signing.key_pass}",
        "--v4-signing-enabled", "false",  # v4 leaves a sidecar .idsig some devices reject
        "--out", str(apk_out),
        str(aligned),
    )
    aligned.unlink(missing_ok=True)
    return apk_out


def verify_signature(apk: Path) -> str:
    result = run([sdk_tool("apksigner"), "verify", str(apk)], check=False, env=jbr_env())
    return "signed OK" if result.returncode == 0 else f"VERIFY FAILED ({result.returncode})"


# --------------------------------------------------------------------------------------
# Split handling
# --------------------------------------------------------------------------------------


def split_set(directory: Path) -> list[Path]:
    apks = [p for p in sorted(directory.glob("*.apk")) if ".aligned" not in p.stem]
    if not apks:
        raise ToolError(
            f"no APKs in {directory}\n  pull them first: python tools/intake.py --package <pkg>"
        )
    base = [p for p in apks if p.name == "base.apk"]
    if len(base) == 1:
        return base + [p for p in apks if p is not base[0]]
    return apks


def merge_splits(splits: list[Path], dest: Path) -> Path:
    """base.apk plus every entry the config splits add, as one APK.

    Only feeds the patcher - it is never installed. Entries already present in the base
    win, because the base is the authoritative copy of anything the splits also ship.
    """
    base = next((p for p in splits if p.name == "base.apk"), splits[0])
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(base, dest)
    have = set(zip_entries(dest))
    added = 0
    with zipfile.ZipFile(dest, "a", zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
        for split in splits:
            if split == base:
                continue
            with zipfile.ZipFile(split) as z:
                for name in z.namelist():
                    if name.endswith("/") or name in have:
                        continue
                    zout.writestr(name, z.read(name))
                    have.add(name)
                    added += 1
    ok(f"merged {len(splits)} split(s): base + {added} added entries -> {dest.name}")
    return dest


def entry_diff(before: Path, after: Path) -> dict[str, bytes]:
    """Entries whose bytes changed between two APKs, keyed by name."""
    a = zip_entry_blob(before)
    b = zip_entry_blob(after)
    return {name: blob for name, blob in b.items() if a.get(name) != blob}


# --------------------------------------------------------------------------------------
# The patcher
# --------------------------------------------------------------------------------------


def run_patch(
    apk: Path,
    mpp: Path,
    out_apk: Path,
    result_path: Path,
    *,
    enable: list[str],
    disable: list[str],
    force: bool,
    echo: bool = True,
) -> dict:
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    args = [
        "patch",
        "--unsigned",           # signing is ours, so all splits share one key
        "--continue-on-error",  # a native-only patch legitimately fails on base.apk
        "-p", str(mpp),
        "-o", str(out_apk),
        "-r", str(result_path),
    ]
    if force:
        args.append("-f")
    for name in enable:
        args += ["-e", name]
    for name in disable:
        args += ["-d", name]
    args.append(str(apk))

    result = desktop.run_cli(args, timeout=5400, echo=echo)
    if result_path.exists():
        return json.loads(result_path.read_text(encoding="utf-8"))
    if not out_apk.exists():
        raise ToolError(
            f"patcher produced no output for {apk.name} (exit {result.returncode});\n"
            + "\n".join((result.stderr or result.stdout).strip().splitlines()[-15:])
        )
    return {}


def _patch_names(entries: list) -> list[str]:
    """Names out of a patcher result list.

    `appliedPatches` entries are `{name}`; `failedPatches` entries are
    `{patch: {name}, reason}`. Both shapes occur in the same document, so normalise.
    """
    names: list[str] = []
    for entry in entries or []:
        if isinstance(entry, str):
            names.append(entry)
        elif isinstance(entry, dict):
            nested = entry.get("patch") or {}
            names.append(nested.get("name") or entry.get("name") or "?")
    return names


def _failure_reasons(result: dict) -> list[str]:
    out: list[str] = []
    for entry in result.get("failedPatches", []) or []:
        if isinstance(entry, dict) and entry.get("reason"):
            first = str(entry["reason"]).splitlines()[0]
            out.append(f"{_patch_names([entry])[0]}: {first}")
    return out


def report(result: dict, *, label: str, loud: bool = True, reasons: bool = False) -> bool:
    """Print a patcher result. Returns True if at least one patch applied."""
    applied = _patch_names(result.get("appliedPatches", []))
    failed = _patch_names(result.get("failedPatches", []))
    ok(f"{label}: applied {len(applied)} patch(es)" + (f": {', '.join(applied)}" if applied else ""))
    if failed:
        (fail if loud else warn)(f"{label}: {len(failed)} did not apply: {', '.join(failed)}")
        if reasons:
            for line in _failure_reasons(result):
                info(line[:200])
    return bool(applied)



# --------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------


def patch_single(apk: Path, work: Path, out_dir: Path, mpp: Path, signing: Signing,
                 *, enable, disable, force) -> Path:
    raw = work / (apk.stem + ".patched-unsigned.apk")
    result = run_patch(apk, mpp, raw, work / "result-single.json",
                       enable=enable, disable=disable, force=force)
    report(result, label=apk.name)
    if not raw.exists():
        raise ToolError("patcher produced no output; nothing to sign")
    out = out_dir / apk.name
    align_and_sign(raw, out, signing, work)
    ok(f"signed {out.name}  {human(out.stat().st_size)} bytes  [{verify_signature(out)}]")
    return out


def patch_split_set(splits: list[Path], work: Path, out_dir: Path, mpp: Path,
                    signing: Signing, *, enable, disable, force) -> list[Path]:
    base = next((p for p in splits if p.name == "base.apk"), splits[0])
    others = [p for p in splits if p is not base]

    # 1. one merged APK, so the patcher sees the whole app
    merged = merge_splits(splits, work / "merged.apk")
    merged_patched = work / "merged.patched.apk"
    head("patching merged APK (ground truth for entry changes)")
    merged_result = run_patch(merged, mpp, merged_patched, work / "result-merged.json",
                              enable=enable, disable=disable, force=force)
    if not report(merged_result, label="merged"):
        raise ToolError("no patch applied to the merged APK; there is nothing to graft")
    changed = entry_diff(merged, merged_patched)
    ok(f"{len(changed)} entry/entries changed: {', '.join(sorted(changed)) or '(none)'}")

    # 2. the base split, patched on its own so its dex/manifest edits and any *new* dex
    #    files land exactly where the installer expects them.
    head("patching base split")
    base_patched = work / "base.patched-unsigned.apk"
    base_result = run_patch(base, mpp, base_patched, work / "result-base.json",
                            enable=enable, disable=disable, force=force, echo=False)
    report(base_result, label="base", loud=False, reasons=True)
    if not base_patched.exists():
        warn("base patch produced no output (expected when every patch is native-only); "
             "falling back to grafting the base-owned changed entries")
        base_patched = work / "base.grafted-unsigned.apk"
        graft = {n: b for n, b in changed.items() if n in set(zip_entries(base))}
        rebundle_apk(base, base_patched, graft)

    # 3. every other split keeps its original bytes except the entries that changed and
    #    that it actually owns.
    outputs: list[Path] = []
    head("assembling installable split set")
    for split in [base, *others]:
        src = base_patched if split is base else split
        owned = {n: b for n, b in changed.items() if n in set(zip_entries(split))}
        if split is not base:
            # A split's own manifest (it carries `split="config.x"`) and resource table are its
            # identity; the merged copies describe the whole app. Grafting them turned every split
            # into a second base -> "INSTALL_FAILED_INVALID_APK: Split null was defined multiple
            # times". Manifest / resource edits belong to the base's own patch run only.
            owned = {n: b for n, b in owned.items() if n not in ("AndroidManifest.xml", "resources.arsc")}
        if split is not base and owned:
            staged = work / (split.stem + ".grafted.apk")
            rebundle_apk(split, staged, owned)
            src = staged
            info(f"{split.name}: grafted {', '.join(sorted(owned))}")
        elif split is not base:
            info(f"{split.name}: unchanged")
        out = out_dir / split.name
        align_and_sign(src, out, signing, work)
        outputs.append(out)
        ok(f"signed {out.name}  {human(out.stat().st_size)} bytes  [{verify_signature(out)}]")
    return outputs


def write_manifest(out_dir: Path, *, identity: dict, mpp: Path, result: dict,
                   outputs: list[Path], signing: Signing) -> Path:
    lines = [
        "# Patched build manifest (generated by tools/patch.py)",
        "",
        f"- package: `{identity.get('package')}`",
        f"- version: `{identity.get('versionName')}` (versionCode {identity.get('versionCode')})",
        f"- patches bundle: `{mpp.name}`",
        f"- patched at: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"- signing key: `{signing.path.name}` alias `{signing.alias}`",
        "",
        "## Patches applied",
        "",
    ]
    applied = _patch_names(result.get("appliedPatches", []))
    failed = _patch_names(result.get("failedPatches", []))
    lines += [f"- {n}" for n in applied] or ["- (none)"]
    if failed:
        lines += ["", "## Patches that did NOT apply", ""] + [f"- {n}" for n in failed]
    lines += ["", "## Output APKs", "", "| file | bytes |", "|---|---|"]
    for out in outputs:
        lines.append(f"| `{out.name}` | {out.stat().st_size:,} |")
    lines += [
        "",
        "## Install",
        "",
        "```",
        "adb install-multiple --no-incremental -r " + " ".join(f'"{o.name}"' for o in outputs),
        "```",
        "",
        "`--no-incremental` is deliberate; the install set must be signed with one key or the",
        "device rejects it. Re-patching with the same signing key keeps app data on `-r`.",
        "",
    ]
    path = out_dir / "MANIFEST.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--package", help="package name; uses apks/<key>/ splits")
    src.add_argument("--apk", type=Path, help="a single, non-split APK")
    src.add_argument("--splits", type=Path, help="directory containing a split set")
    ap.add_argument("--key", help="appdata key (default: derived from --package)")
    ap.add_argument("--patches", type=Path, help="MPP bundle (default: newest in patches/build/libs)")
    ap.add_argument("--out", type=Path, help="output directory")
    ap.add_argument("--enable", action="append", default=[], metavar="NAME")
    ap.add_argument("--disable", action="append", default=[], metavar="NAME")
    ap.add_argument("--force", action="store_true", help="ignore version compatibility")
    ap.add_argument("--list", action="store_true", help="list compatible patches and exit")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--fresh", action="store_true", help="uninstall the package before installing")
    ap.add_argument("--serial")
    ap.add_argument("--jar", type=Path, help="use this Morphe desktop jar")
    ap.add_argument("--keystore", type=Path)
    ap.add_argument("--ks-pass")
    ap.add_argument("--ks-alias")
    ap.add_argument("--key-pass")
    ap.add_argument("--clean-work", action="store_true", help="delete intermediates after")
    args = ap.parse_args(argv[1:])

    mpp = args.patches or built_bundle()
    if mpp is None or not mpp.exists():
        raise ToolError(
            "no patch bundle. Build one first:\n"
            "  ./gradlew :patches:generatePatchesList :patches:buildAndroid   (gradlew.bat on Windows)\n"
            "or pass --patches <file.mpp>"
        )
    info(f"bundle: {mpp}")

    # --- resolve the input(s)
    if args.apk:
        splits = [args.apk]
        label = args.apk.stem
        out_dir = args.out or (APKS_DIR / f"{label}-patched")
    else:
        if args.splits:
            directory = args.splits
            key = args.key or directory.name
        else:
            from intake import resolve_key

            key = resolve_key(args.package, args.key)
            directory = APKS_DIR / key
        splits = split_set(directory)
        label = key
        out_dir = args.out or (APKS_DIR / f"{key}-patched")

    identity = apk_identity(next((p for p in splits if p.name == "base.apk"), splits[0]))
    head("input")
    info(f"package {identity['package']} {identity['versionName']} (code {identity['versionCode']})")
    for s in splits:
        info(f"  {s.name}  {human(s.stat().st_size)} bytes")

    if args.list:
        head("patches compatible with this input")
        desktop.run_cli(
            ["list-patches", "--patches", str(mpp), "-f", identity["package"]],
            jar=args.jar, timeout=600,
        )
        return 0

    signing = ensure_keystore(args.keystore, alias=args.ks_alias,
                              store_pass=args.ks_pass, key_pass=args.key_pass)
    info(f"signing key: {signing.path.name} (alias {signing.alias})")

    work = out_dir / "work"
    work.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    if len(splits) == 1:
        head("patching (single APK)")
        outputs = [patch_single(splits[0], work, out_dir, mpp, signing,
                                enable=args.enable, disable=args.disable, force=args.force)]
        result = json.loads((work / "result-single.json").read_text(encoding="utf-8"))
    else:
        head("patching (split set)")
        outputs = patch_split_set(splits, work, out_dir, mpp, signing,
                                  enable=args.enable, disable=args.disable, force=args.force)
        result = json.loads((work / "result-merged.json").read_text(encoding="utf-8"))

    manifest = write_manifest(out_dir, identity=identity, mpp=mpp, result=result,
                              outputs=outputs, signing=signing)
    ok(f"wrote {manifest}")

    if args.clean_work:
        shutil.rmtree(work, ignore_errors=True)
        info("removed intermediates (--clean-work)")

    if args.install:
        adb = Adb(args.serial)
        if args.fresh:
            head(f"uninstalling {identity['package']} (--fresh)")
            adb.uninstall(identity["package"])
        head(f"installing to {adb.serial}")
        res = adb.install_multiple(outputs)
        text = (res.stdout + res.stderr).strip()
        print(text)
        if "Success" not in text:
            raise ToolError(
                f"install-multiple did not report Success (exit {res.returncode}).\n"
                "If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE, the currently installed copy "
                "was signed with a different key - uninstall it once, or pass --fresh."
            )
        ok("installed")

    head("done")
    info(f"install set: {out_dir}")
    for o in outputs:
        info(f"  {o.name}  {human(o.stat().st_size)} bytes")
    print()
    ok("patched build ready")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
