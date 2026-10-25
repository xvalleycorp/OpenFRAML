# Lộ trình

Giai đoạn 1 chỉ xây luồng inline và benchmark: độ trễ cực thấp là rủi ro kỹ thuật lớn nhất, nên cần được chứng minh
trước khi xây phần còn lại. Thời gian là ước tính cho một nhóm nhỏ và sẽ được chốt khi có kế hoạch nhân sự.

| Giai đoạn | Tháng (ước tính) | Nội dung | Cổng sang giai đoạn sau |
| --- | --- | --- | --- |
| 1. Lõi inline | 0–4 | Mô hình sự kiện chung; `gate` nhúng RuleStone; velocity trên Valkey; bộ rule mẫu Việt Nam; benchmark tải | p99 dưới 30 ms ở 20.000 TPS |
| 2. Giám sát | 4–10 | Đặc trưng trên Flink; kịch bản AML; hồ sơ điều tra; báo cáo và sàng lọc; connector SIMO | Báo cáo đúng mẫu quy định |
| 3. Thí điểm | 10–16 | Chạy shadow tại ngân hàng; model chạy shadow; `gate` lên production; tích hợp VOBS | Thí điểm chạy production |
| 4. Mở rộng | Từ tháng 16 | Model production; phân tích đồ thị; trợ lý điều tra LLM; thêm quốc gia | |

## Giai đoạn 1

- [x] Mô hình sự kiện: mô hình sự kiện giao dịch chung, có phiên bản (`openframl-model`)
- [x] SPI gói quốc gia; lõi được kiểm thử với một nước giả lập
- [x] Chặn giao dịch dạng thư viện: tra danh sách trước, hồ sơ, velocity, model có ngân sách thời gian, bảng quyết định,
      chính sách dự phòng, bản ghi kiểm toán không chứa dữ liệu cá nhân (`openframl-gate`)
- [x] Bộ đếm velocity cục bộ trên node, bản sao danh sách và kho hồ sơ trong bộ nhớ
- [x] Gói quốc gia Việt Nam: rule mẫu, có test cho mọi rule và mọi ngưỡng (`openframl-vn`)
- [x] Dịch vụ chặn giao dịch: gRPC có deadline cho từng lời gọi (`openframl-gate-server`)
- [ ] Hồ sơ và velocity bền vững trên Valkey, kèm cache trong tiến trình Caffeine
- [ ] Ghi decision record lên Kafka (`GateRecordSink`)
- [ ] Hot reload bảng quyết định sau khi được duyệt qua maker-checker
- [ ] `ScoreProvider` chạy ONNX Runtime
- [x] Đo hiệu năng: benchmark JMH và bộ sinh tải gRPC vòng hở trên một ngân hàng tổng hợp
      (`openframl-benchmark`); kết quả đầu tiên trong [Đo hiệu năng](benchmark.md)
- [ ] Định tuyến theo tài khoản gửi giữa các node `gate`
- [ ] Đo mục tiêu 20.000 TPS trên máy chủ thật với Generational ZGC

## Issue dự kiến gửi RuleStone

OpenFRAML là người dùng khắt khe của RuleStone về độ trễ và khối lượng. Những gì OpenFRAML cần từ RuleStone được gửi
lên dưới dạng GitHub issue, theo dõi ở đây bằng nhãn `upstream:rulestone`; trong lúc chờ, OpenFRAML dùng cách lách tạm
thời trong code của mình, kèm link tới issue.
Bốn mục đầu đã được mở và nằm trên đường găng dẫn tới cổng của giai đoạn 1.

| Nhu cầu | Hiện trạng RuleStone | Đề xuất |
| --- | --- | --- |
| Timeout cho từng lần đánh giá ([RuleStone#7](https://github.com/xvalleycorp/RuleStone/issues/7)) | Đang là kế hoạch | Cần cho ngân sách độ trễ của `gate` |
| Số đo hiệu năng công khai ([RuleStone#8](https://github.com/xvalleycorp/RuleStone/issues/8)) | Chưa có benchmark | Bộ JMH đo p99 và mức cấp phát bộ nhớ khi chạy đồng thời |
| Chạy shadow và canary một phiên bản rule ([RuleStone#9](https://github.com/xvalleycorp/RuleStone/issues/9)) | Đang là kế hoạch | Cần cho champion/challenger |
| Ghi decision record ra Kafka ([RuleStone#10](https://github.com/xvalleycorp/RuleStone/issues/10)) | `AuditSink` Kafka đang là kế hoạch | OpenFRAML viết trước rồi đề xuất đưa về upstream |
| Ngưỡng giao dịch giá trị lớn trong ví dụ AML | `aml_transaction_monitoring` dùng 300.000.000 đồng | Hai phiên bản trước và sau 01/12/2023, minh họa ngày hiệu lực |
| Che số tài khoản, số định danh trong log | `DataMasker` đang là kế hoạch | Cần cho bảo vệ dữ liệu cá nhân |
| Sàng lọc trên danh sách lớn | Phần tử danh sách được chuẩn hóa lại ở mọi lần đánh giá | Nhận danh sách đã chuẩn hóa sẵn |
| Điểm model làm đầu vào có chứng thực | Đề xuất thiết kế | Pattern A (điểm là đầu vào) trước Pattern B |
| Maker-checker, audit nối chuỗi hash | Đang là kế hoạch | Bắt buộc với rule tuân thủ |
| Backtest trên dữ liệu lịch sử | Đang là kế hoạch | Cần để chỉnh ngưỡng AML |
