#!/usr/bin/env bash
# Builds the vendored dependency jars for Kotanix entirely offline (after the initial fetch).
#
# What it does:
#   1. Verifies (or fetches) the pinned source tarballs listed in UPSTREAM.lock §6.
#   2. Flattens the Kotlin Multiplatform expect/actual structure of okio and androidx.collection
#      for a plain-JVM kotlinc compile (tools/flatten-mpp.py), and applies the
#      SimpleArrayMap.getOrDefault rename (tools/patch-acollection-simplearraymap.py).
#   3. Compiles okhttp 5.1.0 (commonJvmAndroid + jvmMain) against the okio jar and the
#      compile-only stubs in thirdparty/okhttp-stubs (the optional providers Conscrypt,
#      BouncyCastle, OpenJSSE, GraalVM native-image and animal-sniffer are provided by the
#      end user at runtime, exactly as with Maven `compileOnly`).
#   4. Generates okhttp's IdnaMappingTableInstance.kt from the pinned Unicode table, the same
#      way the upstream Gradle build does (JavaExec GenerateIdnaMappingTableCode), using the
#      driver in tools/gen-idna-mapping-table.kt.
#   5. Packages deterministic jars into build/deps/jars/ via tools/make-jar.py.
#
# Requirements: tools/setup-toolchain.sh has been run (JAVA_HOME + KOTLINC_HOME in env.sh),
# python3, gcc (only for the JNI helper), git (only for the androidx.collection sparse fetch).
#
# Layout produced under <repo>/kotanix/build/deps/:
#   srcs/<pkg>/        extracted sources (disposable)
#   <mod>-flat/        flattened sources (disposable)
#   <mod>-classes/     compiler output (input to the jar packer)
#   jars/              okio-3.16.0.jar, androidx-collection-*.jar, okhttp-5.1.0.jar (+ *.LICENSE)
#
# Exits non-zero on the first failed step. Re-running rebuilds from scratch.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BUILD="$ROOT/build"
REFS="${REFS_DIR:-/home/user/refs/srcs}"
ACOLLECTION_COMMIT="cfa6d5ad3e3333bc4f0a6bdf02a3fa577aea11e2"
ACOLLECTION_DIR="${REFS_DIR:-/home/user/refs}/androidx/collection/collection"

log() { printf '[build-deps] %s\n' "$*"; }

# --- toolchain --------------------------------------------------------------------------
: "${JAVA_HOME:=${KOTANIX_JAVA_HOME:-}}"
: "${KOTLINC_HOME:=${KOTANIX_KOTLINC_HOME:-}}"
if [ -z "$JAVA_HOME" ]; then
  if [ -d "/home/user/tools/jdk" ]; then JAVA_HOME=/home/user/tools/jdk; else
    echo "JAVA_HOME is not set and /home/user/tools/jdk does not exist; run tools/setup-toolchain.sh first" >&2
    exit 1
  fi
fi
if [ -z "$KOTLINC_HOME" ]; then
  if [ -d "/home/user/tools/kotlinc" ]; then KOTLINC_HOME=/home/user/tools/kotlinc; else
    echo "KOTLINC_HOME is not set and /home/user/tools/kotlinc does not exist; run tools/setup-toolchain.sh first" >&2
    exit 1
  fi
fi
export JAVA_HOME
export PATH="$KOTLINC_HOME/bin:$JAVA_HOME/bin:$PATH"
KOTLINC="$KOTLINC_HOME/bin/kotlinc"

# --- pinned sources: (file, sha256, codeload url) --------------------------------------
fetch_verified() { # name sha256 url
  local name="$1" sha="$2" url="$3" dest="$REFS/$1"
  if [ ! -f "$dest" ]; then
    log "fetching $name"
    curl -fSL --retry 3 -o "$dest" "$url"
  fi
  local actual
  actual="$(sha256sum "$dest" | awk '{print $1}')"
  if [ "$actual" != "$sha" ]; then
    echo "sha256 mismatch for $dest:" >&2
    echo "  expected $sha" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
}

mkdir -p "$REFS"
fetch_verified okio-3.16.0.tar.gz   45a3c97d5d51be326cf584bf056ea63f83b76d8b812971118e99c31cb71a59a2 \
  https://codeload.github.com/square/okio/tar.gz/refs/tags/parent-3.16.0
fetch_verified okhttp-5.1.0.tar.gz  5d35da5e140cbfb6f38b2b83083cbe44367db818556d58731ddaa01839ef4cad \
  https://codeload.github.com/square/okhttp/tar.gz/refs/tags/parent-5.1.0
fetch_verified jsoup-1.21.2.tar.gz  6407d606eef462b0f2717bfebed74baad624d5c8a813d3b625ef1d871d08e3ef \
  https://codeload.github.com/jhy/jsoup/tar.gz/refs/tags/jsoup-1.21.2
fetch_verified orgjson-20240303.tar.gz 615a11ebe0f3025d545ee7420b32cbef4351bda6b72ae24a297d5e9f947cb1bd \
  https://codeload.github.com/stleary/JSON-java/tar.gz/refs/tags/20240303

# --- extract ----------------------------------------------------------------------------
rm -rf "$BUILD/deps/srcs"
mkdir -p "$BUILD/deps/srcs"
extract() { # tarball dest
  mkdir -p "$BUILD/deps/srcs/$2"
  tar xzf "$REFS/$1" -C "$BUILD/deps/srcs/$2" --strip-components=1
}
extract okio-3.16.0.tar.gz okio
extract okhttp-5.1.0.tar.gz okhttp

# androidx.collection: sparse git fetch of the single module (the repo is huge; no full clone).
if [ ! -d "$ACOLLECTION_DIR" ]; then
  log "fetching androidx collection module @ $ACOLLECTION_COMMIT (sparse)"
  ACOLLECTION_ROOT="${REFS_DIR:-/home/user/refs}/androidx"
  mkdir -p "$ACOLLECTION_ROOT"
  git init -q "$ACOLLECTION_ROOT"
  git -C "$ACOLLECTION_ROOT" remote add origin https://github.com/androidx/androidx.git
  git -C "$ACOLLECTION_ROOT" config core.sparseCheckout true
  echo "collection/collection" > "$ACOLLECTION_ROOT/.git/info/sparse-checkout"
  git -C "$ACOLLECTION_ROOT" fetch --depth 1 origin "$ACOLLECTION_COMMIT"
  git -C "$ACOLLECTION_ROOT" checkout -q FETCH_HEAD
fi

# --- okio -------------------------------------------------------------------------------
log "flatten okio"
rm -rf "$BUILD/deps/okio-flat" "$BUILD/deps/okio-classes"
python3 "$HERE/flatten-mpp.py" "$BUILD/deps/okio-flat" \
  "$BUILD/deps/srcs/okio/okio/src/commonMain" \
  "$BUILD/deps/srcs/okio/okio/src/nonJsMain" \
  "$BUILD/deps/srcs/okio/okio/src/zlibMain" \
  "$BUILD/deps/srcs/okio/okio/src/systemFileSystemMain" \
  "$BUILD/deps/srcs/okio/okio/src/jvmMain"
log "compile okio"
"$KOTLINC" $(find "$BUILD/deps/okio-flat" -name '*.kt') \
  -opt-in=kotlin.contracts.ExperimentalContracts -jvm-target 21 -language-version 2.2 \
  -d "$BUILD/deps/okio-classes"

# --- androidx.collection -----------------------------------------------------------------
log "flatten androidx.collection"
rm -rf "$BUILD/deps/acollection-flat" "$BUILD/deps/acollection-classes"
python3 "$HERE/flatten-mpp.py" "$BUILD/deps/acollection-flat" \
  "$ACOLLECTION_DIR/src/commonMain" \
  "$ACOLLECTION_DIR/src/jvmMain"
python3 "$HERE/patch-acollection-simplearraymap.py" "$BUILD/deps/acollection-flat"
log "compile androidx.collection"
"$KOTLINC" $(find "$BUILD/deps/acollection-flat" "$ROOT/thirdparty/androidx-collection-overlay" -name '*.kt') \
  -opt-in=kotlin.contracts.ExperimentalContracts -jvm-target 21 -language-version 2.2 \
  -d "$BUILD/deps/acollection-classes"

# --- jars needed for okhttp -------------------------------------------------------------
log "package okio + androidx.collection jars"
python3 "$HERE/make-jar.py" "$BUILD/deps/okio-classes" "$BUILD/deps/jars/okio-3.16.0.jar" \
  Implementation-Title=okio Implementation-Version=3.16.0
python3 "$HERE/make-jar.py" "$BUILD/deps/acollection-classes" "$BUILD/deps/jars/androidx-collection-1.8.0-alpha01.jar" \
  Implementation-Title=androidx.collection Implementation-Version=1.8.0-alpha01
cp "$BUILD/deps/srcs/okio/LICENSE.txt" "$BUILD/deps/jars/okio.LICENSE"

# --- okhttp compile-only stubs ----------------------------------------------------------
log "compile okhttp compile-only stubs"
rm -rf "$BUILD/deps/okhttp-stubs-classes"
"$KOTLINC" $(find "$ROOT/thirdparty/okhttp-stubs" -name '*.kt') -jvm-target 21 \
  -d "$BUILD/deps/okhttp-stubs-classes"

# --- okhttp: generated sources ----------------------------------------------------------
OKHTTP_GEN="$BUILD/deps/okhttp-generated"
rm -rf "$OKHTTP_GEN" "$BUILD/deps/okhttp-idna-classes"
mkdir -p "$OKHTTP_GEN/okhttp3/internal/idn"
sed 's/\$projectVersion/5.1.0/' \
  "$BUILD/deps/srcs/okhttp/okhttp/src/commonJvmAndroid/kotlinTemplates/okhttp3/internal/-InternalVersion.kt" \
  > "$OKHTTP_GEN/okhttp3/internal/-InternalVersion.kt"
log "generate okhttp IdnaMappingTableInstance.kt"
"$KOTLINC" \
  "$BUILD/deps/srcs/okhttp/okhttp-idna-mapping-table/src/main/kotlin/okhttp3/internal/idn/IdnaMappingTableData.kt" \
  "$BUILD/deps/srcs/okhttp/okhttp-idna-mapping-table/src/main/kotlin/okhttp3/internal/idn/MappedRange.kt" \
  "$BUILD/deps/srcs/okhttp/okhttp-idna-mapping-table/src/main/kotlin/okhttp3/internal/idn/MappingTables.kt" \
  "$BUILD/deps/srcs/okhttp/okhttp-idna-mapping-table/src/main/kotlin/okhttp3/internal/idn/SimpleIdnaMappingTable.kt" \
  "$HERE/gen-idna-mapping-table.kt" \
  -cp "$BUILD/deps/jars/okio-3.16.0.jar" -jvm-target 21 -d "$BUILD/deps/okhttp-idna-classes"
"$JAVA_HOME/bin/java" -cp "$BUILD/deps/okhttp-idna-classes:$BUILD/deps/jars/okio-3.16.0.jar:$KOTLINC_HOME/lib/kotlin-stdlib.jar" \
  okhttp3.internal.idn.Gen_idna_mapping_tableKt \
  "$BUILD/deps/srcs/okhttp/okhttp-idna-mapping-table/src/main/resources/okhttp3/internal/idna/IdnaMappingTable.txt" \
  "$OKHTTP_GEN/okhttp3/internal/idn/IdnaMappingTableInstance.kt"

# --- okhttp -----------------------------------------------------------------------------
log "flatten okhttp"
rm -rf "$BUILD/deps/okhttp-flat" "$BUILD/deps/okhttp-classes"
python3 "$HERE/flatten-mpp.py" "$BUILD/deps/okhttp-flat" \
  "$BUILD/deps/srcs/okhttp/okhttp/src/commonJvmAndroid/kotlin" "$BUILD/deps/srcs/okhttp/okhttp/src/jvmMain/kotlin" \
  "$OKHTTP_GEN"
log "compile okhttp"
"$KOTLINC" $(find "$BUILD/deps/okhttp-flat" -name '*.kt') \
  -opt-in=kotlin.contracts.ExperimentalContracts -jvm-target 21 -language-version 2.2 \
  -cp "$BUILD/deps/jars/okio-3.16.0.jar:$BUILD/deps/okhttp-stubs-classes" \
  -d "$BUILD/deps/okhttp-classes"
log "package okhttp jar"
cp -r "$BUILD/deps/srcs/okhttp/okhttp/src/jvmMain/resources/." "$BUILD/deps/okhttp-classes/"
cp "$BUILD/deps/srcs/okhttp/LICENSE.txt" "$BUILD/deps/jars/okhttp.LICENSE"
python3 "$HERE/make-jar.py" "$BUILD/deps/okhttp-classes" "$BUILD/deps/jars/okhttp-5.1.0.jar" \
  Implementation-Title=okhttp Implementation-Version=5.1.0

log "done — jars in $BUILD/deps/jars/"
ls -la "$BUILD/deps/jars/"
