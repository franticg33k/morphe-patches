"""One fingerprint harness for every app in this repo.

Replicates morphe's matcher semantics over apktool smali and asserts each fingerprint
resolves to exactly one method. Replaces the two per-app harnesses that existed before
(nepalipatro/fp_harness.py, hamropatro-analysis/hamro_fp_harness.py), which were the same
tool with app data pasted in - and one of which shipped a descriptor parser that reported
`matches=0` on a correct patch.

    python tools/fingerprints.py --list
    python tools/fingerprints.py --app nepalipatro
    python tools/fingerprints.py --app hamropatro --bundle patches/build/libs/x.mpp
    python tools/fingerprints.py --all

Exit code 0 if every check passes, 1 otherwise.

Matcher semantics (from app/morphe/patcher/Fingerprint.matchOrNull in the decompiled
manager APK, patcher 1.14.1 - the version the manager actually runs):
  * strings     AND over substring containment against the method's const-strings, and
                supplying it forces a non-empty body
  * name        exact string equality
  * returnType  exact string equality
  * accessFlags EXACT int equality, not a mask (so never pinned in this repo)
  * parameters  omitted means unconstrained; otherwise exact ordered list
  * requireBody mirrors `custom { method.implementation != null }`

What this CANNOT check: that a patch fixes anything. It proves a fingerprint resolves.
Whether the resolved method is the right one is engineering, and only the device answers
that - see docs/hamropatro-ads-analysis.md section 3.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import miniyaml
from common import (
    APPDATA_DIR,
    REPO_ROOT,
    Method,
    ToolError,
    dex_blob,
    dex_entries,
    fail,
    head,
    human,
    info,
    ok,
    parse_method_line,
    read_class_type,
    smali_files,
    smali_roots,
    warn,
)

CONST_STRING_RE = re.compile(r'^\s*const-string(?:/jumbo)?\s+\S+,\s+"(?P<s>.*)"\s*$')


# --------------------------------------------------------------------------------------
# App data
# --------------------------------------------------------------------------------------


@dataclass
class Fingerprint:
    name: str
    method: str | None
    return_type: str | None
    parameters: list[str] | None
    strings: list[str] | None
    defining_class: str | None
    require_body: bool
    note: str = ""
    class_level: bool = False
    class_fingerprint: str | None = None

    def matches(self, m: Method, consts: set[str], scope: set[str] | None = None) -> bool:
        # An omitted `method` means unconstrained, exactly as morphe treats an omitted
        # `name`. It must NOT fall back to the fingerprint's own label: that would make a
        # missing field silently match nothing, which reads as a stale fingerprint rather
        # than as the data-entry mistake it is.
        if self.method is not None and m.name != self.method:
            return False
        if scope is not None and m.defining_class not in scope:
            return False
        if self.defining_class and m.defining_class != self.defining_class:
            return False
        if self.return_type and m.return_type != self.return_type:
            return False
        if self.parameters is not None and m.params != self.parameters:
            return False
        if self.require_body and not m.has_body:
            return False
        if self.strings:
            # Supplying strings also forces a non-empty body, per morphe.
            if not m.has_body:
                return False
            for needle in self.strings:
                if not any(needle in s for s in consts):
                    return False
        return True

    def describe(self) -> str:
        bits = [self.defining_class] if self.defining_class else []
        bits.append(self.method or "<any method>")
        params = ", ".join(self.parameters) if self.parameters is not None else "<any>"
        return f"{''.join(bits)}({params}) -> {self.return_type or '?'}"


@dataclass
class AppSpec:
    key: str
    package: str
    app_name: str
    version: str
    extracted: Path
    fingerprints: list[Fingerprint] = field(default_factory=list)
    compiled_literals: list[tuple[str, list[str]]] = field(default_factory=list)
    forbidden_literals: list[str] = field(default_factory=list)
    pinned_versions: list[str] | None = None
    pin_mismatch_expected: bool = False
    method_shapes: list[dict] = field(default_factory=list)


# packageName -> the patch directory that owns it, for the pin-consistency check.
PIN_SOURCE = REPO_ROOT / "patches" / "src" / "main" / "kotlin" / "app" / "franticg33k" / "patches"
PACKAGE_RE = re.compile(r'packageName\s*=\s*"([^"]+)"')
APP_TARGET_RE = re.compile(r'version\s*=\s*"([^"]+)"')


def declared_targets(package: str) -> list[str] | None:
    """Versions listed in the app's own Constants.kt, or None if it is package-name-only.

    Guards the mistake made on Fricam 1.6.5: the fingerprints were re-pinned and the Edge bug
    fixed for 1.6.5 while `AppTarget` still said 1.4.0.1/1.3.7, so the manager would not have
    offered the patch on the version it had just been fixed for. Nothing checked that.
    """
    for constants in PIN_SOURCE.rglob("Constants.kt"):
        text = constants.read_text(encoding="utf-8", errors="replace")
        for m in PACKAGE_RE.finditer(text):
            if m.group(1) != package:
                continue
            targets = APP_TARGET_RE.findall(text)
            # No AppTarget entries means package-name-only, which is a valid and deliberate
            # configuration - not an empty list that should be treated as a mismatch.
            return targets or None
    return None


def check_pin(spec: AppSpec) -> bool:
    """The verified version must be one the app actually offers itself."""
    head(f"{spec.app_name} - compatibility pin")
    targets = declared_targets(spec.package)
    if targets is None:
        info(f"{spec.package}: no AppTarget list (package-name-only) - nothing to cross-check")
        return True
    if not spec.pinned_versions:
        # The yml does not state what it expects; fall back to the single-target assumption.
        pinned = [spec.version]
        if len(targets) == 1 and targets[0] == spec.version:
            ok(f"pin matches the verified version ({spec.version})")
            return True
        fail(f"{spec.package}: verified {spec.version} but Constants.kt lists {targets}")
        info("add `pinnedVersions:` to the appdata yml to make this check explicit")
        return False
    if spec.version not in spec.pinned_versions:
        if spec.pin_mismatch_expected:
            warn(f"{spec.package}: verified {spec.version}, pinned {spec.pinned_versions} - "
                 "DECLARED via pinMismatchExpected, so the patch will not be offered on the "
                 "verified build")
            return True
        fail(f"{spec.package}: verified {spec.version}, "
             f"but the yml pins {spec.pinned_versions} - which is what Constants.kt says")
        info("either the pin is stale (the patch will not be offered) or the yml is")
        return False
    if spec.version not in targets:
        fail(f"{spec.package}: yml pins {spec.pinned_versions} "
             f"but Constants.kt lists {targets} - they disagree")
        return False
    ok(f"pin agrees: verified {spec.version} is offered ({', '.join(targets)})")
    return True


def load_spec(key: str) -> AppSpec:
    path = APPDATA_DIR / f"{key}.yml"
    if not path.exists():
        raise ToolError(f"no app data for {key!r} (expected {path})")
    raw = miniyaml.load(path)
    fps = []
    for entry in raw.get("fingerprints") or []:
        fps.append(
            Fingerprint(
                name=entry["name"],
                method=entry.get("method"),  # None == unconstrained, per morphe
                return_type=entry.get("returnType"),
                parameters=entry.get("parameters"),
                strings=entry.get("strings"),
                defining_class=entry.get("definingClass"),
                require_body=bool(entry.get("requireBody")),
                note=(entry.get("note") or "").strip(),
                class_level=bool(entry.get("classLevel")),
                class_fingerprint=entry.get("classFingerprint"),
            )
        )
    literals = [
        (e["fingerprint"], e.get("literals") or [])
        for e in (raw.get("compiledLiterals") or [])
    ]
    spec = AppSpec(
        key=key,
        package=raw["package"],
        app_name=raw.get("appName", key),
        version=str(raw.get("version", "")),
        extracted=REPO_ROOT / raw["extracted"],
        fingerprints=fps,
        compiled_literals=literals,
        forbidden_literals=list(raw.get("forbiddenLiterals") or []),
        pinned_versions=raw.get("pinnedVersions"),
        pin_mismatch_expected=bool(raw.get("pinMismatchExpected")),
        method_shapes=list(raw.get("methodShape") or []),
    )
    return spec


def available_apps() -> list[str]:
    return sorted(p.stem for p in APPDATA_DIR.glob("*.yml"))


# --------------------------------------------------------------------------------------
# Index
# --------------------------------------------------------------------------------------


BOOLEAN_VALUE_OF = re.compile(
    r"^invoke-static(?:/range)?\s*\{(?P<regs>[^}]*)\},\s*Ljava/lang/Boolean;->valueOf"
)


def _record_boxing(m: Method, line: str) -> None:
    """Record the register(s) each Boolean.valueOf call consumes, in order."""
    hit = BOOLEAN_VALUE_OF.match(line.strip())
    if not hit:
        return
    regs = [r.strip() for r in hit.group("regs").split(",") if r.strip()]
    m.shape.append(regs[0] if len(regs) == 1 else "{" + ",".join(regs) + "}")


class MethodScanner:
    """Index an apktool tree: methods, per-method const-strings, and boxing-call shapes.

    Two scopes matter and getting them wrong is how re-pinning goes wrong:

    * const-strings are scoped to the METHOD BODY, like morphe. A file-level approximation
      attributes every literal in a file to every method in it, so while re-pinning Fricam a
      method appeared to hold fricam_pro, fricam_edge and pro_unlocked at once - they share
      one obfuscated class.
    * labels are NOT instructions, so a smali index and a patcher instruction index differ.
      Fricam's two Boolean.valueOf calls are smali 24/37 but patcher 20/32.
    """

    def __init__(self, extracted: Path) -> None:
        self.extracted = extracted
        self.roots = smali_roots(extracted)
        self.files = smali_files(extracted)
        self.consts_by_key: dict[tuple, set[str]] = {}
        self.classes: dict[str, set[str]] = {}
        self.methods: list[Method] = []
        self._build()

    def _build(self) -> None:
        for path in self.files:
            file_consts: set[str] = set()
            ctype = read_class_type(path) or ""
            current: Method | None = None
            with path.open("r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if line.startswith(".method"):
                        current = parse_method_line(line)
                        if current is not None:
                            current.defining_class = ctype
                            current.path = path
                            self.methods.append(current)
                            self.consts_by_key.setdefault((id(current)), set())
                        continue
                    if line.startswith(".end method"):
                        current = None
                        continue
                    m = CONST_STRING_RE.match(line)
                    if m:
                        value = m.group("s")
                        file_consts.add(value)
                        if current is not None:
                            self.consts_by_key.setdefault(
                                (id(current)), set()
                            ).add(value)
                        continue
                    if current is not None:
                        _record_boxing(current, line)
            if file_consts and ctype:
                self.classes.setdefault(ctype, set()).update(file_consts)

    def consts_for(self, m: Method) -> set[str]:
        return self.consts_by_key.get((id(m)), set())

    def scan(self) -> None:
        info(f"indexed {human(len(self.methods))} methods across "
             f"{human(len(self.files))} smali files")


# --------------------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------------------


def check_fingerprints(spec: AppSpec, index: MethodScanner) -> bool:
    head(f"{spec.app_name} {spec.version} - {len(spec.fingerprints)} fingerprint(s)")
    info(f"tree: {spec.extracted}")
    all_ok = True

    # Class-level fingerprints resolve a CLASS rather than a method, and are then used to
    # scope the method fingerprints that carry `classFingerprint`. This is how the repo
    # pins an R8-obfuscated manager class by its stable SharedPreferences keys instead of
    # by a class name that rotates every build.
    scopes: dict[str, set[str]] = {}
    for fp in spec.fingerprints:
        if not fp.class_level:
            continue
        hits = [
            ctype
            for ctype, cconsts in index.classes.items()
            if _class_matches(fp, cconsts)
        ]
        if len(hits) == 1:
            ok(f"{fp.name:<40} class matches=1")
            info(hits[0])
            scopes[fp.name] = {hits[0]}
        else:
            all_ok = False
            fail(f"{fp.name:<40} class matches={len(hits)}")
            for h in hits[:5]:
                info(h)
            scopes[fp.name] = set()
        print()

    for fp in spec.fingerprints:
        if fp.class_level:
            continue
        scope = scopes.get(fp.class_fingerprint) if fp.class_fingerprint else None
        if fp.class_fingerprint and scope is not None and not scope:
            # Already reported above; do not double-report as a method miss.
            continue
        hits = [
            m
            for m in index.methods
            if fp.matches(m, index.consts_for(m), scope)
        ]
        if len(hits) == 1:
            ok(f"{fp.name:<40} matches=1")
            if scope is not None:
                info(f"scoped to {fp.class_fingerprint}")
            info(fp.describe())
            loc = hits[0].path
            try:
                info(str(loc.relative_to(spec.extracted)))
            except ValueError:
                info(str(loc))
        else:
            all_ok = False
            fail(f"{fp.name:<40} matches={len(hits)}")
            info(f"expected: {fp.describe()}")
            for h in hits[:5]:
                info(str(h.path))
            if not hits:
                warn("no match - if the parameters or method name are obfuscated they "
                     "rotate every release; re-pin and re-run")
        print()
    return all_ok


def _class_matches(fp: Fingerprint, consts: set[str]) -> bool:
    """Class-level match: only the strings anchor applies (morphe ignores name/params)."""
    if not fp.strings:
        return False
    return all(any(needle in s for s in consts) for needle in fp.strings)


def check_method_shapes(spec: AppSpec, index: MethodScanner) -> bool:
    """Assert the internal facts a patch body depends on that a Fingerprint cannot express.

    UnlockEdgePatch broke because it asserted a body shape that was wrong - a `Boolean.valueOf`
    count read off a partial dump. That class of assumption is invisible to fingerprint
    resolution, so it is declared here as data and checked directly.
    """
    if not spec.method_shapes:
        return True
    head(f"{spec.app_name} - patch body assumptions")
    by_name = {f.name: f for f in spec.fingerprints}
    all_ok = True
    for shape in spec.method_shapes:
        fp = by_name.get(shape.get("fingerprint", ""))
        if fp is None or fp.class_level:
            fail(f"{shape.get('fingerprint')}: not a method fingerprint")
            all_ok = False
            continue
        hits = [m for m in index.methods if fp.matches(m, index.consts_for(m))]
        if len(hits) != 1:
            fail(f"{fp.name}: cannot check body shape, resolves to {len(hits)} methods")
            all_ok = False
            continue
        method = hits[0]
        labels, want_count = method.shape, shape.get("booleanValueOfCount")
        if want_count is not None and len(labels) != int(want_count):
            all_ok = False
            fail(f"{fp.name}: {len(labels)} Boolean.valueOf call(s), patch expects {want_count}")
            info("a patch that hard-codes an instruction index will target the wrong call; " +
                 "re-read the body and update BOTH the patch and this file")
            continue
        if want_count is not None:
            ok(f"{fp.name}: {len(labels)} Boolean.valueOf call(s), as the patch expects")
        want_reg = shape.get("firstConsumesRegister")
        if want_reg:
            got = labels[0] if labels else None
            if got != want_reg:
                all_ok = False
                fail(f"{fp.name}: first boxing consumes {got!r}, patch forces {want_reg!r}")
                info("forcing a dead or wrong register is a silent no-op")
            else:
                ok(f"{fp.name}: first boxing consumes {got}, as the patch forces")
    return all_ok


def check_bundle(spec: AppSpec, bundle: Path) -> bool:
    head(f"compiled bundle - {bundle.name}")
    entries = dex_entries(bundle)
    if not entries:
        fail("bundle has no dex entries - the manager will reject this source with "
             "'Patch bundle is missing dex entries' and show Patches: 0")
        return False
    ok(f"{len(entries)} dex entry/entries present")
    blob = dex_blob(bundle)
    all_ok = True

    for fp_name, literals in spec.compiled_literals:
        missing = [lit for lit in literals if lit.encode("utf-8") not in blob]
        label = f"{fp_name} literals"
        if missing:
            all_ok = False
            fail(f"{label}: missing {missing}")
            info("a literal can vanish in compilation even when the fingerprint still "
                 "matches structurally - check for Kotlin string templating damage")
        else:
            ok(f"{label}: all {len(literals)} present")

    if spec.forbidden_literals:
        present = [lit for lit in spec.forbidden_literals if lit.encode("utf-8") in blob]
        if present:
            all_ok = False
            fail(f"stale literals still compiled in: {present}")
            info("these were the previous release's obfuscated names - a re-pin is only "
                 "complete when they are gone")
        else:
            ok(f"no stale literals ({', '.join(spec.forbidden_literals)})")
    return all_ok


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def run_app(key: str, bundle: Path | None, scan: bool) -> bool:
    spec = load_spec(key)
    if not spec.extracted.exists():
        raise ToolError(
            f"extracted tree missing: {spec.extracted}\n"
            f"  run:  python tools/intake.py --package {spec.package}"
        )
    good = check_pin(spec)
    print()
    if scan:
        index = MethodScanner(spec.extracted)
        index.scan()
        good = check_fingerprints(spec, index) and good
        good = check_method_shapes(spec, index) and good
    # NB: do not reassign `good` here. An earlier `good = True` in this branch silently
    # discarded the pin-check result, so a deliberate pin mismatch still reported success.
    if bundle is not None:
        good = check_bundle(spec, bundle) and good
    return good


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--app", help="app key (see --list)")
    ap.add_argument("--all", action="store_true", help="check every app in appdata/")
    ap.add_argument("--bundle", type=Path, help="also check compiled literals in this .mpp")
    ap.add_argument("--list", action="store_true", help="list known apps")
    ap.add_argument("--no-scan", action="store_true", help="skip the smali scan")
    args = ap.parse_args(argv[1:])

    if args.list or not (args.app or args.all):
        print("known apps:")
        for key in available_apps():
            try:
                spec = load_spec(key)
                print(f"  {key:<16} {spec.app_name:<18} {spec.version:<10} "
                      f"{len(spec.fingerprints)} fingerprint(s)  [{spec.package}]")
            except ToolError as exc:
                print(f"  {key:<16} <unreadable: {exc}>")
        if not (args.app or args.all):
            print("\nrun with --app <key> or --all")
            return 0

    keys = available_apps() if args.all else [args.app]
    results: dict[str, bool] = {}
    for key in keys:
        results[key] = run_app(key, args.bundle, not args.no_scan)
        print()

    head("summary")
    bad = [k for k, v in results.items() if not v]
    for key, good in results.items():
        (ok if good else fail)(key)
    if bad:
        fail(f"{len(bad)} app(s) failed: {', '.join(bad)}")
        return 1
    ok("all checks passed")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)

