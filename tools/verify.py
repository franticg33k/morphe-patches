"""Pre-commit gate. Everything that must be true before pushing a patch change.

    python tools/verify.py --all
    python tools/verify.py --app hamropatro
    python tools/verify.py --all --no-build     # skip the gradle step

Each check below exists because it bit us at least once:

  1. parser self-tests        - dexdesc and miniyaml are hand-rolled; a silent mis-parse
                                reads as "fingerprint matches 0", which is indistinguishable
                                from a genuinely stale fingerprint.
  2. buildAndroid LAST        - running :patches:generatePatchesList after :patches:buildAndroid
                                was observed to emit a dex-less bundle, which the manager
                                rejects with "Patch bundle is missing dex entries" and shows
                                "Patches: 0".
  3. bundle has dex           - same failure, caught directly.
  4. fingerprints resolve 1x  - catches a rotated obfuscated type before release.
  5. compiled literals        - a literal can vanish during *compilation* while the
                                structural check still passes.
  6. generated files current  - patches-list.json / README.md are produced by CI. If they
                                are stale here, the next release ships a stale manifest.

What this cannot check: that a patch fixes anything. Only the device answers that.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fingerprints as fp
from common import (
    REPO_ROOT,
    ToolError,
    built_bundle,
    fail,
    gradle,
    gradle_version,
    head,
    info,
    ok,
    warn,
)

TESTS = ["test_dexdesc.py", "test_miniyaml.py"]


def run_tests() -> bool:
    head("parser self-tests")
    good = True
    for name in TESTS:
        proc = subprocess.run(
            [sys.executable, str(REPO_ROOT / "tools" / name)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        # unittest writes its summary to stderr, not stdout.
        combined = (proc.stdout or "") + (proc.stderr or "")
        tail = [ln for ln in combined.splitlines() if ln.startswith("Ran ")]
        detail = tail[-1] if tail else "no summary"
        if proc.returncode == 0:
            ok(f"{name:<20} {detail}")
        else:
            good = False
            fail(f"{name:<20} {detail}")
            for ln in (proc.stderr or "").splitlines()[-15:]:
                info(ln)
    return good


def run_build(do_clean: bool) -> Path:
    head(f"build (gradle {gradle_version()})")
    if do_clean:
        info("clean")
        gradle(":patches:clean")
    info("buildAndroid  <- must run last, after generatePatchesList")
    gradle(":patches:buildAndroid")
    bundle = built_bundle()
    if bundle is None:
        raise ToolError("no .mpp produced in patches/build/libs")
    ok(f"built {bundle.name}  ({bundle.stat().st_size:,} bytes)")
    expected = f"patches-{gradle_version()}.mpp"
    if bundle.name != expected:
        warn(f"newest bundle is {bundle.name}, gradle.properties says {expected} - "
             "you may be looking at a stale artifact")
    return bundle


PACKAGE_RE = re.compile(r'packageName\s*=\s*"([^"]+)"')


def check_generated(bundle: Path) -> bool:
    """Every app declared in the Kotlin source must appear in patches-list.json.

    Deliberately NOT a version comparison. CI bumps gradle.properties on release and
    regenerates the list at the same time, so a version mismatch right after a release is
    expected noise, not a problem. What actually matters is a patch that exists in source
    but is missing from the published manifest - and that is checkable without any version
    coupling.
    """
    head("generated files")
    plist = REPO_ROOT / "patches-list.json"
    if not plist.exists():
        fail("patches-list.json missing")
        return False
    text = plist.read_text(encoding="utf-8", errors="replace")

    declared: set[str] = set()
    constants = REPO_ROOT / "patches" / "src"
    for path in constants.rglob("Constants.kt"):
        for m in PACKAGE_RE.finditer(path.read_text(encoding="utf-8", errors="replace")):
            declared.add(m.group(1))
    if not declared:
        warn("no packageName found in patches/src - nothing to compare")
        return True

    missing = sorted(p for p in declared if p not in text)
    if missing:
        fail(f"{len(missing)} app(s) in source but absent from patches-list.json:")
        for p in missing:
            info(p)
        info("run `./gradlew :patches:generatePatchesList` and "
             "`.github/scripts/generate_patches_readme.py`, then rebuild "
             "(buildAndroid must stay last)")
        return False
    ok(f"patches-list.json covers all {len(declared)} declared app(s)")
    info("this is the check that catches 'added a patch but forgot to regenerate'; it "
         "ignores the version on purpose, since CI bumps gradle.properties and regenerates "
         "together, so a version lag is expected rather than a defect")
    return True


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--app", help="only this app key")
    ap.add_argument("--all", action="store_true", help="every app in appdata/ (default)")
    ap.add_argument("--no-build", action="store_true", help="skip gradle; verify an existing bundle")
    ap.add_argument("--no-clean", action="store_true")
    ap.add_argument("--bundle", type=Path, help="explicit bundle for --no-build")
    args = ap.parse_args(argv[1:])

    results: list[tuple[str, bool]] = []

    results.append(("parser self-tests", run_tests()))

    if args.no_build:
        bundle = args.bundle or built_bundle()
        if bundle is None or not bundle.exists():
            raise ToolError("no bundle to check; drop --no-build or pass --bundle")
        head(f"bundle {bundle.name} (pre-existing)")
    else:
        bundle = run_build(not args.no_clean)

    results.append(("generated files current", check_generated(bundle)))

    keys = [args.app] if args.app else fp.available_apps()
    for key in keys:
        print()
        good = fp.run_app(key, bundle, True)
        results.append((f"fingerprints: {key}", good))

    head("summary")
    bad = [n for n, g in results if not g]
    for name, good in results:
        (ok if good else fail)(name)
    print()
    if bad:
        fail(f"{len(bad)} check(s) failed")
        return 1
    ok("all checks passed - safe to commit")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
