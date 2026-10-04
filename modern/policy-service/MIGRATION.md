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

Implemented by `MotorPolicyInquiry` (`policyType()` = `M`, `requestId()` = `01IMOT`), `MotorDetails`,
`MotorPolicySeeder` and `src/main/resources/schema-motor.sql`.

### Paragraph-to-Java mapping (`GET-MOTOR-DB2-INFO`)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `WHEN '01IMOT'` -> `INITIALIZE DB2-MOTOR`, `PERFORM GET-MOTOR-DB2-INFO` | `PolicyInquiryService.inquire` dispatches to `MotorPolicyInquiry.inquire` when `POLICY.POLICYTYPE` is `M`; every `MotorDetails` field is built fresh (no stale host variables). |
| `MOVE ' SELECT MOTOR ' TO EM-SQLREQ` | Not migrated (only used in the TDQ error text). |
| `EXEC SQL SELECT ISSUEDATE, ... ACCIDENTS FROM POLICY,MOTOR WHERE POLICY.POLICYNUMBER = MOTOR.POLICYNUMBER AND POLICY.CUSTOMERNUMBER = :DB2-CUSTOMERNUM-INT AND POLICY.POLICYNUMBER = :DB2-POLICYNUM-INT` | `MotorPolicyInquiry.SELECT_MOTOR` (same 15 columns in the same order, same implicit join and `WHERE`); run with `JdbcTemplate.query`. |
| `INDICATOR :IND-BROKERID / :IND-BROKERSREF / :IND-PAYMENT` and `IF IND-... NOT EQUAL MINUS-ONE` | `PolicyCommon.fromPolicyColumns`: NULL gives 0 / `""` (the `INITIALIZE`d value). |
| Host variables with no indicator (dates, `LASTCHANGED`, all MOTOR columns): NULL -> SQLCODE -305 | `MotorPolicyInquiry.requireNonNull` throws `SQLException` (SQLSTATE 22002, error code -305) -> `DataAccessException` -> `90` (quirk 3). |
| `IF SQLCODE = 0`: `ADD WS-CA-HEADERTRAILER-LEN`, `ADD WS-FULL-MOTOR-LEN` (137), `IF EIBCALEN < WS-REQUIRED-CA-LEN` -> `'98'` | Not reachable: a REST response has no caller-sized COMMAREA. |
| `MOVE DB2-M-CC-SINT TO DB2-M-CC`, `MOVE DB2-M-VALUE-INT TO DB2-M-VALUE` | `MotorPolicyInquiry.toUnsigned(value, 4)` / `toUnsigned(value, 6)` (quirk 4). |
| `MOVE DB2-M-PREMIUM-INT TO DB2-M-PREMIUM / CA-M-PREMIUM`, `MOVE DB2-M-ACCIDENTS-INT TO DB2-M-ACCIDENTS / CA-M-ACCIDENTS` | `MotorDetails.premium` / `accidents` via `toUnsigned(value, 6)` (quirk 5). |
| `MOVE DB2-POLICY-COMMON TO CA-POLICY-COMMON` | `PolicyCommon.fromPolicyColumns(rs)`. |
| `MOVE DB2-MOTOR TO CA-MOTOR(1:WS-MOTOR-LEN)` | `new MotorDetails(...)` in `MotorPolicyInquiry.map`. |
| `MOVE 'FINAL' TO CA-M-FILLER(1:5)` | Not migrated: end-of-data marker inside the COMMAREA; JSON is self-delimiting. |
| `ELSE IF SQLCODE EQUAL 100` -> `'01'` | Empty result list -> `Optional.empty()` -> `ReturnCode.NOT_FOUND` (404). |
| `ELSE` -> `'90'` + `PERFORM WRITE-ERROR-MESSAGE` | `DataAccessException` propagates -> `PolicyInquiryService.writeErrorMessage` + `ReturnCode.DB2_ERROR` (500). |

### Field mapping (`CA-MOTOR` -> JSON `details`)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property |
|---|---|---|
| `CA-M-MAKE PIC X(15)` | `MOTOR.make CHAR(15)` | `details.make` |
| `CA-M-MODEL PIC X(15)` | `MOTOR.model CHAR(15)` | `details.model` |
| `CA-M-VALUE PIC 9(6)` | `MOTOR.value INTEGER` | `details.value` (number) |
| `CA-M-REGNUMBER PIC X(7)` | `MOTOR.regNumber CHAR(7)` | `details.regNumber` |
| `CA-M-COLOUR PIC X(8)` | `MOTOR.colour CHAR(8)` | `details.colour` |
| `CA-M-CC PIC 9(4)` | `MOTOR.cc SMALLINT` | `details.cc` (number) |
| `CA-M-MANUFACTURED PIC X(10)` | `MOTOR.yearOfManufacture DATE` | `details.manufactured` (`yyyy-MM-dd`) |
| `CA-M-PREMIUM PIC 9(6)` | `MOTOR.premium INTEGER` | `details.premium` (number) |
| `CA-M-ACCIDENTS PIC 9(6)` | `MOTOR.accidents INTEGER` | `details.accidents` (number) |
| `CA-M-FILLER PIC X(32323)` | (none) | not returned (`'FINAL'` marker) |

Seed sources (`MotorPolicySeeder`): `make`, `model`, `value`, `regNumber` from the `ksdspoly.txt` record
(LGAPVS01 `WF-M-MAKE X(15)`, `WF-M-MODEL X(15)`, `WF-M-VALUE 9(6)`, `WF-M-REGNUMBER X(7)` at offsets 0, 15, 30, 36 of
`WF-Policy-Data`); `colour`, `cc`, `yearOfManufacture`, `premium`, `accidents` from the `motor` INSERT in `db2cre.jcl`.
Checked by `MotorPolicyInquiryParityTest` (policies 1, 2, 3 for customers 2, 10, 5) and `MotorSchemaParityTest`.

### Motor-specific legacy quirks

1. **Policy 1 `VALUE` disagrees.** `ksdspoly.txt` has `WF-M-VALUE` = `085000` (85,000) for the FORD KA; the
   `db2cre.jcl` INSERT has `8500`. The file wins, so the service returns `85000`. Policies 2 and 3 (`000600`, `023500`)
   agree with the JCL, and MAKE, MODEL and REGNUMBER agree for all three. Which value is right needs a business check.
2. **`VALUE` is a reserved word in H2.** Db2 accepts `value` as a column name; H2 rejects it unquoted. The H2 DDL
   and the Java SQL use `"VALUE"` (same stored name `VALUE`, so the column list still matches the Db2 DDL).
3. **NULL handling is uneven.** Only `BROKERID`, `BROKERSREFERENCE` and `PAYMENT` have indicator variables. A NULL
   in any other selected column (`ISSUEDATE`, `EXPIRYDATE`, `LASTCHANGED` or any MOTOR column) makes Db2 return
   SQLCODE -305, which the paragraph turns into `90`. The DDL allows NULL in every MOTOR column, so a row inserted
   without, say, a colour cannot be read back. Java keeps this (`90`/500, logged as SQLCODE=-305); a reviewer may
   prefer defaults. `IND-BROKERSREF` is declared but never tested: a NULL leaves the `INITIALIZE`d spaces (`""`).
4. **Numeric MOVEs truncate silently.** `VALUE`, `PREMIUM` and `ACCIDENTS` are `INTEGER` (up to 2,147,483,647) but
   `CA-M-VALUE`/`CA-M-PREMIUM`/`CA-M-ACCIDENTS` are `PIC 9(6)`; `CC` is `SMALLINT` into `PIC 9(4)`. COBOL keeps the
   low-order digits and drops the sign (1,234,567 -> 234567; -1600 -> 1600). Java does the same (`toUnsigned`) for
   parity; no sample row is affected.
5. **`WS-MOTOR-LEN` is 65, not 77.** `MOVE DB2-MOTOR TO CA-MOTOR(1:WS-MOTOR-LEN)` copies only MAKE through
   MANUFACTURED (15+15+6+7+8+4+10 = 65 bytes); PREMIUM and ACCIDENTS reach the COMMAREA only through the separate
   `MOVE ... TO CA-M-PREMIUM/CA-M-ACCIDENTS`. The result is the same; Java maps all nine fields directly.
6. **Sample file formats.** `WF-M-VALUE` is zero-padded (`000600`), and text fields are space-padded (policy 3's
   registration `FIRE1` is followed by two spaces). The JSON strips trailing spaces and returns the number. The
   Motor rows in `db2cre.jcl` are in policy order 1, 3, 2, and `ksdspoly.txt` is sorted by type then customer, so policy
   numbers do not follow customer order (customer 2 -> 1, customer 5 -> 3, customer 10 -> 2).
7. **Sample data has no broker or payment.** All three Motor `policy` INSERTs have `brokerId` 0, `brokersReference`
   `''` and `payment` 0, so the indicator paths are only covered by the test that inserts a row with NULLs.

## Commercial (`GET-Commercial-DB2-INFO-1`, table `COMMERCIAL`)

Java: `CommercialPolicyInquiry` (`policyType()` = `C`, `requestId()` = `01ICOM`) returns a `Policy` whose `details`
is a `CommercialDetails`. `CommercialPolicySeeder` fills `COMMERCIAL` (DDL in `schema-commercial.sql`).

### Paragraph-to-Java mapping (`GET-Commercial-DB2-INFO-1`)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `WHEN '01ICOM'` -> `INITIALIZE DB2-COMMERCIAL`, `PERFORM GET-COMMERCIAL-DB2-INFO-1` | `PolicyInquiryService.inquire` dispatches to `CommercialPolicyInquiry.inquire` when `POLICY.POLICYTYPE` is `C`. |
| `MAINLINE`: `INITIALIZE DB2-POLICY` | `CommercialPolicyInquiry.map`: `brokerId` 0, `brokersReference` "" and `payment` 0 (the SELECT never fills them, see quirk 1). |
| `EXEC SQL SELECT RequestDate, ..., RejectionReason FROM POLICY,COMMERCIAL WHERE (POLICY.POLICYNUMBER = COMMERCIAL.POLICYNUMBER AND POLICY.CUSTOMERNUMBER = :DB2-CUSTOMERNUM-INT AND POLICY.POLICYNUMBER = :DB2-POLICYNUM-INT)` | `CommercialPolicyInquiry.SELECT_COMMERCIAL` (same column list, join and `WHERE`) run by `inquire`. |
| `INTO :DB2-LASTCHANGED, :DB2-ISSUEDATE, :DB2-EXPIRYDATE` | `CommercialPolicyInquiry.map`: `RequestDate` -> `common.lastChanged`, `StartDate` -> `common.issueDate`, `RenewalDate` -> `common.expiryDate`. |
| `INTO` list without `INDICATOR` variables | `CommercialPolicyInquiry.requireNoNulls`: a NULL column raises an `SQLException` with SQLCODE -305, which becomes `'90'` (500). |
| `IF SQLCODE = 0`: `ADD WS-CA-HEADERTRAILER-LEN`/`WS-FULL-COMM-LEN`, `IF EIBCALEN < WS-REQUIRED-CA-LEN` -> `'98'` | Not reachable: there is no caller-sized COMMAREA; the whole `CommercialDetails` is always returned. |
| `MOVE DB2-B-FirePeril-Int TO DB2-B-FirePeril` ... `MOVE DB2-B-Status-Int TO DB2-B-Status` | `CommercialPolicyInquiry.unsigned`: signed SMALLINT/INTEGER -> unsigned `PIC 9(4)`/`9(8)` (sign dropped, high-order digits truncated). |
| `MOVE DB2-POLICY-COMMON TO CA-POLICY-COMMON`, `MOVE DB2-COMMERCIAL TO CA-COMMERCIAL(1:WS-COMM-LEN)` | `new Policy(..., common, details)`. |
| `MOVE 'FINAL' TO CA-B-FILLER(1:5)` | Not migrated: end-of-data marker in the COMMAREA filler; JSON has no filler. |
| `IF SQLCODE EQUAL 100` -> `'01'` | Empty result -> `Optional.empty()` -> `ReturnCode.NOT_FOUND` (404). |
| `ELSE` -> `'90'` + `WRITE-ERROR-MESSAGE` | `DataAccessException` -> `PolicyInquiryService.writeErrorMessage` + `ReturnCode.DB2_ERROR` (500). |

### Field mapping (`CA-COMMERCIAL` -> JSON `details`)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property |
|---|---|---|
| `CA-ISSUE-DATE PIC X(10)` | `COMMERCIAL.StartDate DATE` | `common.issueDate` (`yyyy-MM-dd`) |
| `CA-EXPIRY-DATE PIC X(10)` | `COMMERCIAL.RenewalDate DATE` | `common.expiryDate` (`yyyy-MM-dd`) |
| `CA-LASTCHANGED PIC X(26)` | `COMMERCIAL.RequestDate TIMESTAMP` | `common.lastChanged` (`yyyy-MM-dd-HH.mm.ss.SSSSSS`) |
| `CA-BROKERID PIC 9(10)`, `CA-BROKERSREF PIC X(10)`, `CA-PAYMENT PIC 9(6)` | (not selected) | `common.brokerId` 0, `common.brokersReference` "", `common.payment` 0 |
| `CA-B-Address PIC X(255)` | `Address CHAR(255)` | `details.address` |
| `CA-B-Postcode PIC X(8)` | `Zipcode CHAR(8)` | `details.postcode` |
| `CA-B-Latitude PIC X(11)` | `LatitudeN CHAR(11)` | `details.latitude` |
| `CA-B-Longitude PIC X(11)` | `LongitudeW CHAR(11)` | `details.longitude` |
| `CA-B-Customer PIC X(255)` | `Customer CHAR(255)` | `details.customer` |
| `CA-B-PropType PIC X(255)` | `PropertyType CHAR(255)` | `details.propertyType` |
| `CA-B-FirePeril PIC 9(4)` | `FirePeril SMALLINT` | `details.firePeril` |
| `CA-B-FirePremium PIC 9(8)` | `FirePremium INTEGER` | `details.firePremium` |
| `CA-B-CrimePeril PIC 9(4)` | `CrimePeril SMALLINT` | `details.crimePeril` |
| `CA-B-CrimePremium PIC 9(8)` | `CrimePremium INTEGER` | `details.crimePremium` |
| `CA-B-FloodPeril PIC 9(4)` | `FloodPeril SMALLINT` | `details.floodPeril` |
| `CA-B-FloodPremium PIC 9(8)` | `FloodPremium INTEGER` | `details.floodPremium` |
| `CA-B-WeatherPeril PIC 9(4)` | `WeatherPeril SMALLINT` | `details.weatherPeril` |
| `CA-B-WeatherPremium PIC 9(8)` | `WeatherPremium INTEGER` | `details.weatherPremium` |
| `CA-B-Status PIC 9(4)` | `Status SMALLINT` | `details.status` |
| `CA-B-RejectReason PIC X(255)` | `RejectionReason CHAR(255)` | `details.rejectReason` |
| `CA-B-FILLER PIC X(31298)` | - | Not returned. |

Seed sources (`CommercialPolicySeeder`): `ksdspoly.txt` `WF-C-Policy-Data` (LGAPVS01) supplies `Zipcode`
(`WF-B-Postcode X(8)`), `Status` (`WF-B-Status 9(4)`) and `Customer` (`WF-B-Customer X(31)`); every other column
comes from the `commercial` INSERT in `db2cre.jcl`. Schema: `CCSID EBCDIC`, `IN <DB2DBID>.GENATS06` and the
`iCommercial` unique index (duplicate of the primary key) are dropped; the cascading foreign key to `POLICY` is kept.

### Commercial legacy quirks and known differences

1. **`CA-POLICY-COMMON` does not come from `POLICY`.** The SELECT reads no `POLICY` columns: `RequestDate`,
   `StartDate` and `RenewalDate` go `INTO :DB2-LASTCHANGED, :DB2-ISSUEDATE, :DB2-EXPIRYDATE`, so the policy's own
   `issueDate`/`expiryDate`/`lastChanged` are ignored (policy 10: `POLICY` 2011-08-22 to 2012-08-21, response
   2011-08-25 to 2011-08-24), and `CA-BROKERID`/`CA-BROKERSREF`/`CA-PAYMENT` are always zero/spaces from
   `INITIALIZE DB2-POLICY`. Java keeps this behaviour.
2. **Renewal date before start date.** Both sample rows have `RenewalDate` one or two days before `StartDate`
   (policy 10: 2011-08-25 / 2011-08-24; policy 9: 2011-08-01 / 2011-07-30), so `expiryDate` < `issueDate`.
   Returned as stored.
3. **No NULL indicators.** Unlike the Endowment/House/Motor paragraphs, the `INTO` list has no `INDICATOR`
   variables, so any NULL column fails with SQLCODE -305 and `'90'`, not a default value. Java does the same
   (500, log line `SQLCODE=-305`).
4. **Customer name disagreement.** Policy 9: `ksdspoly.txt` `WF-B-Customer` is `Clarets Merchandise`,
   `db2cre.jcl` `Customer` is `Burnley Football Club`. The file wins, so the service returns
   `Clarets Merchandise`. Policy 10 agrees (`IBM`).
5. **Non-numeric `WF-B-Status 9(4)`.** Both C records hold `A  0` in the status bytes, which fails the COBOL
   NUMERIC test and cannot be stored in a SMALLINT. The seeder keeps the `db2cre.jcl` `Status` (1) when the file
   value is not numeric (read as zoned decimal it would be 1000). Postcodes agree (`SO212JN`, `BB104BX`).
6. **`WF-B-Customer` is only 31 bytes** of the 255-byte `CA-B-Customer`; a file value that is just the truncated
   `db2cre.jcl` value keeps the full Db2 value (no sample row needs this).
7. **`RejectionReason` holds `ACTIVE`** in both sample rows (a status text, not a rejection reason) while `Status`
   is 1. Returned as stored.
8. **Unsigned PIC fields.** The perils, premiums and status are `S9(4)`/`S9(9) COMP` host variables moved into
   unsigned `PIC 9(4)`/`9(8)`: a negative value loses its sign and values above 9999/99999999 lose their high-order
   digits (for example -3 -> 3, 123456789 -> 23456789, 32767 -> 2767). Java applies the same rule.
9. **`WHERE` does not check `POLICYTYPE`.** Any policy with a `COMMERCIAL` row matches 01ICOM. `db2cre.jcl` inserts
   `POLICY` 7 (customer 6) with type `C` but no `COMMERCIAL` row, so COBOL 01ICOM gives `'01'`; `ksdspoly.txt`
   makes policy 7 a House policy, so the shared seeder stores type `H` and the service never routes it here.
10. **Not migrated: list variants.** `GET-Commercial-DB2-INFO-2` (`02ICOM`, by policy number only),
    `GET-Commercial-DB2-INFO-3` (`03ICOM`, cursor by customer number) and `GET-Commercial-DB2-INFO-5` (`05ICOM`,
    cursor by zip code), with return codes `89` (cursor `OPEN` failed) and `88` (cursor `CLOSE` failed), are not
    exposed by `GET /policies/{customerNumber}/{policyNumber}`.
