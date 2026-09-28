"""Dex descriptor tokenising.

smali writes method signatures with their parameter descriptors concatenated and
unseparated:

    .method public getBannerAds(Lp05;Ljava/lang/String;Z)Ljava/util/List;

so this cannot be a whitespace split. A descriptor is N leading '[' array markers followed
by either an L-type running to the next ';' or a single primitive char; the ';' belongs to
the L-type, including when that L-type is itself an array element.

Why this is a separate module with its own tests: a mis-parse here does not raise. It
returns a wrong parameter list, the fingerprint matches nothing, and the harness reports
"matches=0" - indistinguishable from a genuinely stale fingerprint. That exact confusion
nearly caused working Hamro Patro code to be "fixed". `test_dexdesc.py` is the guard.
"""

from __future__ import annotations

PRIMITIVES = frozenset("ZBSCIJFDV")

# Return type: always exactly one descriptor, never empty.
_RETURN_RE_CHARS = set("ZBSCIJFDVL[")


class DescriptorError(ValueError):
    """Raised when a signature cannot be fully tokenised."""


def split_params(raw: str) -> list[str]:
    """Tokenise a parameter list into individual descriptors.

    >>> split_params("Lp05;Ljava/lang/String;Z")
    ['Lp05;', 'Ljava/lang/String;', 'Z']
    >>> split_params("I[I[Ljava/lang/Object;Z")
    ['I', '[I', '[Ljava/lang/Object;', 'Z']
    >>> split_params("")
    []
    """
    out: list[str] = []
    i, n = 0, len(raw)
    while i < n:
        start = i
        while i < n and raw[i] == "[":
            i += 1
        if i < n and raw[i] == "L":
            j = raw.find(";", i)
            i = n if j == -1 else j + 1
        elif i < n and raw[i] in PRIMITIVES:
            i += 1
        else:
            raise DescriptorError(
                f"unparsable parameter at index {start} of {raw!r}"
            )
        out.append(raw[start:i])
    return out


def is_return_type(raw: str) -> bool:
    """True if `raw` is a well-formed single return-type descriptor.

    Stricter than `len(split_params(raw)) == 1`: an unterminated reference such as
    `Ljava/lang/String` (no trailing ';') tokenises to a single element but is not a
    complete descriptor, and accepting it would let a truncated method line look valid.
    """
    if not raw:
        return False
    try:
        params = split_params(raw)
    except DescriptorError:
        return False
    if len(params) != 1 or params[0] != raw:
        return False
    body = raw.lstrip("[")
    if body.startswith("L"):
        return body.endswith(";")
    return len(body) == 1 and body in PRIMITIVES


def params_match(actual: list[str], expected: list[str]) -> bool:
    return actual == expected


def describe(params: list[str]) -> str:
    return ", ".join(params) if params else "<none>"
