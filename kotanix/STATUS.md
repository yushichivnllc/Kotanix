# STATUS — Kotanix (cập nhật 2026-11-18)

Tài liệu này mô tả đúng những gì đã/chưa làm được. Không có tuyên bố nào dưới đây vượt
quá bằng chứng trong repo hoặc output test thật. Quy ước trạng thái:

- **XONG** — code xong + có kiểm chứng trong sandbox này.
- **CHƯA XÁC MINH** — chưa có bằng chứng trực tiếp; cần thiết bị/emulator hoặc môi trường khác.
- **CHẶN** — môi trường không cho phép; giải pháp tạm ghi rõ.

## 1. Toolchain & build (M0)

- **XONG** `tools/setup-toolchain.sh` tải + xác minh SHA-256 JDK (Temurin 21.0.8 qua
  `jdk4py`) và Kotlin compiler 2.2.10 từ npm registry (PyPI/npm — Maven Central bị chặn).
- **XONG** `tools/build-deps.sh` build offline các jar: `okio-3.16.0.jar`,
  `androidx-collection-1.8.0-alpha01.jar`, `okhttp-5.1.0.jar`. Chạy từ đầu (~90s);
  tất cả tarball/source đều xác minh SHA-256 theo `UPSTREAM.lock` §6 (androidx.collection
  theo §7, git sparse checkout commit ghim).
- **XONG** `flatten-mpp.py` vá lỗi generic-multi-line (chèn default cho `actual fun <T> write`
  v.v.) — trước đó OkHttp không compile được với okio đã flatten.
- **XONG** JNI bridge `kotanixsqlite` compile được bằng `gcc` + header trong jdk4py
  (`tools/jdk/include`) + `-l:libsqlite3.so.0`; liên kết với **libsqlite3.so.0 của hệ thống**
  (Debian 12: 3.40.1-2+deb12u2). Không cần `libsqlite3-dev`.
- **XONG** Toàn bộ `src/main/kotlin` + `src/test/kotlin` compile sạch (chỉ warnings).

## 2. Backup `.bak` (M1)

- **XONG (hướng Android → Kotanix)** `kotatsu_test.bak` (fixture chính thức của upstream,
  `app/src/androidTest/assets/kotatsu_test.bak`) restore được vào SQLite trong bộ nhớ:
  categories=2, history=6, favourites=15; manga "Horimiya" (id, source=MANGATOWN, 4 tags,
  rating) đọc lại đúng; lịch sử đọc (chapterId, page=2) đúng. Test: `BackupSmoke.kt`
  → `BACKUP SMOKE TEST PASSED`.
- **XONG (vòng Kotanix export → Kotanix import)** export ra `.bak` rồi import lại cho cùng
  kết quả đếm (history=6, favourites=15) — cùng fixture test.
- **CHƯA XÁC MINH (hướng Kotanix → Android)** chưa có emulator/device + app Android thật
  trong sandbox; **không** có tuyên bố rằng file export Kotanix được Android Kotatsu 9.8.7
  mở thành công. Cần kiểm trên thiết bị thật/máy ảo với đúng SHA trước khi phát hành.
- **CHƯA XÁC MINH** tính tương thích đầy đủ của mọi section trong `.bak` (identity,
  categories, favourites, history, trackers…). `unsupportedSections` hiện = `[]` với fixture
  này, không suy rộng ra fixture khác.
- **XONG** Serialization tuân thủ quy tắc định danh: ID quá 2^53 đọc/ghi như `Long`
  (kotlinx-json/org.json tiêu chuẩn sẽ làm hỏng) — xem `kotanix/core/Json.kt`.

## 3. Đọc local: thư mục / CBZ / lồng nhau (M1)

- **XONG** Tests `Smoke.kt` + `LocalFolderSmoke.kt` → `SMOKE TEST PASSED` /
  `LOCAL LAYOUT SMOKE TEST PASSED`, bao gồm:
  - CBZ gốc chỉ có ảnh (single chapter), thứ tự tự nhiên ("page2" < "page10").
  - Thư mục manga với chapter là subdirectory (trộn ảnh lẫn trong gốc).
  - Thư mục manga với chapter là file `.cbz`.
  - Archive gốc chứa **chapter .cbz lồng nhau** (2 tầng).
  - Archive với **thư mục tiền tố** (`Ch 1/01.png`).
  - Ảnh đọc lại đúng bytes; tên ảnh phi ASCII và natural order xử lý đúng.
- **GHI CHÚ** mọi test chạy trên JRE 21 của toolchain ghim, không trên thiết bị Android.
- **CHƯA XÁC MINH** parity UI (toolbar, gesture, theme…) — chưa có UI.

## 4. Giao diện người dùng (M2)

- **CHƯA BẮT ĐẦU** port UI Linux-native (Kotlin/JVM + toolkit). Milestone này chưa có
  thiết kế/chọn toolkit được phê duyệt; chưa viết code UI.

## 5. Online parsers (M2)

- **CHẶN (dependency)** kotatsu-parsers-redo ghim dùng okhttp/jsoup/org.json/androidx.collection.
  - okhttp ✓ build được (Kotlin, xem `build-deps.sh`).
  - org.json và jsoup là **thư viện Java thuần**; sandbox không có `javac` (jdk4py chỉ có
    JRE; apt bị cấm), **không** build được từ source. Chúng không cần cho M0–M1
    (`kotanix/core/Json.kt` tự cài JSON reader/writer).
  - Hướng đi sẽ quyết định ở M2 (ví dụ: port phần cần thiết sang Kotlin, hoặc yêu cầu
    javac trong môi trường build; **không** re-upload jar không xác minh).

## 6. Đóng gói `.deb` (M3)

- **CHƯA BẮT ĐẦU** `dpkg-deb` có sẵn; skeleton cần quyết định runtime bundling
  (JRE 21 bundle vs phụ thuộc hệ thống) + toolchain giới thiệu cho người dùng cuối.

## 7. Vấn đề đã biết / nợ kỹ thuật

- androidx.collection build từ `COLLECTION=1.8.0-alpha01` @ `cfa6d5ad3e…`, **không** phải
  1.5.0 như parser/app pin (UPSTREAM.lock §7). Khi fetch được tag/commit đúng 1.5.0 và
  build lại được thì cập nhật UPSTREAM.lock.
- `git log -S` trên sparse clone bị timeout nên không chứng minh được commit 1.5.0; ghi
  nhận thay vì đoán mò.
- `build-deps.sh` ghi artifact vào `build/deps/` (đã gitignore); jar không được commit.
- okhttp stubs (`thirdparty/okhttp-stubs/`) chỉ để compile — user phải tự cài conscrypt/
  bouncycastle nếu muốn; okhttp jar không chứa chúng.
- TLS mặc định của JRE jdk4py không tin tưởng CA bundle hệ thống trong sandbox (proxy
  chặn); đã kiểm chứng HTTPS + HTTP/2 bằng cách trust `/etc/ssl/certs/ca-certificates.crt`
  — trên máy thật, ca-certificates hệ thống là đường đúng. Không disabling certificate
  validation ở bất cứ đâu.
- File SQLite `kotanixsqlite.so` cần build bằng `gcc` (xem `docs/BUILDING.md`); chưa đóng
  gói `.deb`, chưa có `Depends: libsqlite3-0` thực tế.
