# Mô hình chi phí

Chi phí để ngân hàng sở hữu OpenFRAML, theo từng nhóm: ngân hàng trả tiền cho những gì, lựa chọn thiết kế nào giúp
giảm chi phí đó, và phần nào vẫn phải trả. Tổng chi phí sở hữu quyết định việc chọn một nền tảng FRAML không kém gì
tính năng, nên trang này được viết để ngân hàng điền số liệu của chính mình, không phải số của chúng tôi.

OpenFRAML chưa phát hành chính thức. Trang này không đưa ra con số tiết kiệm hay giá cả nào: các con số đó đến từ số
liệu của chính ngân hàng và, về sau, từ các chỉ số đo được khi chạy shadow (xem [Đo thực tế](#đo-thực-tế)). Các ô ghi
`[PLACEHOLDER]` để ngân hàng tự điền.

## Các nhóm chi phí

| Nhóm | Ngân hàng trả tiền cho | Điều gì giúp giảm | Phần vẫn còn |
| --- | --- | --- | --- |
| Bản quyền | Không gì cả: Apache 2.0, không phí theo giao dịch, tài khoản, module, node hay theo năm | Mọi dependency đều nằm trong danh sách licence được phép (Apache-2.0, MIT, MIT-0, BSD-3-Clause), được build kiểm tra | Hỗ trợ trả phí, nếu ngân hàng muốn |
| Máy chủ | Các node `gate`, Valkey, Kafka; về sau là tầng stream và tầng giám sát | Máy chủ x86 phổ thông, không cần GPU hay thiết bị chuyên dụng; hồ sơ và bộ đếm nằm trong bộ nhớ | Máy chủ cho tính sẵn sàng cao và site thứ hai; điện, chỗ đặt máy và bảo trì |
| Đội vận hành | Người vận hành các service Java, Valkey và Kafka | Các thành phần tiêu chuẩn mà đội nền tảng của ngân hàng có thể đã vận hành; container image, health check và metrics có trong lộ trình | Chính đội ngũ đó, kể cả trực sự cố |
| Xử lý cảnh báo | Chuyên viên xem xét giao dịch đáng ngờ và cảnh báo AML | `CHALLENGE` yêu cầu khách hàng tự xác nhận thay vì xếp hàng chờ chuyên viên; mọi quyết định kèm mã lý do; model chỉ bổ sung điểm | Chuyên viên cho phần còn lại, và cho hồ sơ AML |
| Thay đổi rule | Thời gian từ lúc đề xuất rule đến khi rule có hiệu lực | Rule là dữ liệu: đội của ngân hàng đề xuất, người duyệt phê duyệt, mọi node làm theo trong vài giây mà không cần phát hành bản mới; ngày hiệu lực chọn phiên bản đang áp dụng | Thời gian xem xét, kiểm thử và phê duyệt; backtest trước khi duyệt có trong lộ trình |
| Tích hợp | Kết nối payment hub, dữ liệu từ core banking và các báo cáo | Một lời gọi gRPC cho quyết định inline; decision record trên Kafka theo định dạng JSON mở; gói quốc gia chứa sẵn mẫu báo cáo theo quy định | Adapter riêng của ngân hàng, dự án tích hợp và kiểm thử |
| Nội dung tuân thủ | Ngưỡng, danh sách và mẫu báo cáo của từng nước | Làm một lần cho mỗi nước, trong một gói quốc gia dùng chung và có phiên bản | Cập nhật bảng và danh sách riêng của ngân hàng |
| Rút lui | Rời bỏ nền tảng, hoặc đổi đơn vị vận hành | Rule, hồ sơ và decision record ở định dạng mở; ngân hàng được đọc, sửa và fork mã nguồn | Chuyển dữ liệu sang hệ thống tiếp theo |

## Tính số máy

Số node `gate` được tính từ thông lượng một node chịu được trong mục tiêu độ trễ, đo trên phần cứng của ngân hàng
bằng [bộ đo hiệu năng](benchmark.md), không phải từ một bảng báo giá. Kết quả đo trên máy chủ chưa được công bố; cho
tới khi có, trang này chỉ đưa ra công thức.

```
số node mỗi site = ceil(TPS đỉnh × hệ số dự phòng / TPS mỗi node) + 1   (một node có thể hỏng lúc cao điểm)
số site          = 2                                                   (site thứ hai tiếp quản)
```

| Đầu vào | Lấy từ đâu | Giá trị |
| --- | --- | --- |
| TPS đỉnh | Payment hub của ngân hàng, giây bận nhất trong năm (ngày trả lương, ngày lễ) | `[PLACEHOLDER]` |
| Hệ số dự phòng | Kế hoạch tăng trưởng 3 năm tới của ngân hàng, ví dụ 1,5 | `[PLACEHOLDER]` |
| TPS mỗi node | Đo trên loại máy chủ của ngân hàng với Generational ZGC, p99 dưới mục tiêu độ trễ | `[PLACEHOLDER]` |

Các thành phần dùng chung, cho mỗi site:

| Thành phần | Giai đoạn | Cấu hình thường gặp | Ghi chú |
| --- | --- | --- | --- |
| Node `gate` | 1 | Theo công thức trên | Không giữ trạng thái ngoài cache; với kho Valkey, node nào cũng phục vụ được mọi tài khoản |
| Valkey | 1 | Một primary và một replica, hoặc một cụm nhỏ | Hồ sơ, bộ đếm velocity, policy đã duyệt; tính dung lượng theo số tài khoản và số lần giao dịch giữ trong 24 giờ |
| Kafka | 1 | 3 broker | Decision record; thời gian lưu theo chính sách kiểm toán của ngân hàng |
| Tầng stream (Flink) | 2 | Có trong lộ trình | Hồ sơ và feature; ngân hàng nhỏ có thể tính hồ sơ theo lô |
| Giám sát, hồ sơ điều tra, báo cáo | 2 | Có trong lộ trình | |

## Bảng tính tổng chi phí

Số liệu theo năm cho từng nhóm, điền từ số liệu của chính ngân hàng. So sánh với cùng bảng tính cho nền tảng ngân
hàng đang dùng, kể cả phí bản quyền và các yêu cầu thay đổi gửi nhà cung cấp.

| Nhóm | Năm 1 | Mỗi năm sau | Cách ngân hàng ước tính |
| --- | --- | --- | --- |
| Bản quyền | 0 | 0 | |
| Hỗ trợ trả phí (tùy chọn) | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Báo giá, nếu ngân hàng muốn có hợp đồng hỗ trợ |
| Máy chủ, cả hai site | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Số node theo [Tính số máy](#tính-số-máy) × chi phí mỗi máy chủ, cộng Valkey và Kafka |
| Đội vận hành | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Phần thời gian của đội nền tảng, kể cả trực sự cố |
| Xử lý cảnh báo | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Số cảnh báo mỗi năm × thời gian mỗi cảnh báo × chi phí chuyên viên |
| Thay đổi rule | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Số lần thay đổi mỗi năm × số giờ từ đề xuất đến khi có hiệu lực |
| Tích hợp | `[PLACEHOLDER]` | `[PLACEHOLDER]` | Chủ yếu ở năm 1 |
| Tổng | `[PLACEHOLDER]` | `[PLACEHOLDER]` | |

## Đo thực tế

Các chỉ số dưới đây biến ước tính trong bảng tính thành số đo. Chúng có được từ một lần chạy shadow tại ngân hàng:
OpenFRAML quyết định mọi giao dịch song song với hệ thống hiện tại nhưng không tác động lên giao dịch (giai đoạn 3
trong [lộ trình](roadmap.md)).

| Chỉ số | Cho thấy điều gì |
| --- | --- |
| Chi phí mỗi quyết định | Chi phí máy chủ và vận hành chia cho số quyết định |
| Số cảnh báo trên 1.000 giao dịch | Khối lượng việc của chuyên viên |
| Tỷ lệ báo động giả | Tỷ lệ cảnh báo không phải gian lận |
| Thời gian từ lúc đề xuất rule đến khi rule có hiệu lực | Chi phí của một lần thay đổi rule, và tốc độ ngân hàng phản ứng với thủ đoạn mới |

## Những chi phí vẫn còn

Vận hành OpenFRAML không miễn phí. Ngân hàng vẫn trả tiền cho máy chủ ở hai site, một đội biết Java, Valkey và Kafka
và trực sự cố, chuyên viên cho các cảnh báo còn lại, việc tích hợp với hệ thống của mình, và việc cập nhật rule cùng
danh sách. Thứ ngân hàng không phải trả là phí bản quyền tăng theo khối lượng giao dịch, hay tiền cho nhà cung cấp
mỗi lần đổi rule của chính mình.
