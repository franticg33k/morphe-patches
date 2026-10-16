# Patching tooling

Helper scripts for the loop: **device -> analysed tree -> fingerprints -> build -> apply ->
install -> A/B -> verify**.

Stdlib-only Python, no Gradle wiring, no third-party packages. Runs on a fresh clone of this
repo *or* of upstream `MorpheApp/morphe-patches` (this directory is unused there, and the two
histories are unrelated, so there is nothing to merge around).

Requires `adb` and a JVM on `PATH`, plus apktool — either an `apktool` on `PATH` or a standalone
`apktool.jar`. Windows, Linux and macOS are all supported; see **Platform support** below.
`pipeline.py doctor` checks all of it at once and prints the fix for each failure.

## The loop

```bash
# 0. is this machine ready? (and fetch the pinned Morphe desktop CLI)
python tools/pipeline.py doctor --fix

# 1. pull + decompile + census (needs a device)
python tools/intake.py --package com.hamropatro

# 2. stage natives for blutter, write the handoff note
python tools/blutter_stage.py --package com.hamropatro
#    ... run blutter yourself, then:
python tools/blutter_stage.py --package com.hamropatro --check   # see layout again

# 3. do the reverse engineering (this is the part with no script)

# 4. write the patch, update tools/appdata/<key>.yml

# 5. gate before committing
python tools/verify.py --app <key> --no-build --no-clean

# 6. apply it for real: merge/patch/graft/align/sign into an installable split set
python tools/patch.py --package com.hamropatro

# 7. install it and prove it changed the symptom (stock vs patched, marker diff)
python tools/device.py ab --plan <key> --stock apks/<key> --patched apks/<key>-patched
```

Steps 5-7 in one command, plus the build:

```bash
python tools/pipeline.py loop --package com.hamropatro --install --ab
```

## Commands

| command | does |
|---|---|
| `pipeline.py doctor [--fix]` | environment readiness check, with the fix for each failure; `--fix` fetches the pinned desktop CLI |
| `pipeline.py loop --package P [--install] [--ab]` | build -> static gate -> apply -> install -> A/B, in order (`--dry-run` to just print the stages) |
| `intake.py --package P` | pull all splits, apktool the base, stage `libapp.so`/`libflutter.so`, locate the AOT magic, count methods, print an ad-SDK census |
| `intake.py --package P --census-only` | just the census; no device needed |
| `fingerprints.py --list` | list known apps and their fingerprint counts |
| `fingerprints.py --app K` | assert each fingerprint resolves to exactly one method |
| `fingerprints.py --app K --bundle x.mpp` | also check compiled literals in a built bundle |
| `desktop.py --setup` / `--which` | download + sha256-verify the pinned Morphe desktop CLI / print what will run |
| `desktop.py run <args...>` | run the desktop CLI directly (e.g. `run list-patches --patches x.mpp`) |
| `patch.py --package P` | merge splits, patch (merged + base), graft changed entries back, align, sign into an installable set |
| `patch.py --apk A` / `--splits D` | the same for a single APK / an arbitrary split directory |
| `patch.py --package P --list` | list the patches compatible with that APK, without patching |
| `device.py ab --plan K --stock D --patched D2` | install stock, run the scripted pass, install patched, run it again, diff the markers |
| `device.py pass --plan K --label L` | one scripted pass against whatever is installed |
| `device.py install --dir D [--fresh]` | install an APK or split set |
| `device.py shot` / `markers --plan K --log F` | one screenshot / count markers in an existing logcat dump |
| `verify.py --all` | parser tests, clean, `buildAndroid` (last), bundle/dex checks, all fingerprints |
| `verify.py --all --no-build` | same checks against the newest existing bundle |
| `blutter_stage.py --package P` | stage natives, write `BLUTTER.md`, summarise existing blutter output |
| `test_dexdesc.py`, `test_miniyaml.py`, `test_platform.py` | unit tests: the two hand-rolled parsers, and the per-platform resolvers |

App keys (`--app`) are the stems of `tools/appdata/*.yml`; `intake.py` and
`blutter_stage.py` take `--package` and resolve the key from there, so `--package
com.hamropatro` lands in `apks/hamropatro/` and lines up with the existing analysis dirs.

## Platform support

Windows, Linux and macOS. Nothing here is Windows-only, and `test_platform.py` — which the gate
runs — asserts the Linux and macOS answers directly, so those branches are tested on whatever host
runs the suite rather than being "supported" on the strength of a code reading.

Everything platform-specific is a **candidate list plus an override**, resolved in `common.py` and
printed by `doctor`:

| what | order tried | override |
|---|---|---|
| JVM | this platform's Android Studio JBR locations, then `java` on `PATH` | `MORPHE_JBR` |
| Android SDK | `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then the platform defaults | either env var |
| build-tools | newest **stable** directory under `<sdk>/build-tools` | — |
| apktool | `apktool` on `PATH`, then a standalone jar | `PATH`, or `~/Downloads/apktool.jar` |
| gradle | `gradlew.bat` on Windows, `./gradlew` elsewhere (`sh gradlew` if the exec bit is gone) | — |
| device | `--serial`, then `ANDROID_SERIAL`, then the only attached device | either |

Three things that are not obvious:

- **The JBR is a preference, not a requirement.** A distro JDK 17 or 21 builds this repo fine;
  only a JDK *newer* than 21 breaks the release build (its jlink rejects Android's
  core-for-system-modules transform). A missing JBR therefore warns and falls back to `java`.
- **Subprocess stdin is closed.** Windows tool wrappers are batch files and some (`apktool.bat`)
  end in `pause`, which blocks forever on an interactive console. Closing stdin turns any such
  prompt into an immediate no-op on every platform.
- **No shell.** Every command is an argv list handed to `subprocess`, so nothing depends on
  quoting rules, path separators, or a particular shell. The one wrapper that must be named
  differently per platform is gradle's, and `common.gradle_wrapper()` is the only place that
  knows.

## Adding an app

1. `python tools/intake.py --package <pkg>` — device must be connected.
2. Copy `apks/extracted/<key>/AndroidManifest.xml` details into a new
   `tools/appdata/<key>.yml` (see the existing ones for the shape).
3. Find the chokepoint. No script does this; see the checklist below.
4. Write the patch under `patches/src/main/kotlin/.../<app>/`.
5. Add the fingerprints to the yml, then `python tools/verify.py --app <key> --no-build --no-clean`.
6. Write `tools/testplans/<key>.json` — the package, launch wait, scroll geometry, tab taps, marker
   substrings and slot prefixes — so the A/B in step 7 drives the app the same way twice.
7. `python tools/patch.py --package <pkg>` then
   `python tools/device.py ab --plan <key> --stock apks/<key> --patched apks/<key>-patched`.

## What `verify.py` checks, and why each exists

| check | the failure it prevents |
|---|---|
| parser self-tests | `dexdesc`/`miniyaml` are hand-rolled. A silent mis-parse reads as `matches=0`, indistinguishable from a genuinely stale fingerprint. This is not hypothetical - a wrong Hamro Patro parser reported `matches=0` on a *correct* patch, and nearly caused working code to be "fixed". |
| `buildAndroid` last | `:patches:generatePatchesList` run *after* `:patches:buildAndroid` was observed to emit a dex-less bundle. The manager then rejects the source with `Patch bundle is missing dex entries` and shows `Patches: 0`. |
| bundle has dex | same failure, caught directly instead of on the device. |
| fingerprints resolve exactly once | a rotated obfuscated type. On Hamro Patro three parameter types went `Lyq7;/Lzq7;/Lar7;` (10.7.30) -> `Lp05;/Lq05;/Lr05;` (10.7.33) and the whole patch aborted. |
| compiled literals present | a literal can vanish during *compilation* while the structural check still passes. The Kotlin `MethodChannel$Result` descriptor is the known case: a bare `$` is a template expression, so it compiles into a different string and fails at runtime with `Failed to match the fingerprint`. |
| forbidden literals absent | a half-finished re-pin - the new names pinned but the old ones still compiled in. |
| source apps in `patches-list.json` | "added a patch but forgot to regenerate". Deliberately *not* a version comparison: CI bumps `gradle.properties` and regenerates together, so a version lag right after a release is expected, not a defect. |

## What none of this checks

**Whether a patch fixes anything.** Every check here proves a patch *applies*. Choosing the right
target is engineering, and only the device answers that. The two wasted release cycles on Nepali
Patro were both well-formed, byte-verified patches aimed at the wrong function - undetectable by
any of the above.

That gap now has an instrument: `device.py ab` installs the stock build, runs a scripted pass,
installs the patched build, runs the *identical* pass, and diffs the logcat markers - the observable
symptom, recorded the same way twice. The bare requirement for a useful A/B is that **stock is
non-zero**: a marker that is 0 on both builds means the pass never triggered the code path, and the
experiment proved nothing.

Three habits that catch the rest:

- **Enumerate callers repo-wide before patching a caller.** `grep` scoped to one file feels
  thorough and is not: it reported 1 caller where there were 24.
- **If the symptom persists, diff the installed binary against stock** to separate "never
  applied" from "applied, wrong target".
- **Read the fall-through target before retargeting a branch.** "It falls through to nothing" is a
  claim about a specific instruction sequence; go and read it.

## Reverse-engineering checklist

From `docs/writing-update-resilient-patches.md`, the short version:

- Anchor on strings and signatures, not on `definingClass` (R8 renames it).
- Never "first match wins" — assert exactly once, or first-match is a silent mispatch.
- Stub the callee; do not remove call sites.
- `accessFlags` in morphe is exact int equality, not a mask — pinning it is almost always wrong.
- Dart `print()` is stripped in release AOT, so logcat will not confirm a Dart path. Use
  observable state instead (e.g. a SharedPreferences timestamp advancing).
- Prefer a layer that stops the *request* over one that stops the *show*: no-opping `show()`
  leaves a mediator waiting on a callback that never arrives, and abstract `show()` methods
  (Pangle) cannot be patched at all.

## Layout

```
tools/
  common.py          shared helpers: paths, platform resolvers, adb, gradle (forces JBR), SDK/build-tools, smali, zip
  dexdesc.py         dex descriptor tokeniser          <- tested
  miniyaml.py        tiny YAML subset (no pyyaml dep) <- tested
  test_platform.py   JBR / SDK / apktool / gradlew per-platform branches <- tested
  fingerprints.py    the one harness; app data from appdata/*.yml
  intake.py          device -> analysed tree + ad census
  blutter_stage.py   stage natives, write BLUTTER.md
  desktop.py         locate/verify/download the pinned Morphe desktop CLI, and drive it
  patch.py           merge splits -> patch -> graft -> zipalign -> sign -> (install)
  device.py          scripted device pass + stock/patched A/B marker diff
  pipeline.py        doctor + the whole loop as one command
  verify.py          pre-commit gate
  appdata/<key>.yml  per-app fingerprints + notes
  testplans/<key>.json  per-app scripted device pass
  .morphe/           downloaded CLI + local signing key   (gitignored)
```

`apks/`, `docs/` and `.opencode` are gitignored, so pulled APKs, analysis output, verification
evidence and per-app docs stay local. `tools/.morphe/` is gitignored too: a 50 MB jar never
travelled with the repo anyway, which is why `desktop.py` fetches a *pinned* release by sha256
instead of asking you to find one.
