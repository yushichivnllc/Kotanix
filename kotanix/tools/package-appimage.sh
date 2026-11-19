#!/usr/bin/env bash
# Builds Kotanix-<version>-x86_64.AppImage fully from pinned git sources — no prebuilt
# binaries are downloaded (GitHub release-asset redirects are blocked in the build sandbox,
# and we prefer sources anyway: everything here is reviewable and hash-pinnable).
#
# Toolchain needed: gcc, make, patch, python3 + venv (for meson/ninja/pkgconf, fetched
# from PyPI at build time), git. Temurin JRE + Kotlin must be present (see
# tools/setup-toolchain.sh) and dependency jars must be built (tools/build-deps.sh).
#
#   bash tools/package-appimage.sh
#
# Output: build/dist/Kotanix-<version>-x86_64.AppImage
#
# Layout: build/appimage/{srcs,deps}  (sources cached there — delete to refetch)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
VERSION="${KOTANIX_VERSION:-0.1.0}"
OUT_DIR="$ROOT/build/dist"
OUT="$OUT_DIR/Kotanix-${VERSION}-x86_64.AppImage"
WORK="$ROOT/build/appimage"
DEPS="$WORK/deps"
SRCS="${APPIMAGE_SRCS:-$WORK/srcs}"
VENV="$WORK/venv"

# ---- pinned sources (commit == tag for all of these) ----------------------------------
ZLIB_COMMIT=51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf      # madler/zlib v1.3.1
ZSTD_COMMIT=f8745da6ff1ad1e7bab384bd1f9d742439278e99      # meta/zstd v1.5.7
LIBFUSE_COMMIT=6d08472ea47db895748d5ca7d3daf032c3fefcf8   # libfuse/libfuse fuse-3.15.0
SQUASHFUSE_COMMIT=775b4cc72ab47641637897f11ce0da15d5c1f115 # vasi/squashfuse 0.5.2
SQFS_TOOLS_COMMIT=d8cb82d9840330f9344ec37b992595b5d7b44184 # plougher/squashfs-tools 4.6.1
RUNTIME_COMMIT=fedfad51acc2860b2916a1eef32ba4a500ff31ef   # AppImage/type2-runtime (main, 2026-10-19)

clone_pinned() { # name repo commit
	local name="$1" repo="$2" commit="$3"
	local dir="$SRCS/$name"
	if [ ! -d "$dir/.git" ]; then
		rm -rf "$dir"
		mkdir -p "$dir"
		git -C "$dir" init -q
		git -C "$dir" remote add origin "https://github.com/$repo.git"
		git -C "$dir" fetch --depth 1 --quiet origin "$commit"
		git -C "$dir" checkout -q FETCH_HEAD
	fi
	local head; head="$(git -C "$dir" rev-parse HEAD)"
	if [ "$head" != "$commit" ]; then
		echo "pin mismatch for $name: expected $commit, got $head" >&2
		exit 1
	fi
	echo "[appimage] sources $name @ $commit"
}

JRE_SRC="${KOTANIX_JRE:-}"
if [ -z "$JRE_SRC" ]; then
	for cand in "$ROOT/.toolchain/jdk" "/home/user/tools/jdk"; do
		[ -d "$cand" ] && JRE_SRC="$cand" && break
	done
fi
KOTLINC="${KOTLINC_HOME:-${ROOT}/.toolchain/kotlinc}/bin/kotlinc"
[ -x "$KOTLINC" ] || KOTLINC="/home/user/tools/kotlinc/bin/kotlinc"
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"

for jar in build/deps/jars/okio-3.16.0.jar build/deps/jars/androidx-collection-1.8.0-alpha01.jar build/deps/jars/okhttp-5.1.0.jar; do
	[ -f "$ROOT/$jar" ] || { echo "missing $ROOT/$jar — run: bash tools/build-deps.sh" >&2; exit 1; }
done
[ -d "$JRE_SRC" ] || { echo "JRE not found — run: bash tools/setup-toolchain.sh" >&2; exit 1; }
[ -x "$KOTLINC" ] || { echo "kotlinc not found — run: bash tools/setup-toolchain.sh" >&2; exit 1; }

mkdir -p "$SRCS" "$DEPS" "$OUT_DIR"
cd "$ROOT"

echo "== [1/8] fetch pinned C sources"
clone_pinned zlib           madler/zlib              "$ZLIB_COMMIT"
clone_pinned zstd           meta/zstd                "$ZSTD_COMMIT"
clone_pinned libfuse        libfuse/libfuse          "$LIBFUSE_COMMIT"
clone_pinned squashfuse     vasi/squashfuse           "$SQUASHFUSE_COMMIT"
clone_pinned squashfs-tools plougher/squashfs-tools  "$SQFS_TOOLS_COMMIT"
clone_pinned type2-runtime  AppImage/type2-runtime   "$RUNTIME_COMMIT"

echo "== [2/8] zlib (static)"
rm -rf "$WORK/zlib-build" "$DEPS/lib/libz.a"
mkdir -p "$WORK/zlib-build"
( cd "$SRCS/zlib" && cp -a . "$WORK/zlib-build/src" && cd "$WORK/zlib-build/src" \
    && CFLAGS="-O2 -fPIE" ./configure --static --prefix="$DEPS" --uname=Linux >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null )

echo "== [3/8] zstd (static, lib only)"
rm -rf "$DEPS/lib/libzstd.a"
make -C "$SRCS/zstd/lib" -j"$(nproc)" libzstd.a CFLAGS="-O2 -fPIE" >/dev/null
mkdir -p "$DEPS/lib" "$DEPS/include"
cp "$SRCS/zstd/lib/libzstd.a" "$DEPS/lib/"
cp "$SRCS/zstd/lib/zstd.h" "$SRCS/zstd/lib/zdict.h" "$SRCS/zstd/lib/zstd_errors.h" "$DEPS/include/"

echo "== [4/8] libfuse 3.15.0 (static, library only)"
if [ ! -x "$VENV/bin/meson" ]; then
	python3 -m venv "$VENV"
	"$VENV/bin/pip" install -q meson ninja pkgconf
fi
if grep -q 'fusermountProg' "$SRCS/libfuse/lib/mount.c"; then
	echo "[appimage] libfuse mount.c already patched — skipping"
else
	patch -d "$SRCS/libfuse" -p1 -s < "$SRCS/type2-runtime/patches/libfuse/mount.c.diff"
fi
export PATH="$VENV/bin:$PATH" PKG_CONFIG_PATH="$DEPS/lib/pkgconfig:$DEPS/share/pkgconfig"
rm -rf "$SRCS/libfuse/build"
meson setup "$SRCS/libfuse/build" "$SRCS/libfuse" \
	--prefix="$DEPS" --default-library=static --buildtype=release -Db_pie=true \
	-Dutils=false -Dexamples=false -Dtests=false -Duseroot=false >/dev/null
ninja -C "$SRCS/libfuse/build" >/dev/null
ninja -C "$SRCS/libfuse/build" install >/dev/null

echo "== [5/8] squashfuse 0.5.2 (static, manual compile)"
SQ="$SRCS/squashfuse"
cat > "$SQ/config.h" <<'EOF'
#ifndef SQFS_CONFIG_H
#define SQFS_CONFIG_H
#define HAVE_CONFIG_H 1
#define FUSE_USE_VERSION 32
#define HAVE_LINUX_TYPES_LE16 1
#define HAVE_ZLIB_H 1
#define HAVE_ZSTD_H 1
#define SQFS_MULTITHREADED 1
#define PACKAGE_NAME "squashfuse"
#define PACKAGE_TARNAME "squashfuse"
#define PACKAGE_VERSION "0.5.2"
#define PACKAGE_STRING "squashfuse 0.5.2"
#define VERSION "0.5.2"
#define HAVE_DECL_FUSE_CMDLINE_HELP 1
#define HAVE_DECL_FUSE_ADD_DIRENTRY 1
#define HAVE_DECL_FUSE_DAEMONIZE 1
#define HAVE_FUSE_LL_FORGET_OP_64T 1
#define HAVE_SYS_SYSMACROS_H 1
#endif
EOF
( cd "$SQ" && SED=sed sh ./gen_swap.sh ./squashfs_fs.h )
SQ_OBJDIR="$WORK/squashfuse-objs"
rm -rf "$SQ_OBJDIR"; mkdir -p "$SQ_OBJDIR"
SQ_CFLAGS="-I$SQ -I$DEPS/include -I$DEPS/include/fuse3 -Os -fPIC -fno-strict-aliasing"
for src in swap.c cache.c table.c dir.c file.c fs.c decompress.c xattr.c hash.c stack.c traverse.c util.c \
           nonstd-pread.c nonstd-stat.c cache_mt.c \
           nonstd-makedev.c nonstd-enoattr.c stat.c \
           ll.c ll_inode.c nonstd-daemon.c; do
	gcc $SQ_CFLAGS -c "$SQ/$src" -o "$SQ_OBJDIR/${src%.c}.o"
done
# fuseprivate.c calls fuse_parse_cmdline (deprecated); fuse_lowlevel.h declares it and maps it to the versioned symbol
gcc $SQ_CFLAGS -DFUSE_USE_VERSION=32 -include fuse_lowlevel.h -c "$SQ/fuseprivate.c" -o "$SQ_OBJDIR/fuseprivate.o"
rm -f "$DEPS/lib/libsquashfuse_all.a"
( cd "$SQ_OBJDIR" && ar rcs "$DEPS/lib/libsquashfuse_all.a" ./*.o )
mkdir -p "$DEPS/include/squashfuse"
cp "$SQ"/*.h "$DEPS/include/squashfuse/"

echo "== [6/8] type2-runtime"
RUNTIME_OUT="$WORK/runtime"
gcc -std=gnu99 -Os -D_FILE_OFFSET_BITS=64 -DGIT_COMMIT="\"${RUNTIME_COMMIT:0:7}\"" \
	-T "$SRCS/type2-runtime/src/runtime/data_sections.ld" \
	-ffunction-sections -fdata-sections -Wl,--gc-sections \
	-I"$DEPS/include" -I"$DEPS/include/fuse3" \
	-static -Wall \
	"$SRCS/type2-runtime/src/runtime/runtime.c" \
	-L"$DEPS/lib" -L"$DEPS/lib/x86_64-linux-gnu" -lsquashfuse_all -lzstd -lz -lfuse3 -lpthread -ldl -lrt \
	-o "$RUNTIME_OUT"
echo "[appimage] runtime: $RUNTIME_OUT ($(wc -c < "$RUNTIME_OUT") bytes)"

echo "== [7/8] squashfs-tools 4.6.1 (gzip-only mksquashfs)"
make -C "$SRCS/squashfs-tools/squashfs-tools" -j"$(nproc)" mksquashfs \
	EXTRA_CFLAGS="-I$DEPS/include" EXTRA_LDFLAGS="-L$DEPS/lib" >/dev/null
MKSQUASHFS="$SRCS/squashfs-tools/squashfs-tools/mksquashfs"
[ -x "$MKSQUASHFS" ] || { echo "mksquashfs failed to build" >&2; exit 1; }

echo "== [8/8] assemble AppDir + package"
export PATH="$JRE_SRC/bin:$PATH"
CLASSES="$WORK/pkg-classes"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
"$KOTLINC" -jvm-target 21 -language-version 2.2 -no-reflect \
	-cp "build/deps/jars/okio-3.16.0.jar:build/deps/jars/androidx-collection-1.8.0-alpha01.jar:build/deps/jars/okhttp-5.1.0.jar:$STDLIB" \
	-d "$CLASSES" \
	$(find src/main/kotlin -name '*.kt')

gcc -shared -fPIC -O2 -I"$JRE_SRC/include" -I"$JRE_SRC/include/linux" \
	src/main/c/kotanixsqlite.c -o "$CLASSES/libkotanixsqlite.so" -l:libsqlite3.so.0

APPDIR="$WORK/AppDir"
rm -rf "$APPDIR"; mkdir -p "$APPDIR/usr/lib/kotanix/lib" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/doc/kotanix"

# app payload — move the native lib out of CLASSES first so it is not jarred
mv "$CLASSES/libkotanixsqlite.so" "$APPDIR/usr/lib/kotanix/lib/"
python3 tools/make-jar.py "$CLASSES" "$APPDIR/usr/lib/kotanix/lib/kotanix-app.jar" \
	Main-Class=kotanix.ui.KotanixKt Implementation-Version="$VERSION"
cp build/deps/jars/okio-3.16.0.jar build/deps/jars/androidx-collection-1.8.0-alpha01.jar \
   build/deps/jars/okhttp-5.1.0.jar "$STDLIB" "$APPDIR/usr/lib/kotanix/lib/"
cp build/deps/jars/okio.LICENSE build/deps/jars/okhttp.LICENSE "$APPDIR/usr/share/doc/kotanix/" || true
cp THIRD_PARTY_NOTICES.md UPSTREAM.lock "$APPDIR/usr/share/doc/kotanix/"
cp -a "$JRE_SRC" "$APPDIR/usr/lib/kotanix/jre"

# root entries (AppImage spec): AppRun, .desktop (name must match Icon), icon
install -m 755 packaging/kotanix-apprun "$APPDIR/AppRun"
cp packaging/kotanix.desktop "$APPDIR/kotanix.desktop"
cp packaging/icons/kotanix-256.png "$APPDIR/kotanix.png"
cp packaging/icons/kotanix-256.png "$APPDIR/.DirIcon"
cp packaging/kotanix.desktop "$APPDIR/usr/share/applications/kotanix.desktop"
for size in 16 32 48 64 128 256 512; do
	dir="$APPDIR/usr/share/icons/hicolor/${size}x${size}/apps"
	mkdir -p "$dir"
	cp "packaging/icons/kotanix-${size}.png" "$dir/kotanix.png"
done
cat > "$APPDIR/usr/share/doc/kotanix/copyright" <<'EOF'
Upstream: Kotatsu-Redo 9.8.7 (GPL-3.0-only), see UPSTREAM.lock for exact pins.
Kotanix:  GPL-3.0-only. This file is a short pointer, not legal advice.
Full notices: /usr/share/doc/kotanix/THIRD_PARTY_NOTICES.md (bundled AppImage:
usr/share/doc/kotanix/). Build recipe: UPSTREAM.lock.
EOF

# squashfs image: gzip, read-only, owners zeroed for reproducibility
rm -f "$WORK/kotanix.squashfs"
"$MKSQUASHFS" "$APPDIR" "$WORK/kotanix.squashfs" \
	-comp gzip -noappend -no-xattrs -all-root -processors 1 >/dev/null

cat "$RUNTIME_OUT" "$WORK/kotanix.squashfs" > "$OUT"
chmod 755 "$OUT"
echo "== wrote $OUT ($(wc -c < "$OUT") bytes)"
echo "Test: $OUT --appimage-extract        # list payload"
echo "      $OUT --selftest=<fixture.bak>  # run headless self-test"
echo "      APPIMAGE_EXTRACT_AND_RUN=1 $OUT # run without FUSE"
