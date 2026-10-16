"""Device test harness - prove a patch changed the symptom, not just the bytes.

    # A/B: install the stock split set, run the pass, install the patched set, run it
    # again, and diff the two marker reports.
    python tools/device.py ab --plan nepalipatro \
        --stock apks/nepalipatro --patched apks/nepalipatro-patched

    # one pass against whatever is currently installed
    python tools/device.py pass --plan nepalipatro --label stock

    # just install an already-built set
    python tools/device.py install --dir apks/nepalipatro-patched --fresh

Why this exists: every static check in the repo (fingerprints, byte assertions, the bundle
check) proves a patch *applies*. None of them proves it *fixes* the symptom - that is the
gap that wasted two Nepali Patro cycles on a well-formed patch aimed at the wrong function.
The only instrument that closes it is a device, driven the same way twice, with the
observable difference recorded.

Three device facts are baked in because each one cost time to learn:

  * **`uiautomator dump` is useless on Flutter.** The whole surface is one
    `FlutterSurfaceView` with no accessibility nodes, so node-driven tapping finds nothing.
    The pass taps coordinates and scores the result by pixels + logcat markers.

  * **`adb exec-out screencap -p > file` corrupts the PNG.** When the redirect goes through
    PowerShell the stream is re-encoded as UTF-16. Shots are taken on the device and pulled,
    which works the same on every platform.

  * **Logcat markers come from the native libraries, not Dart.** Release AOT strips
    `print()`, so the markers that do appear (`555HTML`, `Slider impression send ...`,
    `POPUP_ADS`) are written by the Java/native side. A pass counts those substrings; a
    marker count of 0 with the app running is a real result, not a capture failure.

The scripted pass is data (`tools/testplans/<key>.json`), so a new app is a new plan file,
not new code.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    APKS_DIR,
    TOOLS_DIR,
    Adb,
    ToolError,
    apk_identity,
    fail,
    head,
    human,
    info,
    ok,
    warn,
)

PLANS_DIR = TOOLS_DIR / "testplans"


# --------------------------------------------------------------------------------------
# Plans
# --------------------------------------------------------------------------------------


def load_plan(plan: str | Path) -> dict:
    path = Path(plan)
    if not path.suffix:
        path = PLANS_DIR / f"{plan}.json"
    if not path.exists():
        raise ToolError(
            f"no test plan at {path}\n"
            f"available: {', '.join(sorted(p.stem for p in PLANS_DIR.glob('*.json'))) or '(none)'}"
        )
    data = json.loads(path.read_text(encoding="utf-8"))
    data.setdefault("name", path.stem)
    data.setdefault("launchWaitSeconds", 40)
    data.setdefault("settleSeconds", 3)
    data.setdefault("markers", [])
    data.setdefault("slots", [])
    data.setdefault("steps", [])
    scroll = data.setdefault("scroll", {})
    scroll.setdefault("x", 540)
    scroll.setdefault("yFrom", 1800)
    scroll.setdefault("yTo", 600)
    scroll.setdefault("durationMs", 280)
    scroll.setdefault("settleMs", 900)
    return data


# --------------------------------------------------------------------------------------
# Marker / slot analysis
# --------------------------------------------------------------------------------------


def count_markers(text: str, markers: list[str]) -> dict[str, int]:
    lines = text.splitlines()
    return {m: sum(1 for ln in lines if m in ln) for m in markers}


def extract_slots(text: str, specs: list[dict]) -> dict[str, list[str]]:
    """Unique values that follow a prefix on a logcat line, per spec.

    `prefix` is matched literally; everything after it on the line is the value. One
    marker line can repeat dozens of times with the same slot, so the result is the
    *set* of slots seen - which is the thing a regression changes.
    """
    out: dict[str, list[str]] = {}
    lines = text.splitlines()
    for spec in specs:
        prefix = spec["prefix"]
        seen = []
        for ln in lines:
            idx = ln.find(prefix)
            if idx < 0:
                continue
            value = ln[idx + len(prefix):].strip()
            if spec.get("splitFirst"):
                value = value.split()[0] if value.split() else value
            if value and value not in seen:
                seen.append(value)
        out[spec.get("label", prefix)] = seen
    return out


def analyse(log_path: Path, plan: dict) -> dict:
    text = log_path.read_text(encoding="utf-8", errors="replace")
    return {
        "markers": count_markers(text, plan["markers"]),
        "slots": extract_slots(text, plan["slots"]),
    }


def render_markers(label: str, report: dict) -> str:
    lines = [f"=== {label} marker counts ==="]
    lines += [f"{count:>6}  {marker}" for marker, count in report["markers"].items()]
    for slot_label, values in report.get("slots", {}).items():
        lines.append(f"=== {label} {slot_label} ({len(values)} unique) ===")
        lines += [f"  {v}" for v in values]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# The scripted pass
# --------------------------------------------------------------------------------------


def run_pass(adb: Adb, plan: dict, label: str, out_dir: Path, *, screenshots: bool = True) -> dict:
    package = plan["package"]
    out_dir.mkdir(parents=True, exist_ok=True)
    scroll = plan["scroll"]

    def scroll_down(times: int) -> None:
        for _ in range(times):
            adb.swipe(scroll["x"], scroll["yFrom"], scroll["x"], scroll["yTo"], scroll["durationMs"])
            time.sleep(scroll["settleMs"] / 1000)

    def scroll_up(times: int) -> None:
        for _ in range(times):
            adb.swipe(scroll["x"], scroll["yTo"], scroll["x"], scroll["yFrom"], scroll["durationMs"])
            time.sleep(scroll["settleMs"] / 1000)

    def grab(name: str) -> None:
        if screenshots:
            adb.screencap(out_dir / f"{name}.png")

    head(f"[{label}] scripted pass - {plan['name']}")
    adb.logcat_clear()
    adb.force_stop(package)
    time.sleep(2)
    ident = adb.package_info(package)
    info(f"installed: {ident.get('versionName', '?')} (code {ident.get('versionCode', '?')})")
    adb.launch(package)
    time.sleep(plan["launchWaitSeconds"])

    if not adb.is_running(package):
        warn("the app is not running after launch - the markers below will be empty by construction")

    steps_taken: list[str] = []
    grab("00-launch")
    adb.logcat_dump(out_dir / "t00-launch.txt")
    if plan.get("prewarmScrolls"):
        scroll_down(plan["prewarmScrolls"])
        time.sleep(plan["settleSeconds"])
        grab("00b-launch-scrolled")
        adb.logcat_dump(out_dir / "t00b-launch-scrolled.txt")
        scroll_up(plan["prewarmScrolls"])
        time.sleep(1)

    for index, step in enumerate(plan["steps"], start=1):
        tag = f"{index:02d}-{step['name']}"
        if step.get("tap"):
            x, y = step["tap"]
            adb.tap(x, y)
        time.sleep(step.get("waitSeconds", 12))
        grab(tag)
        scroll_down(step.get("scrolls", 0))
        if step.get("scrolls"):
            time.sleep(plan["settleSeconds"])
            grab(f"{tag}-scrolled")
        adb.logcat_dump(out_dir / f"t{tag}.txt")
        steps_taken.append(tag)
        info(f"step {tag}" + (f" tap={step['tap']}" if step.get("tap") else ""))

    time.sleep(3)
    log = adb.logcat_dump(out_dir / "logcat.txt")
    report = analyse(log, plan)
    report.update(
        {
            "label": label,
            "plan": plan["name"],
            "package": package,
            "installed": ident,
            "steps": steps_taken,
            "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
    )
    (out_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (out_dir / "markers.txt").write_text(render_markers(label, report) + "\n", encoding="utf-8")
    print()
    print(render_markers(label, report))
    return report


# --------------------------------------------------------------------------------------
# Install
# --------------------------------------------------------------------------------------


def install_dir(adb: Adb, directory: Path, *, fresh: bool) -> dict:
    apks = [p for p in sorted(directory.glob("*.apk")) if ".aligned" not in p.stem]
    if not apks:
        raise ToolError(f"no APKs in {directory}")
    apks.sort(key=lambda p: p.name != "base.apk")  # base first
    identity = apk_identity(next(p for p in apks if p.name == "base.apk"))
    head(f"installing {identity['package']} {identity['versionName']} ({len(apks)} apk)")
    if fresh:
        adb.uninstall(identity["package"])
    result = adb.install_multiple(apks)
    text = (result.stdout + result.stderr).strip()
    print(text)
    if "Success" not in text:
        hint = ""
        if "UPDATE_INCOMPATIBLE" in text or "signatures" in text:
            hint = ("\n  the installed copy has a different signature - uninstall it once "
                    "(or pass --fresh)")
        raise ToolError(f"install failed (exit {result.returncode}){hint}")
    ok("installed")
    return identity


# --------------------------------------------------------------------------------------
# A/B
# --------------------------------------------------------------------------------------


def diff_reports(stock: dict, patched: dict) -> list[str]:
    lines = ["", "=== A/B marker diff (stock -> patched) ===",
             f"{'marker':<42} {'stock':>6} {'patched':>8} {'delta':>7}"]
    changed = 0
    for marker, before in stock["markers"].items():
        after = patched["markers"].get(marker, 0)
        delta = after - before
        flag = ""
        if delta:
            flag = "   <-- changed"
            changed += 1
        lines.append(f"{marker:<42} {before:>6} {after:>8} {delta:>+7}{flag}")
    lines.append("")
    for slot_label in stock.get("slots", {}):
        a = stock["slots"].get(slot_label, [])
        b = patched["slots"].get(slot_label, [])
        gone = [s for s in a if s not in b]
        new = [s for s in b if s not in a]
        lines.append(f"=== {slot_label}: {len(a)} -> {len(b)} unique")
        if gone:
            lines.append(f"    gone:  {', '.join(gone)}")
        if new:
            lines.append(f"    new:   {', '.join(new)}")
    lines.append("")
    lines.append(
        f"{changed} marker(s) changed. A delta of 0 everywhere means the patch did not "
        "change the observable behaviour on this pass - either it is not reaching the "
        "symptom, or this pass did not exercise it. Check the stock counts are non-zero: "
        "if stock is 0 too, the pass never triggered the code path and proves nothing."
    )
    return lines


def ab(args) -> int:
    plan = load_plan(args.plan)
    root = args.out or (APKS_DIR / f"{plan['name']}-verify")
    adb = Adb(args.serial)

    head("A/B - stock")
    install_dir(adb, args.stock, fresh=True)
    stock = run_pass(adb, plan, "stock", root / "stock", screenshots=args.screenshots)

    head("A/B - patched")
    install_dir(adb, args.patched, fresh=True)
    patched = run_pass(adb, plan, "patched", root / "patched", screenshots=args.screenshots)

    lines = diff_reports(stock, patched)
    (root / "ab-diff.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print()
    info(f"evidence: {root}")
    ok("A/B complete")
    return 0


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial")
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("pass", help="run the scripted pass once")
    p.add_argument("--plan", required=True)
    p.add_argument("--label", default="pass")
    p.add_argument("--out", type=Path)
    p.add_argument("--no-screenshots", dest="screenshots", action="store_false")

    i = sub.add_parser("install", help="install an APK or split set")
    i.add_argument("--dir", type=Path, required=True)
    i.add_argument("--fresh", action="store_true")

    a = sub.add_parser("ab", help="stock vs patched pass + diff")
    a.add_argument("--plan", required=True)
    a.add_argument("--stock", type=Path, required=True, help="directory with the stock splits")
    a.add_argument("--patched", type=Path, required=True, help="directory with the patched set")
    a.add_argument("--out", type=Path)
    a.add_argument("--no-screenshots", dest="screenshots", action="store_false")

    s = sub.add_parser("shot", help="one screenshot")
    s.add_argument("--out", type=Path, default=Path("shot.png"))

    m = sub.add_parser("markers", help="count markers in an existing logcat dump")
    m.add_argument("--plan", required=True)
    m.add_argument("--log", type=Path, required=True)

    args = ap.parse_args(argv[1:])

    if args.command == "markers":
        plan = load_plan(args.plan)
        report = analyse(args.log, plan)
        print(render_markers(args.log.name, report))
        return 0

    if args.command == "shot":
        adb = Adb(args.serial)
        adb.screencap(args.out)
        ok(f"{args.out}  ({human(args.out.stat().st_size)} bytes)")
        return 0

    if args.command == "install":
        adb = Adb(args.serial)
        install_dir(adb, args.dir, fresh=args.fresh)
        return 0

    if args.command == "pass":
        plan = load_plan(args.plan)
        root = args.out or (APKS_DIR / f"{plan['name']}-verify" / args.label)
        adb = Adb(args.serial)
        run_pass(adb, plan, args.label, root, screenshots=args.screenshots)
        ok(f"evidence: {root}")
        return 0

    if args.command == "ab":
        return ab(args)

    raise ToolError(f"unknown command {args.command!r}")


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ToolError as exc:
        fail(str(exc))
        sys.exit(2)
