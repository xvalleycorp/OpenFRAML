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

## Tiếp theo

Trong [lộ trình](roadmap.md): `CaseStore` lưu trữ bền vững, API và giao diện cho chuyên viên, cảnh báo từ việc sàng
lọc người thụ hưởng, và báo cáo giao dịch đáng ngờ từ các hồ sơ `REPORTED` qua Báo cáo theo quy định
(`openframl-report`).
