# Sub Dịch – phụ đề dịch trực tiếp cho Android

Ứng dụng chạy nền, nghe âm thanh **đang phát trên điện thoại** (video, phim, livestream…),
chuyển thành phụ đề, tự động dịch và hiển thị **nổi phía trên mọi ứng dụng**.
Nhận dạng giọng nói và dịch đều chạy **offline trên máy**.

## Tính năng
- Thu âm thanh hệ thống bằng `AudioPlaybackCapture` (Android 10+), không dùng micro.
- Nhận dạng giọng nói offline bằng [Vosk](https://alphacephei.com/vosk) (18 ngôn ngữ nguồn).
- Dịch offline bằng Google ML Kit Translation (20+ ngôn ngữ đích).
- Hộp phụ đề nổi, kéo lên/xuống được; tùy chọn hiện câu gốc, cỡ chữ.
- Nút **"Phụ đề dịch"** trong bảng điều khiển nhanh (Quick Settings) để bật/tắt.

## Cài đặt
1. Vào tab **Actions** của repo → lần build mới nhất → tải artifact `SubDich-apk`, giải nén và cài file `.apk`.
   (Hoặc tự build: `./gradlew assembleRelease`, cần Android SDK.)
2. Mở app, chọn **Ngôn ngữ lắng nghe** → bấm **Tải mô hình** (chỉ cần mạng lần đầu, ~30–130 MB).
3. Chọn **Dịch sang** → bấm **Tải gói dịch** (~30 MB/ngôn ngữ).
4. Bấm **Bật phụ đề**, cấp quyền *Hiển thị trên ứng dụng khác*, *Ghi âm* và đồng ý *chia sẻ màn hình/âm thanh*.
5. Thêm nút nhanh: bấm **Thêm nút vào bảng điều khiển nhanh** (Android 13+) hoặc tự kéo ô "Phụ đề dịch" vào khi chỉnh sửa bảng điều khiển nhanh.

## Lưu ý
- Android bắt buộc hiện hộp thoại đồng ý chia sẻ màn hình **mỗi lần bật** (giới hạn hệ thống, kể cả khi bật từ nút nhanh).
  Trên Android 14+, hãy chọn chia sẻ **toàn màn hình** để thu được âm thanh mọi ứng dụng.
- Một số ứng dụng chặn việc thu âm thanh (ví dụ app gọi điện, một số app có DRM) – khi đó sẽ không có phụ đề.
- Mô hình Vosk "small" được tối ưu cho điện thoại nên độ chính xác ở mức khá; nhạc nền lớn sẽ làm giảm chất lượng.

## Cấu trúc mã
| File | Vai trò |
|---|---|
| `SpeechEngine.kt` | Thu âm thanh đang phát + nhận dạng bằng Vosk |
| `SubtitleService.kt` | Foreground service: điều phối nhận dạng, dịch, overlay |
| `SubtitleOverlay.kt` | Hộp phụ đề nổi |
| `SubtitleTileService.kt` | Nút Quick Settings |
| `StartCaptureActivity.kt` | Xin quyền + đồng ý chia sẻ màn hình rồi khởi động service |
| `MainActivity.kt` | Màn hình cài đặt, tải mô hình |
| `ModelManager.kt` | Tải/giải nén mô hình Vosk, tải gói dịch ML Kit |
| `Languages.kt` | Danh sách ngôn ngữ và mô hình |
