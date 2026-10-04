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

_To be filled in by the Commercial implementation._
