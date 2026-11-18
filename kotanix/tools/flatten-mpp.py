#!/usr/bin/env python3
"""Flatten Kotlin Multiplatform expect/actual declarations for a plain-JVM kotlinc compile.

Usage: flatten-mpp.py <dest-dir> <src-dir> [<src-dir> ...]

Mirrors each source tree into <dest-dir> and:
  1. deletes every `expect` declaration (class/interface/object/fun/val/var/typealias),
     including member expects inside expect class bodies;
  2. strips the `actual ` modifier everywhere;
  3. copies default parameter values from expect declarations onto their matching
     `actual` counterparts, so call sites relying on defaults still compile.

Why (3): in Kotlin MPP, default argument values may only appear on expect declarations;
actuals must repeat the signature but not the defaults. Deleting the expects therefore
drops the defaults — so before deleting we record them and re-inject them afterwards by
matching parameter names and types, which are identical between expect and actual.
"""
import re
import sys
from pathlib import Path

_DECL_START = (
	"internal|public|private|protected|final|open|abstract|sealed|value|enum|annotation|data|"
	"actual|expect|override|operator|infix|inline|suspend|tailrec|external|companion|"
	"constructor|class|interface|object|fun|val|var|typealias"
)

def strip_actual(text: str) -> str:
	# 'actual' is only a modifier when directly followed by a declaration modifier/keyword
	return re.sub(r"\bactual\s+(?=(" + _DECL_START + r")\b)", "", text)


def _match_brace(text: str, open_index: int):
	depth = 0
	for q in range(open_index, len(text)):
		cc = text[q]
		if cc == "{":
			depth += 1
		elif cc == "}":
			depth -= 1
			if depth == 0:
				return q
	return None


def _skip_ws_and_comments(text: str, q: int, n: int) -> int:
	while True:
		while q < n and text[q] in " \t\r\n":
			q += 1
		if text[q:q + 2] == "//":
			eol = text.find("\n", q)
			q = n if eol < 0 else eol + 1
			continue
		if text[q:q + 2] == "/*":
			depth = 1
			q += 2
			while q < n and depth:
				if text[q:q + 2] == "/*":
					depth += 1
					q += 2
				elif text[q:q + 2] == "*/":
					depth -= 1
					q += 2
				else:
					q += 1
			continue
		return q


def _balanced(text: str, open_paren: int):
	"""Return (params_text, index_after_close) for '(' starting at open_paren."""
	depth = 0
	dangle = 0
	k = open_paren
	in_string = None
	while k < len(text):
		c = text[k]
		if in_string:
			if c == "\\":
				k += 2
				continue
			if c == in_string:
				in_string = None
			k += 1
			continue
		if text[k:k + 2] == "//":
			eol = text.find("\n", k)
			k = len(text) if eol < 0 else eol + 1
			continue
		if text[k:k + 2] == "/*":
			depth_comment = 1
			k += 2
			while k < len(text) and depth_comment:
				if text[k:k + 2] == "/*":
					depth_comment += 1
					k += 2
				elif text[k:k + 2] == "*/":
					depth_comment -= 1
					k += 2
				else:
					k += 1
			continue
		if c in "\"'":
			in_string = c
			k += 1
			continue
		if c == "(":
			depth += 1
		elif c == ")":
			depth -= 1
			if depth == 0:
				return text[open_paren + 1:k], k + 1
		elif c == "<":
			dangle += 1
		elif c == ">":
			dangle = max(0, dangle - 1)
		k += 1
	return text[open_paren + 1:], len(text)


def _split_top_level(params: str):
	"""Split a parameter list on top-level commas (depth/comment/string aware)."""
	parts, depth, k, start = [], 0, 0, 0
	dangle = 0
	in_string = None
	while k < len(params):
		c = params[k]
		if in_string:
			if c == "\\":
				k += 2
				continue
			if c == in_string:
				in_string = None
			k += 1
			continue
		if params[k:k + 2] == "//":
			eol = params.find("\n", k)
			k = len(params) if eol < 0 else eol + 1
			continue
		if params[k:k + 2] == "/*":
			depth_comment = 1
			k += 2
			while k < len(params) and depth_comment:
				if params[k:k + 2] == "/*":
					depth_comment += 1
					k += 2
				elif params[k:k + 2] == "*/":
					depth_comment -= 1
					k += 2
				else:
					k += 1
			continue
		if c in "\"'":
			in_string = c
		elif c == "(":
			depth += 1
		elif c == ")":
			depth -= 1
		elif c == "<":
			dangle += 1
		elif c == ">":
			dangle = max(0, dangle - 1)
		elif c == "[":
			depth += 1
		elif c == "]":
			depth -= 1
		elif c == "{":
			depth += 1
		elif c == "}":
			depth -= 1
		elif c == "," and depth == 0 and dangle == 0:
			parts.append(params[start:k])
			start = k + 1
		k += 1
	parts.append(params[start:])
	return parts


_PARAM_RE = re.compile(r"^(?P<lead>(?:\s|@[A-Za-z_][\w.]*(?:\((?:[^()\n]|\([^()\n]*\))*\))?|vararg|override|crossinline|noinline|reified)*?)\s*(?:vararg\s+)?(?P<name>[A-Za-z_][\w`]*|`[^`]+`)\s*:\s*(?P<rest>.*)$", re.S)
_NAME_RE = re.compile(r"(?:fun|class|interface|object|val|var|typealias)\s+(?P<name>[A-Za-z_][\w`]*|`[^`]+`)")


def _strip_annotations(text: str) -> str:
	return re.sub(r"@[A-Za-z_][\w.]*(\([^\n)]*\)|\[[^\]\n]*\])?", " ", text)


def _strip_comments(text: str) -> str:
	"""Remove // and /* */ comments while preserving string literals."""
	out = []
	k = 0
	n = len(text)
	in_string = None
	while k < n:
		c = text[k]
		if in_string:
			out.append(c)
			if c == "\\":
				if k + 1 < n:
					out.append(text[k + 1])
				k += 2
				continue
			if c == in_string:
				in_string = None
			k += 1
			continue
		if text[k:k + 2] == "//":
			eol = text.find("\n", k)
			if eol < 0:
				return "".join(out)
			out.append("\n")
			k = eol + 1
			continue
		if text[k:k + 2] == "/*":
			depth = 1
			k += 2
			while k < n and depth:
				if text[k:k + 2] == "/*":
					depth += 1
					k += 2
					continue
				if text[k:k + 2] == "*/":
					depth -= 1
					k += 2
					continue
				k += 1
			continue
		if c in "\"'":
			in_string = c
			out.append(c)
			k += 1
			continue
		out.append(c)
		k += 1
	return "".join(out)


def _parse_param(p: str):
	"""Return (name, type_text, default_or_None) or None if not a typed parameter."""
	p = _strip_comments(p)
	m = _PARAM_RE.match(p)
	if not m:
		return None
	rest = m.group("rest")
	# find top-level '=' separating type from default
	depth = 0
	dangle = 0
	in_string = None
	k = 0
	eq = None
	while k < len(rest):
		c = rest[k]
		if in_string:
			if c == "\\":
				k += 2
				continue
			if c == in_string:
				in_string = None
			k += 1
			continue
		if text_pair(rest, k) == "//":
			eol = rest.find("\n", k)
			k = len(rest) if eol < 0 else eol + 1
			continue
		if c in "\"'":
			in_string = c
		elif c == "(":
			depth += 1
		elif c == ")":
			depth -= 1
		elif c == "<":
			dangle += 1
		elif c == ">":
			dangle = max(0, dangle - 1)
		elif c == "{":
			depth += 1
		elif c == "}":
			depth -= 1
		elif c == "=" and depth == 0 and dangle == 0:
			# avoid matching '==' '<=' '>=' '+='
			prev = rest[k - 1] if k else ' '
			nxt = rest[k + 1] if k + 1 < len(rest) else ' '
			if prev not in "=!<>+-*/%&|" and nxt != "=":
				eq = k
				break
		k += 1
	if eq is None:
		return (m.group("name"), rest.strip(), None)
	return (m.group("name"), rest[:eq].strip(), re.sub(r",\s*$", "", rest[eq + 1:].strip()))


def text_pair(text, k):
	return text[k:k + 2]


def _norm_type(t: str) -> str:
	return re.sub(r"\s+", "", t)


def _find_expects(text: str, lo: int, hi: int, parent: str, out: list, regions: list):
	"""Collect expect declarations in text[lo:hi]; record defaults; append delete regions."""
	n = hi
	pos = lo
	token = re.compile(r"(?m)^(?:[ \t]*(?:@[A-Za-z_][\w.:]*(?:\((?:[^()\n]|\([^()\n]*\))*\))?[ \t]*|(?:internal|public|private|protected|final|open|abstract|sealed|value|enum|annotation|data|override|operator|infix|inline|suspend|tailrec|external)[ \t]+)*)expect[ \\t]+")
	while True:
		m = token.search(text, pos, hi)
		if not m:
			return
		start = m.start()
		body_open = None
		body_close = None
		j = m.end()
		dparen = dangle = dbrack = 0
		in_string = None
		in_comment = False
		in_block_comment = 0
		k = j
		region_end = None
		while k < n:
			c = text[k]
			if in_comment:
				if c == "\n":
					in_comment = False
				k += 1
				continue
			if in_block_comment:
				if text[k:k + 2] == "/*":
					in_block_comment += 1
					k += 2
					continue
				if text[k:k + 2] == "*/":
					in_block_comment -= 1
					k += 2
					continue
				k += 1
				continue
			if in_string:
				if c == "\\":
					k += 2
					continue
				if c == in_string:
					in_string = None
				k += 1
				continue
			if text[k:k + 2] == "//":
				in_comment = True
				k += 2
				continue
			if text[k:k + 2] == "/*":
				in_block_comment = 1
				k += 2
				continue
			if c in "\"'":
				in_string = c
				k += 1
				continue
			if c == "(":
				dparen += 1
			elif c == ")":
				dparen -= 1
			elif c == "<":
				dangle += 1
			elif c == ">":
				dangle = max(0, dangle - 1)
			elif c == "[":
				dbrack += 1
			elif c == "]":
				dbrack -= 1
			elif c == "{":
				if dparen == 0 and dangle == 0 and dbrack == 0:
					body_open = k
					body_close = _match_brace(text, k)
					if body_close is None:
						body_close = n - 1
					region_end = body_close + 1
					break
			elif c == "\n" and dparen == 0 and dangle == 0 and dbrack == 0:
				# Declaration may continue on the following line (constructor, supertype list,
				# default value, ...). Two heuristics cover the legal layouts:
				#  a) the previous significant character is a trailing connector (:,=(,< , or ->);
				#  b) the next line starts with constructor/companion/:/{/@.
				jj = _skip_ws_and_comments(text, k + 1, n)
				nxt = text[jj:jj + 64]
				prev_idx = k - 1
				while prev_idx >= 0 and text[prev_idx] in " \t\r":
					prev_idx -= 1
				prevc = text[prev_idx] if prev_idx >= 0 else ""
				arrow = prev_idx >= 1 and text[prev_idx - 1:prev_idx + 1] == "->"
				trailing_connector = prevc in ":,=(<" or arrow
				next_continuation = re.match(r"(constructor\b|(internal|public|private|protected)\s+constructor\b|companion\b|:\s*|\{|@)", nxt)
				if trailing_connector or next_continuation:
					k += 1
					continue
				region_end = k + 1
				break
			k += 1
		if region_end is None:
			region_end = n
		decl = text[start:region_end]
		out.append((parent, decl, body_open, body_close))
		regions.append((start, region_end))
		pos = region_end


def _decl_name_and_params(decl: str):
	"""Extract (name, params_text or None, is_ctor_of_class)."""
	stripped = _strip_annotations(decl)
	m = _NAME_RE.search(stripped)
	kind = None
	kind_m = re.search(r"(fun|class|interface|object|val|var|typealias|constructor)", stripped)
	if kind_m:
		kind = kind_m.group(1)
	name = m.group("name") if m else None
	parent = None
	if kind == "class":
		# expect class Foo(params...)
		parent = name
	p_idx = decl.find("(")
	if p_idx < 0:
		return (kind, name, None, parent)
	params, _ = _balanced(decl, p_idx)
	# ensure the '(' we matched belongs before any body
	return (kind, name, params, parent)


def record_defaults(decl: str, parent: str, defaults: dict):
	kind, name, params, parent_name = _decl_name_and_params(decl)
	if params is None:
		return
	if kind in ("val", "var", "typealias", "object"):
		return
	if kind == "constructor":
		key_name = parent
		kind = "ctor"
	else:
		key_name = name
	if kind in ("class", "interface"):
		key_name = "ctor:" + (name or "")
	else:
		key_name = "fun:" + (name or "")
	for p in _split_top_level(params):
		pparsed = _parse_param(p)
		if not pparsed:
			continue
		pname, ptype, default = pparsed
		if default is None:
			continue
		key = (key_name, pname, _norm_type(ptype))
		defaults.setdefault(key, []).append(default)


_MEMBER_TOKEN = re.compile(r"(?m)^(?:[ \t]*(?:@[A-Za-z_][\w.:]*(?:\((?:[^()\n]|\([^()\n]*\))*\))?[ \t]*|(?:internal|public|private|protected|final|open|abstract|sealed|value|enum|annotation|data|override|operator|infix|inline|suspend|tailrec|external|actual)[ \t]+)*)(fun|constructor|class|interface|object|val|var)[ \\t]*")


def collect_member_defaults(text: str, lo: int, hi: int, defaults: dict):
	"""Record defaults of declarations inside an expect body. Members of expect classes and
	interfaces are implicitly `expect` themselves and do NOT carry the keyword."""
	pos = lo
	while True:
		m = _MEMBER_TOKEN.search(text, pos, hi)
		if not m:
			return
		kind = m.group(1)
		after = m.end()
		rest = text[after:after + 200]
		name_m = re.match(r"([A-Za-z_][\w`]*|`[^`]+`)", rest)
		if kind in ("val", "var"):
			pos = after
			continue
		name = name_m.group(1) if name_m else None
		p_start = after + (name_m.end() if name_m else 0)
		# skip type parameters
		q = p_start
		ndangle = 0
		while q < hi:
			c = text[q]
			if c == "<":
				ndangle += 1
			elif c == ">":
				ndangle = max(0, ndangle - 1)
			elif c == "(" and ndangle == 0:
				break
			elif c == "{" or c == "=" or c == "\n" and False:
				break
			elif c == "\n":
				# allow parameter list on the following lines
				jj = _skip_ws_and_comments(text, q + 1, hi)
				nxt = text[jj:jj + 1]
				if nxt in ("(", "<"):
					q += 1
					continue
				break
			q += 1
		if q >= hi or text[q] != "(":
			# no parameter list (e.g. object declaration) — still recurse its body
			params = None
		else:
			params, _ = _balanced(text, q)
		if params is not None:
			if kind in ("class", "interface", "object"):
				key_name = "ctor:" + (name or "")
			else:
				key_name = "fun:" + (name or "")
			for p in _split_top_level(params):
				pparsed = _parse_param(p)
				if not pparsed:
					continue
				pname, ptype, default = pparsed
				if default is None:
					continue
				defaults.setdefault((key_name, pname, _norm_type(ptype)), []).append(default)
		# recurse into bodies of member classes/functions/objects
		# (expect class members cannot have bodies, but expect interface members might)
		search_from = p_start
		body_match = re.compile(r"\{").search(text, p_start, min(hi, p_start + 20000))
		if body_match:
			body_close = _match_brace(text, body_match.start())
			if body_close is not None and body_close <= hi:
				collect_member_defaults(text, body_match.start() + 1, body_close, defaults)
		pos = after


def _region_parent(decl: str, fallback: str):
	parent_name = None
	m = re.search(r"\b(?:class|interface|object)\s+([A-Za-z_][\w`]*|`[^`]+`)", decl)
	if m:
		parent_name = m.group(1)
	return parent_name or fallback


def scan_defaults(text: str):
	"""First pass: record default parameter values of (implicit and explicit) expect declarations."""
	defaults: dict = {}
	out: list = []
	regions: list = []
	_find_expects(text, 0, len(text), "", out, regions)
	for p, decl, body_open, body_close in out:
		parent_name = _region_parent(decl, p)
		record_defaults(decl, parent_name, defaults)
		if body_open is not None and body_close is not None:
			collect_member_defaults(text, body_open + 1, body_close, defaults)
	return defaults


def merge_defaults(dst: dict, src: dict):
	for k, v in src.items():
		dst.setdefault(k, []).extend(v)


def delete_expects(text: str):
	regions: list = []
	out: list = []
	_find_expects(text, 0, len(text), "", out, regions)
	result = []
	last = 0
	for s_, e in sorted(regions):
		result.append(text[last:s_])
		last = e
	result.append(text[last:])
	return "".join(result)


_ACTUAL_TOKEN = re.compile(r"(?m)^(?:[ \t]*(?:@[A-Za-z_][\w.:]*(?:\((?:[^()\n]|\([^()\n]*\))*\))?[ \t]*|(?:internal|public|private|protected|final|open|abstract|sealed|value|enum|annotation|data|override|operator|infix|inline|suspend|tailrec|external)[ \t]+)*)actual[ \\t]+(?:(?:internal|public|private|protected|final|open|abstract|sealed|override|operator|infix|inline|suspend|tailrec|external)[ \\t]+)*[ \\t]*(fun|constructor|class|interface|object|val|var)[ \\t]*")


def _inject_into_params(params: str, key_name: str, defaults: dict):
	parts = _split_top_level(params)
	changed = False
	new_parts = []
	for p in parts:
		pparsed = _parse_param(p)
		if not pparsed:
			new_parts.append(p)
			continue
		pname, ptype, default = pparsed
		if default is not None:
			new_parts.append(p)
			continue
		key = (key_name, pname, _norm_type(ptype))
		candidates = defaults.get(key)
		if not candidates:
			new_parts.append(p)
			continue
		if len(set(candidates)) > 1:
			# ambiguous default from unrelated same-named declarations; skip safely
			new_parts.append(p)
			continue
		new_parts.append(p.rstrip() + " = " + candidates[0])
		changed = True
	return ", ".join(new_parts) if changed else params


def inject_defaults(text: str, defaults: dict):
	if not defaults:
		return text
	token = _ACTUAL_TOKEN
	out = []
	last = 0
	for m in token.finditer(text):
		kind = m.group(1)
		after = m.end()
		rest = text[after:after + 200]
		name_m = re.match(r"([A-Za-z_][\w`]*|`[^`]+`)", rest)
		if kind in ("class", "interface", "object"):
			if not name_m:
				continue
			key_name = "ctor:" + name_m.group(1)
			p_start = after + name_m.end()
		elif kind == "fun":
			if not name_m:
				continue
			key_name = "fun:" + name_m.group(1)
			p_start = after + name_m.end()
		elif kind == "constructor":
			prefix = text[:m.start()]
			cls = None
			for cm in re.finditer(r"\b(?:class|interface|object)\s+([A-Za-z_][\w`]*|`[^`]+`)", prefix):
				cls = cm.group(1)
			if not cls:
				continue
			key_name = "ctor:" + cls
			p_start = after
		else:
			continue  # val/var: no parameter defaults to inject
		# locate the parameter list '(' — skipping optional type parameters <...>
		q = p_start
		ndangle = 0
		while q < len(text):
			c = text[q]
			if c == "<":
				ndangle += 1
			elif c == ">":
				ndangle = max(0, ndangle - 1)
			elif c == "(" and ndangle == 0:
				break
			elif c == "{":
				q = -1
				break  # no primary constructor params
			elif c == "\n" and ndangle == 0:
				jj = _skip_ws_and_comments(text, q + 1, len(text))
				nxt = text[jj:jj + 48]
				if re.match(r"(constructor\b|\(|:\s*|\{|<)", nxt):
					q += 1
					continue
				q = -1
				break
			q += 1
		if q < 0 or q >= len(text) or text[q] != "(":
			continue
		params, after_close = _balanced(text, q)
		new_params = _inject_into_params(params, key_name, defaults)
		if new_params == params:
			continue
		out.append(text[last:q + 1])
		out.append(new_params)
		last = after_close - 1  # index of closing paren; stays in the tail
	if not out:
		return text
	out.append(text[last:])
	return "".join(out)


def join_annotation_lines(text: str) -> str:
	"""Merge lines that contain ONLY a single annotation into the following line.
		"@JvmOverloads\n  public actual fun f(...)" becomes "@JvmOverloads public actual fun f(...)"
	Kotlin annotations may precede the declaration on the previous line; folding them keeps the
	expect/actual token matcher single-line friendly. Semantics are unchanged (annotations attach
	to the following declaration either way). Lines starting with @ inside KDoc are untouched
	(KDoc content lines start with '*')."""
	pattern = re.compile(r"(?m)^([ \t]*)@([A-Za-z_][\w.:]*)(\((?:[^()\n]|\([^()\n]*\))*\))?[ \t]*\r?\n[ \t]*")
	while True:
		new = pattern.sub(lambda m: m.group(1) + "@" + m.group(2) + (m.group(3) or "") + " ", text)
		if new == text:
			return text
		text = new


def strip_js_only(text: str) -> str:
	"""Drop kotlin.js.* imports and @Js* annotations — JS-only optional-expectation annotations
	that cannot be compiled as JVM/regular code."""
	text = re.sub(r"(?m)^import kotlin\.js\.[^\n]*\r?\n", "", text)
	text = re.sub(r"@Js(Name|Export|Module)\((?:[^()\n]|\([^()\n]*\))*\)[ \t]*", "", text)
	return text


def flatten_text(text: str, defaults: dict):
	text = delete_expects(text)
	text = inject_defaults(text, defaults)
	text = strip_actual(text)
	return text


def main():
	if len(sys.argv) < 3:
		sys.exit("usage: flatten-mpp.py <dest-dir> <src-dir> [<src-dir> ...]")
	dest_root = Path(sys.argv[1])
	files = []
	for src in sys.argv[2:]:
		src = Path(src)
		for path in src.rglob("*.kt"):
			rel = path.relative_to(src)
			files.append((dest_root / src.name / rel, join_annotation_lines(strip_js_only(path.read_text(encoding="utf-8")))))
	defaults: dict = {}
	for _, text in files:
		merge_defaults(defaults, scan_defaults(text))
	for dst, text in files:
		if dst.exists():
			dst.unlink()
		dst.parent.mkdir(parents=True, exist_ok=True)
		dst.write_text(flatten_text(text, defaults), encoding="utf-8")


if __name__ == "__main__":
	main()
