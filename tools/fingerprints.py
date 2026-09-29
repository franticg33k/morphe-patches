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
    return AppSpec(
        key=key,
        package=raw["package"],
        app_name=raw.get("appName", key),
        version=str(raw.get("version", "")),
        extracted=REPO_ROOT / raw["extracted"],
        fingerprints=fps,
        compiled_literals=literals,
        forbidden_literals=list(raw.get("forbiddenLiterals") or []),
    )


def available_apps() -> list[str]:
    return sorted(p.stem for p in APPDATA_DIR.glob("*.yml"))


# --------------------------------------------------------------------------------------
# Index
# --------------------------------------------------------------------------------------


class SmaliIndex:
    """All methods of an apktool tree, with const-strings scoped per METHOD BODY.

    Per-method scoping is what morphe does and it matters: a file-level approximation
    attributes every const-string in a file to every method in it, so a fingerprint can
    appear to match a method that does not contain the string at all. That produced
    nonsense when re-pinning Fricam's obfuscated billing class, where one file legitimately
    holds every `fricam_*` literal and five methods each "had" all of them.
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


def check_fingerprints(spec: AppSpec, index: SmaliIndex) -> bool:
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
    if scan:
        index = SmaliIndex(spec.extracted)
        index.scan()
        good = check_fingerprints(spec, index)
    else:
        good = True
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
