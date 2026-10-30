# Sàng lọc danh sách (`openframl-screen`)

Sàng lọc danh sách đối chiếu cá nhân, tổ chức và tài khoản với các danh sách cấm vận, PEP và danh sách của từng nước.
Module có hai phần: danh sách tài khoản nghi ngờ, được Chặn giao dịch (`openframl-gate`) kiểm tra trước mọi bước khác,
và sàng lọc theo tên, dùng khi mở tài khoản, rà soát định kỳ và giám sát. Một kết quả khớp không bao giờ tự nó là
quyết định: đó là bằng chứng cho bảng quyết định của `gate` hoặc cho chuyên viên.

## Danh sách tài khoản cho `gate`

Danh sách tài khoản nghi ngờ gian lận của cơ quan quản lý, danh sách riêng của ngân hàng, và danh sách chia sẻ giữa
các ngân hàng là các danh sách tài khoản chính xác. Mỗi danh sách là một file CSV tên `<source>@<version>.csv`, mã
UTF-8, với dòng tiêu đề `institution,account`:

```
institution,account
999902,5555666677
```

`WatchlistDirectory` giữ cho danh sách nghi ngờ của `gate` luôn khớp với một thư mục các file như vậy. Nó nạp các file
khi khởi động (`gate` không khởi động nếu không đọc được), và nạp lại trong vài giây mỗi khi có file được thêm, xóa hay
sửa. Danh sách được thay toàn bộ cùng lúc, nên mỗi giao dịch thấy hoặc bộ danh sách cũ, hoặc bộ mới. Nếu không đọc
được file mới, `gate` giữ các danh sách đang có và ghi lỗi, nêu số dòng nhưng không bao giờ ghi số tài khoản. Hãy ghi
danh sách mới dưới một tên tạm rồi đổi tên vào chỗ. Dịch vụ chặn giao dịch đọc thư mục do
`OPENFRAML_GATE_WATCHLIST_DIR` chỉ định; mọi quyết định về một tài khoản có trong danh sách đều ghi lại nguồn và phiên
bản của danh sách.

## Sàng lọc theo tên

`Screener` sàng lọc một tên trên một tập danh sách, được dựng một lần cho mỗi lần danh sách được công bố:

- **Chuẩn hóa.** Bỏ qua dấu, chữ hoa chữ thường và dấu câu, và `đ` được coi là `d`: "Nguyễn Văn Đức" thành "nguyen
  van duc". Các chữ viết tắt phổ biến trong tên doanh nghiệp Việt Nam được viết đầy đủ ("TM" là "thuong mai", "XNK"
  là "xuat nhap khau").
- **So sánh.** Các phần của hai tên được ghép cặp, cặp giống nhau nhất trước, theo độ tương đồng Jaro-Winkler và bất
  kể thứ tự, nên "Duc Nguyen Van" khớp với "Nguyễn Văn Đức". Hai phần có độ tương đồng dưới 0,8 được coi là hai từ khác
  nhau. Thiếu hay thừa một phần sẽ làm giảm điểm: "Pham Thi Nhung" so với "Phạm Thị Hồng Nhung" được khoảng 0,86. Với
  tổ chức, phần lõi của tên, sau khi bỏ hình thức pháp lý ("Cong ty", "TNHH", "Ltd"), cũng được so sánh.
- **Bằng chứng khác.** Trùng số định danh (CCCD, hộ chiếu hay mã số doanh nghiệp) là khớp, bất kể tên. Khác loại (cá
  nhân so với tổ chức) thì điểm nhân với 0,85; năm sinh cách nhau hơn một năm thì nhân với 0,8. Mỗi lần điều chỉnh đều
  được ghi trong lý do của kết quả, nên chuyên viên thấy vì sao kết quả có điểm như vậy.
- **Tốc độ.** Tên được tra qua một chỉ mục các cụm ba chữ cái; chỉ những tên có chung ít nhất một nửa số cụm của tên
  cần tra mới được tính điểm. Sàng lọc theo tên dùng khi mở tài khoản và giám sát, không nằm trong vài mili-giây của
  `gate`.

Tên người Việt trùng nhau rất nhiều: nhiều người có cùng họ và tên đệm. Vì vậy, chỉ khớp tên là lý do để xem xét,
chưa phải kết luận; năm sinh và số định danh mới là thứ phân biệt hai người trùng tên, còn ngưỡng (0,85 trong các ví
dụ) do ngân hàng tự điều chỉnh.

## Định dạng danh sách

Danh sách riêng của ngân hàng, và danh sách chính thức chuyển từ định dạng của đơn vị công bố, dùng chung một dạng CSV
mở, mã UTF-8:

```
id,kind,names,birth_year,nationalities,identifiers,remark
XA-0001,PERSON,Nguyễn Văn Đức|Duc Van Nguyen,1975,XA,XA-P-1234567,Invented sanctions programme
```

`kind` là `PERSON`, `ORGANISATION`, `VESSEL` hoặc `OTHER`. `names`, `nationalities` và `identifiers` chứa nhiều giá
trị cách nhau bằng `|`, tên chính đứng đầu. Chỉ `id`, `kind` và `names` là bắt buộc. Một dòng không đọc được sẽ làm
cả danh sách thất bại, kèm số dòng.

## Tiếp theo

Trong [lộ trình](roadmap.md) của giai đoạn 2:

- Đọc các định dạng chính thức: danh sách tổng hợp của Hội đồng Bảo an Liên hợp quốc, và các danh sách quốc gia mà
  gói quốc gia chỉ định.
- Sàng lọc tên người thụ hưởng của giao dịch, sau giao dịch, với cảnh báo chuyển sang Quản lý hồ sơ điều tra
  (`openframl-case`).
- Quy tắc về tên lấy từ gói quốc gia (chữ viết tắt, hình thức pháp lý) thay cho bộ quy tắc tiếng Việt cài sẵn.
