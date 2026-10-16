"""Morphe Desktop CLI - locate it, verify it, and drive it.

    python tools/desktop.py --setup            # fetch + verify the pinned jar
    python tools/desktop.py --which            # print the jar/java that will be used
    python tools/desktop.py --version          # ask the CLI itself
    python tools/desktop.py run list-patches -p patches/build/libs/x.mpp

`morphe-desktop` is the thing that actually applies patches to an APK. Everything
upstream of it in this repo (`intake.py`, `fingerprints.py`, `verify.py`) only proves a
patch is *well-formed*; this is the first stage that answers "does it apply at all".

Why a pinned download rather than "put a jar somewhere":

  * the CLI is the one moving part whose version changes the patch contract, and a
    stale jar silently applies nothing or, worse, applies a different set of patches;
  * `*.jar` is gitignored, so the file can never travel with the repo anyway - a
    pinned URL + sha256 is the only reproducible way to get the same binary on a
    second machine;
  * an explicit, verified path removes the "which jar did I run last time" guesswork
    that made the earlier ad-hoc runs hard to reproduce.

Resolution order (first hit wins):

  1. `--jar <path>` on the command line
  2. `$MORPHE_DESKTOP_JAR`
  3. `tools/.morphe/morphe-desktop-<pinned>-all.jar` (and then any `*.jar` there)
  4. a system-installed `morphe-desktop` on `PATH`, if one exists

A file is only accepted if it is a readable JAR; it is only *trusted* if it matches the
pinned sha256. Untrusted copies are reported as such and require `--allow-unpinned`,
so "works on my machine" cannot quietly mean "ran a different patcher".
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    TOOLS_DIR,
    ToolError,
    fail,
    head,
    human,
    info,
    java_exe,
    ok,
    run,
    warn,
)

# --------------------------------------------------------------------------------------
# The pin
# --------------------------------------------------------------------------------------

MORPHE_REPO = "MorpheApp/morphe-desktop"

#: The CLI version this repo's patches are authored and verified against. Bump this and
#: PINNED_SHA256 together; both are checked, so a partial bump fails loudly.
PINNED_VERSION = "1.18.1"
PINNED_SHA256 = "1b506ab5f03d16a2f65026d5e0e1910d01fc1e2152f21eaeb44ed2f30856597b"
PINNED_SIZE = 50_323_102

DESKTOP_DIR = TOOLS_DIR / ".morphe"
JAR_NAME = f"morphe-desktop-{PINNED_VERSION}-all.jar"
DOWNLOAD_URL = (
    f"https://github.com/{MORPHE_REPO}/releases/download/v{PINNED_VERSION}/{JAR_NAME}"
)

MIN_BYTES = 1_000_000  # anything smaller is not this jar


def sha256(path: Path, *, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        while block := fh.read(chunk):
            h.update(block)
    return h.hexdigest()


def is_jar(path: Path) -> bool:
    """Cheap JAR sanity: readable, big enough, and starts with the ZIP magic."""
    if not path.is_file() or path.stat().st_size < MIN_BYTES:
        return False
    try:
        with path.open("rb") as fh:
            return fh.read(4) == b"PK\x03\x04"
    except OSError:
        return False


def trusted(path: Path) -> bool:
    if path.stat().st_size != PINNED_SIZE:
        return False
    return sha256(path) == PINNED_SHA256


# --------------------------------------------------------------------------------------
# Java
# --------------------------------------------------------------------------------------


def java() -> str:
    """The JVM to run the CLI with.

    Delegates to `common.java_exe()`: the JBR when Android Studio is installed, otherwise
    `java` on PATH. That is enough for this CLI (it is a plain JVM application, unlike the
    gradle build which needs a JDK the Android transform accepts).
    """
    return java_exe()


# --------------------------------------------------------------------------------------
# Locate / fetch
# --------------------------------------------------------------------------------------


def find_local(explicit: Path | None = None) -> Path | None:
    """First existing candidate, in resolution order. Does not verify trust."""
    if explicit is not None:
        return explicit if explicit.is_file() else None
    env = os.environ.get("MORPHE_DESKTOP_JAR")
    if env:
        return Path(env) if Path(env).is_file() else None
    pinned = DESKTOP_DIR / JAR_NAME
    if is_jar(pinned):
        return pinned
    if DESKTOP_DIR.is_dir():
        jars = sorted(DESKTOP_DIR.glob("*.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
        for jar in jars:
            if is_jar(jar):
                return jar
    for name in ("morphe-desktop-all.jar", "morphe-desktop.jar", "morphe-desktop"):
        hit = shutil.which(name)
        if hit:
            return Path(hit)
    return None


def _download(dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    info(f"GET {DOWNLOAD_URL}")
    tty = sys.stdout.isatty()
    try:
        with urllib.request.urlopen(DOWNLOAD_URL, timeout=120) as resp:  # noqa: S310
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            milestone = 0
            h = hashlib.sha256()
            with tmp.open("wb") as fh:
                while block := resp.read(1 << 20):
                    fh.write(block)
                    h.update(block)
                    done += len(block)
                    pct = 100.0 * done / total if total else 0.0
                    if tty:
                        print(f"\r  {human(done)} / {human(total)}  ({pct:5.1f}%)", end="")
                    elif pct >= milestone + 25:  # ten lines, not five hundred, in a log
                        milestone = int(pct // 25) * 25
                        info(f"  {pct:5.1f}%  ({human(done)} / {human(total)})")
            if tty:
                print()
    except Exception as exc:  # network, TLS, 404 - all the same recovery
        tmp.unlink(missing_ok=True)
        raise ToolError(
            f"download failed: {exc}\n"
            f"  url: {DOWNLOAD_URL}\n"
            "If you already have the jar, import it instead:\n"
            f"  python tools/desktop.py --setup --from <path-to-jar>"
        ) from exc
    digest = h.hexdigest()
    if digest != PINNED_SHA256:
        tmp.unlink(missing_ok=True)
        raise ToolError(
            "downloaded jar does not match the pinned sha256 (refusing to use it):\n"
            f"  expected {PINNED_SHA256}\n  got      {digest}\n"
            "The release asset changed, or the download was corrupted. Verify the "
            f"release page: https://github.com/{MORPHE_REPO}/releases/tag/v{PINNED_VERSION}"
        )
    tmp.replace(dest)
    ok(f"{dest.name}  {human(dest.stat().st_size)} bytes, sha256 verified")


def ensure(
    explicit: Path | None = None,
    *,
    from_path: Path | None = None,
    allow_unpinned: bool = False,
    force: bool = False,
) -> Path:
    """Return a usable jar, downloading/importing if needed."""
    if explicit is not None:
        return _accept(explicit, allow_unpinned, what="--jar")

    if from_path is not None:
        if not is_jar(from_path):
            raise ToolError(f"{from_path} is not a readable Morphe jar")
        dest = DESKTOP_DIR / JAR_NAME
        if trusted(from_path) or allow_unpinned:
            DESKTOP_DIR.mkdir(parents=True, exist_ok=True)
            shutil.copy2(from_path, dest)
            ok(f"imported {from_path} -> {dest}")
            return _accept(dest, allow_unpinned, what="--from")
        raise ToolError(
            f"{from_path} is a JAR but not the pinned build:\n"
            f"  expected sha256 {PINNED_SHA256}\n  got      {sha256(from_path)}\n"
            "Pass --allow-unpinned to use it anyway (the patch contract may differ)."
        )

    existing = find_local()
    if existing is not None and not force:
        return _accept(existing, allow_unpinned, what="found")

    dest = DESKTOP_DIR / JAR_NAME
    if force:
        info("--force: re-downloading")
    _download(dest)
    return dest


def _accept(jar: Path, allow_unpinned: bool, *, what: str) -> Path:
    if not is_jar(jar):
        raise ToolError(f"{jar} ({what}) is not a readable Morphe jar (>1 MB, PK header)")
    if trusted(jar):
        return jar
    if allow_unpinned:
        warn(
            f"{jar} is NOT the pinned build "
            f"(expected sha256 {PINNED_SHA256[:12]}..., got {sha256(jar)[:12]}...); "
            "using it because --allow-unpinned was passed"
        )
        return jar
    raise ToolError(
        f"{jar} ({what}) does not match the pinned build:\n"
        f"  expected sha256 {PINNED_SHA256}  ({human(PINNED_SIZE)} bytes)\n"
        f"  got      sha256 {sha256(jar)}  ({human(jar.stat().st_size)} bytes)\n"
        "Run `python tools/desktop.py --setup --force` to fetch the pinned v"
        f"{PINNED_VERSION}, or pass --allow-unpinned if this is deliberate."
    )


# --------------------------------------------------------------------------------------
# Run
# --------------------------------------------------------------------------------------


def run_cli(args: list[str], *, jar: Path | None = None, timeout: int = 3600, echo: bool = True):
    """Run the CLI with `args`, returning common.Run. `echo` prints its output."""
    jar = ensure(jar)
    argv = [java(), "-jar", str(jar), *args]
    info("$ " + " ".join(argv))
    result = run(argv, check=False, timeout=timeout, capture=True)
    if echo:
        for stream in (result.stdout, result.stderr):
            if stream.strip():
                print(stream.rstrip())
    return result


def version(jar: Path | None = None) -> str:
    result = run_cli(["--version"], jar=jar, echo=False)
    return (result.stdout or result.stderr).strip()


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--jar", type=Path, help="use this jar instead of resolving one")
    ap.add_argument("--setup", action="store_true", help="fetch + verify the pinned jar")
    ap.add_argument("--from", dest="from_path", type=Path, help="import an existing jar")
    ap.add_argument("--force", action="store_true", help="with --setup, re-download")
    ap.add_argument("--allow-unpinned", action="store_true")
    ap.add_argument("--which", action="store_true", help="print jar + java and exit")
    ap.add_argument("--version", action="store_true", help="ask the CLI for its version")
    ap.add_argument("command", nargs="?", help="`run` to forward the rest to the CLI")
    ap.add_argument("rest", nargs=argparse.REMAINDER)
    args = ap.parse_args(argv[1:])

    if args.setup or args.from_path:
        jar = ensure(
            args.jar,
            from_path=args.from_path,
            allow_unpinned=args.allow_unpinned,
            force=args.force,
        )
        head("ready")
        info(f"jar:  {jar}")
        info(f"java: {java()}")
        print()
        ok(f"morphe-desktop {PINNED_VERSION} verified")
        return 0

    jar = ensure(args.jar, allow_unpinned=args.allow_unpinned)

    if args.which:
        head("morphe desktop")
        info(f"jar:    {jar}")
        info(f"size:   {human(jar.stat().st_size)} bytes")
        info(f"sha256: {sha256(jar)}")
        info(f"pin:    {PINNED_VERSION}  (match={trusted(jar)})")
        info(f"java:   {java()}")
        return 0

    if args.version:
        print(version(jar))
        return 0

    if args.command == "run":
        rest = [a for a in args.rest if a != "--"]
        if not rest:
            raise ToolError("`run` needs arguments, e.g. `run list-patches -p x.mpp`")
        return run_cli(rest, jar=jar).returncode

    if args.command is None:
        # No command: behave like --setup --which, the useful default.
        head("morphe desktop")
        info(f"jar:    {jar}")
        info(f"pin:    {PINNED_VERSION}  (match={trusted(jar)})")
        info(f"java:   {java()}")
        print()
        ok("jar ready (plugins: tools/patch.py, tools/pipeline.py)")
        return 0

    raise ToolError(f"unknown command {args.command!r} (try `run <cli args...>`)")


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
