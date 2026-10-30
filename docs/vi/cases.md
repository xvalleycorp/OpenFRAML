# Quản lý hồ sơ điều tra (`openframl-case`)

Quản lý hồ sơ điều tra là nơi con người quyết định về những gì các bộ phát hiện tìm ra. Sàng lọc danh sách
(`openframl-screen`), Giám sát giao dịch (`openframl-monitor`, dự kiến) và Chặn giao dịch (`openframl-gate`) phát sinh
cảnh báo; chuyên viên điều tra; người phụ trách phê duyệt mọi kết luận. Không kết luận nào do một người tự đưa ra, và
mọi bước đều được ghi lại.

## Cảnh báo và hồ sơ

Mỗi `Alert` ghi nguồn (`screening`, `monitoring`, `gate`), điều phát hiện được (ví dụ `SANCTIONS_NAME_MATCH`), đối tượng
liên quan (mã khách hàng của ngân hàng hoặc một tham chiếu ổn định khác), giao dịch gây ra cảnh báo, điểm số, và các
bằng chứng chuyên viên cần: với kết quả sàng lọc, đó là danh sách, phiên bản, mục trong danh sách và tên khớp.

`CaseService.raise` đưa cảnh báo vào hồ sơ chưa đóng của đối tượng, hoặc mở hồ sơ mới. Nhờ vậy chuyên viên thấy toàn
bộ thông tin về một đối tượng cùng lúc, thay vì mỗi cảnh báo một hồ sơ. Cảnh báo có mã đã nằm trong một hồ sơ thì
không thay đổi gì, nên bộ phát hiện có thể gửi lại cảnh báo mà không tạo việc hai lần. Các cảnh báo về cùng một đối
tượng đến cùng lúc vẫn vào chung một hồ sơ.

## Điều tra và phê duyệt

| Bước | Ai thực hiện | Trạng thái sau đó |
| --- | --- | --- |
| Cảnh báo mở hồ sơ | Bộ phát hiện | `OPEN` |
| Giao hồ sơ | Chuyên viên tự nhận, hoặc người phụ trách giao | `IN_REVIEW` |
| Ghi chú những gì đã kiểm tra | Chuyên viên và người phụ trách | Không đổi |
| Đề xuất kết luận: `FALSE_POSITIVE`, `CONFIRMED` hoặc `REPORTED`, kèm lý do | Chuyên viên được giao | `AWAITING_APPROVAL` |
| Phê duyệt kết luận | Người phụ trách không phải người đề xuất | `CLOSED` |
| Trả lại kết luận, kèm lý do | Người phụ trách | `IN_REVIEW` |

- **Bốn mắt.** Người đề xuất kết luận không bao giờ được tự phê duyệt kết luận đó, kể cả khi có cả hai vai trò.
- **Bằng chứng mới được ưu tiên.** Cảnh báo đến khi kết luận đang chờ phê duyệt sẽ đưa hồ sơ về lại bước xem xét, vì
  kết luận đó chưa tính đến cảnh báo mới.
- **Lịch sử.** Mọi thao tác đều được lưu cùng người thực hiện và thời điểm: cảnh báo đến, giao hồ sơ, ghi chú, đề xuất,
  phê duyệt và trả lại. Đây là dấu vết kiểm toán mà thanh tra đọc.
- **Mỗi lúc một người ghi.** `CaseStore` từ chối lưu một hồ sơ đã thay đổi kể từ khi được đọc, nên hai chuyên viên thao
  tác cùng lúc không thể ghi đè lên nhau.

## Lưu trữ hồ sơ

`JdbcCaseStore` lưu hồ sơ trong cơ sở dữ liệu quan hệ qua JDBC, ở dạng bảng thông thường mà thanh tra hay báo cáo có
thể truy vấn bằng SQL: `ofr_case` cho mỗi hồ sơ và trạng thái hiện tại, `ofr_case_alert` và `ofr_case_alert_evidence`
cho cảnh báo của hồ sơ, và `ofr_case_action` cho lịch sử. PostgreSQL là cơ sở dữ liệu tham chiếu; lược đồ nằm trong
[`schema-postgresql.sql`](../../openframl-case/src/main/resources/vn/io/dxa/openframl/cases/jdbc/schema-postgresql.sql),
và ngân hàng tự cung cấp connection pool cùng driver.

- Mỗi lần lưu là một transaction. Dòng hồ sơ mang một phiên bản, chính là độ dài lịch sử, và chỉ được cập nhật nếu
  phiên bản vẫn là phiên bản đã đọc; nếu không, lần lưu bị từ chối và thao tác có thể thực hiện lại.
- Cảnh báo, bằng chứng và lịch sử chỉ được thêm vào qua kho, không bao giờ bị sửa hay xóa.
- Một unique index chỉ cho phép mỗi đối tượng có tối đa một hồ sơ chưa đóng, nên các cảnh báo phát sinh cùng lúc trên
  nhiều node vẫn chỉ mở một hồ sơ.
- `InMemoryCaseStore` vẫn được giữ cho test và chạy thử.

## Dịch vụ hồ sơ điều tra

Dịch vụ hồ sơ điều tra (`openframl-case-server`) cung cấp Quản lý hồ sơ điều tra qua gRPC
([`openframl/cases/v1/case_service.proto`](../../openframl-case-server/src/main/protobuf/openframl/cases/v1/case_service.proto)),
chỉ qua mutual TLS. Người gọi là common name trong client certificate do CA của ngân hàng cấp, và ngân hàng liệt kê tên
nào là chuyên viên, người phụ trách và bộ phát hiện:

| Người gọi | Được làm |
| --- | --- |
| Bộ phát hiện (ví dụ một node sàng lọc) | Chỉ `RaiseAlert` |
| Chuyên viên | Đọc hồ sơ, nhận hồ sơ, ghi chú, đề xuất kết luận cho hồ sơ được giao cho mình |
| Người phụ trách | Đọc hồ sơ, giao hồ sơ, ghi chú, phê duyệt hoặc trả lại kết luận không phải do mình đề xuất |

Mọi lời gọi đều được ghi vào lịch sử của hồ sơ dưới tên người gọi. Mỗi kiểu từ chối có mã trạng thái riêng:
`UNAUTHENTICATED` khi không có certificate, `PERMISSION_DENIED` khi người gọi không có vai trò cần thiết,
`FAILED_PRECONDITION` khi hồ sơ chưa ở bước cho phép thao tác đó, `ABORTED` khi người khác đã sửa hồ sơ trước (đọc lại
hồ sơ rồi thử lại).

| Biến môi trường | Mặc định | Mục đích |
| --- | --- | --- |
| `OPENFRAML_CASE_PORT` | `9190` | Cổng |
| `OPENFRAML_CASE_TLS_CERT`, `OPENFRAML_CASE_TLS_KEY` | Bắt buộc | Chuỗi certificate và khóa PKCS#8 của server, dạng PEM |
| `OPENFRAML_CASE_TLS_CLIENT_CA` | Bắt buộc | CA cấp client certificate |
| `OPENFRAML_CASE_ANALYSTS`, `OPENFRAML_CASE_SUPERVISORS`, `OPENFRAML_CASE_DETECTORS` | Không có | Common name của certificate, cách nhau bằng dấu phẩy |
| `OPENFRAML_CASE_JDBC_URL` | Không đặt: hồ sơ nằm trong bộ nhớ, chỉ để chạy thử | Cơ sở dữ liệu hồ sơ; driver JDBC của nó đặt trên class path |

## Tiếp theo

Sàng lọc danh sách đã phát sinh cảnh báo cho những người thụ hưởng có tên có thể nằm trong danh sách (xem
[Sàng lọc danh sách](screening.md#sàng-lọc-giao-dịch)). Trong [lộ trình](roadmap.md): giao diện cho chuyên viên, và báo cáo giao dịch đáng ngờ từ các hồ sơ `REPORTED` qua Báo cáo theo quy định
(`openframl-report`).
