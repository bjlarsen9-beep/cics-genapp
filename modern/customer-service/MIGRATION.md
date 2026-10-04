# Migration notes: Customer Inquiry (LGICUS01 / LGICDB01)

Legacy flow: a CICS caller LINKs to `LGICUS01` (business logic) with the `LGCMAREA` COMMAREA, which LINKs to
`LGICDB01` (Db2 layer), which SELECTs one row from the `CUSTOMER` table and fills the customer section of the
COMMAREA plus `CA-RETURN-CODE`.

Java flow: `GET /customers/{customerNumber}` -> `CustomerController` -> `CustomerInquiryService` ->
`CustomerRepository` (JDBC SELECT against the `CUSTOMER` table).

## Return-code mapping

| `CA-RETURN-CODE` | Where it is set in COBOL | Meaning | HTTP status | JSON body |
|---|---|---|---|---|
| `00` | `LGICDB01 GET-CUSTOMER-INFO`, SQLCODE 0 | Customer found | 200 OK | `Customer` |
| `01` | `LGICDB01 GET-CUSTOMER-INFO`, SQLCODE 100 or -913 | Customer not found | 404 Not Found | `{"returnCode":"01",...}` |
| `90` | `LGICDB01 GET-CUSTOMER-INFO`, any other SQLCODE | Db2 error | 500 Internal Server Error | `{"returnCode":"90",...}` |
| `98` | `LGICUS01`/`LGICDB01 MAINLINE`, COMMAREA too short | Bad request | 400 Bad Request | `{"returnCode":"98",...}` |

The codes live in `ReturnCode`. Error bodies are `ErrorResponse(returnCode, message)` and never contain customer data.

## Paragraph-to-Java mapping

### LGICUS01 (business logic)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `INITIALIZE WS-HEADER`, `MOVE EIBTRNID/EIBTRMID/EIBTASKN` | Not migrated: CICS task metadata has no REST equivalent (Spring request handling replaces it). |
| `MAINLINE`: `IF EIBCALEN = 0` -> `ABEND LGCA` | Not reachable: Spring only routes `GET /customers/{customerNumber}` when a path value is present. |
| `MAINLINE`: `MOVE '00' TO CA-RETURN-CODE`, `MOVE '00' TO CA-NUM-POLICIES` | `CustomerRepository.findByCustomerNumber` sets `numberOfPolicies` to 0; success maps to `ReturnCode.OK`. |
| `MAINLINE`: COMMAREA length check -> `'98'` | `CustomerInquiryService.inquire`: the customer number must match `CA-CUSTOMER-NUM PIC 9(10)` (1 to 10 digits), otherwise `ReturnCode.BAD_REQUEST`. |
| `GET-CUSTOMER-INFO`: `EXEC CICS LINK PROGRAM(LGICDB01)` | `CustomerInquiryService.getCustomerInfo` calling `CustomerRepository.findByCustomerNumber` (direct method call instead of a LINK). |
| `MAINLINE-END`: `EXEC CICS RETURN` | `CustomerController.getCustomer` returns the `ResponseEntity` (status from `ReturnCode.httpStatus()`). |
| `WRITE-ERROR-MESSAGE` (TDQ via `LGSTSQ`) | Not needed in LGICUS01 (only used for the zero-length COMMAREA ABEND). |

### LGICDB01 (Db2 layer)

| COBOL paragraph / step | Java equivalent |
|---|---|
| `MAINLINE`: `INITIALIZE WS-HEADER`, `INITIALIZE DB2-IN-INTEGERS` | Not migrated (CICS/Db2 host-variable setup). |
| `MAINLINE`: `IF EIBCALEN = 0` -> `ABEND LGCA` | Not reachable (see above). |
| `MAINLINE`: COMMAREA length check -> `'98'` | `CustomerInquiryService.inquire` input validation. |
| `MAINLINE`: `MOVE CA-CUSTOMER-NUM TO DB2-CUSTOMERNUMBER-INT` | `CustomerInquiryService.inquire`: `Long.parseLong(customerNumber)`. |
| `GET-CUSTOMER-INFO`: `EXEC SQL SELECT ... FROM CUSTOMER WHERE CUSTOMERNUMBER = :DB2-CUSTOMERNUMBER-INT` | `CustomerRepository.findByCustomerNumber` (same column list, same `WHERE`). |
| `GET-CUSTOMER-INFO`: `EVALUATE SQLCODE` `WHEN 0` -> `'00'` | Row returned -> `InquiryResult.found` (200). |
| `GET-CUSTOMER-INFO`: `WHEN 100` -> `'01'` | Empty result -> `ReturnCode.NOT_FOUND` (404). |
| `GET-CUSTOMER-INFO`: `WHEN -913` -> `'01'` | `DataAccessException` whose `SQLException.getErrorCode()` is -913 -> `ReturnCode.NOT_FOUND` (`CustomerInquiryService.sqlCode`). |
| `GET-CUSTOMER-INFO`: `WHEN OTHER` -> `'90'` + `WRITE-ERROR-MESSAGE` | Any other `DataAccessException` -> `writeErrorMessage` + `ReturnCode.DB2_ERROR` (500). |
| `WRITE-ERROR-MESSAGE` (date, time, `CNUM=`, `SQLCODE=` and first 90 bytes of COMMAREA to TDQ) | `CustomerInquiryService.writeErrorMessage`: SLF4J error log with the exception type and SQLCODE only. The customer number and COMMAREA dump are deliberately left out (no customer PII in logs). |
| `MAINLINE-END`: `EXEC CICS RETURN` | Return of `CustomerInquiryService.inquire`. |

## Field mapping (COMMAREA -> JSON)

| COBOL field (`lgcmarea.cpy`) | Db2 column | JSON property |
|---|---|---|
| `CA-CUSTOMER-NUM PIC 9(10)` | `customerNumber INTEGER` | `customerNumber` (string, zero-padded to 10 digits) |
| `CA-FIRST-NAME PIC X(10)` | `firstName CHAR(10)` | `firstName` |
| `CA-LAST-NAME PIC X(20)` | `lastName CHAR(20)` | `lastName` |
| `CA-DOB PIC X(10)` | `dateOfBirth DATE` | `dateOfBirth` (`yyyy-MM-dd`) |
| `CA-HOUSE-NAME PIC X(20)` | `houseName CHAR(20)` | `houseName` |
| `CA-HOUSE-NUM PIC X(4)` | `houseNumber CHAR(4)` | `houseNumber` |
| `CA-POSTCODE PIC X(8)` | `postcode CHAR(8)` | `postcode` |
| `CA-NUM-POLICIES PIC 9(3)` | (none) | `numberOfPolicies` (always 0, as LGICUS01 sets it) |
| `CA-PHONE-MOBILE PIC X(20)` | `phonemobile CHAR(20)` | `phoneMobile` |
| `CA-PHONE-HOME PIC X(20)` | `phonehome CHAR(20)` | `phoneHome` |
| `CA-EMAIL-ADDRESS PIC X(100)` | `emailaddress CHAR(100)` | `emailAddress` |

`CA-REQUEST-ID`, `CA-RETURN-CODE` and `CA-POLICY-DATA` are not part of the response body; the return code is the HTTP
status (plus `returnCode` in error bodies). Text values have trailing CHAR padding removed; leading spaces are kept
(for example customer 2's house number is `" 1"`, right-justified in the sample file).

## Schema and seed data

- `src/main/resources/schema.sql` is the `CUSTOMER` DDL from `base/cntl/db2cre.jcl`. Column names, order, types and
  lengths are unchanged (checked by `CustomerSchemaParityTest`). Dropped for H2: `CCSID EBCDIC`, the tablespace
  clause, and the separate unique index (the primary key covers it).
- Seed data: Maven copies `base/data/ksdscust.txt` into `target/classes/seed/` at build time, and `SeedDataLoader`
  inserts it at startup. `KsdsCustRecord` parses the 225-byte records using the `LGCMAREA` layout, which is how
  LGACVS01 writes the file (`WRITE FILE('KSDSCUST') FROM(CA-CUSTOMER-NUM)`).

## Known differences and open questions

1. **Sample data sets disagree.** `ksdscust.txt` and the `INSERT`s in `db2cre.jcl` hold different values for the same
   customers: case (`ANDREW` vs `Andrew`), postcode (`PI101O` vs `PI101OO`), and the two phone numbers are swapped
   (the file, read with the COMMAREA layout, has mobile `01962 811234`; the JCL inserts that number as `phonehome`).
   This service follows `ksdscust.txt` as the task requires. Production parity should be re-checked against a real
   Db2 extract.
2. **SQLCODE -913 returns 404.** LGICDB01 reports a Db2 deadlock/timeout (-913) as "not found". Kept for parity, but
   for REST clients a 503 would be more accurate. Needs a reviewer decision.
3. **Customer numbers above the Db2 INTEGER range.** `PIC 9(10)` allows `9999999999`, but `DB2-CUSTOMERNUMBER-INT`
   is `PIC S9(9) COMP`. Depending on the `TRUNC` compiler option, COBOL may truncate the high-order digit and read a
   different customer. Java looks up the full value, so such numbers return 404.
4. **Malformed input.** COBOL `98` means "COMMAREA too short"; it never validated the digits of `CA-CUSTOMER-NUM`.
   The REST service uses `98`/400 for any customer number that is not 1 to 10 digits.
5. **NULL columns.** COBOL has no indicator variables, so a NULL column would give SQLCODE -305 (`90`). Java returns
   an empty string instead. The sample data has no NULLs.
6. **Error logging.** `WRITE-ERROR-MESSAGE` wrote the customer number and a COMMAREA dump to a TD queue. The Java log
   line has only the exception type and SQLCODE, per the repo rule against logging customer PII.
