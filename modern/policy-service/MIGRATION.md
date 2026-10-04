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

_To be filled in by the Endowment implementation._

## House (`GET-HOUSE-DB2-INFO`, table `HOUSE`)

_To be filled in by the House implementation._

## Motor (`GET-MOTOR-DB2-INFO`, table `MOTOR`)

_To be filled in by the Motor implementation._

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
