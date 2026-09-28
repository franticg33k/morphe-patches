# Patching tooling

Helper scripts for the loop: **device -> analysed tree -> fingerprints -> patch -> verify**.

Stdlib-only Python, no Gradle wiring, no third-party packages. Runs on a fresh clone of this
repo *or* of upstream `MorpheApp/morphe-patches` (this directory is unused there, and the two
histories are unrelated, so there is nothing to merge around).

Requires `adb` and a JDK on `PATH`, plus `apktool.jar` at `%USERPROFILE%\Downloads`.
`verify.py` forces `JAVA_HOME` to Android Studio's JBR itself.

## The loop

```bash
# 1. pull + decompile + census (needs a device)
python tools/intake.py --package com.hamropatro

# 2. stage natives for blutter, write the handoff note
python tools/blutter_stage.py --package com.hamropatro
#    ... run blutter yourself, then:
python tools/blutter_stage.py --package com.hamropatro --check   # see layout again

# 3. do the reverse engineering (this is the part with no script)

# 4. write the patch, update tools/appdata/<key>.yml

# 5. gate before committing
python tools/verify.py --all
```

## Commands

| command | does |
|---|---|
| `intake.py --package P` | pull all splits, apktool the base, stage `libapp.so`/`libflutter.so`, locate the AOT magic, count methods, print an ad-SDK census |
| `intake.py --package P --census-only` | just the census; no device needed |
| `fingerprints.py --list` | list known apps and their fingerprint counts |
| `fingerprints.py --app K` | assert each fingerprint resolves to exactly one method |
| `fingerprints.py --app K --bundle x.mpp` | also check compiled literals in a built bundle |
| `verify.py --all` | parser tests, clean, `buildAndroid` (last), bundle/dex checks, all fingerprints |
| `verify.py --all --no-build` | same checks against the newest existing bundle |
| `blutter_stage.py --package P` | stage natives, write `BLUTTER.md`, summarise existing blutter output |
| `test_dexdesc.py`, `test_miniyaml.py` | unit tests for the two hand-rolled parsers |

App keys (`--app`) are the stems of `tools/appdata/*.yml`; `intake.py` and
`blutter_stage.py` take `--package` and resolve the key from there, so `--package
com.hamropatro` lands in `apks/hamropatro/` and lines up with the existing analysis dirs.

## Adding an app

1. `python tools/intake.py --package <pkg>` — device must be connected.
2. Copy `apks/extracted/<key>/AndroidManifest.xml` details into a new
   `tools/appdata/<key>.yml` (see the existing two for the shape).
3. Find the chokepoint. No script does this; see the checklist below.
4. Write the patch under `patches/src/main/kotlin/.../<app>/`.
5. Add the fingerprints to the yml, then `python tools/verify.py --all`.

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

Two habits that would have caught them:

- **Enumerate callers repo-wide before patching a caller.** `grep` scoped to one file feels
  thorough and is not: it reported 1 caller where there were 24.
- **If the symptom persists, diff the installed binary against stock** to separate "never
  applied" from "applied, wrong target".

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
  common.py          shared helpers: paths, adb, gradle (forces JBR), smali, zip
  dexdesc.py         dex descriptor tokeniser          <- tested
  miniyaml.py        tiny YAML subset (no pyyaml dep) <- tested
  fingerprints.py    the one harness; app data from appdata/*.yml
  intake.py          device -> analysed tree + ad census
  blutter_stage.py   stage natives, write BLUTTER.md
  verify.py          pre-commit gate
  appdata/<key>.yml  per-app fingerprints + notes
```

`apks/`, `docs/` and `.opencode` are gitignored, so pulled APKs, analysis output and per-app
docs stay local.
