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
  duc". Business abbreviations common in Vietnamese company names are written out ("TM" is "thuong mai", "XNK" is
  "xuat nhap khau").
- **Comparing.** The parts of the two names are paired best first by Jaro-Winkler similarity, in any order, so "Duc
  Nguyen Van" matches "Nguyễn Văn Đức". Parts less alike than 0.8 count as different words. A missing or extra part
  lowers the score: "Pham Thi Nhung" against "Phạm Thị Hồng Nhung" scores about 0.86. For an organisation, the core
  of the name without legal forms ("Cong ty", "TNHH", "Ltd") is compared too.
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
in use. Feeding it payments from the bank's stream is the job of the stream layer (planned); until then a batch job
can call it.

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

- Readers for the national lists the country packs name.
- Name rules from the country pack (abbreviations, legal forms) instead of the built-in Vietnamese set.
