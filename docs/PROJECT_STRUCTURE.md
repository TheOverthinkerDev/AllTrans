# Cấu trúc dự án

[English](PROJECT_STRUCTURE_en.md) | [Về README](../README.md)

Một module Android ứng dụng (`app`), viết bằng Kotlin. Mã nguồn nằm trong `app/src/main/kotlin/akhil/alltrans/`. Tên gói Kotlin vẫn là `akhil.alltrans` để giữ lịch sử mã nguồn; tên gói ứng dụng là `ovtk.realltrans`.

## Phần chạy trong ứng dụng đích (module)

| Tệp | Vai trò |
| --- | --- |
| `Alltrans.kt` | Điểm vào của module (`XposedModule`). Gắn hook khi ứng dụng khởi động; trong tiến trình hệ thống thì hook `SettingsProvider` làm proxy |
| `HookRuntime.kt` | Lớp bọc libxposed API: hook, gọi hàm gốc, ghi log, đọc remote preferences |
| `AppOnCreateHookHandler.kt`, `AttachBaseContextHookHandler.kt` | Đọc cài đặt khi ứng dụng khởi động, quản lý bộ nhớ đệm, gắn các hook dịch |
| `PreferenceList.kt`, `PreferenceManager.kt` | Giá trị cài đặt đang áp dụng cho ứng dụng (chung và riêng) |
| `SetTextHookHandler.kt`, `GetTranslate.kt` | Dịch chữ của `TextView` và gợi ý trong ô nhập |
| `StaticLayoutHookHandler.kt`, `LayoutConstructorHookHandler.kt`, `ViewTextMethodHookHandler.kt`, `CustomViewSetTextHookHandler.kt` | Dịch mở rộng cho chữ tự vẽ và View tuỳ chỉnh |
| `NotificationHookHandler.kt` | Dịch thông báo |
| `VirtWebViewOnLoad.kt`, `WebViewClientWrapper.kt`, `WebViewOnCreateHookHandler.kt`, `WebViewSetClientHookHandler.kt` | Dịch WebView |
| `TaobaoHooks.kt`, `UcWebViewTranslator.kt`, `MtopTranslator.kt` | Tối ưu cho ứng dụng Alibaba: chữ DinamicX, thanh tab, UC WebView, dữ liệu MTOP |
| `MarqueeHelper.kt` | Cho chữ dịch dài chạy qua lại |
| `GetTranslateToken.kt` | Gửi chữ đi dịch (Google/Microsoft gọi ngay trong ứng dụng đích, provider làm dự phòng), xử lý bộ nhớ đệm |
| `TranslateHttp.kt` | Kết nối HTTP dùng chung cho Google/Microsoft: thời gian chờ ngắn, bỏ kết nối cũ, thử lại một lần |
| `SystemBridge.kt` | Đổi ngôn ngữ ứng dụng và buộc dừng ứng dụng từ tiến trình hệ thống (hoặc qua root) |

## Phần chạy trong ứng dụng ReAllTrans

| Tệp | Vai trò |
| --- | --- |
| `AllTransApp.kt` | Lớp `Application`, kết nối dịch vụ libxposed khi ứng dụng mở |
| `RemotePrefs.kt` | Chép cài đặt lên framework bằng libxposed/service |
| `AppConfig.kt` | Trạng thái của từng ứng dụng: đang dịch, ngôn ngữ có sẵn, phạm vi LSPosed; quyền chạy nền của ReAllTrans |
| `MainActivity.kt` | Màn hình chính và thanh điều hướng |
| `AppListFragment.kt` | Danh sách ứng dụng |
| `LocalPreferenceFragment.kt` | Trang cài đặt của từng ứng dụng |
| `GlobalPreferencesFragment.kt` | Cài đặt dịch chung |
| `AppSettingsFragment.kt`, `AppSettings.kt` | Cài đặt của ReAllTrans, trạng thái module, sao lưu, thử dịch, credits |
| `LockablePreferences.kt` | Mục cài đặt có thể khoá (xám nhưng vẫn báo lý do khi chạm) |
| `GtransProvider.kt`, `EdgeProvider.kt`, `VietPhraseProvider.kt` | Dịch vụ dịch Google, Microsoft, VietPhrase |
| `VietPhraseTranslator.kt`, `VietPhraseTranslationEngine.kt`, `VietPhraseRuleEngine.kt` | Bộ dịch VietPhrase |
| `SharedPrefProvider.kt` | Cách truyền cài đặt dự phòng khi framework không có remote preferences |

## Tài nguyên

| Đường dẫn | Nội dung |
| --- | --- |
| `app/src/main/res/xml/` | Các màn hình cài đặt, danh sách ngôn ngữ của ReAllTrans |
| `app/src/main/res/values*/strings.xml` | Chữ giao diện: tiếng Anh, Việt, Trung giản thể, Trung phồn thể |
| `app/src/main/assets/vietphrase/` | Từ điển và quy tắc VietPhrase |
| `app/src/main/resources/META-INF/xposed/` | Khai báo module cho libxposed: lớp điểm vào, API tối thiểu, phạm vi mặc định |
| `app/src/test/` | Kiểm thử đơn vị |

## Build

Cần JDK 17 và Android SDK có nền tảng `android-37.0` (thư viện libxposed/service 102 yêu cầu compileSdk 37; targetSdk vẫn là 36).

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Tệp đầu ra:

- `app/build/outputs/apk/debug/ReAllTrans-v3.1.0-debug.apk`
- `app/build/outputs/apk/release/ReAllTrans-v3.1.0.apk`

Để ký bản release bằng khoá riêng, khai báo trong `gradle.properties`:

```properties
RELEASE_STORE_FILE=/duong/dan/toi/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Thư viện libxposed:

- `io.github.libxposed:api:102.0.0` (`compileOnly`, framework cung cấp khi chạy)
- `io.github.libxposed:service:102.0.0` (đóng gói trong APK)
