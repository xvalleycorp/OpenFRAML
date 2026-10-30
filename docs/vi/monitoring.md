# Giám sát giao dịch (`openframl-monitor`)

Giám sát giao dịch chạy các kịch bản AML sau mỗi giao dịch. Module giữ hoạt động gần đây của từng tài khoản, tổng hợp
lại, đưa qua bảng kịch bản của gói quốc gia, và phát sinh một cảnh báo cho Quản lý hồ sơ điều tra (`openframl-case`)
với mỗi kịch bản được kích hoạt. Kịch bản là bảng quyết định, không phải code: ngân hàng đổi ngưỡng hay thêm kịch bản
trong bản sao bảng của mình, có ngày hiệu lực, và kiểm thử giống như các bảng của `gate`.

## Một giao dịch được giám sát thế nào

1. Giao dịch được ghi nhận trên tài khoản của ngân hàng (tài khoản mà mô hình sự kiện gọi là originator, cho cả hai
   chiều): tiền vào với `TRANSFER_IN` và `CASH_DEPOSIT`, tiền ra với các loại còn lại. Tài khoản đối ứng chỉ được giữ
   dưới dạng hash. Một giao dịch được gửi đến hai lần chỉ được ghi nhận một lần.
2. Hoạt động của tài khoản trong 48 giờ gần nhất, tính cả giao dịch này, trở thành các facts bên dưới.
3. `monitor_scenarios` được đánh giá theo ngày nghiệp vụ của giao dịch, với hit policy `COLLECT`.
4. Mỗi kịch bản được kích hoạt phát sinh một cảnh báo về khách hàng, kèm kịch bản, mức độ và thông điệp, phiên bản bảng
   và hoạt động mà kịch bản đã thấy; không bao giờ có số tài khoản. Một kịch bản liên tục được kích hoạt cho cùng một
   khách hàng chỉ phát sinh một cảnh báo trong mỗi chu kỳ chặn lặp (mặc định 7 ngày), vì hồ sơ đang mở đã có cảnh báo
   đó.

Các giao dịch của một tài khoản phải được xử lý theo đúng thứ tự; tầng stream sẽ phân vùng theo tài khoản. Trong lúc
chưa có tầng stream, một batch job có thể gọi `TransactionMonitor.process`. `InMemoryActivityStore` giữ 35 ngày giao
dịch.

## Facts

Số tiền chỉ được cộng theo đồng tiền của giao dịch; các khoảng thời gian kết thúc tại thời điểm giao dịch.

| Fact | Kiểu | Nội dung |
| --- | --- | --- |
| `transaction.amount`, `.type`, `.channel` | money, string, string | Giao dịch này |
| `transaction.direction`, `.cash` | string, bool | `IN` hoặc `OUT`; nộp hay rút tiền mặt |
| `account.daysSinceLastActivity` | int | Số ngày từ giao dịch trước đó của tài khoản; không có với giao dịch đầu tiên |
| `cashIn24h.count`, `.amount`, `.maxAmount` | int, money, money | Nộp tiền mặt trong 24 giờ: số lần, tổng tiền, lần lớn nhất |
| `in24h.count`, `.amount`, `.distinctCounterparties` | int, money, int | Tiền vào trong 24 giờ |
| `in48h.amount`, `out48h.amount` | money | Tiền vào và tiền ra trong 48 giờ |
| `flow48h.outToIn` | decimal | Tiền ra chia tiền vào trong 48 giờ; không có khi không có tiền vào |

## Kịch bản của Việt Nam

Gói quốc gia Việt Nam (`openframl-vn`) có sẵn `monitor_scenarios` phiên bản 1, hiệu lực từ 01/12/2023. Các ngưỡng
minh họa cho các thủ đoạn và không phải ngưỡng pháp lý, trừ `CASH_STRUCTURING`.

| Kịch bản | Mức độ | Kích hoạt khi |
| --- | --- | --- |
| `CASH_STRUCTURING` | `HIGH` | Từ hai lần nộp tiền mặt trở lên trong 24 giờ, cộng lại đạt 400.000.000 đồng trong khi mỗi lần đều dưới mức đó |
| `PASS_THROUGH` | `HIGH` | Từ 100.000.000 đồng trở lên vào tài khoản trong 48 giờ, và từ 90% số đó trở lên đã chuyển đi |
| `MANY_SENDERS` | `MEDIUM` | Nhận tiền từ 10 tài khoản đối ứng khác nhau trở lên trong 24 giờ |
| `DORMANT_REACTIVATED` | `MEDIUM` | Từ 50.000.000 đồng trở lên trên tài khoản không hoạt động từ 180 ngày trở lên |

`CASH_STRUCTURING` dùng ngưỡng giao dịch có giá trị lớn phải báo cáo theo Quyết định 11/2023/QĐ-TTg: 400.000.000 đồng
từ 01/12/2023, thay mức 300.000.000 đồng của Quyết định 20/2013/QĐ-TTg. Con số và ngày hiệu lực đã được đối chiếu với
nhiều bản tóm tắt đã công bố của quyết định, nhưng chưa đối chiếu với Công báo; cần xác nhận tại đó trước khi dựa vào,
như với mọi ngưỡng pháp lý. Việc báo cáo chính các giao dịch giá trị lớn là của Báo cáo theo quy định
(`openframl-report`, dự kiến), không phải của cảnh báo.

Mỗi kịch bản và cả hai phía của từng ngưỡng đều có trường hợp kiểm thử trong
`openframl-vn/src/test/rules/monitor_scenarios.stone-test.json`.

## Tiếp theo

Trong [lộ trình](roadmap.md): đưa giao dịch từ luồng dữ liệu của ngân hàng vào giám sát, kho hoạt động dùng chung giữa
các node, các khoảng thời gian dài hơn (7 và 30 ngày) do Hồ sơ hành vi (`openframl-features`) tính, và backtest một
kịch bản trên giao dịch cũ trước khi duyệt.
