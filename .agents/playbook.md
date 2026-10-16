# The patch playbook

Detailed reference for the workflow in [`../AGENTS.md`](../AGENTS.md). Read that first; this is
the *why* and the *how*, with the failure catalogue.

---

## 0. The mental model

An APK is not one artifact, and a patch is not one technique. The apps here deliver their behaviour
through **layers**, and a patch has to name the layer it is closing:

| layer | lives in | reverse with | evidence of a real fix |
|---|---|---|---|
| Java / Kotlin | `classes*.dex` | apktool smali (jadx only when you need logic) | the modified smali reads back changed in the output dex |
| resources / manifest | `resources.arsc`, `AndroidManifest.xml` | apktool | the entry reads back changed |
| Flutter app logic | `libapp.so` AOT snapshot | **blutter** | the bytes at the offset changed, and the symptom went away |
| Web / JS (Hermes, RN) | `assets/index.android.bundle` | a locator or jadx | the bundle bytes changed |
| server config | prefs JSON, remote flags | read `FlutterSharedPreferences.xml` | the flag is not rewritten on launch |

Two consequences that shape everything:

- **The container and the content are separate layers.** Suppressing the ad *content* (a WebView
  stub, a host rewrite) is independent of suppressing the ad *container* (the full-screen route and
  its five-second countdown). Finishing one layer is not finishing the symptom. That mistake left a
  blank page for the full timer on Nepali Patro and shipped.
- **The layer you can fingerprint is rarely the layer that decides.** Nepali Patro's overlay was
  decided in Dart, in `libapp.so`, where there is no fingerprintable Java layer at all — only
  offsets that must be byte-asserted.

---

## 1. Stage by stage

### 0. Doctor

```bash
python tools/pipeline.py doctor
```

Almost every "the patch didn't work" that is not a wrong target is a missing or wrong-version tool.
`doctor` names which one and how to fix it. Do this before anything else on a new machine.

The **Morphe desktop CLI is pinned** (`tools/desktop.py`: version + sha256). Do not substitute a
hand-found jar — the CLI version changes the patch contract, and a stale jar applies nothing or, at
runtime, is ambiguous about which CLI ran. `python tools/desktop.py --which` prints the exact jar
that will be used and whether it matches the pin.

**Windows, Linux and macOS are all supported.** Every platform-specific choice is a candidate list
plus an override, resolved in `tools/common.py` and reported by `doctor`:

| what | order tried | override |
|---|---|---|
| JVM | Android Studio's JBR for this platform, then `java` on `PATH` | `MORPHE_JBR` |
| Android SDK | `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then the platform defaults | either env var |
| build-tools | newest **stable** dir under `<sdk>/build-tools` (never an `-rc`) | — |
| apktool | `apktool` on `PATH`, then a standalone jar | `PATH`, or `~/Downloads/apktool.jar` |
| gradle | `gradlew.bat` (Windows) / `./gradlew` (elsewhere; `sh gradlew` if the exec bit is gone) | — |
| device | `--serial`, then `ANDROID_SERIAL`, then the only attached device | either |

The JBR is a **preference, not a requirement**: a distro JDK 17 or 21 builds this repo fine, and
only a JDK *newer* than 21 breaks the jlink transform. A missing JBR therefore warns and falls back
to `java` instead of refusing to run. `tools/test_platform.py` (part of the gate) asserts the Linux
and macOS branches from any host, so they are not "supported" on the strength of a code reading
alone.

Subprocess stdin is deliberately closed. Windows tool wrappers are batch files and some
(`apktool.bat`) end in `pause`; on an interactive console that would hang the pipeline with no
output to explain why. Nothing is run through a *shell* either — the argv is built as a list — so
paths with spaces need no quoting games on any platform.

### 1. Acquire

```bash
python tools/intake.py --package np.com.nepalipatro
```

Reads `dumpsys package`, pulls **every split** via `pm path` (the on-device split filenames are
obfuscated and differ per install, so they cannot be guessed), runs apktool on the base, stages
`libapp.so` / `libflutter.so` out of the ABI split, locates the Flutter AOT magic, and prints an
ad-SDK census.

Output (`apks/<key>/`, `apks/extracted/<key>-analysis/`) is gitignored. jadx is deliberately not
run: for this repo's work apktool smali is the source of truth, and jadx adds a multi-minute
decompile of a 100 MB+ APK for little gain.

If the app is already installed on a device but you want the vendor's build, note that the device
copy may be *your* patched build. Pull from a clean install when the stock bytes matter.

### 2. Analysis

Decompile what matters, and write down what you found **as you find it** — the analysis doc is the
deliverable that outlives the session.

```bash
apktool d -f -r -o apks/extracted/<key> app.apk      # -r keeps the resource table raw
jadx --no-res -d apks/extracted/<key>-jadx app.apk   # only when you need Kotlin/Java logic
python tools/blutter_stage.py --package np.com.nepalipatro
#   ... run blutter yourself (it is an interactive Dart tool), then:
python tools/blutter_stage.py --package np.com.nepalipatro --check
```

For native work the blutter output is the map: function bodies in `asm/<pkg>/<file>.dart`, one
`// ** addr:` header per function; strings in `pp.txt`; `objs.txt` holds object metadata and **not**
Dart function names.

### 3. Locate

The single most expensive mistake in this repo is choosing the wrong target. Spend the time here.

**For a dex patch:** write the fingerprint, then run `python tools/fingerprints.py --app <key>` and
require `matches=1` for every fingerprint. `0` means it drifted; `>1` means it is not specific
enough. Mirror the fingerprint into `tools/appdata/<key>.yml` so the gate keeps checking it.

**For a `libapp.so` patch:** find the function in the blutter dump, then decide *which* function.

```
the plan is "patch the caller"  ->  enumerate callers REPO-WIDE first
                                    more than a couple  ->  patch the shared callee instead
```

Nepali Patro: a caller search scoped to `ads_bloc.dart` found 1 caller where the tree has **24**.
The resulting patch applied cleanly, byte-verified, and fixed nothing.

Then confirm the offset: read the bytes at the blutter `addr` and check they decode to the expected
AArch64 prologue (`stp x29,x30,[sp,#-16]!` / `mov x29,sp` / `sub sp,sp,#N`). Do this once per
snapshot — it is what makes offset-based patching safe at all.

**Read the tail before you retarget.** If you are redirecting a conditional so a branch "falls
through to nothing", prove what the fall-through target actually returns. On Nepali Patro the
`SizedBox()` tail at `0xc0dc1c` is `bl AllocateSizedBoxStub; ldp fp, lr; ret` — the same answer the
app already gives for the `ad_mob` format. Reusing the app's own "nothing to show" return means no
new return convention and correct frame teardown.

### 4. Author

One package per app under `patches/src/main/kotlin/app/franticg33k/patches/<key>/`:

```
shared/Constants.kt        compatibility + AppTarget list
shared/Fingerprints.kt     the fingerprint definitions
<feature>/<Name>Patch.kt   the patch itself
```

Conventions that matter:

- `compatibleWith(COMPATIBILITY_<APP>)"<version>"` enumerates the **tested** versions as
  `AppTarget` entries; kill the version pin from the matcher (§3.14 of AGENTS.md).
- For a `libapp.so` edit, every site is a `patchAt(offset, expectedBytes, replacement)`-style call
  that **asserts** and throws with the observed bytes.
- Comments explain *why the target is right*, not what the code does. The next reader's question is
  "how do you know this is the interstitial and not the dashboard popup?" — answer it in the file.

### 5. Build

```bash
# Linux / macOS - run from the repo root
./gradlew :patches:generatePatchesList :patches:buildAndroid --console=plain

# Windows
gradlew.bat :patches:generatePatchesList :patches:buildAndroid --console=plain
```

Order is load-bearing (§2 of AGENTS.md). Artifact: `patches/build/libs/patches-<version>.mpp`.

Running gradle *through the tooling* (`tools/verify.py`, `tools/pipeline.py loop`) needs no setup:
`common.gradle()` points `JAVA_HOME` at whichever JBR it resolved. Running `./gradlew` by hand does
not, so if your `java` is newer than 21 set it yourself:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :patches:buildAndroid        # Linux
```
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :patches:buildAndroid
```

`doctor` reports which JVM the tooling will use; `MORPHE_JBR` overrides the search.

### 6. Static gate

```bash
python tools/verify.py --app <key> --no-build --no-clean
```

This is the pre-commit gate. It exists because each check caught a real failure: the hand-rolled
parsers mis-parsed silently (read as "stale fingerprint"), `buildAndroid` after
`generatePatchesList` emitted a dex-less bundle, and a fingerprint literal can vanish **during
compilation** (the `$` trap below) while reading fine in the source. Run `--all` only when you have
the time budget: it builds everything and can exceed ten minutes.

### 7. Apply

```bash
python tools/patch.py --package np.com.nepalipatro          # or --apk, or --splits <dir>
```

What it does, and why each step exists:

1. **Merge** the splits into one APK. The patcher needs a complete app to resolve the version;
   pointed at a config split it dies with `getVersionName(...) must not be null`.
2. **Patch the merged APK** — ground truth for which entries changed.
3. **Patch `base.apk` on its own** — its dex/manifest edits, and any *new* dex files, land exactly
   where the installer expects. A native-only patch failing here is expected; it warns, it does not
   stop.
4. **Graft** each changed entry back onto the split that owns it, rewriting the archive (order +
   compression preserved) because an in-place byte edit breaks the CRC.
5. **Align and sign every split with one key** — including the splits nothing patched, or
   `install-multiple` rejects the set.

The result is the **original** split structure with patched entries, never the merged APK. The
signing key lives in `tools/.morphe/` and is generated on first use; a *stable* key matters because
`install-multiple -r` keeps app data only while the signature is unchanged.

Output: `apks/<key>-patched/` with the APKs, a `MANIFEST.md`, and `work/` holding `merged.apk`,
`merged.patched.apk`, `base.patched-unsigned.apk` and the patcher result JSONs.

**Confirm against the patched output, entry by entry.** For a native edit, read `libapp.so` out of
the patched APK and assert the exact words — and count the diffs against stock. Nepali Patro is
28 bytes, at nine sites, and nowhere else. That assertion is what separates "the patch didn't
apply" from "the patch applied and the target was wrong".

### 8. Install and test

```bash
python tools/device.py install --dir apks/nepalipatro-patched --fresh
python tools/device.py ab --plan nepalipatro \
    --stock apks/nepalipatro --patched apks/nepalipatro-patched
```

The scripted pass is data: `tools/testplans/<key>.json` holds the package, the launch wait, the
scroll geometry, the tab taps, the marker substrings and the slot prefixes. A new app is a new plan
file, not new code.

Each pass writes, per label: the screenshots, a cumulative logcat dump per step, the whole-pass
`logcat.txt`, `markers.txt` (counts + unique slots) and `report.json`. `ab` writes `ab-diff.txt`.

**Reading an A/B honestly:**

- A marker that dropped to 0 with stock non-zero is the finding.
- A marker that is 0 on **both** builds means the pass never triggered the path. That is not a
  pass, and it is not a failure of the patch either — it is an invalid experiment. Find a state
  where stock shows the symptom, or say in the doc that the A/B could not be obtained and why.
- Screenshots are for the write-up and for spot-checking; the counts are the evidence.

### 9. Document

The analysis doc lives in `docs/` (the separate `security-audit-docs` repo, gitignored here). It is
the artifact a future reader — including you in three months — depends on. It must contain,
**separately and labelled**:

- the static findings (addresses, byte words, chain proofs)
- the packaging result (what the real patcher produced, read back)
- the runtime result (the A/B, the counts, the screenshots)

and a **resilience** note: which offsets are pinned to which version, and what a future release will
do (fail loudly, then need a fresh blutter run).

Then regenerate the generated files and commit:

```bash
./gradlew :patches:generatePatchesList          # gradlew.bat on Windows
python .github/scripts/generate_patches_readme.py franticg33k/morphe-patches <branch>
git add ... && git commit    # conventional-commit message
```

---

## 3. Failure catalogue

Every entry is a real, expensive mistake from this repo. The rule it produced is in `AGENTS.md`.

| mistake | symptom | rule |
|---|---|---|
| Caller search scoped to one file | patch applied perfectly, symptom unchanged; 1 caller reported where the tree has 24 | enumerate callers repo-wide; prefer the callee |
| Patching a caller at all when the callee is shared | 24 sites to patch, and a guess about which one the user hits | patch the shared callee |
| `nop`-ing a conditional | looks applied, does nothing (the fall-through rejoins the show path) | retarget to the block you want |
| Short signature without an exactly-once assertion | 7-byte sub-prologue matched 778 places | require exactly one match |
| Anchoring a fingerprint on immediates | broke on the next rebuild, not the next refactor | anchor on stable strings/signatures |
| `$` in a Kotlin descriptor literal | fingerprint's constant pool holds a truncated descriptor; dies at runtime | `${'$'}`; and verify the **compiled** bundle, not the source |
| `generatePatchesList` after `buildAndroid` | dex-less bundle, manager shows `Patches: 0` | keep `buildAndroid` last |
| Byte-editing a STORED entry in place | installer rejects with `BadZipFile: Bad CRC-32` | rewrite the archive, re-align, re-sign |
| Installing the merged APK | wrong split structure, license/split behaviour differs | graft, never install the merge |
| Signing only the patched split | `install-multiple` rejects the set | re-sign **every** split with one key |
| Patching a config split directly | `getVersionName(...) must not be null` | merge first |
| Driving the UI with `uiautomator` | finds nothing on Flutter, taps land nowhere | coordinates + pixels + markers |
| `adb exec-out screencap -p > f.png` | corrupt PNG when the redirect goes through PowerShell (UTF-16) | capture on device, `adb pull` |
| Expecting Dart `print()` in logcat | a 4.5 MB capture had zero app lines | use native/Java markers and prefs state |
| Stopping at "content is gone" | blank full-screen page for the whole countdown | check the container too |
| Verifying only "it applied" | shipped three wrong-target patches | verify the symptom; label static/packaging/runtime |
| `versions = [...]` pins | patches stop being offered for builds they'd still fix | package-name compatibility + locators |

---

## 4. Checklists

**New app**

- [ ] `intake.py` pulled the splits and decompiled the base
- [ ] an analysis doc exists in `docs/`, with the layer table filled in
- [ ] `tools/appdata/<key>.yml` written; every fingerprint resolves 1x
- [ ] `tools/testplans/<key>.json` written (package, waits, taps, markers, slots)
- [ ] `Constants.kt` `AppTarget`s list the versions actually tested

**New or changed patch**

- [ ] byte edits assert the expected bytes and throw with the observed ones
- [ ] native edits are length-preserving and reuse an existing early return where possible
- [ ] `verify.py --app <key>` green
- [ ] the patched APK read back: the edited entries changed, and (for natives) the diff count is
      exactly the expected byte count, nowhere else
- [ ] a device A/B exists, or the doc states plainly that it does not and why

**Release**

- [ ] `:patches:generatePatchesList` then `:patches:buildAndroid`, in that order
- [ ] bundle contains `classes.dex`
- [ ] `README.md` / `patches-list.json` regenerated
- [ ] analysis docs updated with the runtime section
- [ ] `docs/` and `apks/` are not in the commit
