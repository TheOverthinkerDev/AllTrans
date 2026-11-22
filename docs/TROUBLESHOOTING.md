# Khắc phục sự cố

[English](TROUBLESHOOTING_en.md) | [Về README](../README.md)

Trước khi thử các bước dưới đây, mở tab **ReAllTrans** và xem phần trạng thái module. Phần này cho biết module có đang chạy không và cài đặt đang được truyền theo cách nào.

## Ứng dụng không được dịch

- Kiểm tra mục **Framework** trong tab ReAllTrans có báo đang hoạt động không. Nếu không, bật module trong LSPosed, chọn **System Framework** trong phạm vi rồi khởi động lại máy.
- Kiểm tra ứng dụng đã nằm trong phạm vi LSPosed chưa. Trong trang của ứng dụng, mục **Phạm vi LSPosed** cho biết trạng thái. Chạm vào để gửi lại yêu cầu.
- Kiểm tra **Công tắc chính** trong tab Cài đặt dịch và **Dịch ứng dụng này** trong trang của ứng dụng đều đang bật.
- Buộc dừng rồi mở lại ứng dụng đích (nút **Buộc dừng & mở lại**).
- Nếu ứng dụng đang dùng ngôn ngữ có sẵn (ô đánh dấu màu cam), hãy đặt **Ngôn ngữ của ứng dụng** về **Theo hệ thống** trước khi bật dịch.

## Ứng dụng từng dịch được nay không dịch nữa

- Xem mục **Chạy nền** trong tab ReAllTrans. Nếu đang bị tối ưu pin, chạm để cho phép. Trên HyperOS, có thể cần thêm Cài đặt → Ứng dụng → ReAllTrans → Tiết kiệm pin → Không giới hạn.
- Google Translate và Microsoft Translate được gọi ngay trong ứng dụng đích nên không phụ thuộc mục này. VietPhrase và bản dịch dự phòng thì cần ReAllTrans được chạy nền.

## Đánh dấu ứng dụng xong thì ô tự bỏ

LSPosed cần bạn đồng ý thêm ứng dụng vào phạm vi. Nếu không bấm **Approve** trong khoảng 20 giây, hoặc bấm **Deny**, ReAllTrans sẽ không bật dịch. Đánh dấu lại rồi bấm **Approve** trong thông báo của LSPosed. Nếu thông báo không hiện, mở LSPosed và tự thêm ứng dụng vào phạm vi của ReAllTrans.

## Không chọn được ngôn ngữ của ứng dụng

- Cần Android 13 trở lên.
- Ứng dụng phải khai báo danh sách ngôn ngữ với Android. Nếu không, mục này bị khoá và ghi lý do. Đây là giới hạn của ứng dụng, không sửa được từ ReAllTrans.
- Nếu báo "Không khả dụng", hook hệ thống chưa chạy. Bật phạm vi System Framework và khởi động lại máy, hoặc cấp quyền root cho ReAllTrans.

## Google Translate hoặc Microsoft Translate không dịch

- Kiểm tra kết nối mạng.
- Dùng **Thử dịch** trong tab ReAllTrans để kiểm tra dịch vụ đang chọn.
- Thử đổi sang dịch vụ còn lại.

## VietPhrase không dịch

- VietPhrase chỉ dịch từ tiếng Trung sang tiếng Việt.
- Dùng **Thử dịch** để kiểm tra.

## Một số chữ vẫn không được dịch

- Chữ nằm trong hình ảnh, hoặc trò chơi tự vẽ chữ, không dịch được.
- Thử bật **Dịch mở rộng cho View**.
- Thử tăng độ trễ trước khi thay bằng bản dịch hoặc trước khi dịch WebView.
- Với Taobao: một số màn hình (ví dụ giỏ hàng) chỉ dịch được khi tắt **Chế độ an toàn** và bật **Dịch dữ liệu mạng (MTOP, thử nghiệm)**. Tuỳ chọn này có thể ảnh hưởng tới ứng dụng.

## Ứng dụng đích bị lỗi hoặc thoát sau khi bật dịch

- Tắt **Tự cuộn bản dịch dài**.
- Tắt **Dịch thông báo** hoặc **Dịch văn bản trong WebView** nếu không cần.
- Với ứng dụng không phải Alibaba, tắt **Tối ưu cho ứng dụng Alibaba** nếu đang bật.
- Xoá bộ nhớ đệm dịch rồi buộc dừng ứng dụng đích.
- Nếu vẫn lỗi, bỏ đánh dấu ứng dụng trong danh sách và báo lỗi kèm log.

## Sau khi chuyển từ AllTrans cũ sang ReAllTrans

- Tắt module AllTrans cũ trong LSPosed hoặc gỡ ứng dụng cũ. Hai module cùng bật sẽ dịch chồng lên nhau.
- ReAllTrans là ứng dụng mới nên phạm vi LSPosed và cài đặt phải thiết lập lại, hoặc khôi phục từ bản sao lưu.

## Lấy log để báo lỗi

1. Bật **Nâng cao: Bật chế độ gỡ lỗi** trong tab Cài đặt dịch.
2. Buộc dừng và mở lại ứng dụng đích, làm lại bước gây lỗi.
3. Trên máy tính chạy `adb logcat -s AllTrans` và lưu kết quả.
4. Gửi log khi tạo [Issue](https://github.com/TheOverthinkerDev/ReAllTrans/issues).

Lưu ý: trên một số ROM (ví dụ HyperOS), log mức thấp từ ứng dụng bên thứ ba có thể bị lọc bớt. Log của hook hệ thống vẫn xem được.
