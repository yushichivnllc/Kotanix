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

- **XONG (code + headless render kiểm)** UI dùng **Swing** theo phong cách Kotatsu Android (dark Material 3): black background, pill search bar, cover grid (rounded corners, badge tiến độ %, ellipsis 2 dòng), bottom navigation bar, FAB “Đọc tiếp”, overflow ⋮ menu (nhập/xuất backup), icons vẽ programmatically (không copy asset). Kiểm chứng: compile + headless full-window render ra PNG cho 6 màn hình (Lịch sử / Yêu thích / Khám phá / Local / Details / Dấu trang) + `--selftest` 7/7 + smoke tests. **GUI trên desktop thật vẫn CHƯA XÁC MINH.**
- **Màn hình hiện có** (theo store listing Android, shot 1–3):
  - `kotanix/ui/MainFrame.kt` — cửa sổ chính, **4 tab bottom navigation**: “Lịch sử” (mặc định khi mở app, giống Android), “Yêu thích”, “Khám phá”, “Local”. Tap vào card = mở màn details (trước đây double-click mới mở). FAB “Đọc tiếp” chỉ hiện ở tab Lịch sử (như Android shot 1). Overflow ⋮: nhập/xuất backup, Giới thiệu, Thoát.
  - `kotanix/ui/HistorySectionsPanel.kt` — **Lịch sử gom nhóm theo ngày** (“Vừa xong” / “Hôm nay” / “Hôm qua” / ngày) như shot 1; chips lọc: “Trên máy” (chỉ nguồn LOCAL), “Hoàn thành” (percent ≥ 99,5%), “Xoá lịch sử”. “New chapters” của Android không tính được offline nên không có.
  - `kotanix/ui/ExplorePanel.kt` — **tab Khám phá (shot 3)**: 2×2 shortcut tiles (Bộ nhớ máy → tab Local, Dấu trang → màn Dấu trang, Ngẫu nhiên → mở manga ngẫu nhiên từ thư viện, Tải xuống → hiện thông báo “chưa được port”, không phải nút chết); “Gợi ý cho bạn” (random từ thư viện local, ưu tiên chưa đọc — **không** phải remote suggestions feed của upstream); “Nguồn truyện” (roll-up `source` thật trong DB: LOCAL + nguồn online từ backup, hiển thị pin như source grid Android; click nguồn online → thông báo chưa port M2). Gõ tìm kiếm trong pill → lưới kết quả local (searchByTitle).
  - `kotanix/ui/MangaDetailsDialog.kt` — **màn details (shot 2)**: back arrow + overflow ⋮ (mở thư mục chứa / gỡ khỏi thư viện local / sao chép tiêu đề), cover lớn bo tròn + title + alt title, pill “Yêu thích” (toggle, không có dropdown chọn category — đơn giản hoá), info card (Nguồn / Tác giả / Tình trạng / Chapters / Tiến độ với progress bar accent + %), mô tả, chips thể loại, danh sách chapter đảo ngược (click = mở reader ở chapter đó), CTA “Đọc tiếp” accent. Field online-only hiện “—” khi không có dữ liệu; nguồn online chỉ hiện khi có trong backup.
  - `kotanix/ui/BookmarksDialog.kt` — **Dấu trang (phần bookmarks của shot 3 tile)**: list bản ghi bookmarks từ DB (cover + title + percent + ngày); click mở reader (resume từ history — **chưa** restore đúng trang bookmark, vì ReaderFrame chưa đọc bookmark khi mở).
  - `kotanix/ui/MangaDetailsDialog.kt` chia làm `MangaDetailsContent` (panel) + `MangaDetailsDialog` (shell) và `BookmarksDialog` chia làm `BookmarksContent` (panel) — để headless render kiểm thử được mà không cần X11.
  - `kotanix/ui/ReaderFrame.kt` — reader local (không đổi): lật trang CHƯƠNG/TOC, phím ←/→/ESC, progressbar, ghi history mỗi lần lật trang, resume; nhận tham số `startChapter` (từ details/chapter click).
  - `kotanix/ui/FillWidthPanel` (trong `Theme.kt`) — container GridBag một cột có `Scrollable.tracksViewportWidth` (BoxLayout thiếu điều này + unparented components orientation UNKNOWN ⇒ headless render lệch trái/phải; GridBag + NORTHWEST anchor deterministic).
- **Đơn giản hoá có chủ đích so với Android** (không làm giả): bottom nav 4 tab thay vì 5 (Feed RSS và Suggestions là tính năng online, chưa có backend — tab Local chiếm vị trí); nút “Tải xuống” hiện notice “chưa được port”; không có download manager; không có source catalog online (chỉ roll-up nguồn trong DB); suggestions là local; “Đọc tiếp” mở thẳng most-recent history; details không có dropdown category favourite; reader không có bottom-sheet tuỳ chọn (sẽ làm khi port reader options).
- **CHƯA XÁC MINH** UI **hiển thị thật** (màu, font, layout, icon, click) — sandbox headless, không có X11/Wayland. Toàn bộ logic dữ liệu đã kiểm chứng qua `--selftest` và unit smoke test; các màn hình được kiểm chứng bằng headless paint → PNG. Cần chạy `kotanix` trên máy có desktop thật trước khi tuyên bố UI hoàn tất.
- **CHƯA XÁC MINH** đọc CBZ/CBR: chỉ `.cbz`/`.zip` được hỗ trợ; CBR (rar) chưa có decoder.

## 5. Online parsers (M2)

- **CHẶN (dependency)** kotatsu-parsers-redo ghim dùng okhttp/jsoup/org.json/androidx.collection.
  - okhttp ✓ build được (Kotlin, xem `build-deps.sh`).
  - org.json và jsoup là **thư viện Java thuần**; sandbox không có `javac` (jdk4py chỉ có
    JRE; apt bị cấm), **không** build được từ source. Chúng không cần cho M0–M1
    (`kotanix/core/Json.kt` tự cài JSON reader/writer).
  - Hướng đi sẽ quyết định ở M2 (ví dụ: port phần cần thiết sang Kotlin, hoặc yêu cầu
    javac trong môi trường build; **không** re-upload jar không xác minh).

## 6a. Đóng gói `.AppImage` (M3)

- **XONG (xây + kiểm chạy)** `tools/package-appimage.sh` build
  `Kotanix-0.1.0-x86_64.AppImage` (~40 MB) **từ source ghim** (github release binaries
  bị chặn ở sandbox — xem UPSTREAM.lock §9): type2-runtime (MIT) + libfuse 3.15.0 (static,
  LGPL-2.1, vá `mount.c.diff` của upstream) + squashfuse 0.5.2 (manual build) + zlib +
  zstd + Temurin JRE bundle + cùng jars/stdlib/native lib như `.deb`. `mksquashfs`
  (squashfs-tools 4.6.1) build riêng làm **build tool** (không đóng gói).
- **ĐÃ KIỂM CHỨNG trên máy này**: `--appimage-extract` liệt kê đúng payload; chạy qua
  **FUSE thật** (`/dev/fuse` + `fusermount3` setuid): `--selftest=…kotatsu_test.bak` →
  **SELFTEST OK — 7 checks**; chế độ không FUSE (`APPIMAGE_EXTRACT_AND_RUN=1`) → cũng
  **SELFTEST OK**.
- **CHƯA XÁC MINH**: hiển thị GUI trên desktop thật; FUSE trên distro khác; gói **chưa ký**
  (không có chữ ký/updateinfo — không dùng cho auto-update); cài đặt bởi AppImageLauncher.

## 6. Đóng gói `.deb` (M3)

- **XONG (xây + kiểm staged install)** `tools/package-deb.sh` build
  `kotanix_0.1.0_amd64.deb` hoàn toàn offline:
  - Compile sources → `kotanix-app.jar` (manifest `Main-Class=kotanix.ui.KotanixKt`).
  - `libkotanixsqlite.so` (gcc, `-l:libsqlite3.so.0`) vào `/usr/lib/kotanix/lib/`.
  - Jar phụ thuộc (okio/collection/okhttp/kotlin-stdlib từ toolchain ghim).
  - **Bundled Temurin JRE 21** (mặc định; `KOTANIX_SYSTEM_JRE=1` để build gói nhỏ ~3.6 MB
    phụ thuộc `openjdk-21-jre-headless | default-jre-headless`).
  - `/usr/bin/kotanix` launcher (sh), `.desktop`, icon hicolor 16–512 px, doc
    (`THIRD_PARTY_NOTICES.md`, `UPSTREAM.lock`), maintainer scripts `postinst`/`postrm`
    (an toàn khi thiếu `update-desktop-database`).
  - `Depends: libsqlite3-0 (>= 3.34), ca-certificates`.
- **ĐÃ KIỂM CHỨNG** `dpkg-deb -x` staged install → chạy bằng JRE bundle đóng gói →
  `SELFTEST OK — 7 checks` (restore fixture + local import) **không cần network**.
  Build biến thể `KOTANIX_SYSTEM_JRE=1` cũng thành công.
- **CHƯA XÁC MINH** cài đặt thật (`sudo dpkg -i`) trên máy Debian/Ubuntu sạch: sandbox
  không có root. Chưa chắc `Depends` đủ cho mọi bản phân phối Debian-family; gói không
  tuyên bố hỗ trợ distro khác (rpm, Arch…).

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
- `tools/package-deb.sh` cần toolchain (`setup-toolchain.sh`) + jars (`build-deps.sh`);
  `.deb` build ra đặt ở `kotanix/build/dist/` (gitignored, không commit).
