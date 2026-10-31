# Luồng giao dịch (`openframl-stream`)

Luồng giao dịch nối OpenFRAML với dòng giao dịch của ngân hàng theo hai cách:

- **Dịch vụ luồng giao dịch.** Đọc topic giao dịch, rồi chạy sàng lọc giao dịch và giám sát trên từng giao dịch ngay khi giao dịch diễn ra. Cảnh báo được chuyển sang Quản lý hồ sơ điều tra (`openframl-case`).
- **Job báo cáo hằng ngày.** Chạy sau khi một ngày làm việc kết thúc. Job đọc lại ngày đó từ chính topic trên và ghi ra các báo cáo của ngày.

Topic chính là bản ghi của ngày: OpenFRAML không giữ thêm bản sao nào khác của các giao dịch.

## Topic giao dịch

Hệ thống thanh toán của ngân hàng công bố mỗi giao dịch đã hoàn tất lên một topic Kafka (mặc định là
`openframl.transactions`), ở dạng JSON mở `openframl.transaction/1`. Các trường giống `Transaction` trong API gRPC
của `gate`, mang tên như trong mô hình sự kiện:

```json
{"schema": "openframl.transaction/1", "eventId": "evt-1", "occurredAt": "2026-10-30T02:00:00Z",
 "channel": "MOBILE", "type": "TRANSFER_OUT", "amount": {"value": "1500000", "currency": "VND"},
 "customerId": "cust-1", "originator": {"institution": "999901", "account": "0123456789"},
 "beneficiary": {"institution": "999902", "account": "5555666677"},
 "session": {"deviceId": "phone-1", "remoteAccessDetected": false, "activeCallDetected": true},
 "beneficiaryName": "Nguyễn Văn Đức", "counterpartyCountry": "SG"}
```

- **Số tiền.** Số tiền là chuỗi số thập phân, không bao giờ là số JSON.
- **Trường không bắt buộc.** `beneficiary`, `session`, `beneficiaryName` và `counterpartyCountry` có thể bỏ trống.
- **Khóa của bản ghi.** Khóa là tài khoản gửi, dạng `<institution>/<account>`. Kafka giữ đúng thứ tự trong mỗi partition, nên chính khóa này giúp phần giám sát nhận giao dịch của mỗi tài khoản theo đúng thứ tự.
- **Thời điểm của bản ghi.** Timestamp của bản ghi nên gần với `occurredAt`. Mặc định của Kafka, tức thời điểm bản ghi được gửi lên, là đủ gần.
- **Thời gian lưu.** Thời gian lưu của topic phải đủ dài cho mọi ngày có thể cần chạy lại báo cáo.
- **Bảo mật.** Topic có số tài khoản và họ tên, nên quyền truy cập topic được giới hạn như quyền truy cập hệ thống thanh toán.

## Dịch vụ luồng giao dịch

`StreamService` chạy `TransactionStream` với các bộ xử lý sau, theo thứ tự:
1. Sàng lọc giao dịch, nếu có chỉ định thư mục danh sách.
2. Giám sát.

Mỗi bản ghi được xử lý ít nhất một lần:
- Offset của một bản ghi chỉ được commit sau khi mọi bộ xử lý đã nhận nó.
- Nếu một bộ xử lý lỗi, ví dụ không kết nối được cơ sở dữ liệu của Quản lý hồ sơ điều tra, luồng quay lại đúng bản ghi đó và thử lại sau năm giây. Trong lúc đó không bản ghi nào phía sau được nhận.
- Mọi bộ xử lý đều idempotent theo mã sự kiện, nên một bản ghi được nhận hai lần cũng không sinh thêm gì. Phần giám sát chỉ ghi nhận giao dịch sau khi các cảnh báo của nó đã được gửi đi.

Bản ghi không phải là sự kiện thì bị bỏ qua và được đếm lại. Log chỉ ghi partition, offset và trường bị sai, không bao
giờ ghi nội dung bản ghi.

Phần giám sát giữ hoạt động gần đây của mỗi tài khoản ngay trong tiến trình (`InMemoryActivityStore`). Hãy chạy một
Dịch vụ luồng giao dịch cho mỗi consumer group, cho đến khi kho hoạt động dùng chung được giữa các tiến trình. Lý do:
sau một lần rebalance, tiến trình thứ hai sẽ bắt đầu mà không có lịch sử của các partition nó nhận thêm.

| Biến môi trường | Mặc định | Mục đích |
| --- | --- | --- |
| `OPENFRAML_STREAM_KAFKA_BOOTSTRAP` | (bắt buộc) | Kafka bootstrap servers |
| `OPENFRAML_STREAM_TOPIC` | `openframl.transactions` | Topic giao dịch |
| `OPENFRAML_STREAM_GROUP` | `openframl-stream` | Consumer group |
| `OPENFRAML_COUNTRY` | `VN` | Gói quốc gia |
| `OPENFRAML_CASE_JDBC_URL` | (trong bộ nhớ) | Cơ sở dữ liệu của Quản lý hồ sơ điều tra, dùng chung với Dịch vụ hồ sơ điều tra |
| `OPENFRAML_STREAM_SCREENING_DIR` | (không sàng lọc) | Thư mục danh sách sàng lọc, như của `ScreeningDirectory` |
| `OPENFRAML_STREAM_SCREENING_THRESHOLD` | `0.85` | Điểm tên thấp nhất sinh cảnh báo |
| `OPENFRAML_STREAM_MONITOR` | `true` | Có chạy giám sát hay không |
| `OPENFRAML_STREAM_SUPPRESSION_DAYS` | `7` | Thời gian một kịch bản đã kích hoạt với một khách hàng được giữ im lặng |

## Job báo cáo hằng ngày

`DailyReportJob [yyyy-mm-dd]` chạy Báo cáo theo quy định (`openframl-report`) cho một ngày làm việc. Nếu không chỉ định
ngày, job chạy cho ngày hôm trước.

- `KafkaDayReader` đọc, từ mọi partition, các bản ghi có timestamp nằm trong ngày, nới thêm một giờ ở mỗi đầu. `DailyReporting` chỉ giữ các sự kiện của đúng ngày đó. Sự kiện được đọc đến đâu dùng đến đó, nên một ngày lớn không bao giờ phải nằm trọn trong bộ nhớ.
- Job ghi hai file JSON lines vào thư mục đầu ra:
  - `transaction-reports-<date>.jsonl`, các báo cáo theo ngưỡng;
  - `suspicious-reports-<date>.jsonl`, các hồ sơ có kết luận phải báo cáo trong ngày.
- Mỗi file được ghi dưới một tên tạm rồi mới đổi tên vào chỗ, nên không ai đọc phải một file ghi dở. Chạy lại một ngày sẽ thay các file của ngày đó bằng đúng nội dung cũ.
- Chỉ chủ sở hữu đọc được các file, vì báo cáo theo ngưỡng có số tài khoản.
- Job ghi log số giao dịch đã đọc. Job cũng ghi số giao dịch bị trùng, số giao dịch thuộc ngày khác, và số giao dịch không có tỷ giá. Giao dịch ngoại tệ không có tỷ giá thì không được so với ngưỡng nào, nên hãy cung cấp tỷ giá.

| Biến môi trường | Mặc định | Mục đích |
| --- | --- | --- |
| `OPENFRAML_STREAM_KAFKA_BOOTSTRAP`, `OPENFRAML_STREAM_TOPIC` | | Như trên |
| `OPENFRAML_COUNTRY`, `OPENFRAML_CASE_JDBC_URL` | | Như trên |
| `OPENFRAML_REPORT_OUT_DIR` | `.` | Nơi ghi các file báo cáo |
| `OPENFRAML_REPORT_RATES` | (không có) | Mỗi dòng `currency,rate`: số nội tệ cho một đơn vị ngoại tệ, dùng cho cả ngày |
| `OPENFRAML_REPORT_HOLIDAYS` | (không có) | Mỗi dòng một ngày dạng ISO: các ngày nghỉ lễ được bỏ qua khi tính thời hạn |
| `OPENFRAML_REPORT_MARGIN_MINUTES` | `60` | Đọc topic rộng ra ngoài ngày bao nhiêu phút |

## Tiếp theo

Trong [lộ trình](roadmap.md):
- kho hoạt động dùng chung giữa các tiến trình của Dịch vụ luồng giao dịch;
- một topic dead-letter cho các bản ghi không phải là sự kiện;
- các chỉ số về những gì luồng đã nhận, bỏ qua và thử lại.
