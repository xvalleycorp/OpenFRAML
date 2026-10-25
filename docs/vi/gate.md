# Luồng chặn giao dịch (`gate`)

`gate` quyết định xử lý một giao dịch sau khi khách hàng xác nhận lệnh và trước khi payment hub gửi lệnh sang chuyển
mạch. Chuyển khoản nhanh không thu hồi được, nên đây là thời điểm duy nhất để chặn. Mục tiêu là p99 dưới 30 ms, tính
từ lúc nhận request đến lúc trả quyết định; con số này sẽ được kiểm chứng bằng benchmark của giai đoạn 1.

## Các bước và ngân sách độ trễ

Mọi thứ `gate` đọc đều được tính sẵn và nằm trong bộ nhớ hoặc kho key-value cục bộ. RuleStone và model chạy ngay
trong tiến trình `gate`. Luồng nóng không truy vấn database quan hệ và không có lời gọi mạng nào ngoài các bước dưới
đây.

| Bước | Việc làm | Ngân sách (p99) |
| --- | --- | --- |
| Nhận | Giải mã request | 1 ms |
| Danh sách | Tra tài khoản thụ hưởng trong bản sao cục bộ của danh sách tài khoản nghi ngờ | 2 ms |
| Hồ sơ | Đọc tài khoản gửi, tài khoản nhận và thiết bị từ kho hồ sơ, trong một lời gọi | 3 ms |
| Velocity | Đếm lần giao dịch này và đọc bộ đếm ngắn hạn của tài khoản gửi | 2 ms |
| Model | Chấm điểm giao dịch; điểm đến muộn bị bỏ qua | 5 ms |
| Rule | Đánh giá bảng quyết định `gate_decision` | 2 ms |
| Trả lời | Trả quyết định; bản ghi kiểm toán được gửi bất đồng bộ | 1 ms |
| Dự phòng | Dừng GC, hàng đợi, đột biến | 14 ms |

Kiểm tra danh sách chạy đầu tiên, trước mọi bước có thể lỗi hoặc quá thời gian. Bộ đếm velocity được cập nhật trước
khi trả quyết định, và request được định tuyến tới node theo tài khoản gửi, nên lệnh sau luôn thấy lệnh trước. Bên
gọi gửi kèm deadline trong mỗi request; khi còn quá ít thời gian, `gate` bỏ bước model và quyết định chỉ bằng rule.

## Quyết định

| Quyết định | Ý nghĩa | Hành động phía ngân hàng |
| --- | --- | --- |
| `ALLOW` | Cho qua | Gửi lệnh sang chuyển mạch |
| `CHALLENGE` | Yêu cầu thêm | Hiển thị cảnh báo lừa đảo, xác thực lại, hoặc khách hàng xác nhận lần hai |
| `HOLD` | Tạm giữ để xem xét nhanh | Mở hồ sơ ưu tiên cao, liên hệ khách hàng |
| `DENY` | Từ chối | Thông báo khách hàng; gửi báo cáo nếu cần |

Bảng quyết định dùng hit policy `COLLECT`: mỗi rule kích hoạt đề xuất một hành động, hành động nghiêm khắc nhất được
chọn, và kết quả giữ mã lý do của tất cả các rule đó. Mã lý do dành cho nhân viên ngân hàng, không bao giờ hiển thị
cho khách hàng, vì lý do AML phải giữ bí mật.

## Facts

`gate` đưa các facts dưới đây vào cả hai bảng của mình. Bảng khai báo trường nào thì dùng trường đó; facts không được
khai báo sẽ bị bỏ qua. Trường mà `gate` không điền được (bước không chạy, bị lỗi, hoặc không áp dụng) được để trống,
nên bảng kiểm tra các trường không bắt buộc bằng `has(...)`.

| Fact | Kiểu | Có giá trị khi |
| --- | --- | --- |
| `transaction.amount` | money | Luôn có |
| `transaction.type`, `transaction.channel` | string | Luôn có |
| `originator.accountAgeDays` | int | Kho hồ sơ có tài khoản gửi |
| `originator.riskRating` | string | Tài khoản có xếp hạng rủi ro AML |
| `beneficiary.watchlisted` | bool | Giao dịch có người thụ hưởng và bước tra danh sách đã chạy |
| `beneficiary.watchlistSource` | string | Người thụ hưởng có trong danh sách |
| `beneficiary.known` | bool | Đọc hồ sơ thành công: tài khoản gửi đã từng chuyển cho người thụ hưởng này |
| `beneficiary.accountAgeDays` | int | Kho hồ sơ có tài khoản nhận |
| `beneficiary.sameInstitution` | bool | Giao dịch có người thụ hưởng |
| `device.remoteAccess`, `device.activeCall` | bool | Luôn có (`false` khi kênh không biết) |
| `device.known` | bool | Phiên có mã thiết bị và đọc hồ sơ thành công |
| `velocity.count10m`, `velocity.count24h`, `velocity.distinctBeneficiaries24h` | int | Bước velocity thành công; số đếm gồm cả lần này |
| `velocity.amount24h` | money | Bước velocity thành công; theo loại tiền của giao dịch này |
| `score.fraud` | decimal, từ 0 đến 1 | Có điểm kịp thời |
| `score.modelVersion` | string | Có điểm kịp thời |
| `failure` | string | Chỉ cho `gate_fallback`: `TIMEOUT`, `STORE_UNAVAILABLE` hoặc `POLICY_ERROR` |

Ngày nghiệp vụ dùng để chọn phiên bản bảng đang có hiệu lực là `occurredAt` của giao dịch theo múi giờ của gói quốc
gia. Vì vậy chạy lại một giao dịch sẽ dùng đúng rule có hiệu lực lúc giao dịch được thực hiện.

## Chính sách dự phòng

Khi quá deadline trước lúc bảng quyết định chạy, khi một kho dữ liệu lỗi, hoặc khi bảng quyết định lỗi, `gate` đánh
giá `gate_fallback` thay thế, với các facts đã thu được. Ngân hàng viết trong bảng này giao dịch nào được cho qua
(fail-open) và giao dịch nào bị giữ lại (fail-closed). Ví dụ với gói Việt Nam:

| Tình huống | Quyết định | Lý do |
| --- | --- | --- |
| Người thụ hưởng có trong danh sách | `HOLD` (fail-closed) | Kiểm tra danh sách chạy trước, không phụ thuộc phần bị lỗi |
| Giá trị nhỏ, người thụ hưởng quen thuộc | `ALLOW`, đánh dấu để xem lại (fail-open) | Rủi ro thấp; không làm gián đoạn khách hàng |
| Các trường hợp khác | `CHALLENGE` | An toàn hơn từ chối; khách hàng vẫn hoàn tất được |

Nếu `gate_fallback` cũng lỗi, phương án cuối cùng có sẵn trong code sẽ tạm giữ người thụ hưởng có trong danh sách và
yêu cầu xác thực thêm với mọi giao dịch khác, kèm mã lý do `GATE_FALLBACK_FAILED`.

Cả hai bảng được kiểm tra khi khởi tạo `gate`. Thiếu bảng, bảng không có output kiểu string `action`, hoặc rule đặt
một hành động không tồn tại sẽ làm khởi tạo thất bại, thay vì làm hỏng một giao dịch.

## Bản ghi

Mỗi quyết định sinh một `GateRecord`, được chuyển cho record sink trước khi `decide` trả về. Sink gửi bản ghi bất đồng
bộ (lên Kafka) và không được chặn luồng. Bản ghi gồm mã sự kiện, kết quả, phiên bản rule kèm decision record của
RuleStone, phiên bản model và điểm, danh sách chứa người thụ hưởng, và thời gian của từng bước. Giống decision record
của RuleStone, bản ghi không chứa giá trị đầu vào: không có số tài khoản, tên hay số tiền.

## Cấu hình

| Tùy chọn | Mặc định | Mục đích |
| --- | --- | --- |
| `profiles` | Bắt buộc | Hồ sơ tính sẵn |
| `velocity` | Bắt buộc | Bộ đếm ngắn hạn; `InMemoryVelocityStore` giữ bộ đếm ngay trên node |
| `watchlist` | Không có danh sách | Bản sao cục bộ của danh sách tài khoản nghi ngờ |
| `scoreProvider` | Không có model | Model gian lận; nếu không có, mọi quyết định đi nhánh chỉ có rule |
| `recordSink` | Bỏ bản ghi | Nơi nhận bản ghi kiểm toán |
| `modelBudget` | 5 ms | Thời gian tối đa chờ điểm của model |
| `ruleReserve` | 2 ms | Thời gian giữ lại cho bảng quyết định sau bước chấm điểm |
| `executor` | Virtual thread | Nơi chạy chấm điểm |
