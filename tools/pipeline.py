"""One entry point for the whole loop, and a readiness check for the machine running it.

    python tools/pipeline.py doctor                     # is this box ready to patch?
    python tools/pipeline.py doctor --fix                # ...and fetch the desktop CLI if not

    # build -> static gate -> apply -> install -> A/B, in that order
    python tools/pipeline.py loop --package np.com.nepalipatro --install --ab

    # start from an APK you already pulled, skipping the device stages
    python tools/pipeline.py loop --package np.com.nepalipatro --skip-build --skip-ab

Each stage is a **separate process** running the sibling tool with its own CLI, not an
in-library call. Two reasons: every tool stays runnable on its own (so a failure is
reproducible with one copy-pasted command), and the loop's own output cannot interleave
with a tool's buffered stdout and make a failure hard to attribute.

    doctor   environment readiness, with the fix for each failure
    build    gradle :patches:generatePatchesList :patches:buildAndroid
    gate     tools/verify.py --app <key>          (fingerprints, literals, bundle dex)
    apply    tools/patch.py --package <pkg>       (merge, patch, graft, align, sign)
    install  adb install-multiple of the patched set
    ab       tools/device.py ab                   (stock vs patched pass + marker diff)

`doctor` is the first command to run on a new machine, and the first thing to paste into a
bug report: almost every "the patch didn't work" that is not a wrong target is a missing or
wrong-version tool, and this names which one.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402
import desktop  # noqa: E402
import patch as patch_tool  # noqa: E402
from common import (  # noqa: E402
    APPDATA_DIR,
    APKS_DIR,
    REPO_ROOT,
    TOOLS_DIR,
    ToolError,
    adb_devices,
    build_tools,
    built_bundle,
    fail,
    head,
    human,
    info,
    jbr_env,
    ok,
    sdk_root,
    warn,
)

PY = sys.executable
PY_MIN = (3, 10)


# --------------------------------------------------------------------------------------
# doctor
# --------------------------------------------------------------------------------------

#: (label, severity) where severity is "fail" (the loop cannot run) or "warn" (one stage
#: needs it, or the box is fine for everything except the device stages).
FAIL, WARN = "fail", "warn"


def _python_check() -> tuple[bool, str, str]:
    v = sys.version_info
    good = (v.major, v.minor) >= PY_MIN
    return good, f"{v.major}.{v.minor}.{v.micro}", f"need >= {PY_MIN[0]}.{PY_MIN[1]}"


def _adb_check() -> tuple[bool, str, str]:
    if not shutil.which("adb"):
        return False, "not on PATH", "install platform-tools, or add it to PATH"
    try:
        devices = adb_devices()
    except ToolError as exc:
        return False, str(exc), "run `adb start-server`"
    if not devices:
        return False, "adb present, no device attached", "start an emulator (tools/pipeline.py doctor needs none for build/gate)"
    return True, ", ".join(devices), ""


def _jbr_check() -> tuple[bool, str, str]:
    jbr = common.resolve_jbr()
    if jbr is not None:
        return True, str(jbr), ""
    override_hint = f"set {common.JBR_ENV_VAR} to your JBR/JDK 17-21"
    if shutil.which("java"):
        return (
            True,
            f"no Android Studio JBR; using `java` on PATH ({shutil.which('java')})",
            f"fine if that is JDK 17-21; the gradle release build breaks on newer - {override_hint}",
        )
    return False, "no JVM found", f"install a JDK 17-21, or {override_hint}"


def _apktool_check() -> tuple[bool, str, str]:
    try:
        argv = common.resolve_apktool()
    except ToolError as exc:
        return False, "not found", str(exc)
    return True, " ".join(argv), ""


def _sdk_check() -> tuple[bool, str, str]:
    try:
        root = sdk_root()
    except ToolError as exc:
        return False, str(exc), "set ANDROID_HOME to the SDK root"
    return True, str(root), ""


def _build_tools_check() -> tuple[bool, str, str]:
    try:
        bt = build_tools()
    except ToolError as exc:
        return False, str(exc), "install build-tools via the SDK manager"
    missing = [n for n in ("zipalign", "apksigner", "aapt") if not _has_tool(bt, n)]
    if missing:
        return False, f"{bt.name} missing {', '.join(missing)}", "install a complete build-tools package"
    return True, bt.name, ""


def _has_tool(bt: Path, name: str) -> bool:
    return any((bt / c).exists() for c in (common.exe(name), f"{name}.bat", name))


def _desktop_check() -> tuple[bool, str, str]:
    jar = desktop.find_local()
    if jar is None:
        return False, "not found", "run `python tools/desktop.py --setup` (downloads the pinned release)"
    if desktop.trusted(jar):
        return True, f"{jar.name} (pinned {desktop.PINNED_VERSION}, sha256 ok)", ""
    return (
        False,
        f"{jar.name} present but NOT the pinned build",
        "run `python tools/desktop.py --setup --force`, or pass --allow-unpinned deliberately",
    )


def _bundle_check() -> tuple[bool, str, str]:
    bundle = built_bundle()
    if bundle is None:
        return False, "no .mpp in patches/build/libs (sources/javadoc excluded)", "run `python tools/pipeline.py loop --skip-build ...` once, or the gradle build"
    return True, f"{bundle.name}  {human(bundle.stat().st_size)} bytes", ""


def _appdata_check() -> tuple[bool, str, str]:
    keys = sorted(p.stem for p in APPDATA_DIR.glob("*.yml"))
    if not keys:
        return False, "none", "add tools/appdata/<key>.yml"
    return True, f"{len(keys)}: {', '.join(keys)}", ""


def _keystore_check() -> tuple[bool, str, str]:
    if patch_tool.KEYSTORE_META.exists() or patch_tool.KEYSTORE_PATH.exists():
        return True, str(patch_tool.KEYSTORE_PATH), ""
    env = os.environ.get("MORPHE_KEYSTORE")
    if env and Path(env).exists():
        return True, f"{env} (from MORPHE_KEYSTORE)", ""
    return True, "none yet - one is generated on first `apply`", ""


CHECKS: list[tuple[str, str, object]] = [
    ("python", FAIL, _python_check),
    ("adb", WARN, _adb_check),
    ("jdk / JBR", FAIL, _jbr_check),
    ("apktool", WARN, _apktool_check),
    ("android sdk", FAIL, _sdk_check),
    ("build-tools", FAIL, _build_tools_check),
    ("morphe desktop cli", FAIL, _desktop_check),
    ("patch bundle", WARN, _bundle_check),
    ("appdata", WARN, _appdata_check),
    ("signing key", WARN, _keystore_check),
]


def doctor(fix: bool) -> int:
    head("environment")
    blockers = 0
    for label, severity, fn in CHECKS:
        good, detail, remedy = fn()  # type: ignore[operator]
        if good:
            ok(f"{label:<20} {detail}")
        elif severity == FAIL:
            fail(f"{label:<20} {detail}")
            info(f"{'':<20} -> {remedy}")
            blockers += 1
        else:
            warn(f"{label:<20} {detail}")
            info(f"{'':<20} -> {remedy}")

    if fix:
        jar = desktop.find_local()
        if jar is None or not desktop.trusted(jar):
            head("--fix: fetching the pinned Morphe desktop CLI")
            desktop.ensure(force=True)

    print()
    if blockers:
        fail(f"{blockers} blocker(s): the loop cannot run until they are fixed")
        return 1
    ok("ready - `python tools/pipeline.py loop --package <pkg> --install --ab`")
    return 0


# --------------------------------------------------------------------------------------
# loop
# --------------------------------------------------------------------------------------


def _stage(name: str, argv: list[str], *, env: dict | None = None) -> int:
    head(name)
    info("$ " + " ".join(str(a) for a in argv))
    # Flush before handing the console to the child: when stdout is a pipe (or the harness
    # captures it) Python buffers, and the child's output would appear *before* the header.
    sys.stdout.flush()
    sys.stderr.flush()
    proc = subprocess.run([str(a) for a in argv], env=env, cwd=str(REPO_ROOT))
    if proc.returncode != 0:
        fail(f"{name} exited {proc.returncode}")
    else:
        ok(f"{name} done")
    return proc.returncode


def loop(args) -> int:
    key = args.key
    if key is None:
        from intake import resolve_key

        key = resolve_key(args.package)
    apks_dir = APKS_DIR / key
    patched_dir = args.patched or (APKS_DIR / f"{key}-patched")

    stages: list[tuple[str, list[str], dict | None]] = []

    if not args.skip_build:
        stages.append(
            (
                "build bundle",
                [*common.gradle_wrapper(), ":patches:generatePatchesList",
                 ":patches:buildAndroid", "--console=plain"],
                jbr_env(),
            )
        )

    stages.append(
        (
            "static gate",
            [PY, str(TOOLS_DIR / "verify.py"), "--app", key, "--no-build", "--no-clean"],
            None,
        )
    )
    stages.append(
        ("apply patches", [PY, str(TOOLS_DIR / "patch.py"), "--package", args.package, "--key", key]
         + (["--force"] if args.force else []), None)
    )
    if args.install:
        stages.append(
            ("install", [PY, str(TOOLS_DIR / "device.py")]
             + (["--serial", args.serial] if args.serial else [])
             + ["install", "--dir", str(patched_dir), "--fresh"], None)
        )
    if args.ab:
        stages.append(
            ("A/B device test", [PY, str(TOOLS_DIR / "device.py")]
             + (["--serial", args.serial] if args.serial else [])
             + ["ab", "--plan", args.plan or key,
                "--stock", str(apks_dir), "--patched", str(patched_dir)], None)
        )

    if args.dry_run:
        head("dry run - stages that would run")
        for name, argv, _ in stages:
            info(f"{name:<18} {' '.join(str(a) for a in argv)}")
        return 0

    for name, argv, env in stages:
        rc = _stage(name, argv, env=env)
        if rc != 0 and not args.keep_going:
            print()
            fail(f"stopped at `{name}` (pass --keep-going to continue)")
            return rc

    print()
    head("loop complete")
    info(f"install set: {patched_dir}")
    if not args.ab:
        info("no A/B run - remember that the static gate proves 'it applies', never 'it fixes'")
    ok("done")
    return 0


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="command", required=True)

    d = sub.add_parser("doctor", help="check the environment is ready to patch")
    d.add_argument("--fix", action="store_true", help="fetch the pinned desktop CLI if missing")

    l = sub.add_parser("loop", help="build -> gate -> apply -> install -> A/B")
    l.add_argument("--package", required=True)
    l.add_argument("--key", help="appdata key (default: derived from --package)")
    l.add_argument("--serial")
    l.add_argument("--plan", help="device test plan name for --ab (default: the key)")
    l.add_argument("--patched", type=Path, help="patched output dir")
    l.add_argument("--install", action="store_true")
    l.add_argument("--ab", action="store_true", help="run the stock vs patched device test")
    l.add_argument("--force", action="store_true", help="pass -f to the patcher (ignore version compat)")
    l.add_argument("--skip-build", action="store_true")
    l.add_argument("--keep-going", action="store_true", help="do not stop at the first failing stage")
    l.add_argument("--dry-run", action="store_true", help="print the stages and exit")

    args = ap.parse_args(argv[1:])
    if args.command == "doctor":
        return doctor(args.fix)
    return loop(args)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
