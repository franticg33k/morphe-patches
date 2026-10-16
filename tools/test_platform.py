"""Tests for the platform resolvers and artefact selection in common.py.

Run: python tools/test_platform.py

Why this file exists: the toolchain has to run on Windows (where it was written), on Linux
(where the CI and most users are), and ideally on macOS. Every one of those branches is a
candidate list plus a suffix, and a branch nobody has executed is a branch nobody has
tested. So the resolvers take their platform inputs as *parameters* and this file asserts
each branch's output from whatever host happens to run it.

What this does NOT prove: that the tools actually work end to end on Linux. That needs a
Linux host. These tests prove the Windows-only assumptions are gone and that the Linux/macOS
answers are the intended ones.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402


class TestPlatformCandidates(unittest.TestCase):
    def test_picks_the_windows_list(self):
        got = common.platform_candidates(
            [r"C:\a\jbr"], ["/l/jbr"], ["/m/jbr"],
            windows_flag=True, macos_flag=False, home=Path("/home/u"),
        )
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0].name, "jbr")
        self.assertIn("a", str(got[0]))

    def test_picks_the_linux_list_and_not_windows(self):
        got = common.platform_candidates(
            [r"C:\a\jbr"], ["/opt/android-studio/jbr"], ["/Applications/x"],
            windows_flag=False, macos_flag=False, home=Path("/home/u"),
        )
        self.assertEqual([p.as_posix() for p in got], ["/opt/android-studio/jbr"])

    def test_picks_the_macos_list(self):
        got = common.platform_candidates(
            [r"C:\a\jbr"], ["/l/jbr"], ["/Applications/Android Studio.app/x"],
            windows_flag=False, macos_flag=True, home=Path("/home/u"),
        )
        self.assertEqual([p.as_posix() for p in got], ["/Applications/Android Studio.app/x"])

    def test_tilde_expands_against_the_injected_home(self):
        got = common.platform_candidates(
            [], ["~/jbr"], [], windows_flag=False, macos_flag=False, home=Path("/home/u"),
        )
        self.assertEqual(got, [Path("/home/u/jbr")])

    def test_tilde_in_the_middle_is_left_alone(self):
        # Only a leading `~` is a home reference; `~` elsewhere is a literal character.
        got = common.platform_candidates(
            [], ["a~/jbr"], [], windows_flag=False, macos_flag=False, home=Path("/home/u"),
        )
        self.assertEqual(got, [Path("a~/jbr")])


class TestExe(unittest.TestCase):
    def test_windows_suffix(self):
        self.assertEqual(common.exe("zipalign", windows_flag=True), "zipalign.exe")

    def test_posix_has_no_suffix(self):
        self.assertEqual(common.exe("zipalign", windows_flag=False), "zipalign")


class TestJbr(unittest.TestCase):
    def test_all_three_platforms_are_covered(self):
        self.assertTrue(common._JBR_WINDOWS)
        self.assertTrue(common._JBR_LINUX)
        self.assertTrue(common._JBR_MACOS)

    def test_linux_candidates_are_posix_paths(self):
        for cand in common._JBR_LINUX:
            self.assertFalse(cand.startswith("C:"), f"{cand} is not a Linux path")

    def test_windows_candidates_are_windows_paths(self):
        for cand in common._JBR_WINDOWS:
            self.assertTrue(cand.startswith("C:\\"), f"{cand} is not a Windows path")

    def test_linux_default_is_the_first_linux_candidate(self):
        got = common.platform_candidates(
            common._JBR_WINDOWS, common._JBR_LINUX, common._JBR_MACOS,
            windows_flag=False, macos_flag=False,
        )
        self.assertEqual(got[0].as_posix(), "/opt/android-studio/jbr")

    def test_macos_default_points_into_the_app_bundle(self):
        got = common.platform_candidates(
            common._JBR_WINDOWS, common._JBR_LINUX, common._JBR_MACOS,
            windows_flag=False, macos_flag=True,
        )
        self.assertIn("Android Studio.app", got[0].as_posix())

    def test_env_override_wins_when_it_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {common.JBR_ENV_VAR: tmp}):
                self.assertEqual(common.resolve_jbr(), Path(tmp))

    def test_env_override_that_is_not_a_dir_is_rejected(self):
        with mock.patch.dict(os.environ, {common.JBR_ENV_VAR: "/definitely/not/here"}):
            self.assertIsNone(common.resolve_jbr())

    def test_missing_everything_returns_none_or_a_path(self):
        # Either the host has a JBR (a Path) or it does not (None). Both are valid answers;
        # what must never happen is an exception escaping at import time.
        with mock.patch.dict(os.environ, {common.JBR_ENV_VAR: ""}):
            got = common.resolve_jbr()
        self.assertTrue(got is None or isinstance(got, Path))

    def test_jbr_default_is_a_path_for_the_current_host(self):
        self.assertIsInstance(common.JBR, Path)
        self.assertIsInstance(common._jbr_default(), Path)


class TestJbrFallback(unittest.TestCase):
    """The "the JBR is a preference, not a requirement" claim, exercised rather than
    asserted in prose: on a Linux box without Android Studio the tools must degrade to
    `java` on PATH instead of refusing to run."""

    def test_jbr_env_leaves_the_environment_alone_without_a_jbr(self):
        with mock.patch.object(common, "JBR", Path("/no/such/jbr")):
            env = common.jbr_env({"PATH": "/usr/bin"})
        self.assertNotIn("JAVA_HOME", env)
        self.assertEqual(env["PATH"], "/usr/bin")

    def test_jbr_env_sets_java_home_when_there_is_a_jbr(self):
        with tempfile.TemporaryDirectory() as tmp:
            jbr = Path(tmp)
            with mock.patch.object(common, "JBR", jbr):
                env = common.jbr_env({"PATH": "/usr/bin"})
        self.assertEqual(env["JAVA_HOME"], str(jbr))
        # The JBR's bin also goes on PATH, ahead of everything else.
        self.assertTrue(env["PATH"].startswith(str(jbr / "bin") + os.pathsep))

    def test_jbr_env_does_not_mutate_the_caller_env(self):
        base = {"PATH": "/usr/bin"}
        with mock.patch.object(common, "JBR", Path("/no/such/jbr")):
            common.jbr_env(base)
        self.assertEqual(base, {"PATH": "/usr/bin"})

    def test_java_exe_falls_back_to_path_without_a_jbr(self):
        with mock.patch.object(common, "JBR", Path("/no/such/jbr")):
            with mock.patch("shutil.which", return_value="/usr/bin/java"):
                self.assertEqual(common.java_exe(), "/usr/bin/java")

    def test_java_exe_error_names_both_options(self):
        with mock.patch.object(common, "JBR", Path("/no/such/jbr")):
            with mock.patch("shutil.which", return_value=None):
                with self.assertRaises(common.ToolError) as ctx:
                    common.java_exe()
        msg = str(ctx.exception)
        self.assertIn("JBR", msg)
        self.assertIn("PATH", msg)


class TestGradleWrapper(unittest.TestCase):
    def test_windows_uses_the_bat(self):
        argv = common.gradle_wrapper(windows_flag=True)
        self.assertEqual(argv, [str(common.REPO_ROOT / "gradlew.bat")])

    def test_posix_uses_gradlew(self):
        argv = common.gradle_wrapper(windows_flag=False)
        self.assertEqual(argv[-1], str(common.REPO_ROOT / "gradlew"))
        self.assertIn(argv[0], (argv[-1], "sh"))

    def test_gradlew_is_committed_executable(self):
        # The `sh gradlew` fallback covers a lost bit, but a checkout that has the bit is
        # what makes `./gradlew` work in the docs and in a user's shell.
        wrapper = common.REPO_ROOT / "gradlew"
        self.assertTrue(wrapper.exists())
        self.assertTrue(os.access(wrapper, os.R_OK))


class TestSdkCandidates(unittest.TestCase):
    def test_windows_includes_the_appdata_default(self):
        got = [p.as_posix() for p in common.sdk_candidates(windows_flag=True, home=Path("/h"))]
        self.assertIn("/h/AppData/Local/Android/Sdk", got)

    def test_linux_excludes_windows_and_macos_defaults(self):
        got = [p.as_posix() for p in common.sdk_candidates(windows_flag=False, home=Path("/h"))]
        self.assertNotIn("/h/AppData/Local/Android/Sdk", got)
        self.assertNotIn("/h/Library/Android/sdk", got)
        self.assertIn("/h/Android/Sdk", got)
        self.assertIn("/usr/lib/android-sdk", got)

    def test_macos_includes_the_library_default(self):
        got = [p.as_posix() for p in common.sdk_candidates(
            windows_flag=False, macos_flag=True, home=Path("/h"))]
        self.assertIn("/h/Library/Android/sdk", got)

    def test_sdk_root_honours_android_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"ANDROID_HOME": tmp}, clear=True):
                self.assertEqual(common.sdk_root(), Path(tmp))

    def test_sdk_root_honours_android_sdk_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": tmp}, clear=True):
                self.assertEqual(common.sdk_root(), Path(tmp))

    def test_sdk_root_prefers_android_home_over_sdk_root(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            with mock.patch.dict(
                os.environ, {"ANDROID_HOME": a, "ANDROID_SDK_ROOT": b}, clear=True
            ):
                self.assertEqual(common.sdk_root(), Path(a))

    def test_sdk_root_raises_a_readable_error(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with mock.patch.object(common, "sdk_candidates", return_value=[]):
                with self.assertRaises(common.ToolError) as ctx:
                    common.sdk_root()
        self.assertIn("ANDROID_HOME", str(ctx.exception))


class TestResolveApktool(unittest.TestCase):
    def test_path_wrapper_wins(self):
        with mock.patch("shutil.which", return_value="/usr/local/bin/apktool"):
            self.assertEqual(common.resolve_apktool(), ["/usr/local/bin/apktool"])

    def test_jar_is_run_through_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            jar = home / "Downloads" / "apktool.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"PK\x03\x04")
            with mock.patch("shutil.which", return_value=None):
                argv = common.resolve_apktool(home=home, java="JAVA")
        self.assertEqual(argv, ["JAVA", "-jar", str(jar)])

    def test_second_candidate_is_found_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "apktool.jar").write_bytes(b"PK\x03\x04")
            with mock.patch("shutil.which", return_value=None):
                argv = common.resolve_apktool(home=home, java="JAVA")
        self.assertEqual(argv, ["JAVA", "-jar", str(home / "apktool.jar")])

    def test_nothing_found_names_the_places_it_looked(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch("shutil.which", return_value=None):
                with self.assertRaises(common.ToolError) as ctx:
                    common.resolve_apktool(home=Path(tmp), java="JAVA")
        msg = str(ctx.exception)
        self.assertIn("apktool", msg)
        self.assertIn("PATH", msg)
        self.assertIn("apktool.jar", msg)

    def test_absolute_candidate_is_reachable_without_home(self):
        # `/usr/share/apktool/apktool.jar` is absolute, so it must be in the list verbatim.
        self.assertIn("/usr/share/apktool/apktool.jar", common.APKTOOL_JAR_CANDIDATES)


class TestBuiltBundle(unittest.TestCase):
    """Which file in patches/build/libs counts as "the bundle".

    The build publishes the sources and javadoc artefacts next to the real bundle, and
    handing one of those to the patcher fails in a confusing way: the gate reports
    `no dex entries`, which reads like a broken build rather than a mis-selected file.
    So the selection rules are pinned here rather than left to a directory listing order.
    """

    @staticmethod
    def _libs(tmp, artefacts):
        libs = Path(tmp) / "libs"
        libs.mkdir()
        for name, mtime in artefacts:
            f = libs / name
            f.write_bytes(b"PK\x03\x04")
            os.utime(f, (mtime, mtime))
        return libs

    def test_sources_and_javadoc_are_never_selected(self):
        # Both artefacts are newer than the real bundle, so an unfiltered mtime sort
        # would return them.
        with tempfile.TemporaryDirectory() as tmp:
            libs = self._libs(tmp, [
                ("patches-1.4.2.mpp", 100),
                ("patches-1.4.2-sources.mpp", 200),
                ("patches-1.4.2-javadoc.mpp", 300),
            ])
            with mock.patch.object(common, "PATCHES_BUILD", libs):
                got = common.built_bundle()
        self.assertEqual(got.name, "patches-1.4.2.mpp")

    def test_the_newest_bundle_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            libs = self._libs(tmp, [
                ("patches-1.4.2.mpp", 100),
                ("patches-1.5.0.mpp", 200),
            ])
            with mock.patch.object(common, "PATCHES_BUILD", libs):
                got = common.built_bundle()
        self.assertEqual(got.name, "patches-1.5.0.mpp")

    def test_only_artefacts_means_no_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            libs = self._libs(tmp, [
                ("patches-1.4.2-sources.mpp", 100),
                ("patches-1.4.2-javadoc.mpp", 200),
            ])
            with mock.patch.object(common, "PATCHES_BUILD", libs):
                self.assertIsNone(common.built_bundle())

    def test_a_missing_directory_is_not_an_error(self):
        with mock.patch.object(common, "PATCHES_BUILD", Path("/no/such/patches/build/libs")):
            self.assertIsNone(common.built_bundle())

    def test_a_tie_does_not_depend_on_listing_order(self):
        # One build writes several bundles with the same timestamp; which one wins must
        # not change with the order the directory happens to be listed in.
        names = ["patches-1.4.2.mpp", "patches-1.4.2-dev.2.mpp"]
        winners = []
        for order in (names, list(reversed(names))):
            with tempfile.TemporaryDirectory() as tmp:
                libs = self._libs(tmp, [(n, 500) for n in order])
                with mock.patch.object(common, "PATCHES_BUILD", libs):
                    winners.append(common.built_bundle().name)
        self.assertEqual(winners[0], winners[1])
        self.assertNotIn(winners[0], ("patches-1.4.2-sources.mpp", "patches-1.4.2-javadoc.mpp"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
