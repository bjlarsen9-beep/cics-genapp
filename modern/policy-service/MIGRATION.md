# Migration notes: Inquire Policy (LGIPOL01 / LGIPDB01)

Legacy flow: a CICS caller LINKs to `LGIPOL01` (business logic) with the `LGCMAREA` COMMAREA. `LGIPOL01` LINKs to
`LGIPDB01` (Db2 layer), which uppercases `CA-REQUEST-ID` and runs one paragraph per policy type (`01IEND`,
`01IHOU`, `01IMOT`, `01ICOM`). Each paragraph SELECTs the `POLICY` row joined to its type table and fills
`CA-POLICY-COMMON` plus the type section of the COMMAREA and `CA-RETURN-CODE`.

Java flow: `GET /policies/{customerNumber}/{policyNumber}` -> `PolicyController` -> `PolicyInquiryService` ->
`PolicyRepository.findPolicyType` (reads `POLICY.POLICYTYPE`) -> the `PolicyTypeInquiry` bean for that type
(JDBC SELECT against `POLICY` + the type table).

## Return-code mapping

| `CA-RETURN-CODE` | Where it is set in COBOL | Meaning | HTTP status | JSON body |
|---|---|---|---|---|
| `00` | `LGIPDB01 GET-*-DB2-INFO`, SQLCODE 0 | Policy found | 200 OK | `Policy` |
| `01` | `LGIPDB01 GET-*-DB2-INFO`, SQLCODE 100 | Policy not found for this customer | 404 Not Found | `{"returnCode":"01",...}` |
| `90` | `LGIPDB01 GET-*-DB2-INFO`, any other SQLCODE | Db2 error | 500 Internal Server Error | `{"returnCode":"90",...}` |
| `98` | `LGIPDB01 MAINLINE`, COMMAREA too short | Bad request | 400 Bad Request | `{"returnCode":"98",...}` |
| `99` | `LGIPDB01 MAINLINE`, `EVALUATE WS-REQUEST-ID` `WHEN OTHER` | Unknown request / policy type | 400 Bad Request | `{"returnCode":"99",...}` |

The codes live in `ReturnCode`. Error bodies are `ErrorResponse(returnCode, message)` and never contain customer or
policy data.

## Paragraph-to-Java mapping (shared)

### LGIPOL01 (business logic)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `INITIALIZE WS-HEADER`, `MOVE EIBTRNID/EIBTRMID/EIBTASKN` | Not migrated: CICS task metadata has no REST equivalent. |
| `MAINLINE`: `IF EIBCALEN = 0` -> `ABEND LGCA` | Not reachable: Spring only routes the request when both path values are present. |
| `MAINLINE`: `MOVE '00' TO CA-RETURN-CODE` | Success maps to `ReturnCode.OK`. |
| `MAINLINE`: `EXEC CICS LINK PROGRAM(LGIPDB01)` | `PolicyInquiryService.inquire` (direct method call instead of a LINK). |
| `MAINLINE-END`: `EXEC CICS RETURN` | `PolicyController.getPolicy` returns the `ResponseEntity` (status from `ReturnCode.httpStatus()`). |

### LGIPDB01 (Db2 layer, MAINLINE)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `INITIALIZE WS-HEADER`, `DB2-IN-INTEGERS`, `DB2-OUT-INTEGERS` | Not migrated (CICS/Db2 host-variable setup). |
| `MAINLINE`: `MOVE CA-CUSTOMER-NUM/CA-POLICY-NUM TO DB2-*-INT` | `PolicyInquiryService.inquire`: `Long.parseLong` after the 1-to-10-digit check (`98` otherwise). |
| `MAINLINE`: `MOVE FUNCTION UPPER-CASE(CA-REQUEST-ID)`, `EVALUATE WS-REQUEST-ID` | `PolicyRepository.findPolicyType` + `PolicyTypeInquiry.policyType()` dispatch (see quirk 1). No `POLICY` row -> `01`. |
| `EVALUATE` `WHEN OTHER` -> `'99'` | `PolicyInquiryService.inquire`: policy type with no `PolicyTypeInquiry` bean -> `ReturnCode.UNKNOWN_REQUEST`. |
| `WRITE-ERROR-MESSAGE` (date, time, `CNUM=`, `PNUM=`, `SQLCODE=` and COMMAREA dump to TDQ) | `PolicyInquiryService.writeErrorMessage`: SLF4J error log with exception type and SQLCODE only (no PII). |

## Common field mapping (`CA-POLICY-COMMON` -> JSON `common`)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property |
|---|---|---|
| `CA-CUSTOMER-NUM PIC 9(10)` | `POLICY.customerNumber INTEGER` | `customerNumber` (string, zero-padded to 10 digits) |
| `CA-POLICY-NUM PIC 9(10)` | `POLICY.policyNumber INTEGER` | `policyNumber` (string, zero-padded to 10 digits) |
| (`CA-REQUEST-ID` type letter) | `POLICY.policyType CHAR(1)` | `policyType` (`E`, `H`, `M`, `C`) |
| `CA-ISSUE-DATE PIC X(10)` | `issueDate DATE` | `common.issueDate` (`yyyy-MM-dd`) |
| `CA-EXPIRY-DATE PIC X(10)` | `expiryDate DATE` | `common.expiryDate` (`yyyy-MM-dd`) |
| `CA-LASTCHANGED PIC X(26)` | `lastChanged TIMESTAMP` | `common.lastChanged` (`yyyy-MM-dd-HH.mm.ss.SSSSSS`) |
| `CA-BROKERID PIC 9(10)` | `brokerId INTEGER` | `common.brokerId` |
| `CA-BROKERSREF PIC X(10)` | `brokersReference CHAR(10)` | `common.brokersReference` |
| `CA-PAYMENT PIC 9(6)` | `payment INTEGER` | `common.payment` |

Type-specific fields are in `details`; see each policy-type section below.

## Schema and seed data

- `src/main/resources/schema.sql` is the `POLICY` DDL from `base/cntl/db2cre.jcl` (columns, order, types, lengths
  and identity start checked by `PolicySchemaParityTest`). Each type table is in its own
  `src/main/resources/schema-<type>.sql`. Dropped for H2: `CCSID EBCDIC`, tablespace clauses, and the foreign key to
  `CUSTOMER` (customer data is owned by `customer-service`).
- Maven copies `base/data/ksdspoly.txt` and `base/cntl/db2cre.jcl` into `target/classes/seed/` at build time.
  `SeedDataLoader` inserts one `POLICY` row per `ksdspoly.txt` record (type, customer and policy number from the
  file; dates, broker, payment and commission from the matching `policy` INSERT in `db2cre.jcl`) and hands the record
  to the `PolicySeeder` bean for its type. `KsdsPolyRecord` parses the 64-byte records using the LGAPVS01
  `WF-Policy-Info` layout.

## Known differences and open questions (shared)

1. **Dispatch by stored type, not request ID.** LGIPDB01 trusts `CA-REQUEST-ID` from the caller; asking with the
   wrong type for an existing policy gives SQLCODE 100 (`01`). The REST path has no request ID, so the service reads
   `POLICY.POLICYTYPE` and runs the matching paragraph.
2. **`ksdspoly.txt` is only a 43-byte summary.** The full Db2 rows (dates, broker, payment, most type columns) exist
   only as INSERTs in `db2cre.jcl`; the file decides which policies exist and the JCL supplies the rest.
3. **Malformed input.** COBOL `98` means "COMMAREA too short"; it never validated the digits of `CA-CUSTOMER-NUM` or
   `CA-POLICY-NUM`. The REST service uses `98`/400 for any value that is not 1 to 10 digits.
4. **Numbers above the Db2 INTEGER range.** `PIC 9(10)` allows `9999999999` but the host variables are
   `PIC S9(9) COMP`; Java looks up the full value, so such numbers return 404.
5. **Error logging.** `WRITE-ERROR-MESSAGE` wrote customer and policy numbers and a COMMAREA dump to a TD queue. The
   Java log line has only the exception type and SQLCODE.

## Endowment (`GET-ENDOW-DB2-INFO`, table `ENDOWMENT`)

Request ID `01IEND`. `EndowmentPolicyInquiry` (bean for `POLICY.POLICYTYPE = 'E'`), `EndowmentDetails` (JSON
`details`), `EndowmentPolicySeeder` (seed data), `schema-endowment.sql` (DDL).

### Paragraph-to-Java mapping

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `WHEN '01IEND'` -> `INITIALIZE DB2-ENDOWMENT`, `PERFORM GET-ENDOW-DB2-INFO` | `PolicyInquiryService.inquire` dispatches to `EndowmentPolicyInquiry` (`policyType()` = `E`, `requestId()` = `01IEND`). Defaults of the INITIALIZE are applied per field in `EndowmentPolicyInquiry.details` / `PolicyCommon.fromPolicyColumns`. |
| `GET-ENDOW-DB2-INFO`: `MOVE ' SELECT ENDOW ' TO EM-SQLREQ` | Not migrated (only used in the TDQ error message). |
| `GET-ENDOW-DB2-INFO`: `EXEC SQL SELECT ... FROM POLICY,ENDOWMENT WHERE ...` | `EndowmentPolicyInquiry.inquire` running `SELECT_ENDOWMENT` (same 15-item column list incl. `LENGTH(PADDINGDATA)`, same implicit join and WHERE). |
| `IF SQLCODE = 0`: `ADD WS-CA-HEADERTRAILER-LEN/WS-FULL-ENDOW-LEN/DB2-E-PADDING-LEN TO WS-REQUIRED-CA-LEN`, `IF EIBCALEN < WS-REQUIRED-CA-LEN` -> `'98'` | Not migrated: there is no caller-sized COMMAREA; the JSON body is as long as the data (see quirk 7). |
| `IF IND-BROKERID/IND-PAYMENT NOT EQUAL MINUS-ONE` -> `MOVE DB2-*-INT TO DB2-*` | `PolicyCommon.fromPolicyColumns` (NULL gives 0 / ""). |
| `MOVE DB2-E-TERM-SINT TO DB2-E-TERM`, `MOVE DB2-E-SUMASSURED-INT TO DB2-E-SUMASSURED` | `EndowmentPolicyInquiry.unsignedDisplay` (`abs(value) % 100` and `% 1000000`, see quirk 5). |
| `MOVE DB2-POLICY-COMMON TO CA-POLICY-COMMON` | `Policy.common` (`PolicyCommon`). |
| `MOVE DB2-ENDOW-FIXED TO CA-ENDOWMENT(1:WS-ENDOW-LEN)` | `EndowmentPolicyInquiry.details` builds `EndowmentDetails`. |
| `IF IND-E-PADDINGDATA NOT EQUAL MINUS-ONE` -> `MOVE DB2-E-PADDINGDATA TO CA-E-PADDING-DATA(1:DB2-E-PADDING-LEN)` | `EndowmentDetails.paddingData` (column value as stored; NULL gives ""). |
| `MOVE 'FINAL' TO CA-E-PADDING-DATA(END-POLICY-POS:5)` | Not migrated: COMMAREA end-of-data marker (see quirk 7). |
| `ELSE IF SQLCODE EQUAL 100` -> `'01'` | Empty result -> `Optional.empty()` -> `PolicyInquiryService` returns `ReturnCode.NOT_FOUND` (404). |
| `ELSE` -> `'90'`, `PERFORM WRITE-ERROR-MESSAGE` | `DataAccessException` -> `ReturnCode.DB2_ERROR` (500), logged by `PolicyInquiryService.writeErrorMessage` without PII. NULL in a column without an indicator raises SQLCODE -305 (quirk 4). |

### Field mapping (`CA-ENDOWMENT` -> JSON `details`)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property | `ksdspoly.txt` (LGAPVS01) |
|---|---|---|---|
| `CA-E-WITH-PROFITS PIC X` | `ENDOWMENT.withProfits CHAR(1)` | `details.withProfits` | `WF-E-WITH-PROFITS X(1)`, offset 21 |
| `CA-E-EQUITIES PIC X` | `ENDOWMENT.equities CHAR(1)` | `details.equities` | `WF-E-EQUITIES X(1)`, offset 22 |
| `CA-E-MANAGED-FUND PIC X` | `ENDOWMENT.managedFund CHAR(1)` | `details.managedFund` | `WF-E-MANAGED-FUND X(1)`, offset 23 |
| `CA-E-FUND-NAME PIC X(10)` | `ENDOWMENT.fundName CHAR(10)` | `details.fundName` | `WF-E-FUND-NAME X(10)`, offset 24 |
| `CA-E-TERM PIC 99` | `ENDOWMENT.term SMALLINT` | `details.term` (number) | not in file (from `db2cre.jcl`) |
| `CA-E-SUM-ASSURED PIC 9(6)` | `ENDOWMENT.sumAssured INTEGER` | `details.sumAssured` (number) | not in file (from `db2cre.jcl`) |
| `CA-E-LIFE-ASSURED PIC X(31)` | `ENDOWMENT.lifeAssured CHAR(31)` | `details.lifeAssured` | `WF-E-LIFE-ASSURED X(30)`, offset 34 |
| `CA-E-PADDING-DATA PIC X(32348)` | `ENDOWMENT.paddingData VARCHAR(32606)` | `details.paddingData` ("" when NULL) | not in file; not in `db2cre.jcl` INSERTs (NULL) |

Sample records: policy 4 (customer 8, `YYNLIONTMR   J. MORRIS`) and policy 5 (customer 3, `NNNSHEPPA    Shep`).
`EndowmentPolicyInquiryParityTest` checks both against the file and the `db2cre.jcl` INSERTs;
`EndowmentSchemaParityTest` checks the H2 DDL (columns, types, lengths, primary key, cascading FK to `POLICY`).

### Endowment-specific quirks

1. **Column order differs between the table and the COMMAREA.** The `ENDOWMENT` DDL has `equities` before
   `withProfits`; `CA-ENDOWMENT`, the SELECT and `WF-E-Policy-Data` have `WITH-PROFITS` first. Both sample policies
   have the same value in the two columns (`N`/`N`, `Y`/`Y`), so the sample data cannot detect a swap of these
   fields; the Java code maps by column name and the parity tests check each property separately.
2. **`ksdspoly.txt` truncates the life assured.** `WF-E-LIFE-ASSURED` is `X(30)` but `CA-E-LIFE-ASSURED` and the
   column are 31 bytes, so LGAPVS01 drops the 31st character. Seeding from the file (as required) would lose it;
   neither sample name (`J. MORRIS`, `Shep`) is long enough to be affected.
3. **The file has no term or sum assured.** LGAPVS01 does not write `CA-E-TERM` or `CA-E-SUM-ASSURED`, so
   `EndowmentPolicySeeder` takes `term` and `sumAssured` from the `db2cre.jcl` INSERT (policy 4: 5 / 12500; policy 5:
   10 / 50000). For the five fields the file does hold, the file and the INSERTs agree for both sample policies
   (checked by `summaryFieldsAgreeWithDb2CreInserts`), so no file-over-JCL override changes a value.
4. **Only `PADDINGDATA` has a NULL indicator.** `withProfits`, `equities`, `managedFund`, `fundName`, `term`,
   `sumAssured` and `lifeAssured` are nullable in the DDL but are fetched without an indicator variable. On Db2 a NULL
   there fails the SELECT with SQLCODE -305, which the paragraph reports as `'90'`. `EndowmentPolicyInquiry` raises
   the same SQLCODE (-305), so the service returns 500 / `90`. `PADDINGDATA` NULL is normal (no INSERT sets it):
   the COBOL skips the MOVE and Java returns "".
5. **Numeric MOVEs truncate.** `DB2-E-TERM-SINT PIC S9(4) COMP` is moved to `PIC 99` and `DB2-E-SUMASSURED-INT
   PIC S9(9) COMP` to `PIC 9(6)`: the sign is dropped and high-order digits are lost (term 123 -> 23, sum assured
   1234567 -> 234567, -7 -> 7). Java reproduces this for parity; a reviewer should decide whether the REST API should
   return the real Db2 value instead. (The shared `PolicyCommon.payment`, `INTEGER` -> `CA-PAYMENT PIC 9(6)`, is not
   truncated.)
6. **The join does not check the policy type.** `WHERE` only joins on `POLICYNUMBER` and filters `POLICY.CUSTOMERNUMBER`;
   a `POLICY` row of type `E` without an `ENDOWMENT` row returns SQLCODE 100 (`01`), and Java does the same.
7. **COMMAREA framing is not migrated.** The `EIBCALEN` length check (`98`) and the `'FINAL'` marker written right
   after the padding data (at `CA-E-PADDING-DATA(1:5)` when `PADDINGDATA` is NULL) have no REST equivalent, so
   `paddingData` never contains `FINAL`. Note also the size mismatch: the column is `VARCHAR(32606)`, the host
   variable `X(32611)`, but `CA-E-PADDING-DATA` only `X(32348)`; COBOL relies on the `EIBCALEN` check, Java returns
   the full value.

## House (`GET-HOUSE-DB2-INFO`, table `HOUSE`)

Request ID `01IHOU`, policy type `H`. Java: `HousePolicyInquiry` (SELECT), `HouseDetails` (JSON `details`),
`HousePolicySeeder` (H2 seed), `schema-house.sql` (DDL). Sample data: policies 6, 7 and 8 (customers 4, 6 and 9).

### Paragraph-to-Java mapping (`GET-HOUSE-DB2-INFO`)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `WHEN '01IHOU'` -> `INITIALIZE DB2-HOUSE`, `PERFORM GET-HOUSE-DB2-INFO` | `PolicyInquiryService.inquire` dispatches to `HousePolicyInquiry` (`policyType()` = `H`, `requestId()` = `01IHOU`); a fresh `HouseDetails` per call replaces `INITIALIZE`. |
| `MOVE ' SELECT HOUSE ' TO EM-SQLREQ` | Not migrated (only used in the TDQ error message). |
| `EXEC SQL SELECT ISSUEDATE ... POSTCODE FROM POLICY,HOUSE WHERE POLICY.POLICYNUMBER = HOUSE.POLICYNUMBER AND POLICY.CUSTOMERNUMBER = :DB2-CUSTOMERNUM-INT AND POLICY.POLICYNUMBER = :DB2-POLICYNUM-INT` | `HousePolicyInquiry.SELECT_HOUSE` (same 12 columns in the same order, same implicit join and `WHERE`), run by `HousePolicyInquiry.inquire`. |
| `INTO ... :DB2-BROKERID-INT INDICATOR :IND-BROKERID`, `:DB2-BROKERSREF INDICATOR :IND-BROKERSREF`, `:DB2-PAYMENT-INT INDICATOR :IND-PAYMENT` | `PolicyCommon.fromPolicyColumns`: NULL -> 0 / `""` (the `INITIALIZE`d host-variable value). |
| `INTO` the other nine host variables (no indicator) | `HousePolicyInquiry.toPolicy`: a NULL in any of `HousePolicyInquiry.COLUMNS_WITHOUT_INDICATOR` throws `SQLException` with SQLCODE -305 -> `90` (quirk 6). |
| `IF SQLCODE = 0`: `ADD WS-CA-HEADERTRAILER-LEN`, `WS-FULL-HOUSE-LEN`; `IF EIBCALEN < WS-REQUIRED-CA-LEN` -> `'98'` | Not migrated: no COMMAREA length in REST; the JSON response is always complete. |
| `IF IND-BROKERID NOT EQUAL MINUS-ONE MOVE DB2-BROKERID-INT TO DB2-BROKERID`; same for `IND-PAYMENT` | `Db2Format.number` (NULL -> 0) in `PolicyCommon.fromPolicyColumns`. |
| `MOVE DB2-H-BEDROOMS-SINT TO DB2-H-BEDROOMS`, `MOVE DB2-H-VALUE-INT TO DB2-H-VALUE` | `HousePolicyInquiry.toPolicy`: `Math.abs(n) % 1_000` and `Math.abs(n) % 100_000_000` (unsigned `PIC 9(3)`/`9(8)` receiving fields, quirk 7). |
| `MOVE DB2-POLICY-COMMON TO CA-POLICY-COMMON` | `PolicyCommon.fromPolicyColumns` -> `Policy.common`. |
| `MOVE DB2-HOUSE TO CA-HOUSE(1:WS-HOUSE-LEN)` | `new HouseDetails(...)` -> `Policy.details`. |
| `MOVE 'FINAL' TO CA-H-FILLER(1:5)` | Not migrated: end-of-data marker of the COMMAREA; JSON needs none. |
| `ELSE IF SQLCODE EQUAL 100` -> `'01'` | Empty result -> `Optional.empty()` -> `ReturnCode.NOT_FOUND` (404). |
| `ELSE` -> `'90'` + `PERFORM WRITE-ERROR-MESSAGE` | `DataAccessException` (including SQLCODE -305 above and -811 via `IncorrectResultSizeDataAccessException`) -> `PolicyInquiryService.writeErrorMessage` + `ReturnCode.DB2_ERROR` (500). |

### Field mapping (`CA-HOUSE` -> JSON `details`)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property |
|---|---|---|
| `CA-H-PROPERTY-TYPE PIC X(15)` | `HOUSE.propertyType CHAR(15)` | `details.propertyType` |
| `CA-H-BEDROOMS PIC 9(3)` | `HOUSE.bedrooms SMALLINT` | `details.bedrooms` (number) |
| `CA-H-VALUE PIC 9(8)` | `HOUSE.value INTEGER` | `details.value` (number) |
| `CA-H-HOUSE-NAME PIC X(20)` | `HOUSE.houseName CHAR(20)` | `details.houseName` |
| `CA-H-HOUSE-NUMBER PIC X(4)` | `HOUSE.houseNumber CHAR(4)` | `details.houseNumber` (leading spaces kept, e.g. `"   5"`) |
| `CA-H-POSTCODE PIC X(8)` | `HOUSE.postcode CHAR(8)` | `details.postcode` |
| `CA-H-FILLER PIC X(32342)` | - | Not returned (holds `FINAL`). |

Seed sources (`HousePolicySeeder`): `ksdspoly.txt` `WF-H-Policy-Data` (LGAPVS01) gives `propertyType`
(`WF-H-PROPERTY-TYPE X(15)`), `bedrooms` (`WF-H-BEDROOMS 9(3)`), `value` (`WF-H-VALUE 9(8)`), `postcode`
(`WF-H-POSTCODE X(8)`) and `houseName` (`WF-H-HOUSE-NAME X(9)`); `houseNumber` comes from the `house` INSERT in
`db2cre.jcl`. Parity is checked by `HousePolicyInquiryParityTest` (file read independently) and `HouseSchemaParityTest`.

### House legacy quirks

1. **Policy 7 type disagreement.** `ksdspoly.txt` has policy 7 (customer 6) as `H`; the `policy` INSERT in
   `db2cre.jcl` has `POLICYTYPE 'C'`, yet there is a `house` INSERT for 7 (`FARM`, `HOME FARM`) and no `commercial`
   INSERT. The file wins: policy 7 is a House policy.
2. **Policy 7 bedrooms disagreement.** `WF-H-BEDROOMS` is `004`; the `house` INSERT has `bedrooms 8`. The file wins:
   the service returns 4.
3. **Non-numeric `PIC 9(3)` bedrooms.** Policies 6 and 8 have `WF-H-BEDROOMS` `"5 0"` and `"1 0"` (an embedded space)
   while `WF-H-VALUE` (`01500000`, `00260000`) is valid, so the record was probably hand-edited. Invalid numeric data
   is not used: the seeder falls back to the `db2cre.jcl` values 5 and 1 (which match the leading digit). Read as
   zoned decimal on z/OS (space = x'40', digit nibble 0) the field would be 500 and 100.
4. **House name truncated in the summary.** `WF-H-HOUSE-NAME` is `X(9)` but `CA-H-HOUSE-NAME`/`HOUSENAME` is 20
   characters, and the summary field order (postcode before name) differs from the COMMAREA. `HOME FARM` fits
   exactly; the seeder keeps a longer `db2cre.jcl` name only when it starts with the 9-character summary. Policies 6
   and 8 have a blank name (`' '` in the INSERT).
5. **House number only in Db2.** `CA-H-HOUSE-NUMBER` is not in the summary, so `houseNumber` comes only from
   `db2cre.jcl`; the values are right-aligned with leading spaces (`'   5'`, `'   4'`, `' 12b'`) and returned as is.
6. **Missing NULL indicators.** Only `BROKERID`, `BROKERSREFERENCE` and `PAYMENT` have indicator variables. A NULL in
   `ISSUEDATE`, `EXPIRYDATE`, `LASTCHANGED` or any HOUSE column gives SQLCODE -305 and `'90'`, so all HOUSE columns
   are effectively mandatory even though the DDL allows NULL. Java does the same. `IND-BROKERSREF` is set but never
   tested: the NULL leaves the `INITIALIZE`d spaces, i.e. `""`.
7. **Numeric MOVE truncation.** `SMALLINT` bedrooms goes to unsigned `PIC 9(3)` and `INTEGER` value to unsigned
   `PIC 9(8)`: the sign is dropped and high-order digits are lost (1234 -> 234, -123456789 -> 23456789). A house
   worth 100,000,000 or more cannot be returned correctly.
8. **`VALUE` is reserved in H2.** The column is quoted (`"VALUE"`) in `schema-house.sql`, the SELECT and the seed
   INSERT; its name stays `VALUE`.
9. **Short policy term.** Policy 6 runs `2011-09-01` to `2011-12-31` (4 months) while the other sample policies run
   one year; returned as stored.

## Motor (`GET-MOTOR-DB2-INFO`, table `MOTOR`)

_To be filled in by the Motor implementation._

## Commercial (`GET-Commercial-DB2-INFO-1`, table `COMMERCIAL`)

_To be filled in by the Commercial implementation._
