# OpenFRAML

[English](../../README.md)

OpenFRAML là nền tảng phòng chống gian lận và rửa tiền (FRAML) mã nguồn mở cho ngân hàng. Nền tảng chặn giao dịch
gian lận trước khi tiền rời ngân hàng, đồng thời đáp ứng các nghĩa vụ phòng chống rửa tiền — giám sát giao dịch,
sàng lọc danh sách, quản lý hồ sơ và báo cáo theo quy định — trong cùng một hệ thống, dùng
[RuleStone](https://github.com/xvalleycorp/RuleStone) làm engine ra quyết định.

> **Trạng thái: trước phát hành, giai đoạn 1.** `gate` đã chạy được dưới dạng thư viện và có kiểm thử; tiếp theo là
> service gRPC, tích hợp Valkey và Kafka, và bộ benchmark. Chưa phát hành lên Maven Central. Xem
> [lộ trình](roadmap.md).

- **Hai luồng.** Luồng inline quyết định một giao dịch trong vài chục mili-giây, ngay trong luồng thanh toán. Luồng
  bất đồng bộ xử lý khối lượng lớn cho giám sát và báo cáo. Lời gọi inline là lời gọi đồng bộ duy nhất.
- **Rule ra quyết định, model chấm điểm.** Điểm của model là một đầu vào của bảng quyết định; rule bắt buộc không
  bao giờ bị model ghi đè. Mỗi quyết định ghi lại phiên bản rule và phiên bản model.
- **Trạng thái nằm ngoài engine.** RuleStone không lưu trạng thái và tất định. Velocity và hồ sơ hành vi được tính
  sẵn rồi đưa vào dưới dạng facts, nên mọi quyết định đều chạy lại được.
- **Lõi không gắn với quốc gia nào.** Mọi đặc thù của một nước — ngưỡng pháp lý, mẫu báo cáo, danh sách, chuyển
  mạch, múi giờ, ngôn ngữ — nằm trong gói quốc gia có phiên bản. Việt Nam (`openframl-vn`) là gói đầu tiên.
- **Mã nguồn mở từ đầu đến cuối.** Apache 2.0, mọi dependency nằm trong danh sách giấy phép cho phép và được build
  kiểm tra, chạy on-premise trên máy chủ x86 phổ thông, không cần GPU.

## Module

| Module | Chức năng | Giai đoạn | Trạng thái |
| --- | --- | --- | --- |
| [`openframl-model`](../../openframl-model) | Mô hình sự kiện giao dịch chung; SPI gói quốc gia | 1 | Dùng được |
| [`openframl-gate`](../../openframl-gate) | Quyết định inline trước khi gửi lệnh: `ALLOW`, `CHALLENGE`, `HOLD`, `DENY` | 1 | Thư viện dùng được; service gRPC là bước kế |
| [`openframl-vn`](../../openframl-vn) | Gói quốc gia Việt Nam: rule mẫu cho `gate`, lý do bằng tiếng Anh và tiếng Việt | 1 | Rule mẫu |
| `openframl-features` | Hồ sơ hành vi và velocity, tính bằng Apache Flink | 2 | Dự kiến |
| `openframl-monitor` | Kịch bản AML và gian lận sau giao dịch | 2 | Dự kiến |
| `openframl-screen` | Sàng lọc theo danh sách cấm vận, PEP và danh sách của từng nước | 2 | Dự kiến |
| `openframl-case` | Cảnh báo, điều tra, phê duyệt hai cấp | 2 | Dự kiến |
| `openframl-report` | Báo cáo cho cơ quan quản lý theo mẫu của từng gói quốc gia | 2 | Dự kiến |
| `openframl-ml` | Phục vụ model, champion/challenger, chạy shadow | 3 | Dự kiến |

## Bắt đầu

Cần JDK 21, Maven 3.9 trở lên và git. RuleStone chưa có trên Maven Central, nên cài từ mã nguồn một lần trước khi
build:

```bash
./scripts/install-rulestone.sh  # cài RuleStone tại commit cố định vào kho Maven cục bộ
mvn verify                      # build và chạy test mọi module
mvn -Plicense-check verify      # kiểm tra giấy phép của mọi dependency
```

Quyết định một giao dịch bằng `gate` với gói Việt Nam:

```java
Gate gate = Gate.builder(CountryPack.forCountry("VN"))
        .profiles(profileStore)          // hồ sơ tính sẵn: tuổi tài khoản, người thụ hưởng và thiết bị quen
        .velocity(new InMemoryVelocityStore())
        .watchlist(watchlist)            // bản sao cục bộ của danh sách tài khoản nghi ngờ
        .recordSink(kafkaPublisher)      // mỗi quyết định một bản ghi kiểm toán, không chứa dữ liệu cá nhân
        .build();

GateOutcome outcome = gate.decide(event, Duration.ofMillis(30));
// outcome.decision() == CHALLENGE, outcome.reasonCodes() == [ACTIVE_CALL_NEW_BENEFICIARY, ...]
```

## Tài liệu

- [`gate`: các bước, facts, chính sách dự phòng](gate.md)
- [Gói quốc gia](country-packs.md)
- [Lộ trình](roadmap.md)
- [Hướng dẫn đóng góp](../../CONTRIBUTING.md) · [Chính sách bảo mật](../../SECURITY.md)

## Dự án liên quan

- [RuleStone](https://github.com/xvalleycorp/RuleStone): engine ra quyết định. OpenFRAML dùng phiên bản đã phát hành
  và gửi nhu cầu qua GitHub issue; không fork RuleStone.
- [VOBS](https://github.com/xvalleycorp/vobs): Vietnam Open Banking Stack. API Gateway của VOBS gọi `gate` cho lệnh
  thanh toán do bên thứ ba khởi tạo.

## Giấy phép

[Apache License 2.0](../../LICENSE).
