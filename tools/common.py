"""Shared helpers for the morphe-patches tooling.

Deliberately dependency-free (stdlib only) and deliberately not wired into Gradle, so the
scripts run on a fresh clone of either this repo or upstream MorpheApp/morphe-patches
without any build-system changes. See tools/README.md.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------------------
# Repo layout
# --------------------------------------------------------------------------------------

TOOLS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_DIR.parent
APKS_DIR = REPO_ROOT / "apks"
EXTRACTED_DIR = APKS_DIR / "extracted"
APPDATA_DIR = TOOLS_DIR / "appdata"
PATCHES_BUILD = REPO_ROOT / "patches" / "build" / "libs"

# Android Studio's bundled JBR. The system JDK 26 fails Android's core-for-system-modules
# transform, so the gradle steps need this explicitly.
JBR = Path(r"C:\Program Files\Android\Android Studio\jbr")
APKTOOL_JAR = Path.home() / "Downloads" / "apktool.jar"

DEFAULT_SERIAL = "RZCW51E0C5H"

# --------------------------------------------------------------------------------------
# Output helpers
# --------------------------------------------------------------------------------------

_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None


def _c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _COLOR else text


def ok(msg: str) -> None:
    print(_c("32", "OK  ") + msg)


def fail(msg: str) -> None:
    print(_c("31", "FAIL") + "  " + msg)


def warn(msg: str) -> None:
    print(_c("33", "WARN") + "  " + msg)


def info(msg: str) -> None:
    print(_c("36", "  ") + msg)


def head(msg: str) -> None:
    print()
    print(_c("1;36", "== " + msg + " "))


class ToolError(RuntimeError):
    """Raised for an expected, explainable failure (missing apk, no device, ...)."""


# --------------------------------------------------------------------------------------
# Subprocess
# --------------------------------------------------------------------------------------


@dataclass
class Run:
    argv: list[str]
    returncode: int
    stdout: str
    stderr: str


def run(
    argv: list[str],
    *,
    cwd: Path | None = None,
    check: bool = True,
    timeout: int | None = None,
    capture: bool = True,
    env: dict | None = None,
) -> Run:
    """Run a command, raising ToolError with captured output on failure.

    The whole point is that failures are readable: a bare CalledProcessError hides the
    output that explains it, which is how the "does it apply vs does it fix" gap stays
    invisible.
    """
    printable = " ".join(str(a) for a in argv)
    proc = subprocess.run(
        [str(a) for a in argv],
        cwd=str(cwd) if cwd else None,
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        env=env,
    )
    result = Run(argv, proc.returncode, proc.stdout or "", proc.stderr or "")
    if check and result.returncode != 0:
        tail = (result.stderr or result.stdout or "").strip().splitlines()[-25:]
        raise ToolError(
            f"command failed ({result.returncode}): {printable}\n" + "\n".join(tail)
        )
    return result


# --------------------------------------------------------------------------------------
# adb
# --------------------------------------------------------------------------------------


class Adb:
    def __init__(self, serial: str | None = None) -> None:
        self.serial = serial or os.environ.get("ANDROID_SERIAL") or DEFAULT_SERIAL
        if not shutil.which("adb"):
            raise ToolError("adb not on PATH")
        if not self.connected():
            raise ToolError(
                f"no device {self.serial!r}. Connect it, or pass --serial / set ANDROID_SERIAL."
            )

    def argv(self, *args: str) -> list[str]:
        return ["adb", "-s", self.serial, *args]

    def devices(self) -> list[str]:
        out = run(["adb", "devices"], check=True).stdout
        found = []
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                found.append(parts[0])
        return found

    def connected(self) -> bool:
        try:
            return self.serial in self.devices()
        except ToolError:
            return False

    def shell(self, *args: str, check: bool = True) -> Run:
        return run(self.argv("shell", *args), check=check)

    def pull(self, remote: str, local: Path) -> Path:
        local.parent.mkdir(parents=True, exist_ok=True)
        run(self.argv("pull", remote, str(local)), timeout=1800)
        return local

    def package_info(self, package: str) -> dict:
        out = self.shell("dumpsys", "package", package, check=False).stdout
        info: dict = {}
        for key, pat in (
            ("versionName", r"versionName=(\S+)"),
            ("versionCode", r"versionCode=(\d+)"),
            ("abi", r"primaryCpuAbi=(\S+)"),
            ("codePath", r"codePath=(\S+)"),
            ("minSdk", r"minSdk=(\d+)"),
            ("targetSdk", r"targetSdk=(\d+)"),
        ):
            m = re.search(pat, out)
            if m:
                info[key] = m.group(1)
        return info

    def apk_paths(self, package: str) -> list[str]:
        """All split APK paths for a package, base first.

        `pm path` is the reliable way to enumerate splits - the on-device filenames are
        obfuscated (split_config.arm64_v8a.apk) and differ between installs.
        """
        out = self.shell("pm", "path", package, check=False).stdout
        paths = [ln.split(":", 1)[1].strip() for ln in out.splitlines() if ln.startswith("package:")]
        return sorted(paths, key=lambda p: (not p.endswith("/base.apk"), p))

    # -- install / lifecycle ----------------------------------------------------------

    def install_multiple(
        self,
        apks: list[Path],
        *,
        reinstall: bool = True,
        incremental: bool = False,
        timeout: int = 3600,
    ) -> Run:
        """Install a split set. Returns the Run so the caller can read Success/Failure.

        `--no-incremental` is deliberate: incremental install streams from the device and
        gives confusing partial-failure modes on an emulator; a plain push-and-install is
        what you want when validating a patched build.
        """
        argv = self.argv("install-multiple")
        if reinstall:
            argv.append("-r")
        if not incremental:
            argv.append("--no-incremental")
        argv += [str(a) for a in apks]
        return run(argv, check=False, timeout=timeout)

    def uninstall(self, package: str, *, keep_data: bool = False) -> Run:
        argv = self.argv("uninstall")
        if keep_data:
            argv.append("-k")
        argv.append(package)
        return run(argv, check=False, timeout=600)

    def force_stop(self, package: str) -> None:
        self.shell("am", "force-stop", package, check=False)

    def launch(self, package: str) -> None:
        self.shell(
            "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", check=False
        )

    def is_running(self, package: str) -> bool:
        out = self.shell("pidof", package, check=False).stdout.strip()
        return bool(out)



# --------------------------------------------------------------------------------------
# Gradle
# --------------------------------------------------------------------------------------


def gradle_version() -> str:
    props = REPO_ROOT / "gradle.properties"
    m = re.search(r"^\s*version\s*=\s*(.+)$", props.read_text(encoding="utf-8"), re.M)
    return m.group(1).strip() if m else "unknown"


def gradle(task: str, *, timeout: int = 1800) -> Run:
    """Run a gradle task with JAVA_HOME forced to Android Studio's JBR.

    Not optional: the system JDK 26's jlink rejects Android's core-for-system-modules.jar
    transform, failing :extensions:extension:compileReleaseJavaWithJavac. The env var must be
    passed to the child process - merely having JBR defined in this module does nothing.
    """
    if not JBR.exists():
        raise ToolError(
            f"Android Studio JBR not found at {JBR}.\n"
            "It is required: the system JDK 26 breaks Android's jlink transform."
        )
    env = dict(os.environ)
    env["JAVA_HOME"] = str(JBR)
    env["PATH"] = str(JBR) + os.pathsep + env.get("PATH", "")
    wrapper = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    proc = subprocess.run(
        [str(wrapper), task],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    result = Run([str(wrapper), task], proc.returncode, proc.stdout or "", proc.stderr or "")
    if result.returncode != 0:
        combined = (result.stdout + "\n" + result.stderr)
        interesting = [
            ln for ln in combined.splitlines()
            if any(k in ln for k in ("error:", "FAILED", "What went wrong", "Caused by", ">"))
        ]
        tail = interesting[-20:] or combined.strip().splitlines()[-20:]
        raise ToolError(
            f"gradle task failed ({result.returncode}): {task}\n" + "\n".join(tail)
        )
    return result


def built_bundle() -> Path | None:
    if not PATCHES_BUILD.exists():
        return None
    bundles = sorted(PATCHES_BUILD.glob("*.mpp"), key=lambda p: p.stat().st_mtime, reverse=True)
    return bundles[0] if bundles else None


# --------------------------------------------------------------------------------------
# Android SDK / build tools
# --------------------------------------------------------------------------------------


def jbr_env(base: dict | None = None) -> dict:
    """An environment with JAVA_HOME pointed at Android Studio's JBR.

    Needed by anything that shells out to `apksigner.bat`/`jlink`: the Oracle JDK 26 on
    PATH is wrong for Android's build tools and for Gradle, and `apksigner.bat` prefers
    JAVA_HOME when it is set.
    """
    env = dict(base if base is not None else os.environ)
    if JBR.exists():
        env["JAVA_HOME"] = str(JBR)
        env["PATH"] = str(JBR / "bin") + os.pathsep + env.get("PATH", "")
    return env


def java_exe() -> str:
    """JBR's java, or whatever `java` is on PATH as a fallback."""
    jbr_java = JBR / "bin" / ("java.exe" if os.name == "nt" else "java")
    if jbr_java.exists():
        return str(jbr_java)
    found = shutil.which("java")
    if not found:
        raise ToolError("no JVM found (neither Android Studio's JBR nor `java` on PATH)")
    return found


def sdk_root() -> Path:
    """The Android SDK, from the environment or the usual install locations."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(var)
        if val and Path(val).is_dir():
            return Path(val)
    for cand in (
        Path.home() / "AppData" / "Local" / "Android" / "Sdk",  # Windows default
        Path.home() / "Android" / "Sdk",                          # Linux default
        Path("/usr/lib/android-sdk"),
    ):
        if cand.is_dir():
            return cand
    raise ToolError(
        "Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT) to the SDK root."
    )


_VERSION_NUM_RE = re.compile(r"\d+")


def _build_tools_key(name: str) -> tuple:
    """Sort key for build-tools dir names.

    `37.0.0-rc2` must not outrank the stable `36.1.0` for normal use, so a release
    candidate sorts below its own release. zipalign/apksigner are version-insensitive
    enough that "newest stable" is the right default.
    """
    nums = [int(n) for n in _VERSION_NUM_RE.findall(name)]
    stable = 0 if "rc" in name or "beta" in name or "alpha" in name else 1
    return (*nums, stable)


def build_tools(major: int | None = None) -> Path:
    """The newest build-tools directory (or one matching `major`)."""
    root = sdk_root() / "build-tools"
    if not root.is_dir():
        raise ToolError(f"no build-tools under {root}")
    dirs = [d for d in root.iterdir() if d.is_dir()]
    if major is not None:
        dirs = [d for d in dirs if d.name.startswith(f"{major}.")]
    if not dirs:
        raise ToolError(f"no build-tools matching {major!r} under {root}")
    return max(dirs, key=lambda d: _build_tools_key(d.name))


def sdk_tool(name: str) -> str:
    """Absolute path to a build-tools executable (`zipalign`, `apksigner`, ...)."""
    bt = build_tools()
    for cand in (bt / f"{name}.exe", bt / f"{name}.bat", bt / name):
        if cand.exists():
            return str(cand)
    raise ToolError(f"{name} not found in {bt}")


_PACKAGE_RE = re.compile(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'")


def apk_identity(apk: Path) -> dict:
    """package name / versionCode / versionName, straight out of the binary manifest.

    Uses `aapt dump badging` rather than apktool: it reads the manifest directly and
    answers in well under a second, where a decode is a multi-minute operation. The
    patcher keys its compatibility filter on exactly this pair, so the flow reports the
    same identity it is about to patch against.
    """
    result = run([sdk_tool("aapt"), "dump", "badging", str(apk)], check=False, timeout=300)
    for line in result.stdout.splitlines():
        m = _PACKAGE_RE.match(line)
        if m:
            return {"package": m.group(1), "versionCode": m.group(2), "versionName": m.group(3)}
    raise ToolError(f"could not read package identity from {apk} (aapt: {result.stderr.strip()[:200]})")



def zipalign(*argv: str, timeout: int = 600) -> Run:
    return run([sdk_tool("zipalign"), *argv], timeout=timeout, env=jbr_env())


def apksigner(*argv: str, timeout: int = 900) -> Run:
    return run([sdk_tool("apksigner"), *argv], timeout=timeout, env=jbr_env())



# --------------------------------------------------------------------------------------
# smali
# --------------------------------------------------------------------------------------

CLASS_RE = re.compile(r"^\.class\b.*?(L[\w/$]+;)\s*$")
METHOD_RE = re.compile(
    r"^\.method\s+(?P<rest>.*?)(?P<name>[\w<>$]+)\((?P<params>[^)]*)\)(?P<ret>\S+)\s*$"
)
ACCESS_FLAGS = {
    "public": 0x1,
    "private": 0x2,
    "protected": 0x4,
    "static": 0x8,
    "final": 0x10,
    "synchronized": 0x20,
    "bridge": 0x40,
    "varargs": 0x80,
    "native": 0x100,
    "abstract": 0x400,
    "strict": 0x800,
    "synthetic": 0x1000,
    "constructor": 0x10000,
    "declared-synchronized": 0x20000,
}


@dataclass
class Method:
    name: str
    params: list[str]
    return_type: str
    access_flags: int
    defining_class: str
    path: Path
    has_body: bool = field(default=False)
    # Registers consumed by each Boolean.valueOf(invoke-static) call, in order. Filled only
    # for methods read through MethodScanner, which is how a patch body's hard-coded
    # register assumption can be checked instead of taken on trust.
    shape: list[str] = field(default_factory=list)

    @property
    def signature(self) -> str:
        return f"{self.defining_class}{self.name}({''.join(self.params)}){self.return_type}"


def smali_files(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(root.rglob("*.smali"))


def smali_roots(extracted: Path) -> list[Path]:
    """All smali* directories in an apktool tree, base `smali` first."""
    if not extracted.exists():
        return []
    roots = [p for p in extracted.iterdir() if p.is_dir() and p.name.startswith("smali")]

    def key(p: Path) -> tuple[int, str]:
        if p.name == "smali":
            return (0, p.name)
        m = re.match(r"smali_classes(\d+)$", p.name)
        return (int(m.group(1)) if m else 999, p.name)

    return sorted(roots, key=key)


def read_class_type(path: Path) -> str | None:
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith(".class"):
                m = CLASS_RE.match(line.rstrip())
                if m:
                    return m.group(1)
                return None
    return None


def parse_method_line(line: str) -> Method | None:
    m = METHOD_RE.match(line.rstrip())
    if not m:
        return None
    from dexdesc import split_params  # local import: keeps dexdesc dependency-free

    flags = 0
    for word in m.group("rest").split():
        flags |= ACCESS_FLAGS.get(word, 0)
    return Method(
        name=m.group("name"),
        params=split_params(m.group("params")),
        return_type=m.group("ret"),
        access_flags=flags,
        defining_class="",  # filled by caller
        path=Path("."),
        has_body=flags & ACCESS_FLAGS["abstract"] == 0,
    )


def iter_methods(paths: list[Path]):
    """Yield (Method, path) for every method in the given smali files."""
    for path in paths:
        ctype = read_class_type(path) or ""
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if not line.startswith(".method"):
                    continue
                parsed = parse_method_line(line)
                if parsed is None:
                    continue
                parsed.defining_class = ctype
                parsed.path = path
                # `.method` with a body always has an `.end method`; abstract/interface
                # declarations do not.
                yield parsed


# --------------------------------------------------------------------------------------
# APK / zip helpers
# --------------------------------------------------------------------------------------


def zip_entries(apk: Path) -> list[str]:
    import zipfile

    with zipfile.ZipFile(apk) as z:
        return z.namelist()


def zip_read(apk: Path, member: str) -> bytes:
    import zipfile

    with zipfile.ZipFile(apk) as z:
        return z.read(member)


def rebundle_apk(src: Path, dest: Path, replacements: dict[str, bytes]) -> list[str]:
    """Copy a zip/APK, substituting entries in `replacements`, and return the names changed.

    Byte-editing an APK entry in place is not an option even when the entry is STORED:
    the CRC-32 recorded in the central directory stops matching and the installer rejects
    the whole file with `BadZipFile: Bad CRC-32`. Rewriting the archive is the only safe
    edit, and the rewrite must preserve two things or the resulting APK misbehaves:

      * entry order - the installer and the loader assume `classes*.dex` / resources.arc
        come before the payload they point at;
      * per-entry compression - a native `.so` that was STORED must stay STORED (the
        loader mmaps it and zipalign page-aligns it; deflating it silently changes both).

    Returns the list of entry names that were actually replaced, so a caller can assert
    that a graft landed instead of trusting it.
    """
    import zipfile

    changed: list[str] = []
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(
        dest, "w", zipfile.ZIP_DEFLATED, allowZip64=True
    ) as zout:
        for info in zin.infolist():
            data = b"" if info.is_dir() else zin.read(info.filename)
            if info.filename in replacements:
                data = replacements[info.filename]
                changed.append(info.filename)
            newinfo = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            newinfo.compress_type = info.compress_type
            newinfo.external_attr = info.external_attr
            newinfo.internal_attr = info.internal_attr
            newinfo.create_system = info.create_system
            newinfo.comment = info.comment
            zout.writestr(newinfo, data, compress_type=info.compress_type)
    return changed


def zip_entry_blob(apk: Path) -> dict[str, bytes]:
    """Every file entry of an APK as {name: bytes}, for entry-level diffing."""
    import zipfile

    with zipfile.ZipFile(apk) as z:
        return {i.filename: z.read(i.filename) for i in z.infolist() if not i.is_dir()}



def dex_entries(apk: Path) -> list[str]:
    return [n for n in zip_entries(apk) if re.fullmatch(r"classes\d*\.dex", n)]


def dex_blob(apk: Path) -> bytes:
    import zipfile

    with zipfile.ZipFile(apk) as z:
        members = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        return b"".join(z.read(n) for n in sorted(members))


# --------------------------------------------------------------------------------------
# Misc
# --------------------------------------------------------------------------------------


def rel(path: Path, base: Path | None = None) -> str:
    base = base or REPO_ROOT
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def aot_magic_offset(data: bytes) -> int | None:
    """Offset of the Flutter AOT snapshot magic (0xdcdcf5f5) in a libapp.so."""
    idx = data.find(bytes.fromhex("f5f5dcdc"))
    return idx if idx >= 0 else None


def human(n: int) -> str:
    return f"{n:,}"
