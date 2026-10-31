# Báo cáo theo quy định (`openframl-report`)

Báo cáo theo quy định tìm những gì ngân hàng phải báo cáo cho đơn vị tình báo tài chính:
- các giao dịch đạt ngưỡng pháp lý;
- các giao dịch đáng ngờ mà kết quả điều tra kết luận là phải báo cáo.

Ngưỡng và thời hạn là các bảng quyết định trong gói quốc gia. Mỗi phiên bản có hiệu lực từ ngày của văn bản quy định
nó, nên một ngày trước khi quy định thay đổi vẫn được xét theo quy định của chính ngày đó.

OpenFRAML tạo ra nội dung của từng báo cáo ở một dạng JSON mở. Điền vào mẫu biểu của cơ quan quản lý và gửi qua kênh
của cơ quan đó là một bước riêng. Ở bước đó, thông tin nhận biết khách hàng (họ tên, số định danh, địa chỉ) được lấy
từ hệ thống của chính ngân hàng.

## Báo cáo theo ngưỡng, theo từng ngày

`DailyReporting` chạy sau khi một ngày làm việc kết thúc, trên các giao dịch của ngày đó:

1. Mỗi giao dịch được đưa qua `report_transaction`. Mỗi quy tắc được kích hoạt đưa giao dịch vào loại báo cáo đó cho
   khách hàng của nó, ví dụ một giao dịch chuyển tiền điện tử lớn.
2. Tiền mặt của mỗi khách hàng trong ngày (nộp và rút) được cộng lại và đưa qua `report_daily`. Mỗi quy tắc được kích
   hoạt đưa mọi giao dịch tiền mặt trong ngày của khách hàng đó vào báo cáo. Nhờ vậy, tiền mặt chia nhỏ được báo cáo
   như một khoản lớn.
3. Thời hạn của mỗi báo cáo là ngày của giao dịch cộng `dueWorkingDays` của quy tắc, tính theo ngày làm việc.

- **Ngày làm việc.** Ngày làm việc tính theo múi giờ của gói quốc gia (với Việt Nam là `Asia/Ho_Chi_Minh`). Sự kiện của ngày khác bị bỏ qua và được đếm lại.
- **Chạy lại.** Chạy lại một ngày cho ra đúng các báo cáo cũ. Các giao dịch được phân biệt bằng mã sự kiện, còn mã báo cáo gồm loại báo cáo, khách hàng và ngày (`LARGE_CASH:cust-1:2026-10-30`).
- **Ngoại tệ.** Ngưỡng được so bằng nội tệ. Số tiền bằng ngoại tệ được quy đổi theo `ExchangeRates` của ngân hàng. Số tiền không có tỷ giá thì không được so với ngưỡng nào, và được đếm trong `unconverted` của lần chạy để ngân hàng xem lại.
- **Ngày nghỉ lễ.** `WorkingDays` nhận các ngày cuối tuần và danh sách ngày nghỉ lễ của ngân hàng. Ngày nghỉ lễ thay đổi theo từng năm, nên ngân hàng tự cung cấp.

Facts của `report_transaction`:

| Fact | Kiểu | Nội dung |
| --- | --- | --- |
| `transaction.amount` | money | Như giao dịch ghi |
| `transaction.nationalAmount` | money | Bằng nội tệ; không có nếu thiếu tỷ giá |
| `transaction.type`, `.channel` | string | Lấy từ mô hình sự kiện |
| `transaction.direction`, `.cash` | string, bool | `IN` hoặc `OUT`; có phải nộp hay rút tiền mặt |

Facts của `report_daily`, bằng nội tệ:

| Fact | Kiểu | Nội dung |
| --- | --- | --- |
| `cashIn.count`, `.amount` | int, money | Các lần nộp tiền mặt trong ngày |
| `cashOut.count`, `.amount` | int, money | Các lần rút tiền mặt trong ngày |
| `cash.count`, `.amount` | int, money | Cả hai cộng lại |

Cả hai bảng đều:
- dùng hit policy `COLLECT`;
- khai báo output kiểu int `dueWorkingDays` (từ 1 đến 99);
- ghi reason code cho mọi quy tắc, và reason code là tên của loại báo cáo.

Chính sách nào vi phạm một trong các điều trên thì không bao giờ được dựng.

## Báo cáo giao dịch đáng ngờ

`SuspiciousTransactionReport.fromCases` lấy các hồ sơ đã đóng với kết luận `REPORTED`. Trong Quản lý hồ sơ điều tra
(`openframl-case`), điều này có nghĩa là một chuyên viên đề xuất kết luận và một người giám sát khác đã phê duyệt.

Mỗi báo cáo gồm:
- khách hàng;
- kết luận đã được phê duyệt, kèm lý do của chuyên viên;
- thời điểm phê duyệt;
- mọi cảnh báo cùng bằng chứng;
- toàn bộ lịch sử xử lý của hồ sơ.

Các cảnh báo chỉ ghi giao dịch bằng mã sự kiện, không có số tài khoản. Vì vậy ở bước nộp báo cáo, các thông tin chi
tiết được tra trong hệ thống của ngân hàng.

Thời điểm phê duyệt là lúc muộn nhất mà ngân hàng đã biết về nghi ngờ. Thời hạn ở Việt Nam tính từ ngày giao dịch hoặc
từ khi phát hiện, tùy trường hợp luật áp dụng, nên hồ sơ cần được xử lý sớm hơn nhiều so với thời hạn đó. Hiện module
chưa tính thời hạn cho loại báo cáo này.

Khách hàng không bao giờ được biết là đã có báo cáo. Ở Việt Nam, Điều 40 Luật Phòng, chống rửa tiền cấm tiết lộ việc
này. Không có gì trong module này dành cho một kênh mà khách hàng nhìn thấy.

## Định dạng báo cáo

`ReportJson` ghi mỗi báo cáo thành một đối tượng JSON:
- `openframl.report.transaction/1` cho báo cáo theo ngưỡng;
- `openframl.report.suspicious/1` cho báo cáo giao dịch đáng ngờ.

Tên các trường không đổi trong cùng một phiên bản schema.

Báo cáo theo ngưỡng có số tài khoản đầy đủ, vì cơ quan quản lý cần chúng. Đây là kết quả duy nhất của OpenFRAML có
số tài khoản đầy đủ. Các file báo cáo là tài liệu mật, và được lưu trong thời hạn luật quy định (ở Việt Nam là 5 năm).
`toString` của các báo cáo vẫn che số tài khoản, nên một báo cáo lỡ bị ghi vào log cũng không làm lộ chúng.

```json
{"schema": "openframl.report.transaction/1", "id": "LARGE_CASH:cust-1:2026-10-30", "kind": "LARGE_CASH",
 "subject": "cust-1", "businessDate": "2026-10-30", "dueBy": "2026-11-02",
 "tables": ["report_daily@1@sha256:..."],
 "transactions": [{"eventId": "evt-1", "occurredAt": "2026-10-30T02:00:00Z", "type": "CASH_DEPOSIT",
   "channel": "BRANCH", "amount": {"value": "450000000", "currency": "VND"},
   "account": {"institution": "999901", "account": "0123456789"}}]}
```

## Ngưỡng của Việt Nam

Gói quốc gia Việt Nam (`openframl-vn`) có phiên bản 1 của cả hai bảng, hiệu lực từ 01/12/2023.

| Báo cáo | Bảng | Phải báo cáo khi | Thời hạn |
| --- | --- | --- | --- |
| `LARGE_CASH` | `report_daily` | Tiền mặt của một khách hàng trong một ngày từ 400.000.000 đồng trở lên | 1 ngày làm việc |
| `ELECTRONIC_TRANSFER` | `report_transaction` | Một giao dịch chuyển tiền trong nước, đi hoặc đến, từ 500.000.000 đồng trở lên | 1 ngày làm việc |

Nguồn của từng con số:
- Quyết định 11/2023/QĐ-TTg: ngưỡng 400.000.000 đồng, thay mức 300.000.000 đồng của Quyết định 20/2013/QĐ-TTg.
- Thông tư 09/2023/TT-NHNN, được thay bằng Thông tư 27/2025/TT-NHNN từ 01/11/2025 và Thông tư mới giữ nguyên ngưỡng: ngưỡng chuyển tiền 500.000.000 đồng.
- Khoản 1 Điều 37 Luật Phòng, chống rửa tiền 14/2022/QH15: thời hạn 01 ngày làm việc với báo cáo bằng dữ liệu điện tử.

Các con số này được đối chiếu với các bản tóm tắt và bản đăng lại văn bản, chưa đối chiếu với Công báo. Còn ba điểm
cần xác nhận trên nguồn chính thức; phần mô tả của mỗi bảng ghi điểm nào áp dụng cho bảng đó:

- **Cộng chung tiền mặt.** Tiền nộp và tiền rút trong ngày có được cộng chung hay không. Bảng đang cộng chung, tức là báo cáo nhiều hơn chứ không ít hơn.
- **Bên nào báo cáo.** Tổ chức nào báo cáo bên nào của giao dịch chuyển tiền. Bảng báo cáo cả hai bên.
- **Giao dịch được loại trừ.** Thông tư loại trừ một số giao dịch, như rút tiền gửi tiết kiệm hay trả nợ vay. Mô hình sự kiện chưa phân biệt được các giao dịch này, nên ngân hàng cần loại chúng ra khỏi sự kiện của ngày.

Chuyển tiền quốc tế (từ 1.000 USD) chưa được xử lý, vì mô hình sự kiện chưa cho biết giao dịch có ra nước ngoài hay
không.

Cả hai phía của mỗi ngưỡng đều có trường hợp kiểm thử trong `openframl-vn/src/test/rules/report_daily.stone-test.json`
và `report_transaction.stone-test.json`.

## Tiếp theo

Trong [lộ trình](roadmap.md):
- mẫu biểu và kênh nộp điện tử của cơ quan quản lý, dưới dạng bộ chuyển đổi trong gói quốc gia, đối chiếu với mẫu chính thức;
- chuyển tiền quốc tế;
- các loại giao dịch được loại trừ;
- thời hạn cho báo cáo giao dịch đáng ngờ.
