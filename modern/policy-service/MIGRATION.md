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

_To be filled in by the Commercial implementation._
