# BUILDING — Kotanix

Môi trường mục tiêu: Debian 12 (bookworm) x86_64. Mọi lệnh chạy từ `kotanix/`.

## 1. Toolchain (một lần)

```sh
tools/setup-toolchain.sh          # mặc định <repo>/kotanix/.toolchain nếu không truyền tham số
source /home/user/tools/env.sh    # hoặc: export JAVA_HOME/KOTLINC_HOME trỏ tới nơi đã cài
```

Tải + xác minh SHA-256:
- JDK Temurin 21.0.8+9 (runtime + JNI header, đóng gói bởi `jdk4py`) từ PyPI
- Kotlin compiler 2.2.10 từ npm registry

Không có `javac` — toàn bộ phần Kotanix và mọi dependency build ở đây đều là Kotlin
(hoặc C cho JNI). Các thư viện Java thuần (org.json, jsoup) **không** build được tại
môi trường này; xem `STATUS.md` §5.

## 2. Dependency jars (okio / androidx.collection / okhttp)

```sh
bash tools/build-deps.sh
```

- Tải/xác minh tarball theo `UPSTREAM.lock` §6 (đã cache tại `$REFS_DIR` nếu có, mặc định
  `/home/user/refs/srcs`), extract vào `build/deps/srcs/`.
- `flatten-mpp.py` dẹp expect/actual cho JVM; `patch-acollection-simplearraymap.py`
  rename `SimpleArrayMap.getOrDefault`; `thirdparty/androidx-collection-overlay/`
  chứa bản port 3 file Java nguồn của androidx.collection + stub `androidx.annotation`.
- okhttp: sinh `-InternalVersion.kt` (projectVersion=5.1.0) và `IdnaMappingTableInstance.kt`
  từ bảng Unicode ghim trong release (trình sinh chạy y như Gradle upstream, driver
  `tools/gen-idna-mapping-table.kt`); compile `commonJvmAndroid` + `jvmMain` với
  compile-only stubs `thirdparty/okhttp-stubs/` (providers tùy chọn, không đóng gói jar).
- Kết quả: `build/deps/jars/okio-3.16.0.jar`,
  `build/deps/jars/androidx-collection-1.8.0-alpha01.jar`,
  `build/deps/jars/okhttp-5.1.0.jar` (+ `*.LICENSE`).

Ghi chú androidx.collection: build từ tip `1.8.0-alpha01` (@ `cfa6d5ad3e`), xem
UPSTREAM.lock §7 về việc lệch pin 1.5.0.

## 3. JNI bridge (SQLite)

```sh
gcc -shared -fPIC -O2 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    src/main/c/kotanixsqlite.c -o libkotanixsqlite.so -l:libsqlite3.so.0
```

Cần `libsqlite3.so.0` trên hệ thống (Debian: `libsqlite3-0`); khi chạy dùng
`-Djava.library.path=<thư mục chứa .so>` hoặc `LD_LIBRARY_PATH`.

## 4. Compile Kotanix + unit smoke tests

```sh
CP="build/deps/jars/okio-3.16.0.jar:build/deps/jars/androidx-collection-1.8.0-alpha01.jar:build/deps/jars/okhttp-5.1.0.jar"
$KOTLINC_HOME/bin/kotlinc $(find src/main/kotlin -name '*.kt') -cp "$CP" \
  -jvm-target 21 -language-version 2.2 -d build/classes/main
$KOTLINC_HOME/bin/kotlinc $(find src/test/kotlin -name '*.kt') \
  -cp "build/classes/main:$CP" -jvm-target 21 -language-version 2.2 -d build/classes/test
```

## 5. Chạy tests

```sh
export LD_LIBRARY_PATH=<thư mục chứa libkotanixsqlite.so>
JAVA="$JAVA_HOME/bin/java" ; CP="build/classes/test:build/classes/main:$CP:$KOTLINC_HOME/lib/kotlin-stdlib.jar"
$JAVA -Djava.library.path=$LD_LIBRARY_PATH -cp "$CP" kotanix.SmokeKt
$JAVA -Djava.library.path=$LD_LIBRARY_PATH -cp "$CP" kotanix.BackupSmokeKt
$JAVA -Djava.library.path=$LD_LIBRARY_PATH -cp "$CP" kotanix.LocalFolderSmokeKt
```

- `SmokeKt` — round-trip SQLite (manga/chapters/history) + CBZ đơn giản.
- `BackupSmokeKt` — restore `kotatsu_test.bak` (fixture upstream GPL-3.0, trong
  `app/src/androidTest/assets/`), kiểm tra dữ liệu đọc lại + export→import round-trip.
- `LocalFolderSmokeKt` — folder/CBZ/nested CBZ/archive-directory và natural ordering.

Backup file tham chiếu: `../app/src/androidTest/assets/kotatsu_test.bak` (đường dẫn tương
đối từ `kotanix/`).

## 6. Đóng gói `.deb`

```sh
bash tools/package-deb.sh                 # .deb self-contained (bundled Temurin JRE), ~26 MB
KOTANIX_SYSTEM_JRE=1 bash tools/package-deb.sh   # gói nhỏ ~3.6 MB, Depends: openjdk-21-jre-headless | default-jre-headless
```

Yêu cầu: bước 1 (toolchain) và bước 2 (jars) đã chạy. Kết quả:
`kotanix/build/dist/kotanix_0.1.0_amd64.deb` (gitignored).

Bố cục gói: `/usr/bin/kotanix` launcher (sh, tôn trọng `$KOTANIX_JRE`),
`/usr/lib/kotanix/{jre,lib}` (app jar + deps + `libkotanixsqlite.so`),
`/usr/share/applications/kotanix.desktop`, icons hicolor 16–512 px,
`/usr/share/doc/kotanix/{THIRD_PARTY_NOTICES.md,UPSTREAM.lock,copyright}`.
`Depends: libsqlite3-0 (>= 3.34), ca-certificates`.

Kiểm nhanh không cần root:

```sh
dpkg-deb -x build/dist/kotanix_0.1.0_amd64.deb /tmp/kotanix-stage
LANG=C.UTF-8 /tmp/kotanix-stage/usr/lib/kotanix/jre/bin/java \
    -Djava.library.path=/tmp/kotanix-stage/usr/lib/kotanix/lib \
    -cp '/tmp/kotanix-stage/usr/lib/kotanix/lib/*' \
    kotanix.ui.KotanixKt --selftest=../app/src/androidTest/assets/kotatsu_test.bak
```

Kết quả kỳ vọng: `SELFTEST OK — 7 checks`.

**CHƯA XÁC MINH**: `sudo dpkg -i` trên máy thật (sandbox không có root); hiển thị UI trên
màn hình thật; auto-update (ngoài phạm vi M3).

## 7. App swing — chạy thử

```sh
$JAVA_HOME/bin/java -Djava.library.path=<dir> \
    -cp 'build/classes/main:build/deps/jars/*:$KOTLINC_HOME/lib/kotlin-stdlib.jar' \
    kotanix.ui.KotanixKt                    # mở cửa sổ chính
    kotanix.ui.KotanixKt --selftest=../app/src/androidTest/assets/kotatsu_test.bak
```

## 8. Xác minh nguồn

- `UPSTREAM.lock` — mọi revision/tarball/hash cho app, parsers, deps, toolchain.
- `THIRD_PARTY_NOTICES.md` — license + attribution của tất cả thành phần.
- `git rev-parse HEAD` trong repo = nguồn app tham chiếu.
