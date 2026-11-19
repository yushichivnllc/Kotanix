# THIRD_PARTY_NOTICES — Kotanix

Kotanix là bản port Linux của Kotatsu-Redo (Android). License của Kotanix: **GPL-3.0**
(xem `LICENSE`), kế thừa từ dự án gốc. Các thành phần bên thứ ba dưới đây được build từ
source ghim theo `UPSTREAM.lock` và không có sửa đổi mã nguồn nào ngoài các bước được ghi
rõ trong UPSTREAM.lock §6–§7 (xoá expect/actual, rename `SimpleArrayMap.getOrDefault`,
thêm `= false` cho `actual fun` có generic, port 3 file Java của androidx.collection sang
Kotlin). SHA-256 của từng tarball ghi trong UPSTREAM.lock; license đầy đủ nằm trong
source gốc tương ứng (cũng được copy kèm JAR dưới tên `*.LICENSE` trong `build/deps/jars/`).

| Thành phần | Phiên bản / revision | License | Bản quyền |
|---|---|---|---|
| Kotatsu-Redo for Android (upstream tham chiếu, cơ sở của port) | Tag `9.8.7`, commit `9ef70777557e96c136c767b136a3022f7aff9dc4` | GPL-3.0 | The Kotatsu-Redo authors |
| kotatsu-parsers-redo (dữ liệu danh mục nguồn nhúng dưới dạng `SourceCatalog.kt` — CHỈ dữ liệu factual: tên/tiêu đề/locale/loại nội dung/cờ hỏng; KHÔNG có mã parser) | commit `3432a85ac02ce99824cf841aca40bfd3af78ed3f` | GPL-3.0 | The kotatsu-parsers authors |
| okio (build offline) | 3.16.0, tag `parent-3.16.0` | Apache-2.0 | Square, Inc. / The Okio authors |
| okhttp (build offline) | 5.1.0, tag `parent-5.1.0` | Apache-2.0 | Square, Inc. / The OkHttp authors |
| androidx.collection (build offline) | `COLLECTION = "1.8.0-alpha01"` @ `cfa6d5ad3e...` — **lệch với pin 1.5.0, xem UPSTREAM.lock §7** | Apache-2.0 | The Android Open Source Project |
| Public Suffix List (đóng gói trong okhttp jar, không sửa) | bảng tại `okhttp/.../publicsuffix/PublicSuffixDatabase.list` | MPL-2.0 | Mozilla Foundation / contributors |
| Unicode IDNA Compatible Preprocessing (bảng trong okhttp) | Unicode 15.1.0, sinh `IdnaMappingTableInstance.kt` từ `IdnaMappingTable.txt` | Unicode License (https://www.unicode.org/license.txt) | © 2023 Unicode, Inc. |
| Kotlin stdlib + compiler (build tool, ghim 2.2.10) | 2.2.10 (npm `kotlin-compiler`) | Apache-2.0 | JetBrains s.r.o. |
| OpenJDK Temurin 21 (runtime để chạy build + bundle vào .deb) | 21.0.8+9 (qua `jdk4py`) | GPL-2.0 with classpath exception | Eclipse Adoptium / Temurin authors |
| libsqlite3 (liên kết động, `Depends: libsqlite3-0`) | 3.40.1-2+deb12u2 (Debian 12) | Public Domain-ish (SQLite: https://www.sqlite.org/copyright.html) | Hipp, Wyrick & Company |
| jdk4py (đóng gói Temurin để phân phối qua PyPI) | 21.0.8.2 | MIT (packaging) + license của JDK | mwiede / adoptium |

Ghi chú:
- Stub trong `thirdparty/okhttp-stubs/` là **compile-only** mimic API của các provider
  tùy chọn (Conscrypt — Apache-2.0; Bouncy Castle — Bouncy Castle License; OpenJSSE —
  OpenJSSE/OpenJDK license; GraalVM native-image annotations — Universal Permissive License;
  animal-sniffer annotations — Apache-2.0). Stub KHÔNG được đóng gói vào jar okhttp;
  không có mã nguồn thật của các dự án đó trong repo Kotanix.
- `thirdparty/androidx-collection-overlay/` là bản port Kotlin của 3 file Java thuộc
  androidx.collection (Apache-2.0), kèm stub `androidx.annotation` tối thiểu cho compile.
- `kotanix/src/main/kotlin/kotanix/online/SourceCatalog.kt` được sinh tự động từ danh sách
  `@MangaSourceParser` của kotatsu-parsers-redo tại đúng commit ghim ở trên (generator:
  `tools/gen-source-catalog.py`). Nó chứa dữ liệu factual (tên/tiêu đề nguồn), không chứa mã
  parser; tái sinh lại bằng generator với `UPSTREAM.lock` §2 cho cùng một revision thì cho
  kết quả byte-identical. Code parser KHÔNG được copy vào Kotanix.
- File `.deb` phân phối kèm JAR của 3 thư viện build offline ở trên (okio, okhttp,
  androidx.collection) với license gốc của chúng, theo đúng yêu cầu attribution của
  Apache-2.0 (§4(d)) và GPL-3.0 (§5). GPL-3.0 tương thích với Apache-2.0.
- `kotatsu_test.bak` dùng trong test được lấy từ `app/src/androidTest/assets/` của
  upstream (test fixture của Kotatsu-Redo, GPL-3.0), chỉ dùng để kiểm thử backup.
- Icon hicolor trong `packaging/icons/` là ảnh do AI tạo cho dự án (không sao chép icon
  upstream); asset thuộc về Kotanix và được phát hành cùng license GPL-3.0.
- `/usr/bin/kotanix` và `packaging/kotanix.desktop` là script/metadata của dự án (GPL-3.0),
  không có mã nguồn bên thứ ba.
- Gói **AppImage** đóng gói thêm (link tĩnh vào type2-runtime): libfuse 3.15.0
  (LGPL-2.1, library only — source ghim trong UPSTREAM.lock §9, người dùng có quyền
  relink theo LGPL), squashfuse 0.5.2 (BSD-2-Clause), zlib 1.3.1 (zlib License),
  zstd 1.5.7 (BSD-3-Clause), type2-runtime fedfad51 (MIT). `mksquashfs`
  (GPL-2.0, squashfs-tools 4.6.1) chỉ là **build tool**, KHÔNG nằm trong AppImage.
  License đầy đủ của từng thành phần nằm trong source gốc tương ứng.
