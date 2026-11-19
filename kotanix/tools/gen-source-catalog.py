#!/usr/bin/env python3
"""Generate kotanix/src/main/kotlin/kotanix/online/SourceCatalog.kt
from the pinned kotatsu-parsers-redo checkout (see UPSTREAM.lock §2).

Usage:
    python3 tools/gen-source-catalog.py /path/to/kotatsu-parsers-redo > src/main/kotlin/kotanix/online/SourceCatalog.kt

The output is deterministic for a given input revision. It contains only
factual data (name / title / locale / content type / broken flag), no code.
"""
import re
import sys
from pathlib import Path

def iter_annotations(text):
    """Yield (start, end, args) for each @MangaSourceParser(...), paren-matching inside strings."""
    marker = "@MangaSourceParser("
    i = 0
    while True:
        start = text.find(marker, i)
        if start == -1:
            return
        args_start = start + len(marker)
        depth = 1
        j = args_start
        in_str = False
        while j < len(text):
            ch = text[j]
            if in_str:
                if ch == "\\":
                    j += 2
                    continue
                if ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                elif ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                    if depth == 0:
                        break
            j += 1
        yield start, j + 1, text[args_start:j]
        i = j + 1

CLASS_DECL = re.compile(r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*)")
QUOTED = re.compile(r'"((?:[^"\\]|\\.)*)"')
def UNESCAPE(s):
    # Decode unicode escapes as the Kotlin compiler would, then the simple ones.
    s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)
    return s.replace('\\"', '"').replace("\\\\", "\\")
NAME_RE = re.compile(r"^[A-Z_][A-Z0-9_]{3,}$")


class Match:
    def __init__(self, start, args):
        self.start = start
        self.args = args

def parse_args(args: str):
    """Return (name, title, locale, type) from the annotation argument list."""
    positional = [UNESCAPE(x) for x in QUOTED.findall(args)]
    if len(positional) < 2:
        raise ValueError(f"annotation needs name and title: {args!r}")
    name, title = positional[0], positional[1]
    locale_m = re.search(r'locale\s*=\s*"((?:[^"\\]|\\.)*)"', args)
    type_m = re.search(r"type\s*=\s*(?:ContentType\.)?([A-Z_]+)", args)
    # locale/type were matched positionally above too (named args are still quoted);
    # strip them out of positional parsing so positional[0..1] are always name/title.
    if locale_m:
        locale = UNESCAPE(locale_m.group(1))
    elif len(positional) >= 3:
        locale = positional[2]
    else:
        locale = ""
    ctype = type_m.group(1) if type_m else "MANGA"
    return name, title, locale, ctype


def main() -> int:
    root = Path(sys.argv[1])
    assert (root / "src/main").is_dir(), f"not a parsers checkout: {root}"
    entries = []
    seen = {}
    for f in sorted(root.glob("src/main/**/*.kt")) + sorted(root.glob("src/main/**/*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for start, end, args in iter_annotations(text):
            m = Match(start, args)
            tail = text[end:]
            cls = CLASS_DECL.search(tail)
            if cls is None:
                continue
            head = text[max(0, start - 400):start]
            block = text[start:end + cls.start()]
            context = head + block
            is_broken = "@Broken" in context
            is_deprecated = "@Deprecated" in context
            name, title, locale, ctype = parse_args(args)
            if not NAME_RE.match(name):
                raise ValueError(f"bad source name {name!r} in {f}")
            if name in seen:
                raise ValueError(f"duplicate source name {name}: {seen[name]} and {f}")
            seen[name] = f
            entries.append((name, title, locale, ctype, is_broken, is_deprecated))
    if len(entries) < 1000:
        raise ValueError(f"suspiciously few sources found: {len(entries)}")
    entries.sort(key=lambda e: (e[2], e[1].lower(), e[0]))

    out = []
    out.append("// Generated from kotatsu-parsers-redo @ 3432a85ac02ce99824cf841aca40bfd3af78ed3f (GPL-3.0).")
    out.append("// DO NOT EDIT BY HAND — regenerate with: python3 tools/gen-source-catalog.py <path-to-kotatsu-parsers-redo>")
    out.append("// Factual catalog data only (source name/title/locale/content type/flags). No parser code.")
    out.append("package kotanix.online")
    out.append("")
    out.append("/** One online source as declared by the pinned Kotatsu-Redo parser catalog. */")
    out.append("data class CatalogSource(")
    out.append("\tval name: String,")
    out.append("\tval title: String,")
    out.append("\tval locale: String,")
    out.append("\tval contentType: String,")
    out.append("\tval isBroken: Boolean,")
    out.append("\tval isDeprecated: Boolean,")
    out.append(")")
    out.append("")
    out.append("object SourceCatalog {")
    out.append(f"\t/** Number of sources in the catalog (as generated). */")
    out.append(f"\tconst val SIZE = {len(entries)}")
    out.append("\n\tval SOURCES: List<CatalogSource> = listOf(")
    for name, title, locale, ctype, is_broken, is_deprecated in entries:
        def esc(s: str) -> str:
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        out.append(
            f'\t\tCatalogSource("{esc(name)}", "{esc(title)}", "{esc(locale)}", "{ctype}", '
            f'{str(is_broken).lower()}, {str(is_deprecated).lower()}),'
        )
    out.append("\t)")
    out.append("}")
    out.append("")
    print("\n".join(out))
    print(f"sources={len(entries)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
