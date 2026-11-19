# PORTING_MATRIX — Kotatsu-Redo (Android) → Kotanix (Linux)

Quy ước trạng thái: **XONG** (code + kiểm chứng), **MỘT PHẦN**, **CHƯA LÀM**,
**CHẶN** (môi trường). Mức ưu tiên theo milestone M0–M3 (M0 build+license, M1 backup+local,
M2 UI+online, M3 packaging).

| Thành phần upstream (SHA ghim) | Kotanix | Trạng thái | Ghi chú |
|---|---|---|---|
| `io.github.kotatsuredo.kotatsu` app 9.8.7 @ `9ef70777` | — | — | chỉ đọc, tham chiếu |
| Lõi dữ liệu: `data/manga/Manga*.kt` (models) | `kotanix/core/Models.kt` | **XONG** | data class thuần JVM |
| Backup: `backup/*` (json → .bak) | `kotanix/backup/Backup.kt` | **XONG** | restore + export, kiểm bằng fixture |
| Backup fixture `app/src/androidTest/assets/kotatsu_test.bak` | dùng trong `BackupSmoke.kt` | **XONG** | chỉ kiểm hướng Android→Kotanix + round-trip |
| Room/SQLite schema (`MangaDatabase`) | `kotanix/data/Schema.kt` + JNI `kotanixsqlite.c` | **XONG (tương đương chức năng)** | không dùng Room; SQL thuần qua JNI + libsqlite3 hệ thống; schema tương thích backup, không tương thích file DB Android |
| History/Favourites stores | `kotanix/data/HistoryStore.kt`, `FavouritesStore.kt`, `MangaStore.kt` | **XONG** | kiểm bằng smoke tests |
| Đọc local: `LocalMangaParser` (folder/cbz/nest) | `kotanix/local/LocalMangaParser.kt` | **XONG** | kiểm 4 layout + natural order |
| Local reading UI (reader, gestures) | `kotanix/ui/ReaderFrame.kt` | **MỘT PHẦN** | Swing; reader page-by-page, ←/→/ESC; không có gesture/zoom/mode ngang; chưa render trên màn hình thật |
| Screens (home/feed/details/search…) Compose | `kotanix/ui/{MainFrame,CoverGridPanel,CoverLoader,Theme,Icons,BottomNav}.kt` | **MỘT PHẦN** | Swing dark phong cách Kotatsu Android; Favourites/Local/History tabs, cover grid + progress badge + FAB; headless component render OK; pixels chưa kiểm trên desktop thật |
| `.deb` packaging (M3) | `tools/package-deb.sh`, `packaging/` | **XONG (chưa cài thật)** | dpkg-deb; staged install kiểm bằng JRE bundle; `sudo dpkg -i` chưa kiểm |
| `.AppImage` (M3) | `tools/package-appimage.sh`, `packaging/kotanix-apprun` | **XONG (kiểm chạy được)** | build từ source ghim (§9 UPSTREAM.lock); selftest qua FUSE + extract-and-run đều OK; GUI/sign/updateinfo chưa làm |
| kotatsu-parsers-redo @ `3432a85a` | — | **CHẶN (một phần)** | okhttp ✓; org.json/jsoup là Java thuần, không javac → xem STATUS.md §5 |
| Backup: export đọc được bởi Android Kotatsu | — | **CHƯA XÁC MINH** | cần emulator/device 9.8.7 |
| `app/src/androidTest` (UI tests) | — | **CHƯA LÀM** | cần UI + thiết bị |
| Desktop integration (`.desktop`, hicolor icons, launcher) | `packaging/kotanix.desktop`, `packaging/icons/`, `packaging/kotanix-launcher` | **XONG (chưa chạy thật)** | staged install qua `dpkg-deb -x` + selftest OK; desktop chưa chạy thật (headless) |
| Backup `identity` key handling | xử lý an toàn, không ghi log key | **XONG** | `report.identityKeyPresent` dùng để báo cáo, không in giá trị |

## Nguồn online

- Mọi nguồn parser online đều dựa vào HTTP (okhttp) + HTML parsing (jsoup hoặc tương đương).
  Trong môi trường build này, jsoup không build/không xác minh được ⇒ **mọi nguồn online
  đều ở trạng thái CHẶN** cho đến khi giải quyết M2 hoặc người dùng cung cấp artifact xác minh.
- Không có mã parser nào được copy vào Kotanix ở thời điểm hiện tại.

## Nguyên tắc giữ nguyên

- Hành vi đo được trên đúng SHA (UPSTREAM.lock §4).
- Không tắt kiểm tra chứng chỉ, không bỏ qua xác minh backup; khi không kiểm chứng được,
  đánh dấu CHƯA XÁC MINH thay vì đoán.
