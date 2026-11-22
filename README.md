# ReAllTrans

[English](README_en.md)

ReAllTrans là module Xposed (libxposed API 102) dịch giao diện ứng dụng Android ngay khi ứng dụng đang chạy, không cần sửa APK. Dự án làm tiếp [AllTrans](https://github.com/akhilkedia/AllTrans) của Akhil Kedia (chỉ chạy tới Android 13) để chạy được trên Android 14 đến 17, có kế thừa phần port của [Chanhnh](https://github.com/Chanhnh/AllTrans).

- Tên gói: `ovtk.realltrans`
- Phiên bản: 3.1.0
- Android 10 trở lên, đã thử trên Redmi K40s (HyperOS, Android 17) với LSPosed 2.2.1 (API 102)

## Tính năng

- Dịch chữ trong ứng dụng khi đang chạy: chữ thường, gợi ý trong ô nhập, thông báo, WebView và chữ của View tự vẽ.
- Ba dịch vụ dịch: Google Translate, Microsoft Translate và VietPhrase (Trung sang Việt, chạy offline).
- Cài đặt chung cho mọi ứng dụng, và cài đặt riêng cho từng ứng dụng khi cần.
- Bộ nhớ đệm bản dịch, tự làm mới khi đổi dịch vụ hoặc ngôn ngữ.
- Chữ dịch dài tự chạy qua lại thay vì bị cắt bằng "…" (tuỳ chọn).
- Tối ưu riêng cho ứng dụng Alibaba (Taobao, Tmall, Xianyu…): dịch chữ DinamicX, thanh tab, trang UC WebView. Có chế độ an toàn chỉ đổi phần hiển thị.
- Chọn ngôn ngữ có sẵn của ứng dụng (tính năng ngôn ngữ riêng cho từng ứng dụng của Android 13+), dùng được cả trên ROM đã ẩn mục này.
- Tự yêu cầu thêm ứng dụng vào phạm vi LSPosed khi bạn chọn dịch ứng dụng đó.
- Màn hình cài đặt của chính ReAllTrans: ngôn ngữ, giao diện sáng/tối, trạng thái module, thử dịch, sao lưu và khôi phục cài đặt.
- Giao diện tiếng Anh, tiếng Việt, tiếng Trung giản thể và phồn thể.

## Yêu cầu

- Android 10 trở lên.
- Máy đã root và cài LSPosed 2.1 trở lên, hoặc framework khác hỗ trợ libxposed API 102.
- Internet nếu dùng Google Translate hoặc Microsoft Translate. VietPhrase không cần mạng.
- Chọn ngôn ngữ có sẵn của ứng dụng cần Android 13 trở lên.

## Cài đặt

1. Tải `ReAllTrans-v3.1.0.apk` ở trang [Releases](https://github.com/TheOverthinkerDev/ReAllTrans/releases) và cài lên máy.
2. Mở LSPosed, bật module **ReAllTrans** và chọn **System Framework** trong phạm vi của module.
3. Khởi động lại máy.
4. Mở ReAllTrans, vào tab **ReAllTrans** để xem trạng thái module. Mục **Framework** phải báo đang hoạt động.
5. Khi ReAllTrans hỏi, cho phép nó chạy nền (mục **Chạy nền**). Nếu không, Android có thể chặn mạng của ReAllTrans khi chạy nền và VietPhrase hoặc bản dịch dự phòng sẽ không hoạt động.

Nếu bạn đang dùng AllTrans cũ (tên gói `akhil.alltrans`): ReAllTrans là một ứng dụng riêng, không tự nhận cài đặt cũ. Hãy sao lưu cài đặt trong AllTrans cũ (nếu có chức năng này), gỡ AllTrans cũ và tắt module cũ trong LSPosed để tránh hai module cùng dịch, rồi khôi phục cài đặt trong ReAllTrans.

## Sử dụng

1. Tab **Cài đặt dịch**: chọn dịch vụ dịch, ngôn ngữ nguồn và ngôn ngữ đích.
2. Tab **Ứng dụng**: đánh dấu ứng dụng muốn dịch. Nếu ứng dụng chưa nằm trong phạm vi LSPosed, LSPosed sẽ hiện thông báo xin phép. Bấm **Approve** để đồng ý. Nếu không đồng ý trong khoảng 20 giây, ô đánh dấu tự bỏ.
3. Buộc dừng rồi mở lại ứng dụng đích. Có thể dùng nút **Buộc dừng & mở lại** trong trang của ứng dụng đó.

Trong danh sách ứng dụng:

- Ứng dụng đã được thiết lập (đang dịch, dùng ngôn ngữ có sẵn, hoặc có cài đặt riêng) luôn được đánh dấu và nằm ở đầu danh sách.
- Ô đánh dấu màu cam nghĩa là ứng dụng đang dùng ngôn ngữ có sẵn thay vì bản dịch.
- Bỏ đánh dấu sẽ hỏi lại, sau đó tắt dịch, xoá cài đặt riêng, đưa ngôn ngữ của ứng dụng về theo hệ thống và gỡ ứng dụng khỏi phạm vi LSPosed.

Trong trang của từng ứng dụng:

- **Ghi đè cài đặt toàn cục**: dùng dịch vụ, ngôn ngữ và tuỳ chọn riêng cho ứng dụng này.
- **Ngôn ngữ của ứng dụng**: chỉ chọn được khi ứng dụng có khai báo danh sách ngôn ngữ với Android. Mục này ghi ứng dụng hỗ trợ bao nhiêu ngôn ngữ.
- Dịch và ngôn ngữ có sẵn không dùng cùng lúc. Khi đang dùng một bên, bên kia chuyển xám, chạm vào sẽ báo cần tắt bên nào trước.
- Nhóm **Ứng dụng Alibaba** chỉ bật sẵn cho các ứng dụng Alibaba. Với ứng dụng khác, nhóm này mặc định tắt.

## Quyền riêng tư

- ReAllTrans không có máy chủ riêng và không thu thập dữ liệu sử dụng.
- Khi dùng Google Translate hoặc Microsoft Translate, chữ cần dịch được gửi tới dịch vụ đó, thường là trực tiếp từ ứng dụng đang được dịch.
- VietPhrase dịch hoàn toàn trên máy.
- Quyền `INTERNET` dùng cho dịch vụ dịch trực tuyến. Quyền `QUERY_ALL_PACKAGES` dùng để liệt kê ứng dụng cho bạn chọn.
- Root chỉ được dùng khi hook hệ thống không hoạt động, để đổi ngôn ngữ ứng dụng hoặc buộc dừng ứng dụng.

## Cơ chế hoạt động

- LSPosed nạp module vào các ứng dụng trong phạm vi. Module hook những chỗ hiển thị chữ (như `TextView.setText`, `StaticLayout`, thông báo, WebView), gửi chữ đi dịch và thay bằng bản dịch.
- Google Translate và Microsoft Translate được gọi ngay trong ứng dụng đích, vì ứng dụng đang mở không bị hệ thống hạn chế mạng. Có thể tắt bằng **Dịch trong ứng dụng đích** (chung, hoặc riêng từng ứng dụng khi bật ghi đè); bật lại sẽ có cảnh báo về ảnh hưởng. VietPhrase, và Google/Microsoft khi cách trên lỗi, chạy trong ReAllTrans qua ContentProvider. Ứng dụng đích gọi tới chúng qua một proxy trong tiến trình hệ thống, vì vậy cần phạm vi **System Framework**.
- Cài đặt được ReAllTrans chép lên framework bằng [libxposed/service](https://github.com/libxposed/service). Ứng dụng đích đọc lại bằng `getRemotePreferences()` của [libxposed/api](https://github.com/libxposed/api), không phải hỏi qua provider mỗi lần mở.
- Đổi ngôn ngữ có sẵn và buộc dừng ứng dụng được thực hiện trong tiến trình hệ thống qua hook, hoặc qua root nếu hook không có.

## Cấu trúc dự án

Xem [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md): vai trò của từng tệp và cách build.

## Khắc phục sự cố

Xem [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Đóng góp

- Báo lỗi hoặc đề xuất ở mục [Issues](https://github.com/TheOverthinkerDev/ReAllTrans/issues).
- Khi báo lỗi, ghi rõ phiên bản Android, ROM, phiên bản LSPosed, tên và phiên bản ứng dụng đích, các bước gây lỗi. Nếu được, bật **Nâng cao: Bật chế độ gỡ lỗi** và gửi kèm log `adb logcat -s AllTrans`.
- Muốn sửa code: fork repository, tạo nhánh mới và gửi pull request.

## Credits

- **Bản gốc:** [AllTrans](https://github.com/akhilkedia/AllTrans) của Akhil Kedia.
- **VietPhrase từ Chanhnh:** bộ dịch VietPhrase, bản port libxposed API 102 và nhiều tính năng từ [Chanhnh/AllTrans](https://github.com/Chanhnh/AllTrans). Quy tắc và một phần dữ liệu VietPhrase lấy từ [duongden/script-vietphrase-translator](https://github.com/duongden/script-vietphrase-translator).
- **libxposed:** [libxposed/api](https://github.com/libxposed/api) và [libxposed/service](https://github.com/libxposed/service) (Apache-2.0).

Giấy phép: GPL-3.0-or-later, xem [LICENSE.GPL](LICENSE.GPL).
