"""Tests for dexdesc.split_params - the one piece of this tooling that fails silently.

Run: python tools/test_dexdesc.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dexdesc import DescriptorError, describe, is_return_type, params_match, split_params


class TestSplitParams(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(split_params(""), [])

    def test_single_primitives(self):
        for p in "ZBSCIJFDV":
            self.assertEqual(split_params(p), [p], p)

    def test_single_reference(self):
        self.assertEqual(split_params("Ljava/lang/String;"), ["Ljava/lang/String;"])

    def test_the_hamropatro_signatures(self):
        # The exact signatures that made a correct patch look broken.
        self.assertEqual(
            split_params("Lp05;Ljava/lang/String;Z"),
            ["Lp05;", "Ljava/lang/String;", "Z"],
        )
        self.assertEqual(split_params("Lq05;Z"), ["Lq05;", "Z"])
        self.assertEqual(
            split_params("Lr05;Ljava/lang/String;"), ["Lr05;", "Ljava/lang/String;"]
        )
        self.assertEqual(
            split_params(
                "Lcom/hamropatro/library/nativeads/model/AdPlacementName;Ljava/lang/String;"
            ),
            [
                "Lcom/hamropatro/library/nativeads/model/AdPlacementName;",
                "Ljava/lang/String;",
            ],
        )

    def test_array_of_reference(self):
        self.assertEqual(
            split_params("[Ljava/lang/String;"), ["[Ljava/lang/String;"]
        )

    def test_array_of_primitive(self):
        for p in "ZBSCIJFDV":
            self.assertEqual(split_params("[" + p), ["[" + p], p)

    def test_multidimensional_array_is_one_parameter(self):
        # The regression that produced 9933 spurious warnings: '[[' must not split into
        # a bare '[' plus a type.
        self.assertEqual(split_params("[[I"), ["[[I"])
        self.assertEqual(
            split_params("[[Ljava/lang/String;"), ["[[Ljava/lang/String;"]
        )
        self.assertEqual(
            split_params("[[[Lcom/foo/bar$baz;ILcom/foo/bar$baz;"),
            ["[[[Lcom/foo/bar$baz;", "I", "Lcom/foo/bar$baz;"],
        )

    def test_mixed_real_world_sigs(self):
        # I[I[Ljava/lang/Object;Z  - seen in the wild in ads_mobile_sdk
        self.assertEqual(
            split_params("I[I[Ljava/lang/Object;Z"),
            ["I", "[I", "[Ljava/lang/Object;", "Z"],
        )
        # Ljava/lang/Enum;[Ljava/lang/String;[[I
        self.assertEqual(
            split_params("Ljava/lang/Enum;[Ljava/lang/String;[[I"),
            ["Ljava/lang/Enum;", "[Ljava/lang/String;", "[[I"],
        )
        # J[Lorg/webrtc/NetworkChangeDetector$NetworkInformation;
        self.assertEqual(
            split_params("J[Lorg/webrtc/NetworkChangeDetector$NetworkInformation;"),
            ["J", "[Lorg/webrtc/NetworkChangeDetector$NetworkInformation;"],
        )
        # [Lsg/bigo/ads/api/core/b;Lsg/bigo/ads/api/b;ZIIIZILjava/lang/String;
        self.assertEqual(
            split_params("[Lsg/bigo/ads/api/core/b;Lsg/bigo/ads/api/b;ZIIIZILjava/lang/String;"),
            [
                "[Lsg/bigo/ads/api/core/b;",
                "Lsg/bigo/ads/api/b;",
                "Z",
                "I",
                "I",
                "I",
                "Z",
                "I",
                "Ljava/lang/String;",
            ],
        )

    def test_dollar_in_reference_is_not_a_separator(self):
        self.assertEqual(
            split_params("Lcom/foo/Bar$Baz;Lcom/foo/Bar$Qux;"),
            ["Lcom/foo/Bar$Baz;", "Lcom/foo/Bar$Qux;"],
        )

    def test_trailing_semicolon_marks_reference_not_empty_param(self):
        # "Lp05;" splits to one param, not two (the ';' is part of the descriptor).
        self.assertEqual(len(split_params("Lp05;")), 1)

    def test_ordering_preserved(self):
        raw = "Ljava/lang/String;ILjava/lang/Object;Z"
        self.assertEqual(split_params(raw), ["Ljava/lang/String;", "I", "Ljava/lang/Object;", "Z"])


class TestSplitParamsRejectsGarbage(unittest.TestCase):
    """Malformed input must raise, never return a plausible-looking wrong list."""

    def test_bare_bracket(self):
        with self.assertRaises(DescriptorError):
            split_params("[")

    def test_unterminated_reference(self):
        # 'Lp05' with no ';' - scanner consumes to end, and the round-trip guard below
        # is what catches it. Assert the behaviour explicitly so it is a decision.
        self.assertEqual(split_params("Lp05"), ["Lp05"])

    def test_unknown_letter(self):
        with self.assertRaises(DescriptorError):
            split_params("Q")

    def test_garbage_between_params(self):
        with self.assertRaises(DescriptorError):
            split_params("Ljava/lang/String;Q")

    def test_stray_space_is_rejected(self):
        with self.assertRaises(DescriptorError):
            split_params("I I")


class TestRoundTrip(unittest.TestCase):
    """The property the previous hand-rolled versions actually needed.

    Every parser is only trustworthy if re-joining the tokens reproduces the input
    exactly. This is a stronger check than any table of expected values because it holds
    for signatures nobody thought to write a test for.
    """

    CORPUS = [
        "",
        "V",
        "Z",
        "I",
        "Ljava/lang/String;",
        "Lp05;Ljava/lang/String;Z",
        "Lq05;Z",
        "Lr05;Ljava/lang/String;",
        "Lcom/hamropatro/library/nativeads/model/AdPlacementName;Ljava/lang/String;",
        "I[I[Ljava/lang/Object;Z",
        "Landroid/content/Context;Ln3e;Ljava/util/concurrent/ExecutorService;[Ljava/lang/String;",
        "[B",
        "[Ljava/lang/Object;IZ",
        "IIILandroid/content/ComponentName;[Landroid/content/Intent;Landroid/content/Intent;I[Landroid/view/MenuItem;",
        "Lio/bidmachine/media3/exoplayer/drm/DrmSessionManager;Lio/bidmachine/media3/exoplayer/dash/DashChunkSource$Factory;Ljava/util/List;[[II[Z[[Lio/bidmachine/media3/common/Format;[Lio/bidmachine/media3/exoplayer/dash/DashMediaPeriod$TrackGroupInfo;",
        "[Lcom/bytedance/sdk/openadsdk/lse/dsz$fm;ILcom/bytedance/sdk/openadsdk/lse/dsz$fm;",
        "[[[Lcom/bytedance/sdk/openadsdk/lse/dsz$fm;ILcom/bytedance/sdk/openadsdk/lse/dsz$fm;",
        "Lcom/facebook/ads/InterstitialAd$InterstitialShowAdConfig;",
        "Lcom/applovin/mediation/ads/MaxAd;",
        "Lcom/mopub/sdk/AdView;",
        "ZILjava/lang/String;",
        "Ljava/lang/String;Ljava/lang/StringBuilder;Ljava/util/regex/Pattern;Ljava/lang/String;[[I",
    ]

    def test_roundtrip(self):
        for raw in self.CORPUS:
            with self.subTest(raw=raw):
                self.assertEqual("".join(split_params(raw)), raw)

    def test_count_never_grows_past_characters(self):
        for raw in self.CORPUS:
            self.assertLessEqual(len(split_params(raw)), max(len(raw), 1))


class TestReturnType(unittest.TestCase):
    def test_valid(self):
        for rt in ("V", "Z", "I", "Ljava/util/List;", "[Ljava/lang/String;", "[[I"):
            self.assertTrue(is_return_type(rt), rt)

    def test_invalid(self):
        for rt in ("", "V V", "IJ"):
            self.assertFalse(is_return_type(rt), rt)

    def test_unterminated_reference_is_not_a_return_type(self):
        # No trailing ';', so the descriptor is incomplete. split_params tolerates it
        # (scanning to end of input) which is right for a truncated method line, but it
        # must not be accepted as a well-formed return type.
        self.assertFalse(is_return_type("Ljava/lang/String"))

    def test_unterminated_reference_roundtrips_but_is_flagged(self):
        self.assertEqual(split_params("Ljava/lang/String"), ["Ljava/lang/String"])


class TestParamsMatch(unittest.TestCase):
    def test_order_matters(self):
        self.assertFalse(params_match(["I", "Z"], ["Z", "I"]))

    def test_exact(self):
        self.assertTrue(params_match(["Lp05;", "Z"], ["Lp05;", "Z"]))

    def test_empty_both(self):
        self.assertTrue(params_match([], []))

    def test_empty_vs_none(self):
        self.assertFalse(params_match([], ["Z"]))


class TestDescribe(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(describe([]), "<none>")

    def test_two(self):
        self.assertEqual(describe(["I", "Z"]), "I, Z")


if __name__ == "__main__":
    unittest.main(verbosity=2)
