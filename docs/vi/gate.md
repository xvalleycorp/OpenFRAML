# Chặn giao dịch (`openframl-gate`)

Chặn giao dịch (`gate`) quyết định xử lý một giao dịch sau khi khách hàng xác nhận lệnh và trước khi payment hub gửi lệnh sang chuyển
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

## Thay đổi policy

Ngân hàng thay rule và ngưỡng thường xuyên, vì thủ đoạn lừa đảo thay đổi rất nhanh; việc này không cần phát hành bản
mới hay khởi động lại. Hai bảng tạo thành policy của `gate` (`GatePolicy`): mặc định là bảng mẫu của gói quốc gia,
hoặc bảng riêng của ngân hàng, giữ bí mật trong một thư mục các file `.stone.json` cùng mọi phiên bản cũ.

1. Thay đổi được soạn thành một bộ bảng mới và biên dịch. Bước biên dịch kiểm tra mọi thứ kiểm tra được trước khi có
   giao dịch: RuleStone kiểm tra kiểu mọi rule, còn `gate` yêu cầu đủ hai bảng và hành động hợp lệ. Policy không qua
   được kiểm tra thì không bao giờ được tạo ra.
2. Người thứ hai duyệt. `Approval` ghi người soạn, người duyệt và lý do; người duyệt không bao giờ được là người soạn.
3. `Gate.activate` ghi thay đổi vào `PolicyAudit`, rồi đổi policy trong một bước. Nếu nhật ký không ghi được, thay
   đổi không được áp dụng. Giao dịch bắt đầu từ lúc đó dùng policy mới; giao dịch đang được quyết định hoàn tất trên
   policy nó bắt đầu.
4. `Gate.rollback` quay về policy trước đó trong một bước, cũng được duyệt và ghi nhật ký như vậy.

Mỗi policy có một mã định danh là hash SHA-256 của nội dung các bảng, và mỗi quyết định ghi lại phiên bản bảng đã
quyết định. Backtest hoặc chạy shadow trước khi kích hoạt, và API quản trị có xác thực người soạn và người duyệt, là
các bước kế tiếp trong [lộ trình](roadmap.md).

## Bản ghi

Mỗi quyết định sinh một `GateRecord`, được chuyển cho record sink trước khi `decide` trả về. Sink gửi bản ghi bất đồng
bộ (lên Kafka) và không được chặn luồng. Bản ghi gồm mã sự kiện, kết quả, phiên bản rule kèm decision record của
RuleStone, phiên bản model và điểm, danh sách chứa người thụ hưởng, và thời gian của từng bước. Giống decision record
của RuleStone, bản ghi không chứa giá trị đầu vào: không có số tài khoản, tên hay số tiền.

`GateRecordJson` ghi mỗi bản ghi thành một dòng JSON theo schema `openframl.gate.record/1`; decision record của
RuleStone giữ nguyên định dạng JSON của RuleStone. Đây là định dạng mở: ngân hàng đọc được bản ghi của mình mà không
cần OpenFRAML. Trong cùng một phiên bản schema, không trường nào bị bỏ hay đổi tên.

Kết nối Kafka (`openframl-kafka`) đẩy bản ghi lên Kafka: `KafkaGateRecordSink` gửi mỗi bản ghi vào một topic với key là
mã sự kiện. Mỗi bản ghi phải được mọi replica đồng bộ xác nhận, retry không sinh bản trùng, và dữ liệu nén bằng lz4.
`accept` chỉ đặt bản ghi vào một hàng đợi có giới hạn (mặc định 100.000, khoảng 10 giây ở 10.000 TPS); một luồng gửi
riêng làm phần còn lại, vì Kafka producer có thể chặn bên gọi và luồng quyết định không được phải chờ. Khi hàng đợi
đầy, bản ghi bị bỏ, còn quyết định thì không bao giờ bị giữ lại: `dropped()` và `failed()` đếm số bản ghi bị mất, và
cần đặt cảnh báo trên hai số này.

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
| `policy` | Bảng mẫu của gói quốc gia | Bộ bảng dùng khi khởi động, ví dụ `GatePolicy.fromDirectory` |
| `policyAudit` | Ghi log thay đổi | Ghi mọi thay đổi policy trước khi có hiệu lực |

## Service gRPC

Dịch vụ chặn giao dịch (`openframl-gate-server`) cung cấp Chặn giao dịch qua gRPC, với API trong
[`openframl/gate/v1/gate.proto`](../../openframl-gate-server/src/main/protobuf/openframl/gate/v1/gate.proto): một lời
gọi `GateService.Decide`, từ `Transaction` ra `DecideResponse`.

- **Deadline.** Bên gọi đặt deadline cho mọi lời gọi. Service lấy thời gian còn lại trừ đi khoảng dành cho việc trả
  lời (mặc định 1 ms) làm ngân sách cho `gate`. Lời gọi không có deadline dùng ngân sách mặc định (30 ms).
- **Định tuyến.** Định tuyến lời gọi theo tài khoản gửi (consistent hashing trên `originator.institution` và
  `originator.account`), để bộ đếm velocity của mỗi tài khoản nằm trên một node và lệnh sau luôn thấy lệnh trước.
- **Lỗi.** Giao dịch thiếu hoặc sai bị từ chối với mã `INVALID_ARGUMENT`, kèm tên trường. Thông báo lỗi không bao giờ
  lặp lại số tài khoản.
- **Số tiền.** Số thập phân chính xác dạng chuỗi, không có số mũ (`"1500000"`, `"12.50"`); số thực dấu phẩy động
  không bao giờ tới được `gate`.

| Biến môi trường | Mặc định | Mục đích |
| --- | --- | --- |
| `OPENFRAML_COUNTRY` | `VN` | Gói quốc gia |
| `OPENFRAML_GATE_RULES_DIR` | Bảng mẫu của gói | Thư mục chứa các bảng `*.stone.json` riêng của ngân hàng |
| `OPENFRAML_GATE_PORT` | `9090` | Cổng |
| `OPENFRAML_GATE_DEFAULT_BUDGET_MS` | `30` | Ngân sách của lời gọi không có deadline |
| `OPENFRAML_GATE_REPLY_MARGIN_MS` | `1` | Thời gian giữ lại từ deadline của bên gọi để trả lời |
| `OPENFRAML_GATE_KAFKA_BOOTSTRAP` | Không đặt: không giữ bản ghi | Các broker Kafka nhận decision record |
| `OPENFRAML_GATE_RECORDS_TOPIC` | `openframl.gate.records` | Topic nhận decision record |

Cho tới khi có kho dữ liệu trên Valkey, server khởi động với kho trong bộ nhớ, rỗng: chỉ dùng để thử nghiệm và đo
hiệu năng, không dùng cho production. Ứng dụng có thể tự dựng `Gate` và phục vụ bằng `GateServer.start`.
