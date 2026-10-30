# Gói quốc gia

Gói quốc gia chứa mọi thứ khác nhau giữa các nước, để lõi không nhắc tới nước nào. Việt Nam là gói đầu tiên, không
phải gói duy nhất: thêm một nước là thêm một gói, không sửa lõi.

## Nội dung của một gói

| Nội dung | Giai đoạn 1 | Sau đó |
| --- | --- | --- |
| Múi giờ nghiệp vụ và đồng tiền quốc gia | Có | |
| Bảng quyết định cho `gate` (`gate_decision`, `gate_fallback`) | Có | |
| Thông điệp lý do bằng tiếng Anh và ngôn ngữ của nước đó | Có | |
| Ngưỡng pháp lý có ngày hiệu lực (giao dịch giá trị lớn, chuyển tiền điện tử) | | Giai đoạn 2 |
| Kịch bản AML cho giám sát (`monitor_scenarios`), tách riêng khỏi các bảng của `gate` | | Giai đoạn 2: đã bắt đầu, xem [Giám sát giao dịch](monitoring.md) |
| Mẫu báo cáo và kênh nộp | | Giai đoạn 2 |
| Nguồn danh sách (cấm vận, công an, cơ quan quản lý) và chuẩn hóa tên | | Giai đoạn 2 |
| Adapter chuyển mạch, lịch ngày làm việc | | Giai đoạn 2 |

Một gói cài đặt `CountryPack` và tự khai báo trong `META-INF/services/vn.io.dxa.openframl.pack.CountryPack`.
`ResourceCountryPack` đọc bảng và thông điệp từ resource trong classpath, cạnh lớp của gói; thông điệp lý do là các
file UTF-8 `reasons_<ngôn ngữ>.properties`, bắt buộc có tiếng Anh để làm phương án mặc định.

Bảng quyết định có ngày hiệu lực, và gói giữ mọi phiên bản cũ của từng bảng, nên quyết định của một ngày bất kỳ chạy
lại được với đúng rule có hiệu lực hôm đó. Khi quy định thay đổi, gói có thêm một phiên bản bảng mang ngày hiệu lực
của văn bản mới; code không thay đổi.

## Giữ lõi không phụ thuộc quốc gia

Bộ test của lõi chạy với một nước giả lập, `XA`, dùng mã tiền tệ kiểm thử `XTS` của ISO 4217, giống cách ví dụ của
RuleStone dùng mã quốc gia giả `XA` và `XB`. Một test khác đọc mã nguồn của lõi và báo lỗi nếu gặp tiền tệ, múi giờ,
cơ quan quản lý hay chuyển mạch của một nước thật. Không test nào đụng tới Việt Nam, nên nếu lõi vô tình phụ thuộc
Việt Nam, build sẽ thất bại.

## Gói quốc gia Việt Nam (`openframl-vn`)

`openframl-vn` (phiên bản `2026.10.0`) dùng múi giờ `Asia/Ho_Chi_Minh` và đồng tiền `VND`. Các bảng của `gate` là
bảng mẫu: ngưỡng trong đó minh họa các thủ đoạn phổ biến ở Việt Nam, không phải ngưỡng pháp lý. Mỗi ngân hàng tự
chỉnh bản của mình và giữ bí mật, vì ai biết rule và ngưỡng thì biết cách né.

| Rule | Hành động | Thủ đoạn |
| --- | --- | --- |
| `WATCHLISTED_BENEFICIARY` | `HOLD` | Người thụ hưởng có trong danh sách tài khoản nghi ngờ; bắt buộc, không điểm nào hạ được |
| `REMOTE_ACCESS` | `HOLD` | Có ứng dụng điều khiển từ xa khi giao dịch (app giả chiếm quyền điện thoại) |
| `CALL_TO_NEW_BENEFICIARY` | `CHALLENGE` | Đang nghe điện thoại khi chuyển cho người thụ hưởng mới (giả danh công an, cơ quan nhà nước) |
| `NEW_DEVICE_LARGE` | `CHALLENGE` | Từ 10.000.000 đồng trên thiết bị mới |
| `YOUNG_BENEFICIARY` | `CHALLENGE` | Từ 20.000.000 đồng tới tài khoản mở chưa đầy 30 ngày (tài khoản thuê, mua) |
| `NEW_BENEFICIARY_LARGE` | `CHALLENGE` | Từ 50.000.000 đồng tới người thụ hưởng mới |
| `RAPID_SUCCESSION` | `CHALLENGE` | Từ 5 lệnh trong 10 phút |
| `MANY_BENEFICIARIES` | `CHALLENGE` | Từ 10 người thụ hưởng trong 24 giờ |
| `REPEATED_NEAR_LIMIT` | `CHALLENGE` | Nhiều lệnh liên tiếp sát hạn mức chuyển nhanh 499.999.999 đồng, ngay dưới ngưỡng báo cáo 500.000.000 đồng |
| `FRAUD_SCORE_HIGH`, `FRAUD_SCORE_ELEVATED` | `HOLD`, `CHALLENGE` | Điểm model từ 0,9, và từ 0,7 đến dưới 0,9 |

Bảng mẫu ưu tiên `CHALLENGE` hơn `DENY`: chặn nhầm một giao dịch thật cũng tốn kém, và trong lừa đảo, chính khách
hàng xác nhận lệnh, nên một cảnh báo đúng lúc mới là thứ chặn được. Mỗi rule và cả hai phía của từng ngưỡng đều có
trường hợp kiểm thử trong `openframl-vn/src/test/rules`; build thất bại nếu có rule chưa từng được kích hoạt.

Ngưỡng pháp lý và mẫu báo cáo được thêm ở giai đoạn 2, mỗi mục đối chiếu với Công báo trước khi đưa vào. Phần phân
tích đến nay dựa trên nguồn thứ cấp.

## Thêm một quốc gia

1. Tạo module `openframl-<mã quốc gia>` với một lớp kế thừa `ResourceCountryPack` và file service của nó.
2. Viết `gate_decision` và `gate_fallback` theo thủ đoạn của nước đó, dựa trên các facts trong
   [`gate`](gate.md#facts), mỗi rule có một trường hợp trong `.stone-test.json`.
3. Thêm thông điệp bằng tiếng Anh và ngôn ngữ của nước đó cho mọi mã lý do.
4. Chạy `mvn verify`. Lõi không thay đổi; nếu buộc phải đổi, mở issue trước.
