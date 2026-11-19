#!/bin/sh
# Build kotanix_<version>_amd64.deb — fully offline.
#
#   bash tools/package-deb.sh            # bundled Temurin JRE (self-contained, ~100 MB)
#   KOTANIX_SYSTEM_JRE=1 bash tools/package-deb.sh   # small package, Depends on openjdk-21
#
# Requires: bash tools/setup-toolchain.sh đã chạy, build-deps.sh đã build xong jars trong build/deps/jars/.
# Output: kotanix/build/dist/kotanix_0.1.0_amd64.deb
set -eu

cd "$(dirname "$0")/.."   # kotanix/
ROOT="$(pwd)"
VERSION="${KOTANIX_VERSION:-0.1.0}"
SYSTEM_JRE="${KOTANIX_SYSTEM_JRE:-0}"
DIST="$ROOT/build/dist"
PKG="$DIST/pkg-root"
if [ "$SYSTEM_JRE" = "1" ]; then OUT="$DIST/kotanix_${VERSION}~systemjre_amd64.deb"; else OUT="$DIST/kotanix_${VERSION}_amd64.deb"; fi

TOOLS_DIR="${KOTANIX_TOOLS:-/home/user/tools}"
JRE_SRC="$TOOLS_DIR/jdk"                       # Temurin JRE unpacked by setup-toolchain.sh
KOTLINC="$TOOLS_DIR/kotlinc/bin/kotlinc"
STDLIB="$TOOLS_DIR/kotlinc/lib/kotlin-stdlib.jar"

for jar in build/deps/jars/okio-3.16.0.jar build/deps/jars/androidx-collection-1.8.0-alpha01.jar build/deps/jars/okhttp-5.1.0.jar; do
	[ -f "$jar" ] || { echo "missing $jar — run: bash tools/build-deps.sh" >&2; exit 1; }
done
[ -x "$KOTLINC" ] || { echo "missing kotlinc — run: bash tools/setup-toolchain.sh" >&2; exit 1; }
[ -d "$JRE_SRC" ] || { echo "missing JRE at $JRE_SRC — run: bash tools/setup-toolchain.sh" >&2; exit 1; }

rm -rf "$DIST"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin" "$PKG/usr/lib/kotanix/lib" \
	"$PKG/usr/share/applications" "$PKG/usr/share/doc/kotanix"

# ---------------------------------------------------------------- compile
echo "== compile kotanix sources"
CLASSES="/tmp/kotanix-pkg-classes"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
export PATH="$TOOLS_DIR/jdk/bin:$PATH"
"$KOTLINC" -jvm-target 21 -language-version 2.2 -no-reflect \
	-cp "build/deps/jars/okio-3.16.0.jar:build/deps/jars/androidx-collection-1.8.0-alpha01.jar:build/deps/jars/okhttp-5.1.0.jar:$STDLIB" \
	-d "$CLASSES" \
	$(find src/main/kotlin -name '*.kt')

# ---------------------------------------------------------------- native lib
echo "== compile libkotanixsqlite.so"
gcc -shared -fPIC -O2 -Wl,-z,defs \
	-I"$JRE_SRC/include" -I"$JRE_SRC/include/linux" \
	src/main/c/kotanixsqlite.c \
	-o "$PKG/usr/lib/kotanix/lib/libkotanixsqlite.so" \
	-l:libsqlite3.so.0

# ---------------------------------------------------------------- app jar + libs
echo "== package jars"
python3 tools/make-jar.py "$CLASSES" "$PKG/usr/lib/kotanix/lib/kotanix-app.jar" \
	Main-Class=kotanix.ui.KotanixKt Implementation-Version="$VERSION"
cp build/deps/jars/okio-3.16.0.jar build/deps/jars/androidx-collection-1.8.0-alpha01.jar \
   build/deps/jars/okhttp-5.1.0.jar "$STDLIB" "$PKG/usr/lib/kotanix/lib/"
cp build/deps/jars/okio.LICENSE build/deps/jars/okhttp.LICENSE "$PKG/usr/share/doc/kotanix/" || true
cp THIRD_PARTY_NOTICES.md UPSTREAM.lock "$PKG/usr/share/doc/kotanix/"

# ---------------------------------------------------------------- JRE bundle
if [ "$SYSTEM_JRE" = "1" ]; then
	echo "== system JRE package (Depends: openjdk-21)"
	DEPENDS_LINE="Depends: libsqlite3-0 (>= 3.34), ca-certificates, openjdk-21-jre-headless | default-jre-headless"
else
	echo "== bundling Temurin JRE from $JRE_SRC"
	cp -a "$JRE_SRC" "$PKG/usr/lib/kotanix/jre"
	# drop build-time baggage the runtime does not need
	rm -f "$PKG/usr/lib/kotanix/jre/release".tmp || true
	DEPENDS_LINE="Depends: libsqlite3-0 (>= 3.34), ca-certificates"
fi

# ---------------------------------------------------------------- icons + desktop
for size in 16 32 48 64 128 256 512; do
	dir="$PKG/usr/share/icons/hicolor/${size}x${size}/apps"
	mkdir -p "$dir"
	cp "packaging/icons/kotanix-${size}.png" "$dir/kotanix.png"
done
cp packaging/kotanix.desktop "$PKG/usr/share/applications/kotanix.desktop"
chmod 644 "$PKG/usr/share/applications/kotanix.desktop"

# ---------------------------------------------------------------- launcher
install -m 755 packaging/kotanix-launcher "$PKG/usr/bin/kotanix"

# ---------------------------------------------------------------- control + maintainer scripts
INSTALLED_SIZE=$(du -ks "$PKG" | cut -f1)
cat > "$PKG/DEBIAN/control" <<EOF
Package: kotanix
Version: ${VERSION}
Section: graphics
Priority: optional
Architecture: amd64
Installed-Size: ${INSTALLED_SIZE}
${DEPENDS_LINE}
Maintainer: yushichivnllc <noreply@users.noreply.github.com>
Homepage: https://github.com/yushichivnllc/Kotanix
Description: Local-first manga reader — Linux port of Kotatsu
 Kotanix is a Linux-native (Kotlin/Swing) port of Kotatsu-Redo 9.8.7 (GPL-3.0).
 It reads local manga (folders, CBZ and nested CBZ archives) and restores/creates
 Android Kotatsu-compatible .bak backups (the Kotanix-to-Android direction is not
 yet verified on a real Android device). Online sources are NOT included in this
 build. UI is in Vietnamese and mirrors the Android layout (Favourites, Local,
 History tabs). Bundles a Temurin JRE unless built with KOTANIX_SYSTEM_JRE=1.
EOF

cat > "$PKG/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
	update-desktop-database -q /usr/share/applications || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
	gtk-update-icon-cache -q -t /usr/share/icons/hicolor || true
fi
EOF
chmod 755 "$PKG/DEBIAN/postinst"
cp "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/postrm"

mkdir -p "$PKG/usr/share/doc/kotanix"
cat > "$PKG/usr/share/doc/kotanix/copyright" <<'EOF'
Upstream: Kotatsu-Redo 9.8.7 (GPL-3.0-only), see UPSTREAM.lock for exact pins.
Kotanix:  GPL-3.0-only. This file is a short pointer, not legal advice.
Full notices: /usr/share/doc/kotanix/THIRD_PARTY_NOTICES.md
Build recipe: /usr/share/doc/kotanix/UPSTREAM.lock
EOF

# ---------------------------------------------------------------- build .deb
echo "== dpkg-deb --build"
chmod -R u+w,go+rX,go-w "$PKG"
dpkg-deb --root-owner-group --build "$PKG" "$OUT"
dpkg-deb --info "$OUT"
echo "== wrote $OUT"
