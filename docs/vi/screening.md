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
  van duc". Các chữ viết tắt được viết đầy đủ theo quy tắc tên của gói quốc gia: với gói Việt Nam, "TM" là "thuong
  mai" và "XNK" là "xuat nhap khau".
- **So sánh.** Các phần của hai tên được ghép cặp, cặp giống nhau nhất trước, theo độ tương đồng Jaro-Winkler và bất
  kể thứ tự, nên "Duc Nguyen Van" khớp với "Nguyễn Văn Đức". Hai phần có độ tương đồng dưới 0,8 được coi là hai từ khác
  nhau. Thiếu hay thừa một phần sẽ làm giảm điểm: "Pham Thi Nhung" so với "Phạm Thị Hồng Nhung" được khoảng 0,86. Với
  tổ chức, phần lõi của tên, sau khi bỏ hình thức pháp lý ("Cong ty", "TNHH", "Ltd"), cũng được so sánh. Các hình
  thức pháp lý quốc tế có sẵn; gói quốc gia bổ sung các hình thức của nước mình.
- **Bằng chứng khác.** Trùng số định danh (CCCD, hộ chiếu hay mã số doanh nghiệp) là khớp, bất kể tên. Khác loại (cá
  nhân so với tổ chức) thì điểm nhân với 0,85; năm sinh cách mọi năm sinh mà danh sách ghi hơn một năm thì nhân với
  0,8. Mỗi lần điều chỉnh đều được ghi trong lý do của kết quả, nên chuyên viên thấy vì sao kết quả có điểm như vậy.
- **Tốc độ.** Tên được tra qua một chỉ mục các cụm ba chữ cái; chỉ những tên có chung ít nhất một nửa số cụm của tên
  cần tra mới được tính điểm. Sàng lọc theo tên dùng khi mở tài khoản và giám sát, không nằm trong vài mili-giây của
  `gate`.

Tên người Việt trùng nhau rất nhiều: nhiều người có cùng họ và tên đệm. Vì vậy, chỉ khớp tên là lý do để xem xét,
chưa phải kết luận; năm sinh và số định danh mới là thứ phân biệt hai người trùng tên, còn ngưỡng (0,85 trong các ví
dụ) do ngân hàng tự điều chỉnh.

## Sàng lọc giao dịch

`PaymentScreening` sàng lọc tên người thụ hưởng mà mỗi giao dịch mang theo (`beneficiaryName` trong mô hình sự kiện,
lấy từ tra cứu tên của hệ thống thanh toán), sau giao dịch, chạy bên cạnh `gate` chứ không nằm trong `gate`. Mỗi mục
trong danh sách mà tên đó có thể trùng sẽ thành một cảnh báo cho Quản lý hồ sơ điều tra (`openframl-case`):

- Cảnh báo nói về khách hàng trả tiền, người đã chuyển tiền cho một người có thể nằm trong danh sách; các cảnh báo về
  cùng một khách hàng được gom vào một hồ sơ.
- Mã cảnh báo được tạo từ sự kiện, danh sách và mục trong danh sách, nên sàng lọc lại một giao dịch không sinh thêm
  cảnh báo mới.
- Bằng chứng gồm những gì chuyên viên cần: danh sách và phiên bản, mục trong danh sách và ghi chú của nó, tên trên giao
  dịch và tên trong danh sách gần với nó, điểm số và lý do, và số tiền; không bao giờ có số tài khoản.
- Kết quả được giữ trong cache theo tên người thụ hưởng, chừng nào danh sách đang dùng chưa thay đổi, vì cùng một tên
  thường xuất hiện lại.

`ScreeningDirectory` giữ các danh sách trong một thư mục: các file `<source>@<version>.csv` theo dạng CSV mở, và danh
sách của Liên hợp quốc dưới tên `UN-SC*.xml`. Mỗi khi có file thay đổi, nó dựng một screener mới; nếu không đọc được
một file, các danh sách cũ vẫn được dùng. Dịch vụ luồng giao dịch của Luồng giao dịch ([`openframl-stream`](stream.md))
đưa các giao dịch từ topic giao dịch của ngân hàng vào; một batch job cũng có thể gọi trực tiếp.

## Quy tắc về tên lấy từ gói quốc gia

Gói quốc gia ghi quy tắc về tên trong `names.properties`, một file properties mã UTF-8 đặt cạnh các bảng của gói:

```
# Chữ viết tắt, rồi đến nghĩa đầy đủ
abbreviation.TM = Thương mại
# Các hình thức pháp lý, cách nhau bằng dấu cách
legalForms = Công ty TNHH Cổ phần MTV Tập đoàn
```

Khóa và giá trị được chuẩn hóa như tên, nên có thể viết có dấu và viết hoa tùy ý. Khóa nào không phải
`abbreviation.<từ>` hay `legalForms` sẽ làm gói quốc gia không nạp được. `NameRules.of(pack)` cho screener các hình
thức pháp lý quốc tế ("Co", "Ltd", "LLC", "Inc", "GmbH"...) cùng với quy tắc của gói; Dịch vụ luồng giao dịch dùng quy
tắc của gói quốc gia đang chạy.

Gói Việt Nam viết đầy đủ `CTY` (công ty), `TM` (thương mại), `DV` (dịch vụ), `SX` (sản xuất), `XNK` (xuất nhập khẩu),
`XD` (xây dựng), `DT` (đầu tư) và `CP` (cổ phần), và bỏ qua "Công ty", "TNHH", "Cổ phần", "MTV" và "Tập đoàn". Nếu
thiếu các quy tắc này, "CTY CP XD DT HOANG LONG" sẽ không khớp với "Công ty Cổ phần Xây dựng Đầu tư Hoàng Long".

## Các danh sách mà gói quốc gia yêu cầu

Gói quốc gia nêu tên các danh sách mà quy định của nước đó yêu cầu ngân hàng sàng lọc (`CountryPack.screeningLists()`).
Với mỗi danh sách, gói ghi tên nguồn trên các file của danh sách, đơn vị công bố, định dạng file, và văn bản quy định
việc áp dụng. `ScreeningDirectory.missing` cho biết thư mục đang thiếu những danh sách nào trong số đó, và Dịch vụ
luồng giao dịch ghi một cảnh báo cho mỗi danh sách thiếu khi khởi động.

| Danh sách | Tên nguồn | Đơn vị công bố | Định dạng |
| --- | --- | --- | --- |
| Danh sách tổng hợp của Hội đồng Bảo an Liên hợp quốc | `UN-SC` | Hội đồng Bảo an Liên hợp quốc | XML của Liên hợp quốc |
| Danh sách đen: khủng bố và tài trợ khủng bố | `VN-BLACKLIST-TERRORISM` | Bộ Công an | CSV mở |
| Danh sách đen: tài trợ phổ biến vũ khí hủy diệt hàng loạt | `VN-BLACKLIST-PROLIFERATION` | Bộ Quốc phòng | CSV mở |
| Danh sách cảnh báo: rủi ro rửa tiền cao | `VN-WARNING` | Ngân hàng Nhà nước Việt Nam | CSV mở |

Danh sách đen và danh sách cảnh báo được định nghĩa trong Luật Phòng, chống rửa tiền 14/2022/QH15. Các bản công bố ghi
phần định nghĩa ở Điều 3 hoặc Điều 4, nên số điều cần được xác nhận trên Công báo. Điều 44 yêu cầu ngân hàng trì hoãn
giao dịch khi nghi ngờ một bên nằm trong danh sách đen. Các danh sách quốc gia được công bố dưới dạng quyết định, không
ở dạng chương trình đọc được, nên ngân hàng chuyển mỗi danh sách sang dạng CSV mở, đặt tên `<tên nguồn>@<phiên bản>.csv`.

## Danh sách của Hội đồng Bảo an Liên hợp quốc

`UnConsolidatedList` đọc danh sách tổng hợp của Hội đồng Bảo an Liên hợp quốc ở dạng XML do Liên hợp quốc công bố
(`https://scsanctions.un.org/resources/xml/en/consolidated.xml`), lấy `dateGenerated` của danh sách làm phiên bản.

- Cá nhân thành mục `PERSON`, tổ chức thành mục `ORGANISATION`, với số tham chiếu của Liên hợp quốc (ví dụ
  `QDi.001`) làm mã.
- Tên: các phần tên của cá nhân theo thứ tự; các tên của tổ chức, mà danh sách nối với nhau bằng `;`; mọi alias; và
  tên viết bằng chữ gốc.
- Alias bị danh sách đánh dấu là chất lượng thấp sẽ bị bỏ qua, trừ khi được yêu cầu: chính Liên hợp quốc nói các alias
  này không đủ để nhận diện ai, và chúng gây ra phần lớn báo động giả.
- Năm sinh lấy từ ngày chính xác, ngày ước lượng và mọi năm trong một khoảng; số giấy tờ là số định danh.
- Bộ đọc không đọc DTD và không phân giải external entity nào.

Với danh sách công bố ngày 29/10/2026, bộ đọc đọc được 1.007 mục (733 cá nhân, 274 tổ chức) và 3.506 tên và alias, và
mục nào cũng được tìm thấy bằng tên chính của nó.

## Định dạng danh sách

Danh sách riêng của ngân hàng, và danh sách chính thức chuyển từ định dạng của đơn vị công bố, dùng chung một dạng CSV
mở, mã UTF-8:

```
id,kind,names,birth_year,nationalities,identifiers,remark
XA-0001,PERSON,Nguyễn Văn Đức|Duc Van Nguyen,1975,XA,XA-P-1234567,Invented sanctions programme
```

`kind` là `PERSON`, `ORGANISATION`, `VESSEL` hoặc `OTHER`. `names`, `birth_year`, `nationalities` và `identifiers`
chứa nhiều giá trị cách nhau bằng `|`, tên chính đứng đầu. Chỉ `id`, `kind` và `names` là bắt buộc. Một dòng không đọc được sẽ làm
cả danh sách thất bại, kèm số dòng.

## Tiếp theo

Trong [lộ trình](roadmap.md) của giai đoạn 2:

- Bộ đọc cho các danh sách mà nước khác công bố ở dạng chương trình đọc được, khi có gói quốc gia của nước đó.
- Trì hoãn giao dịch khi danh sách tài khoản của `gate` trúng một bên trong danh sách đen, qua bảng quyết định của `gate`.
