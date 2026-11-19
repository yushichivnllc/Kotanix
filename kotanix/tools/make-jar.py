#!/usr/bin/env python3
"""Create a JAR deterministically from a class directory (the JRE has no `jar` tool).

Usage: make-jar.py <classes-dir> <out.jar> [Name=Value ...]  (Name=Value pairs go to MANIFEST.MF)
"""
import sys
import zipfile
import pathlib
import hashlib

def main() -> int:
	classes_dir = pathlib.Path(sys.argv[1])
	out = pathlib.Path(sys.argv[2])
	manifest_lines = ["Manifest-Version: 1.0"]
	for kv in sys.argv[3:]:
		k, _, v = kv.partition("=")
		manifest_lines.append(f"{k}: {v}")
	manifest = "\r\n".join(manifest_lines) + "\r\n"
	files = sorted(p for p in classes_dir.rglob("*") if p.is_file())
	out.parent.mkdir(parents=True, exist_ok=True)
	with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
		zi = zipfile.ZipInfo("META-INF/MANIFEST.MF")
		zi.date_time = (1980, 1, 1, 0, 0, 0)
		zf.writestr(zi, manifest)
		for f in files:
			rel = f.relative_to(classes_dir).as_posix()
			if rel == "META-INF/MANIFEST.MF":
				continue
			zi = zipfile.ZipInfo(rel)
			zi.date_time = (1980, 1, 1, 0, 0, 0)
			zf.writestr(zi, f.read_bytes())
	print(out, len(files) + 1, "entries")
	return 0

if __name__ == "__main__":
	sys.exit(main())
