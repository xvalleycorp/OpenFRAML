# Đo hiệu năng (`openframl-benchmark`)

Cổng của giai đoạn 1 là p99 dưới 30 ms ở 20.000 TPS trên máy chủ x86 phổ thông. Module Đo hiệu năng đo theo hai cách,
cả hai trên một ngân hàng tổng hợp tất định, với Gói quốc gia Việt Nam. Mục tiêu chưa được kiểm chứng: các lần chạy
đầu tiên dưới đây làm trên một máy phát triển, bộ sinh tải và server chạy cạnh nhau; các lần đo trên máy chủ sẽ làm
tiếp theo.

## Đo cái gì

| Công cụ | Đo | Mạng |
| --- | --- | --- |
| `GateBenchmark` (JMH, sample time) | Một quyết định trong tiến trình: danh sách, hồ sơ, velocity, model (tùy chọn), bảng quyết định | Không |
| `LoadTest` (vòng hở) | Đầu-cuối qua gRPC, ở tốc độ cố định; với server nhúng, đo thêm thời gian từng bước của `gate` | gRPC qua loopback, hoặc tới server ở máy khác |

Ngân hàng tổng hợp có 100.000 khách hàng, mỗi người có hồ sơ, bốn người thụ hưởng quen và một thiết bị quen; 1% tài
khoản mule mở trong 20 ngày gần đây, một phần mười số mule nằm trong danh sách. Tỷ lệ giao dịch theo các thủ đoạn của
gói Việt Nam: 80% thông thường, 8% giá trị nhỏ và 4% giá trị lớn tới người thụ hưởng mới, 3% lừa đảo qua điện thoại
tới tài khoản mule, 2% thiết bị mới, 2% người thụ hưởng trong danh sách, 1% điều khiển từ xa. Mỗi luồng JMH chuyển
tiền cho một phần khách hàng riêng, giống một node chỉ nhận các tài khoản được định tuyến tới nó.

`LoadTest` gửi theo lịch cố định, bất kể các giao dịch trước đã được trả lời hay chưa, và đo độ trễ từ thời điểm giao
dịch lẽ ra được gửi. Vì vậy server chậm sẽ hiện ra trong các phân vị, thay vì làm bộ gửi chậm lại (không bị
coordinated omission).

## Cách chạy

```bash
mvn -pl openframl-benchmark -am -DskipTests package
java -jar openframl-benchmark/target/openframl-benchmark.jar GateBenchmark -t 8
java -XX:+UseZGC -XX:+ZGenerational -Xms4g -Xmx4g \
  -cp openframl-benchmark/target/openframl-benchmark.jar vn.io.dxa.openframl.benchmark.LoadTest \
  --rate=20000 --seconds=60 --warmup=15
```

Tùy chọn của `LoadTest`: `--rate` (giao dịch mỗi giây), `--seconds` (thời gian đo), `--warmup`, `--deadline-ms`,
`--customers`, `--target=host:port` cho server ở máy khác.

Dùng bản cập nhật mới của JDK 21. Trên macOS 26, JDK 21.0.2 và 22.0.2 bị crash khi chạy tải với ZGC (`SIGBUS`, địa
chỉ lệch trong code đã biên dịch); vì vậy các lần chạy dưới đây dùng G1.

## Kết quả đầu tiên

Máy phát triển: ARM64 8 nhân, 16 GB, macOS 26.6, JDK 22.0.2, G1 với heap 4 GB, ngày 25/10/2026.

`GateBenchmark`, 4 luồng, độ trễ của một quyết định tính bằng micro-giây:

| Model | p50 | p90 | p99 | p99.9 | p99.99 |
| --- | --- | --- | --- | --- | --- |
| Không có (chỉ rule) | 16,8 | 19,7 | 50,6 | 129 | 19.300 |
| Giả lập (trả lời ngay, ở luồng khác) | 28,6 | 46,1 | 79,5 | 138 | 19.110 |

`LoadTest`, server nhúng, độ trễ tính bằng mili-giây:

| Tốc độ | Được trả lời | Client p50 | Client p99 | Client p99.9 | `gate` p99 | `gate` p99.9 |
| --- | --- | --- | --- | --- | --- | --- |
| 10.000 TPS, 20 s | 100% | 0,096 | 2,67 | 21,0 | 0,042 | 0,108 |
| 20.000 TPS, 30 s | 50% (còn lại `DEADLINE_EXCEEDED`) | 0,102 | 55,4 | 79,5 | 0,084 | 1,05 |

Các con số đến nay cho thấy:

- **Bản thân việc quyết định không phải nút thắt.** Trong tiến trình, p99.9 là 0,13 ms, nằm rất xa trong ngân sách
  30 ms. Bảng quyết định chiếm phần lớn (khoảng 16 µs ở p50); kho dữ liệu và danh sách chỉ vài micro-giây.
- **Đuôi độ trễ do GC.** p99.99 khoảng 19 ms, và p99.9 phía client 21 ms ở 10.000 TPS, khớp với các lần dừng của G1
  (16 lần dừng trong lượt 10.000 TPS, dài nhất 19, 25 và 38 ms). Đây là lý do thiết kế chọn Generational ZGC, với
  thời gian dừng dưới 1 ms.
- **Lượt 20.000 TPS chưa kết luận được.** Bộ gửi bị trễ lịch 19.219 lần: một máy 8 nhân chạy cả bộ gửi, client và
  server, nên máy bão hòa trước khi `gate` bão hòa.
- **Chuyển việc chấm điểm sang luồng khác tốn khoảng 12 µs** ở p50, nằm trong ngân sách 5 ms của model.

## Các lần đo tiếp theo

1. Máy chủ Linux x86 theo hồ sơ sizing (16 vCPU, 64 GB), Temurin 21 bản mới, Generational ZGC.
2. Bộ sinh tải chạy trên máy riêng, nhiều kênh gRPC, nhắm vào một node, sau đó một cụm định tuyến theo tài khoản.
3. Đưa kho dữ liệu Valkey và ghi bản ghi lên Kafka vào luồng khi đã có.
4. Công bố kết quả tại đây, và gửi số đo của RuleStone lên upstream cùng issue về benchmark.
