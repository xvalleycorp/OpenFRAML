# Watchlist Screening (`openframl-screen`)

Watchlist Screening checks people, organisations and accounts against sanctions, PEP and national lists. It has two
parts: lists of suspicious accounts, which the Payment Gate (`openframl-gate`) checks before anything else, and name
screening, for onboarding, periodic review and monitoring. A match is never a decision by itself: it is evidence for
the gate's decision table or for an analyst.

## Account lists for the gate

The regulator's list of accounts suspected of fraud, the bank's own list, and lists shared between banks are exact
lists of accounts. Each is a CSV file named `<source>@<version>.csv`, UTF-8, with the header `institution,account`:

```
institution,account
999902,5555666677
```

`WatchlistDirectory` keeps a gate's watchlist equal to a directory of such files. It loads them at start-up (a gate
does not start if they cannot be read) and again within seconds when a file is added, removed or changed. The
watchlist is replaced as a whole, so a payment sees either the old lists or the new ones. If a new file cannot be
read, the gate keeps the lists it has and logs the error, naming the row but never an account number. Write a new
list under a temporary name and rename it into place. The Gate Service reads the directory named by
`OPENFRAML_GATE_WATCHLIST_DIR`; every decision on a listed account records the list's source and version.

## Name screening

`Screener` screens a name against a set of lists, built once per publication of the lists:

- **Normalising.** Diacritics, case and punctuation are ignored, and `đ` is `d`: "Nguyễn Văn Đức" is "nguyen van
  duc". Abbreviations are written out as the country pack's name rules say: with the Vietnam pack, "TM" is "thuong
  mai" and "XNK" is "xuat nhap khau".
- **Comparing.** The parts of the two names are paired best first by Jaro-Winkler similarity, in any order, so "Duc
  Nguyen Van" matches "Nguyễn Văn Đức". Parts less alike than 0.8 count as different words. A missing or extra part
  lowers the score: "Pham Thi Nhung" against "Phạm Thị Hồng Nhung" scores about 0.86. For an organisation, the core
  of the name without legal forms ("Cong ty", "TNHH", "Ltd") is compared too. The international legal forms are
  built in; the pack adds the country's own.
- **Other evidence.** The same identifier (ID, passport or registration number) is a match whatever the name. A
  different kind (person against organisation) multiplies the score by 0.85; a birth year more than a year from
  every year the list gives, by 0.8. Each adjustment is listed in the match's reasons, so an analyst sees why it
  scored what it did.
- **Speed.** Names are looked up through an index of their three-letter pieces; only names sharing at least half of
  the query's pieces are scored. Screening is meant for onboarding and monitoring, not the gate's few milliseconds.

Vietnamese names repeat a lot: many people share a family name and a middle name. A name match alone is therefore a
reason to look, not a finding; birth year and identifiers are what separate two people of the same name, and the
threshold (0.85 in the examples) is the bank's to tune.

## Screening payments

`PaymentScreening` screens the beneficiary name each payment carries (`beneficiaryName` in the event model, from the
scheme's name inquiry), after the payment, beside the gate rather than inside it. Each listed entry the name may be
becomes an alert for Case Management (`openframl-case`):

- The alert is about the paying customer, who sent money to someone who may be listed; alerts about one customer
  gather in one case.
- Its id is made from the event, the list and the entry, so screening a payment again raises nothing new.
- Its evidence is what the analyst needs: the list and its version, the entry and its remark, the name on the
  payment and the listed name it came close to, the score and its reasons, and the amount; never an account number.
- Results are cached per beneficiary name while the lists in use do not change, because the same names come back
  often.

`ScreeningDirectory` keeps the lists in one directory, `<source>@<version>.csv` files in the open CSV form and the UN
list as `UN-SC*.xml`, and builds a new screener when a file changes; if a file cannot be read, the previous lists stay
in use. The Stream Service of Transaction Stream ([`openframl-stream`](stream.md)) feeds it the payments of the
bank's transaction topic; a batch job can also call it.

## Name rules from the country pack

A country pack gives its name rules in `names.properties`, a UTF-8 properties file next to its tables:

```
# An abbreviation, then what it stands for
abbreviation.TM = Thương mại
# Legal forms, separated by spaces
legalForms = Công ty TNHH Cổ phần MTV Tập đoàn
```

Keys and values are normalised like names, so they are written with their diacritics and in any case. A key that is
neither `abbreviation.<word>` nor `legalForms` fails the pack. `NameRules.of(pack)` gives the screener the
international legal forms ("Co", "Ltd", "LLC", "Inc", "GmbH"...) and the pack's rules; the Stream Service uses the
rules of its country pack.

The Vietnam pack writes out `CTY` (công ty), `TM` (thương mại), `DV` (dịch vụ), `SX` (sản xuất), `XNK` (xuất nhập
khẩu), `XD` (xây dựng), `DT` (đầu tư) and `CP` (cổ phần), and leaves out "Công ty", "TNHH", "Cổ phần", "MTV" and "Tập
đoàn". Without them, "CTY CP XD DT HOANG LONG" does not match "Công ty Cổ phần Xây dựng Đầu tư Hoàng Long".

## The lists a country pack expects

A country pack names the lists its country's rules expect a bank to screen against (`CountryPack.screeningLists()`).
For each list it gives the source name that the list's files carry, who publishes it, the form of its files, and the
regulation that makes it apply. `ScreeningDirectory.missing` names the expected lists that a directory lacks, and the
Stream Service logs a warning for each one when it starts.

| List | Source name | Publisher | Form |
| --- | --- | --- | --- |
| UN Security Council Consolidated List | `UN-SC` | UN Security Council | The UN's XML |
| Blacklist: terrorism and terrorist financing | `VN-BLACKLIST-TERRORISM` | Ministry of Public Security | Open CSV |
| Blacklist: proliferation financing | `VN-BLACKLIST-PROLIFERATION` | Ministry of National Defence | Open CSV |
| Warning list: high money laundering risk | `VN-WARNING` | State Bank of Vietnam | Open CSV |

The blacklist and the warning list are defined by the Anti-Money Laundering Law 14/2022/QH15. Published texts give
the definitions as article 3 or article 4, so the article number is to be confirmed against the official gazette.
Article 44 requires a bank to delay a transaction when it suspects that a party is on the blacklist. The national
lists are published as decisions rather than in a form a program can read, so the bank converts each one to the open
CSV form as `<source name>@<version>.csv`.

## The UN Security Council list

`UnConsolidatedList` reads the UN Security Council Consolidated List in the XML form the UN publishes
(`https://scsanctions.un.org/resources/xml/en/consolidated.xml`), with the list's `dateGenerated` as its version.

- Individuals become `PERSON` entries and entities `ORGANISATION` entries, with the UN reference number (e.g.
  `QDi.001`) as id.
- Names: the individual's name parts in order; an entity's names, which the list joins with `;`; every alias; and the
  names in their original script.
- Aliases the list marks as of low quality are left out unless asked for: the UN says they are not enough to identify
  anyone, and they cause most false positives.
- Birth years come from exact and approximate dates and from every year of a range; document numbers are the
  identifiers.
- The parser reads no DTD and resolves no external entity.

On the list published on 29 October 2026 it reads 1,007 entries (733 individuals, 274 entities) and 3,506 names and
aliases, and every entry is found by its own primary name.

## The list format

The bank's own lists, and official lists converted from their publisher's format, use one open CSV form, UTF-8:

```
id,kind,names,birth_year,nationalities,identifiers,remark
XA-0001,PERSON,Nguyễn Văn Đức|Duc Van Nguyen,1975,XA,XA-P-1234567,Invented sanctions programme
```

`kind` is `PERSON`, `ORGANISATION`, `VESSEL` or `OTHER`. `names`, `birth_year`, `nationalities` and `identifiers`
take several values separated by `|`, the primary name first. Only `id`, `kind` and `names` are required. A row that cannot be read
fails the whole list, naming the row.

## Next

On the [roadmap](roadmap.md) for phase 2:

- Readers for lists that other countries publish in a form a program can read, as their packs come.
- Delaying a transaction when the gate's account lists hit a blacklisted party, through the gate's decision table.
