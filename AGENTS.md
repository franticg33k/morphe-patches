# AGENTS.md

Instructions for AI agents (and humans in a hurry) working in this repository.

This repo is **franticg33k's morphe-patches**: a set of [Morphe](https://morphe.software) patches
for the Android apps the author actually uses. A "patch" here is a Kotlin program that rewrites a
target APK — dex, resources, or the Flutter AOT snapshot inside `libapp.so`.

Read this file first. It is the contract. The detailed playbook is
[`.agents/playbook.md`](.agents/playbook.md); the tool reference is
[`tools/README.md`](tools/README.md).

---

## 1. The one thing that matters most

> **A patch that applies is not a patch that works.**

Three separate Nepali Patro attempts were correctly-formed, byte-verified, fingerprint-clean,
bundle-clean, and shipped as releases — and fixed nothing, because they targeted the wrong
function. Nothing in the static toolchain could see it. Only a device, or reading the caller of the
address a blutter label points at, revealed it.

So: **never report a patch as working without a device observation, and always label what you
actually verified.** Use exactly these words:

| label | means |
|---|---|
| **static** | the source compiles, fingerprints resolve, player bytes/literals are present |
| **packaging** | the real patcher applied it; the entry reads back changed in the output APK |
| **runtime** | observed on a device: an A/B pass with markers, screenshots, or prefs state |

"Verified" alone is banned. "Verified statically", "verified through the real patcher", "verified
on device (A/B, markers X and Y dropped from N to 0)" are the required forms.

A **failed attempt is a finding, not a setback.** If a patch applied correctly and the symptom did
not change, the target is wrong — diff the installed binary to prove the patch *did* apply, then
re-aim. Do not "fix" a patch that was never the problem.

---

## 2. Run the loop

```bash
python tools/pipeline.py doctor          # is this machine ready? (fix each line it prints)
python tools/pipeline.py doctor --fix    # ...and fetch the pinned Morphe desktop CLI

# the whole thing: build -> static gate -> apply -> install -> A/B
python tools/pipeline.py loop --package np.com.nepalipatro --install --ab
```

Each stage is also usable on its own — prefer the single stage when debugging, so a failure is
reproducible with one copy-pasted command:

```bash
python tools/intake.py --package com.hamropatro          # device -> splits -> apktool tree
python tools/blutter_stage.py --package com.hamropatro   # stage natives for blutter
python tools/fingerprints.py --app hamropatro            # does each fingerprint resolve 1x?
python tools/desktop.py --which                          # which patcher will run
python tools/patch.py --package com.hamropatro           # merge/patch/graft/align/sign
python tools/device.py ab --plan nepalipatro \
    --stock apks/nepalipatro --patched apks/nepalipatro-patched
python tools/verify.py --app nepalipatro --no-build --no-clean   # the pre-commit gate
```

Build order is load-bearing: `:patches:generatePatchesList` **before** `:patches:buildAndroid`.
Reversed, it emits a dex-less bundle that the manager rejects with *"Patch bundle is missing dex
entries"* and shows `Patches: 0`.

**Windows, Linux and macOS all work.** Each platform-specific choice is a candidate list plus an
override, resolved in `tools/common.py` and printed by `doctor`: the JBR (then `java` on `PATH`),
the SDK (`ANDROID_HOME` / `ANDROID_SDK_ROOT`), build-tools (newest **stable**, never an `-rc`),
apktool (`PATH`, else a jar), and `gradlew.bat` vs `./gradlew`. Overrides: `MORPHE_JBR`,
`ANDROID_HOME`. Prefer the JBR when Android Studio is installed — the *release* build breaks on a
JDK newer than 21 — but a distro JDK 17/21 is fine, so a missing JBR warns and falls back rather
than refusing to run. `tools/test_platform.py` is part of the gate and asserts the Linux and macOS
branches from any host; do not claim Linux support without it passing.

---

## 3. Hard rules

These are not style preferences. Each one exists because breaking it cost a release.

**Writing a patch**

1. **Assert before you write.** Every byte edit asserts the expected bytes at the offset and fails
   loudly with the observed bytes on mismatch. A stale offset must throw, never rewrite an
   unrelated instruction.
2. **Exactly-once, never first-match.** A 7-byte sub-signature matched 778 places; the full
   16-byte signature matched 1. Count the matches and require exactly one.
3. **Length-preserving edits only** for native code. `b.ne #imm` → `b #imm` is 4 bytes in, 4 out.
   There is no re-layout pass.
4. **Retarget, do not NOP.** A conditional's fall-through usually rejoins the path you meant to
   skip, so `nop` looks applied and does nothing. Branch to the block you actually want.
5. **Reuse the app's own early return.** Routing to a `return` pair the app already takes for
   "nothing to show" keeps frame teardown correct and needs no new convention.
6. **Patch the callee, not the callers.** Enumerate callers **repo-wide** first. Nepali Patro
   routes 24 interstitial sites through one callee; patching one caller is a guess about which
   trigger the user hits. A caller search scoped to one file is not a caller search.
7. **Patch the single writer, not the many consumers of a flag.**
8. **Minimal edit surface.** One length-preserving replacement beats a logic rewrite: fewer
   offsets disturbed, smaller blast radius when the app updates.
9. **Idempotency.** Recognise your own output and report "already patched", not "not found".
10. **Fail with the environment and a recovery step** ("append this prologue to
    `KNOWN_SIGNATURES`"), never a bare "signature not found".

**Fingerprints (dex)**

11. **Pin the minimum.** Every extra constraint (`definingClass`, `parameters`, `returnType`) is
    another thing that drifts. Anchor on the most stable *unique* subset.
12. **Strings beat names.** `strings = listOf("is_premium", "rawcam_prefs")` survives a class
    rename; `CameraViewModel.setPremium` does not.
13. **Never pin** immediate operands, string-table indices, file offsets, absolute addresses, or
    bundle sizes. They change on *every rebuild*, not just on refactors.
14. **No `versions = [...]` in `compatibleWith`.** Version pins stop patches being offered for the
    builds they would still work on. Key compatibility on the package name and let the locators
    decide per bundle.
15. **Neutralise the callee body**, not the call sites.

**Native (`libapp.so`)**

16. Blutter's per-function `addr` **is** the `libapp.so` file offset — confirm once per snapshot
    against a known function prologue before trusting any offset.
17. Function bodies live in `asm/<pkg>/<file>.dart`, one `// ** addr:` header each. `objs.txt` does
    **not** contain Dart function names; grepping it for a Dart function looks like the function
    does not exist.
18. Every native offset is pinned to a snapshot. An app update must **fail the patch loudly**, then
    get a fresh blutter run. Say so in the analysis.

**Packaging**

19. A Play-delivered app is a **split set**. Merge the splits only to give the patcher a complete
    app; graft the changed entries back onto the splits that own them; re-align and re-sign
    **every** split with one key or `install-multiple` rejects the set. Never install the merged
    APK. `tools/patch.py` does this; do not hand-roll it.
20. An entry cannot be byte-edited in place — the zip CRC stops matching and the installer reports
    `BadZipFile: Bad CRC-32`. Rewrite the archive.

**Evidence**

21. **Never** capture a screenshot with `adb exec-out screencap -p > file` — when the redirect goes
    through PowerShell the stream is re-encoded to UTF-16 and the PNG is corrupt. Take it on the
    device and `adb pull` (every platform).
22. `uiautomator dump` finds **nothing** on Flutter: the surface is one `FlutterSurfaceView` with no
    accessibility nodes. Drive by coordinates and score by pixels + logcat markers.
23. Release AOT strips Dart `print()`. The usable markers come from the native/Java side. A marker
    count of 0 with the app running is a **result**, not a capture failure — but check the stock
    build shows the same 0 before concluding anything (see §5).
24. Read `FlutterSharedPreferences.xml` for proof a code path ran (a timestamp advancing), and for
    diagnosis of server-driven flags. Do not inject state there; the server rewrites it on launch.

---

## 4. Where things live

| path | what | tracked? |
|---|---|---|
| `patches/` | Kotlin patch sources, one package per app (`Constants.kt`, `Fingerprints.kt`, patch files) | yes |
| `tools/` | the pipeline: `intake`, `blutter_stage`, `fingerprints`, `desktop`, `patch`, `device`, `pipeline`, `verify` | yes |
| `tools/appdata/<key>.yml` | the YAML mirror of each app's fingerprint set — the harness checks this | yes |
| `tools/testplans/<key>.json` | the scripted device pass (taps, scrolls, markers, slots) | yes |
| `README.md`, `patches-list.json` | **generated** — never hand-edit | yes |
| `apks/` | pulled splits, apktool trees, blutter output, verification evidence | **no** (gitignored) |
| `docs/` | the analysis write-ups (a separate repo, `security-audit-docs`) | **no** (gitignored) |
| `tools/.morphe/` | the downloaded patcher and the local signing key | **no** (gitignored) |

`README.md` and `patches-list.json` come from `.github/scripts/` and `:patches:generatePatchesList`.
Regenerate them; do not type into them.

---

## 5. Verification that actually counts

The gate (`tools/verify.py`) proves a patch is *well-formed*. It cannot prove it *fixes*. Close that
gap on a device:

1. Install the **stock** build, run the scripted pass, record the markers.
2. Install the **patched** build, run the identical pass.
3. Diff the marker counts and the unique slot lists — `python tools/device.py ab ...` does exactly
   this and writes `ab-diff.txt`.

**A delta of 0 everywhere proves nothing if stock is 0 too.** If the stock build never triggers the
code path, the pass is invalid — find a state where stock *does* show the symptom (for the ad work:
`IADS GG` lines reporting **empty** ad unit ids means the first-party slots are in use) and rerun.
Say so in the write-up when it cannot be reproduced; a documented "statically verified, not yet
exercised" is honest, an unlabelled "fixed" is not.

---

## 6. Before you commit

- [ ] `python tools/verify.py --app <key> --no-build --no-clean` is green
- [ ] a new/changed byte offset is byte-asserted, and the assertion text records the expected word
- [ ] a new fingerprint resolves to **exactly one** method, mirrored into `tools/appdata/<key>.yml`
- [ ] `tools/appdata/<key>.yml` `version:` and the `Constants.kt` `AppTarget` list agree
- [ ] the analysis doc in `docs/` records static vs packaging vs runtime, separately
- [ ] generated files (`README.md`, `patches-list.json`) regenerated, not hand-edited
- [ ] commit message says *what changed and why*, in conventional-commit form
- [ ] `docs/` and `apks/` changes are **not** in the commit (they are separate/gitignored)

---

## 7. When a patch stops applying

That is the normal case, not an incident. Treat a new app version as one-version-ahead of the last
verified target and **extend** compatibility rather than rewriting the patch.

1. `python tools/intake.py --package <pkg>` — pull and decompile the new version.
2. `python tools/fingerprints.py --app <key>` — see which fingerprint drifted to 0 matches.
3. Re-anchor it on the most stable unique subset (§3, 11–15).
4. For native edits, re-run `blutter_stage.py`, re-locate the function, and update the offset **and**
   its byte assertion together.
5. Add the new version to the app's `Constants.kt` target list and to `tools/appdata/<key>.yml`, so
   the harness checks both.
6. Re-run the gate, then the device A/B.
