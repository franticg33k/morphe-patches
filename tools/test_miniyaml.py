"""Tests for miniyaml - the second hand-rolled parser, so it gets the same treatment as
dexdesc. Run: python tools/test_miniyaml.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from miniyaml import YamlError, loads


class TestScalars(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(loads("a: hello")["a"], "hello")

    def test_int(self):
        self.assertEqual(loads("a: 42")["a"], 42)

    def test_bool(self):
        self.assertEqual(loads("a: true")["a"], True)
        self.assertEqual(loads("a: false")["a"], False)

    def test_null(self):
        self.assertIsNone(loads("a: null")["a"])

    def test_quoted(self):
        self.assertEqual(loads('a: "x"')["a"], "x")
        self.assertEqual(loads("a: 'x'")["a"], "x")

    def test_quoted_keeps_hash(self):
        self.assertEqual(loads('a: "Ljava/lang/String; # not a comment"')["a"],
                         "Ljava/lang/String; # not a comment")

    def test_hash_in_plain_value_is_kept_when_not_spaced(self):
        self.assertEqual(loads("a: MobileAds#initialize")["a"], "MobileAds#initialize")

    def test_trailing_comment_stripped(self):
        self.assertEqual(loads("a: hello # note")["a"], "hello")

    def test_whole_line_comment_ignored(self):
        self.assertEqual(loads("# just a comment\na: 1")["a"], 1)

    def test_blank_lines_ignored(self):
        self.assertEqual(loads("a: 1\n\n\nb: 2")["b"], 2)


class TestDollarSign(unittest.TestCase):
    """Load-bearing: the Kotlin template escape has to survive verbatim."""

    def test_dollar_in_reference_descriptor(self):
        # The data files store the REAL descriptor (literal $). The ${'$'} escape is a
        # Kotlin-source concern only - a bare $ there would be a template expression.
        got = loads('a: "Lio/flutter/plugin/common/MethodChannel$Result;"')["a"]
        self.assertEqual(got, "Lio/flutter/plugin/common/MethodChannel$Result;")

    def test_template_escape_survives_verbatim(self):
        # Comments and notes quote the Kotlin spelling; it must not be reinterpreted.
        text = '# note: write MethodChannel${"$"}Result; in Kotlin\na: 1'
        self.assertEqual(loads(text)["a"], 1)

    def test_dollar_in_plain_scalar(self):
        self.assertEqual(loads("a: Lcom/foo/Bar$Baz;"), {"a": "Lcom/foo/Bar$Baz;"})


class TestFlowSequences(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(loads("a: []")["a"], [])

    def test_three(self):
        self.assertEqual(loads("a: [I, Z, Ljava/lang/String;]")["a"],
                         ["I", "Z", "Ljava/lang/String;"])

    def test_quoted_items(self):
        self.assertEqual(loads('a: ["x", "y"]')["a"], ["x", "y"])

    def test_nested_flow_is_rejected_not_guessed(self):
        # Nesting inside a flow sequence is outside the supported subset. It must raise
        # rather than silently flattening to a string - a plausible-looking wrong value is
        # the exact failure mode this module exists to prevent.
        with self.assertRaises(YamlError):
            loads("a: [[I, Z]]")


class TestNesting(unittest.TestCase):
    def test_map_in_map(self):
        got = loads("a:\n  b: 1\n  c: 2")
        self.assertEqual(got, {"a": {"b": 1, "c": 2}})

    def test_three_levels(self):
        got = loads("a:\n  b:\n    c: deep")
        self.assertEqual(got["a"]["b"]["c"], "deep")

    def test_seq_of_maps(self):
        got = loads("items:\n  - name: a\n    v: 1\n  - name: b\n    v: 2")
        self.assertEqual(got["items"],
                         [{"name": "a", "v": 1}, {"name": "b", "v": 2}])

    def test_seq_of_maps_with_nested_map(self):
        got = loads("f:\n  - name: a\n    strings:\n      - s1\n      - s2\n  - name: b")
        self.assertEqual(got["f"][0]["strings"], ["s1", "s2"])
        self.assertEqual(got["f"][1], {"name": "b"})

    def test_key_with_empty_value_is_none(self):
        self.assertIsNone(loads("a:")["a"])

    def test_flow_seq_under_key(self):
        got = loads("a:\n  - 1\n  - 2")
        self.assertEqual(got["a"], [1, 2])


class TestFoldedScalars(unittest.TestCase):
    def test_folded_joins_with_spaces(self):
        got = loads("note: >-\n  one\n  two\n  three")
        self.assertEqual(got["note"], "one two three")

    def test_folded_absorbs_deeper_indent(self):
        got = loads("note: >-\n  one\n  two\nother: x")
        self.assertEqual(got["note"], "one two")
        self.assertEqual(got["other"], "x")

    def test_folded_empty(self):
        self.assertEqual(loads("note: >-\nother: x")["note"], "")


class TestRejectsGarbage(unittest.TestCase):
    def test_no_space_before_colon_content(self):
        # key: must be followed by space or EOL
        with self.assertRaises(YamlError):
            loads("a:b\nc: 1")

    def test_bare_scalar_line_at_top_level(self):
        with self.assertRaises(YamlError):
            loads("just text\nnext: 1")


class TestRealAppDataFiles(unittest.TestCase):
    """The files that actually matter must parse to the right shape."""

    def _data(self, name):
        return loads((Path(__file__).resolve().parent / "appdata" / name).read_text(encoding="utf-8"))

    def test_nepalipatro(self):
        d = self._data("nepalipatro.yml")
        self.assertEqual(d["package"], "np.com.nepalipatro")
        self.assertEqual(len(d["fingerprints"]), 4)
        names = {f["name"] for f in d["fingerprints"]}
        self.assertIn("AdMobOnMethodCallFingerprint", names)
        ad = next(f for f in d["fingerprints"] if f["name"] == "AdMobOnMethodCallFingerprint")
        self.assertEqual(len(ad["parameters"]), 2)
        self.assertEqual(
            ad["parameters"][1], "Lio/flutter/plugin/common/MethodChannel$Result;"
        )
        self.assertEqual(len(ad["strings"]), 5)
        self.assertIn("note", ad)
        for f in d["fingerprints"]:
            self.assertIn("returnType", f)
            self.assertIn("parameters", f)

    def test_hamropatro(self):
        d = self._data("hamropatro.yml")
        self.assertEqual(d["package"], "com.hamropatro")
        self.assertEqual(len(d["fingerprints"]), 5)
        by = {f["name"]: f for f in d["fingerprints"]}
        self.assertEqual(by["GetBannerAdsFingerprint"]["parameters"],
                         ["Lp05;", "Ljava/lang/String;", "Z"])
        self.assertEqual(by["GetFullScreenAdsFingerprint"]["parameters"], ["Lq05;", "Z"])
        self.assertEqual(by["GetNativeAdFingerprint"]["parameters"],
                         ["Lr05;", "Ljava/lang/String;"])
        self.assertEqual(
            by["GetNativeAdByPlacementFingerprint"]["parameters"],
            ["Lcom/hamropatro/library/nativeads/model/AdPlacementName;", "Ljava/lang/String;"],
        )
        self.assertEqual(d["forbiddenLiterals"], ["Lyq7;", "Lzq7;", "Lar7;"])
        self.assertEqual(len(d["compiledLiterals"]), 5)

    def test_every_app_declares_its_pins(self):
        """The gap that let Fricam 1.6.5 ship with a stale AppTarget list.

        The fingerprints were re-pinned and the Edge bug fixed for 1.6.5 while
        Constants.kt still said 1.4.0.1/1.3.7, so the manager would not have offered the
        patch on the version it had just been fixed for. Every app data file must therefore
        state `pinnedVersions` explicitly - an empty list is a valid answer for a
        package-name-only app, an absent key is not.
        """
        for path in (Path(__file__).resolve().parent / "appdata").glob("*.yml"):
            d = loads(path.read_text(encoding="utf-8"))
            with self.subTest(app=path.stem):
                self.assertIn(
                    "pinnedVersions", d,
                    "%s is missing pinnedVersions; fingerprints.py cannot cross-check the "
                    "AppTarget list without it" % path.stem,
                )
                self.assertIsInstance(d["pinnedVersions"], list)
                if d["pinnedVersions"] and d["version"] not in d["pinnedVersions"]:
                    # Only acceptable when the app has explicitly opted in, so the mismatch
                    # is a recorded decision rather than an accident nobody noticed.
                    self.assertTrue(
                        d.get("pinMismatchExpected"),
                        "%s: verified %s but pins %s, without pinMismatchExpected: true"
                        % (path.stem, d["version"], d["pinnedVersions"]),
                    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
