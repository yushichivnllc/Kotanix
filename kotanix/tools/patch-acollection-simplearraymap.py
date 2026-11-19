#!/usr/bin/env python3
"""Post-flatten patch for the androidx-collection JVM build.

Why: androidx.collection.SimpleArrayMap (commonMain) declares
`public open fun getOrDefault(key: Any?, defaultValue: V): V` on purpose, so that the Java
ArrayMap (which implements java.util.Map and extends SimpleArrayMap) satisfies
java.util.Map.getOrDefault via inheritance. The Kotlin compiler, however, also models the
JDK8 default method `getOrDefault(K, V)` (mapped through kotlin.collections.MutableMap) as a
member of the same erased JVM signature, and reports an "inherited platform declarations clash"
ERROR for the Kotlin port of ArrayMap (see thirdparty/androidx-collection-overlay/.../ArrayMap.kt).
The collision is purely a mapped-type artifact.

Patch: rename the SimpleArrayMap method to `getOrDefaultArrayMap`. No other source in
androidx-collection or in the Kotanix sources calls it directly (all internal uses go through
the private getOrDefaultInternal helper), so the rename is behaviour-neutral. The
java.util.Map JDK8 default method then applies for the MutableMap path and delegates to
get(key), which is semantically identical to the original implementation
(get(key) ?: defaultValue).

Usage: patch-acollection-simplearraymap.py <acollection-flat-dir>
"""
import sys
from pathlib import Path

OLD = "    public open fun getOrDefault(key: Any?, defaultValue: V): V {"
NEW = "    // Renamed from getOrDefault by the Kotanix offline build (see thirdparty overlay).\n    public open fun getOrDefaultArrayMap(key: Any?, defaultValue: V): V {"

def main():
    if len(sys.argv) != 2:
        sys.exit("usage: patch-acollection-simplearraymap.py <acollection-flat-dir>")
    target = Path(sys.argv[1]) / "commonMain/kotlin/androidx/collection/SimpleArrayMap.kt"
    text = target.read_text(encoding="utf-8")
    text = text.replace('    @JvmName("getOrDefaultArrayMap")\n', "")  # drop interim @JvmName if present
    if NEW in text:
        return  # already patched
    if text.count(OLD) != 1:
        sys.exit(f"expected exactly one occurrence in {target}, found {text.count(OLD)}")
    target.write_text(text.replace(OLD, NEW), encoding="utf-8")

if __name__ == "__main__":
    main()
