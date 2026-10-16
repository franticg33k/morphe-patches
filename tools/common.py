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

# Android Studio's bundled JBR. Preferred over whatever `java` is on PATH because the
# system JDK 26 fails Android's core-for-system-modules transform, so the gradle steps need
# a known-good JVM explicitly.
#
# The candidate list is per-platform and evaluated at import; `MORPHE_JBR` overrides it
# outright. Keeping this a *list* rather than one constant is the whole cross-platform
# story for the toolchain: the same code has to find the JBR on a Windows install, a Linux
# tarball, and a macOS .app bundle.
JBR_ENV_VAR = "MORPHE_JBR"

_JBR_WINDOWS = [
    r"C:\Program Files\Android\Android Studio\jbr",
    r"C:\Program Files\Android\Android Studio Preview\jbr",
]
_JBR_LINUX = [
    "/opt/android-studio/jbr",
    "/usr/local/android-studio/jbr",
    "/usr/lib/android-studio/jbr",
    "/snap/android-studio/current/android-studio/jbr",
    "~/android-studio/jbr",
    "~/.local/share/JetBrains/Toolbox/apps/AndroidStudio/jbr",
]
_JBR_MACOS = [
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
    "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home",
    "~/Applications/Android Studio.app/Contents/jbr/Contents/Home",
]


def _expand(item: str, home: Path) -> Path:
    """A candidate path as a Path, expanding a leading `~` against `home`.

    Only a *leading* `~` is expanded, and only for the POSIX-style entries; a Windows path
    never starts with one.
    """
    return Path(item.replace("~", str(home), 1)) if item.startswith("~") else Path(item)


def platform_candidates(
    windows: list[str], linux: list[str], macos: list[str], *,
    windows_flag: bool | None = None,
    macos_flag: bool | None = None,
    home: Path | None = None,
) -> list[Path]:
    """Pick a platform's candidate paths, expanding `~`.

    The flags are injectable so all three branches are unit-testable from any one host - a
    resolver that can only be exercised on the platform it targets is a resolver nobody has
    tested on two thirds of the platforms it claims to support.
    """
    if windows_flag is None:
        windows_flag = os.name == "nt"
    if macos_flag is None:
        macos_flag = sys.platform == "darwin"
    if home is None:
        home = Path.home()
    if windows_flag:
        raw = windows
    elif macos_flag:
        raw = macos
    else:
        raw = linux
    return [_expand(item, home) for item in raw]


def exe(name: str, *, windows_flag: bool | None = None) -> str:
    """`name` plus the platform's executable suffix."""
    if windows_flag is None:
        windows_flag = os.name == "nt"
    return f"{name}.exe" if windows_flag else name


def _jbr_default() -> Path:
    """Where we *expect* the JBR, for error messages when no candidate exists."""
    cands = platform_candidates(_JBR_WINDOWS, _JBR_LINUX, _JBR_MACOS)
    return cands[0]


def resolve_jbr(*, windows_flag: bool | None = None, macos_flag: bool | None = None,
                home: Path | None = None) -> Path | None:
    """The first JBR that actually exists, or None."""
    override = os.environ.get(JBR_ENV_VAR)
    if override:
        p = Path(override)
        return p if p.is_dir() else None
    for cand in platform_candidates(_JBR_WINDOWS, _JBR_LINUX, _JBR_MACOS,
                                    windows_flag=windows_flag, macos_flag=macos_flag, home=home):
        if cand.is_dir():
            return cand
    return None


#: The JBR that will be used. A Path either way, so `.exists()` checks and messages work.
JBR: Path = resolve_jbr() or _jbr_default()

#: Where a standalone apktool jar is commonly dropped. A `apktool` on PATH wins over these.
#: Kept as `~`-strings rather than Paths: they have to resolve against the *caller's* home,
#: which is also what keeps the resolver testable from any host.
APKTOOL_JAR_CANDIDATES = [
    "~/Downloads/apktool.jar",
    "~/apktool.jar",
    "~/.local/share/apktool/apktool.jar",
    "/opt/apktool/apktool.jar",
    "/usr/local/share/apktool/apktool.jar",
    "/usr/share/apktool/apktool.jar",
]

#: The conventional location, kept for messages and for callers that predate the resolver.
APKTOOL_JAR = _expand(APKTOOL_JAR_CANDIDATES[0], Path.home())

#: Last-resort device serial. Left unset on purpose: `Adb` prefers an explicit --serial,
#: then $ANDROID_SERIAL, then the only attached device, so no machine-specific serial is
#: baked into the toolchain.
DEFAULT_SERIAL: str | None = None

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

    stdin is closed rather than inherited. Windows tool wrappers are shell scripts
    (`apktool.bat` ends in `pause`), and a wrapper that waits for a keypress on an
    interactive console hangs the pipeline with no output to explain why. Closing stdin
    makes any such prompt return immediately, on every platform.
    """
    printable = " ".join(str(a) for a in argv)
    proc = subprocess.run(
        [str(a) for a in argv],
        cwd=str(cwd) if cwd else None,
        capture_output=capture,
        stdin=subprocess.DEVNULL,
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


def adb_devices() -> list[str]:
    """Attached device serials - needs no device to be selected first.

    `Adb()` deliberately refuses to construct without a live device; `doctor` needs to
    *report* that state rather than raise on it, so the listing lives outside the class.
    """
    if not shutil.which("adb"):
        raise ToolError("adb not on PATH")
    out = run(["adb", "devices"], check=True).stdout
    found = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            found.append(parts[0])
    return found


class Adb:
    def __init__(self, serial: str | None = None) -> None:
        if not shutil.which("adb"):
            raise ToolError("adb not on PATH")
        devices = adb_devices()
        chosen = serial or os.environ.get("ANDROID_SERIAL") or DEFAULT_SERIAL
        if chosen is None:
            if len(devices) == 1:
                chosen = devices[0]
            elif not devices:
                raise ToolError("no device attached. Start an emulator, or plug one in.")
            else:
                raise ToolError(
                    "several devices attached and none selected:\n"
                    + "\n".join(f"  {d}" for d in devices)
                    + "\n  pass --serial <serial>, or set ANDROID_SERIAL."
                )
        self.serial = chosen
        if chosen not in devices:
            raise ToolError(
                f"no device {chosen!r} (attached: {', '.join(devices) or 'none'}).\n"
                "  pass --serial <serial>, or set ANDROID_SERIAL."
            )

    def argv(self, *args: str) -> list[str]:
        return ["adb", "-s", self.serial, *args]

    def devices(self) -> list[str]:
        return adb_devices()

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

    # -- input / capture --------------------------------------------------------------

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y), check=False)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self.shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms),
                   check=False)

    def keyevent(self, key: str) -> None:
        self.shell("input", "keyevent", key, check=False)

    def screencap(self, dest: Path) -> Path:
        """Screenshot to `dest`, taken on the device and pulled, never piped.

        `adb exec-out screencap -p > file` looks equivalent and is not: when the redirect
        goes through PowerShell the byte stream is re-encoded as UTF-16 and the PNG is
        corrupt on arrival. Taking the shot on the device sidesteps the shell entirely, and
        is also the only way that works identically on Windows, Linux and macOS.
        """
        remote = "/sdcard/_morphe_shot.png"
        self.shell("screencap", "-p", remote, check=False)
        return self.pull(remote, dest)

    def logcat_clear(self) -> None:
        self.shell("logcat", "-c", check=False)

    def logcat_dump(self, dest: Path) -> Path:
        out = self.shell("logcat", "-d", "-v", "time", check=False).stdout
        dest.parent.mkdir(parents=True, exist_ok=True)
        # logcat's own output is already text; write it as bytes so nothing re-encodes it.
        dest.write_bytes(out.encode("utf-8", errors="replace"))
        return dest



# --------------------------------------------------------------------------------------
# Gradle
# --------------------------------------------------------------------------------------


def gradle_version() -> str:
    props = REPO_ROOT / "gradle.properties"
    m = re.search(r"^\s*version\s*=\s*(.+)$", props.read_text(encoding="utf-8"), re.M)
    return m.group(1).strip() if m else "unknown"


def gradle_wrapper(*, windows_flag: bool | None = None) -> list[str]:
    """The command that runs this repo's gradle wrapper on this platform.

    `gradlew` is committed with the executable bit (mode 100755), so `./gradlew` works on a
    normal Linux/macOS checkout. The bit can still be lost - a zip download, a copied tree,
    some mount options - and the resulting EACCES is unhelpful, so fall back to
    `sh gradlew` instead of making the user diagnose it.
    """
    if windows_flag is None:
        windows_flag = os.name == "nt"
    if windows_flag:
        return [str(REPO_ROOT / "gradlew.bat")]
    wrapper = REPO_ROOT / "gradlew"
    if os.access(wrapper, os.X_OK):
        return [str(wrapper)]
    return ["sh", str(wrapper)]


def gradle(task: str, *, timeout: int = 1800) -> Run:
    """Run a gradle task with JAVA_HOME forced to Android Studio's JBR.

    Not optional on a machine where the system JDK is newer than 21: its jlink rejects
    Android's core-for-system-modules.jar transform, failing
    `:extensions:extension:compileReleaseJavaWithJavac`. The env var must be passed to the
    child process - merely having JBR defined in this module does nothing.

    On a Linux box without Android Studio this degrades to `java` on PATH rather than
    refusing to run: a distro JDK 17 or 21 is perfectly good here, and only the *newest*
    JDKs are the problem. The warning names the override for when it is not.
    """
    if not JBR.exists():
        warn(
            f"Android Studio JBR not found (looked in {JBR}); using `java` on PATH.\n"
            f"  if that is a JDK newer than 21 the release build breaks - set {JBR_ENV_VAR} "
            "to a JDK 17-21."
        )
    env = jbr_env()
    argv = [*gradle_wrapper(), task]
    proc = subprocess.run(
        argv,
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        stdin=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    result = Run(argv, proc.returncode, proc.stdout or "", proc.stderr or "")
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


#: Artefact names that live in patches/build/libs but are not patch bundles. The build
#: publishes `patches-<version>-sources.mpp` and `patches-<version>-javadoc.mpp` beside the
#: real bundle, so an unfiltered sort over the directory can hand one of those to the
#: patcher or to the gate, which then fails with a `no dex entries` that is not the
#: caller's fault. `util/PatchListGenerator.kt` filters exactly these two names; keep the
#: two in step.
_BUNDLE_ARTEFACT_SKIPS = ("sources", "javadoc")


def built_bundle() -> Path | None:
    """The newest real patch bundle in patches/build/libs, or None if there is not one.

    "Real" means a `.mpp` that is neither a sources nor a javadoc artefact. Ties are broken
    on the name, so which bundle wins never depends on filesystem listing order.
    """
    if not PATCHES_BUILD.exists():
        return None
    bundles = sorted(
        (
            p for p in PATCHES_BUILD.glob("*.mpp")
            if not any(skip in p.name for skip in _BUNDLE_ARTEFACT_SKIPS)
        ),
        key=lambda p: (p.stat().st_mtime, p.name),
        reverse=True,
    )
    return bundles[0] if bundles else None


# --------------------------------------------------------------------------------------
# Android SDK / build tools
# --------------------------------------------------------------------------------------


def jbr_env(base: dict | None = None) -> dict:
    """An environment with JAVA_HOME pointed at Android Studio's JBR, when there is one.

    Needed by anything that shells out to Android's build tools: `apksigner` is a wrapper
    around a jar and prefers JAVA_HOME when it is set, and gradle's jlink step needs a JDK
    the Android transform accepts. A host with a usable `java` already set up keeps its own
    JAVA_HOME - this only overrides when the JBR is actually present.
    """
    env = dict(base if base is not None else os.environ)
    if JBR.exists():
        env["JAVA_HOME"] = str(JBR)
        env["PATH"] = str(JBR / "bin") + os.pathsep + env.get("PATH", "")
    return env


def java_exe() -> str:
    """JBR's java, or whatever `java` is on PATH as a fallback."""
    jbr_java = JBR / "bin" / exe("java")
    if jbr_java.exists():
        return str(jbr_java)
    found = shutil.which("java")
    if not found:
        raise ToolError("no JVM found (neither Android Studio's JBR nor `java` on PATH)")
    return found


def resolve_apktool(*, home: Path | None = None, java: str | None = None) -> list[str]:
    """The argv prefix that runs apktool, whichever way it is installed.

    Three shapes occur in practice: an `apktool` wrapper/script on PATH (the Homebrew and
    distro-package case), a standalone `apktool.jar` run through java, and nothing at all.
    Returning an argv *prefix* rather than a path keeps the `java -jar` case and the direct
    case behind one call site.

    `home` and `java` are injectable so the branches are testable without an apktool
    installation and without a JVM on the test host.
    """
    on_path = shutil.which("apktool")
    if on_path:
        return [on_path]
    if home is None:
        home = Path.home()
    for cand in [_expand(c, home) for c in APKTOOL_JAR_CANDIDATES]:
        if cand.exists():
            return [java or java_exe(), "-jar", str(cand)]
    raise ToolError(
        "apktool not found. Either put `apktool` on PATH, or place apktool.jar at one of:\n"
        + "\n".join(f"  {c}" for c in APKTOOL_JAR_CANDIDATES)
    )


def sdk_candidates(*, home: Path | None = None, windows_flag: bool | None = None,
                   macos_flag: bool | None = None) -> list[Path]:
    """The SDK roots we search, most-likely first.

    The flags and `home` are injectable so each platform's list can be asserted from any
    host. `/usr/lib/android-sdk` and `/opt/android-sdk` cover the distro packages, which is
    the way the SDK exists on most Linux desktops.
    """
    if home is None:
        home = Path.home()
    if windows_flag is None:
        windows_flag = os.name == "nt"
    if macos_flag is None:
        macos_flag = sys.platform == "darwin"
    cands: list[Path] = []
    if windows_flag:
        cands.append(home / "AppData" / "Local" / "Android" / "Sdk")   # Windows default
    if macos_flag:
        cands.append(home / "Library" / "Android" / "sdk")             # macOS default
    cands += [
        home / "Android" / "Sdk",   # Linux default, and a sane fallback elsewhere
        home / "android-sdk",
        Path("/usr/lib/android-sdk"),                                  # distro package
        Path("/opt/android-sdk"),
    ]
    return cands


def sdk_root() -> Path:
    """The Android SDK, from the environment or the usual install locations."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(var)
        if val and Path(val).is_dir():
            return Path(val)
    for cand in sdk_candidates():
        if cand.is_dir():
            return cand
    raise ToolError(
        "Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT) to the SDK root.\n"
        "  looked in: ANDROID_HOME, ANDROID_SDK_ROOT, ~/AppData/Local/Android/Sdk,\n"
        "             ~/Library/Android/sdk, ~/Android/Sdk, /usr/lib/android-sdk"
    )


_VERSION_NUM_RE = re.compile(r"\d+")


def _build_tools_key(name: str) -> tuple:
    """Sort key for build-tools dir names, stable-first.

    A release candidate must never outrank an older *stable* just because its number is
    higher: `37.0.0-rc2` is not a better default than `36.1.0`, it is a different one that
    moves under you. So the stable flag is the primary key and the version is secondary,
    which picks the newest stable whenever one exists.
    """
    nums = [int(n) for n in _VERSION_NUM_RE.findall(name)]
    stable = 0 if "rc" in name or "beta" in name or "alpha" in name else 1
    return (stable, *nums)


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
    """Absolute path to a build-tools executable (`zipalign`, `apksigner`, ...).

    Tries every shape: `zipalign.exe` (Windows), `apksigner.bat` (older Windows build
    tools), and the bare name, which is what both exist as on Linux and macOS.
    """
    bt = build_tools()
    for cand in (bt / exe(name), bt / f"{name}.bat", bt / name):
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
