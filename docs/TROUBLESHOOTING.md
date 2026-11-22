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

## Dịch được trên Wi-Fi nhưng không dịch được trên dữ liệu di động

- Kiểm tra **Dịch trong ứng dụng đích** đang bật (chung, hoặc trong trang của ứng dụng nếu đã bật ghi đè). Khi bật, ứng dụng đích tự dùng mạng của nó nên không phụ thuộc ReAllTrans.
- Nếu tắt mục trên hoặc dùng VietPhrase, bản dịch đi qua ReAllTrans. Cho phép ReAllTrans dùng dữ liệu nền: Cài đặt → Ứng dụng → ReAllTrans → Sử dụng dữ liệu → bật dữ liệu nền (tên mục tuỳ ROM), và cho phép mục **Chạy nền**.
- Kiểm tra tường lửa hoặc giới hạn dữ liệu di động theo từng ứng dụng (của ROM hoặc NetGuard) không chặn ứng dụng đích.
- Mạng 3G (HSPA) hoặc sóng yếu có thể làm yêu cầu dịch quá thời gian chờ khi ứng dụng đang tải video. Thử lại khi có 4G hoặc Wi-Fi.

## Lần đầu mở ứng dụng, bản dịch hiện chậm hoặc chỉ dịch một phần

- Lần đầu mọi chữ đều phải gửi đi dịch, bản dịch sẽ hiện dần. Những lần sau dùng bộ nhớ đệm nên nhanh hơn.
- Nếu một số chữ mãi không đổi, cuộn hoặc chuyển màn hình để ứng dụng vẽ lại chữ.

## Một số chữ giữ nguyên ngôn ngữ gốc

- Chữ đã cùng ngôn ngữ với ngôn ngữ đích (Google nhận diện như vậy) được giữ nguyên.
- Chữ ghép trong video, ảnh, biểu tượng hoặc phụ đề in sẵn không dịch được.
- Một số ứng dụng tự vẽ chữ bằng engine riêng (ví dụ giỏ hàng Taobao dùng Weex 2). Xem mục "Một số chữ vẫn không được dịch".

## Chữ bị chồng lên nhau hoặc bị cắt

- Một số ứng dụng xếp chữ theo độ rộng cố định của chữ gốc. Bản dịch dài hơn sẽ chồng lên nhau, ví dụ hàng tab trên cùng của Douyin. ReAllTrans không đổi bố cục của ứng dụng nên hiện chưa khắc phục được.
- Bật **Tự cuộn bản dịch dài** để chữ dài chạy qua lại thay vì bị cắt (áp dụng cho dòng chữ đơn).

## Đổi cài đặt nhưng ứng dụng đích không thay đổi

- Phần lớn cài đặt áp dụng khi ứng dụng đích khởi động lại. Dùng **Buộc dừng & mở lại** trong trang của ứng dụng.
- Sau khi cài lại hoặc xoá dữ liệu ReAllTrans, mở ReAllTrans một lần để cài đặt được chép lên framework. Mục **Truyền cài đặt** trong tab ReAllTrans phải báo đang dùng remote preferences.
- Muốn đổi dịch vụ, ngôn ngữ hay tuỳ chọn cho riêng một ứng dụng, bật **Ghi đè cài đặt toàn cục** trong trang của ứng dụng đó. Khi tắt, các mục này xám và ứng dụng dùng cài đặt chung.

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
- Với ứng dụng ngân hàng, ví hoặc game có kiểm tra bảo mật, tắt **Dịch trong ứng dụng đích** cho riêng ứng dụng đó (bật ghi đè cài đặt toàn cục trước).
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
