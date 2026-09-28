"""A deliberately tiny YAML subset, because pyyaml is not a dependency we can assume.

Why not JSON: the app-data files carry the single most valuable content in this tooling -
hand-written notes about *why* each fingerprint is anchored the way it is. JSON has no
comment syntax, and those notes are what stops the next person re-deriving the analysis.

Supported subset (everything the app-data files use, nothing else):
  * block mappings and block sequences, nested by indentation
  * scalars: plain, single-quoted, double-quoted
  * `>-` folded block scalars for long notes
  * `#` comments on their own line or after a value
  * flow sequences on one line: [a, b, c]

Anything outside that raises YamlError rather than being silently misread. A parser that
guesses is worse than one that refuses - that rule is the reason this module and dexdesc
both have their own tests.

Dollar signs pass through untouched, so `${'$'}` survives into the Python string. That is
load-bearing: it is how a Kotlin template literal is expressed inside a data file.
"""

from __future__ import annotations

import re
from typing import Any


class YamlError(ValueError):
    pass


_KEY_RE = re.compile(r"^(?P<key>[A-Za-z0-9_.$-]+):(?:\s+(?P<rest>.*))?$")
_FLOW_RE = re.compile(r"^\[(?P<body>.*)\]$")


def _strip_comment(line: str) -> str:
    """Remove a trailing `#` comment, respecting quotes."""
    out = []
    quote = None
    for i, ch in enumerate(line):
        if quote:
            out.append(ch)
            if ch == quote and (i == 0 or line[i - 1] != "\\"):
                quote = None
            continue
        if ch in "'\"":
            quote = ch
            out.append(ch)
            continue
        if ch == "#" and (not out or out[-1] in " \t"):
            break
        out.append(ch)
    return "".join(out).rstrip()


def _unquote(s: str) -> str:
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "'\"":
        inner = s[1:-1]
        if s[0] == '"':
            return inner.encode("utf-8", "surrogatepass").decode("unicode_escape")
        return inner.replace("''", "'")
    return s


def _scalar(s: str) -> Any:
    s = s.strip()
    if not s:
        return ""
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "'\"":
        return _unquote(s)
    if s in ("null", "~"):
        return None
    if s == "true":
        return True
    if s == "false":
        return False
    try:
        return int(s)
    except ValueError:
        pass
    return s


def _flow_seq(body: str) -> list[Any]:
    items, depth, cur, quote = [], 0, [], None
    for ch in body:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in "'\"":
            quote = ch
            cur.append(ch)
            continue
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "," and depth == 0:
            items.append(_scalar("".join(cur)))
            cur = []
            continue
        cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        items.append(_scalar(tail))
    for item in items:
        if isinstance(item, str) and item.startswith("["):
            raise YamlError(
                f"nested flow sequences are not supported: {body!r}. "
                "Use a block sequence (one '- ' per line) instead."
            )
    return items


class _Reader:
    def __init__(self, text: str) -> None:
        self.lines: list[tuple[int, str]] = []
        for n, raw in enumerate(text.splitlines(), 1):
            if raw.strip().startswith("#") or not raw.strip():
                continue
            stripped = _strip_comment(raw)
            if not stripped.strip():
                continue
            indent = len(stripped) - len(stripped.lstrip(" "))
            self.lines.append((indent, stripped.strip(), n))
        self.i = 0

    def peek(self):
        return self.lines[self.i] if self.i < len(self.lines) else None

    def next(self):
        item = self.lines[self.i]
        self.i += 1
        return item


def _parse_block(reader: _Reader, indent: int) -> Any:
    head = reader.peek()
    if head is None or head[0] < indent:
        return None
    if head[1].startswith("- "):
        return _parse_seq(reader, head[0])
    return _parse_map(reader, indent)


def _parse_seq(reader: _Reader, indent: int) -> list[Any]:
    items: list[Any] = []
    while True:
        head = reader.peek()
        if head is None or head[0] != indent or not head[1].startswith("- "):
            break
        _, text, lineno = reader.next()
        rest = text[2:].strip()
        if not rest:
            child = _parse_block(reader, indent + 1)
            items.append(child if child is not None else None)
            continue
        m = _KEY_RE.match(rest)
        if m:
            # "- key: value" starts an inline mapping whose remaining keys are indented
            # to the column where `key` began.
            inner_indent = indent + 2
            synthetic = [(inner_indent, rest, lineno)]
            while True:
                nxt = reader.peek()
                if nxt is None or nxt[0] < inner_indent:
                    break
                synthetic.append(reader.next())
            sub = _Reader.__new__(_Reader)
            sub.lines = synthetic
            sub.i = 0
            items.append(_parse_map(sub, inner_indent))
            continue
        if rest.startswith("- "):
            raise YamlError(f"line {lineno}: nested inline sequences are not supported")
        fm = _FLOW_RE.match(rest)
        items.append(_flow_seq(fm.group("body")) if fm else _scalar(rest))
    return items


def _parse_map(reader: _Reader, indent: int) -> dict[str, Any]:
    out: dict[str, Any] = {}
    while True:
        head = reader.peek()
        if head is None or head[0] != indent:
            break
        _, text, lineno = head
        if text.startswith("- "):
            break
        m = _KEY_RE.match(text)
        if not m:
            raise YamlError(f"line {lineno}: cannot parse {text!r}")
        reader.next()
        key = m.group("key")
        rest = (m.group("rest") or "").strip()
        if rest in (">-", ">", "|", "|-"):
            out[key] = _parse_folded(reader, indent, folded=rest.startswith(">"))
            continue
        if not rest:
            nxt = reader.peek()
            if nxt is not None and nxt[0] > indent:
                out[key] = _parse_block(reader, nxt[0])
            elif nxt is not None and nxt[0] == indent and nxt[1].startswith("- "):
                out[key] = _parse_seq(reader, indent)
            else:
                out[key] = None
            continue
        fm = _FLOW_RE.match(rest)
        out[key] = _flow_seq(fm.group("body")) if fm else _scalar(rest)
    return out


def _parse_folded(reader: _Reader, indent: int, *, folded: bool) -> str:
    parts: list[str] = []
    while True:
        nxt = reader.peek()
        if nxt is None or nxt[0] <= indent:
            break
        parts.append(reader.next()[1])
    if not parts:
        return ""
    if folded:
        return " ".join(parts)
    return "\n".join(parts)


def loads(text: str) -> Any:
    reader = _Reader(text)
    if reader.peek() is None:
        return {}
    return _parse_block(reader, reader.peek()[0])


def load(path) -> Any:
    from pathlib import Path

    return loads(Path(path).read_text(encoding="utf-8"))
