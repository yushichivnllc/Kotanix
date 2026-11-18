#!/usr/bin/env bash
# Tải + xác minh toolchain ghim cho Kotanix (xem toolchain.lock).
# Dùng: tools/setup-toolchain.sh [THƯ_MỤC_ĐÍCH]   (mặc định: <repo>/.toolchain)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-$HERE/../.toolchain}"
mkdir -p "$DEST"
# shellcheck source=toolchain.lock
. "$HERE/toolchain.lock"

log() { printf '[setup-toolchain] %s\n' "$*"; }

verify() { # file expected_sha256
	actual="$(sha256sum "$1" | awk '{print $1}')"
	if [ "$actual" != "$2" ]; then
		echo "sha256 mismatch for $1:" >&2
		echo "  expected $2" >&2
		echo "  actual   $actual" >&2
		exit 1
	fi
}

if [ ! -d "$DEST/jdk" ]; then
	log "tải JDK (Temurin-derived, qua jdk4py $JDK4PY_VERSION)…"
	curl -fSL --retry 3 -o "$DEST/jdk4py.whl" "$JDK4PY_URL"
	verify "$DEST/jdk4py.whl" "$JDK4PY_SHA256"
	python3 - "$DEST" <<'PY'
import sys, zipfile, os
dest = sys.argv[1]
with zipfile.ZipFile(os.path.join(dest, 'jdk4py.whl')) as z:
    z.extractall(os.path.join(dest, 'jdk4py'))
os.rename(os.path.join(dest, 'jdk4py', 'jdk4py', 'java-runtime'), os.path.join(dest, 'jdk'))
PY
	rm "$DEST/jdk4py.whl"
else
	log "JDK đã có: $DEST/jdk"
fi

if [ ! -d "$DEST/kotlinc" ]; then
	log "tải Kotlin compiler $KOTLINC_VERSION…"
	curl -fSL --retry 3 -o "$DEST/kotlinc.tgz" "$KOTLINC_URL"
	verify "$DEST/kotlinc.tgz" "$KOTLINC_SHA256"
	tar xzf "$DEST/kotlinc.tgz" -C "$DEST"
	mv "$DEST/package" "$DEST/kotlinc"
	rm "$DEST/kotlinc.tgz"
else
	log "kotlinc đã có: $DEST/kotlinc"
fi

chmod -R u+rwx "$DEST"
"$DEST/jdk/bin/java" -version || { log "JDK lỗi"; exit 1; }
cat > "$DEST/env.sh" <<EOF
export KOTANIX_JAVA_HOME="$DEST/jdk"
export KOTANIX_KOTLINC_HOME="$DEST/kotlinc"
export JAVA_HOME="$DEST/jdk"
export PATH="$DEST/kotlinc/bin:$DEST/jdk/bin:\$PATH"
EOF
log "xong. Dùng: source $DEST/env.sh"
