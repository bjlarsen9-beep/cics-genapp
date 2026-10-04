# policy-service

Java 21 / Spring Boot 3 replacement for GenApp's Inquire Policy transaction (`base/src/lgipol01.cbl` ->
`base/src/lgipdb01.cbl`). See [MIGRATION.md](MIGRATION.md) for the paragraph-by-paragraph mapping and known
differences.

## API

`GET /policies/{customerNumber}/{policyNumber}`: both values are 1 to 10 digits (`PIC 9(10)`), leading zeros
optional. The response has `customerNumber`, `policyNumber`, `policyType`, `common` (`CA-POLICY-COMMON`) and
`details` (the type section of the COMMAREA).

| COBOL `CA-RETURN-CODE` | HTTP status |
|---|---|
| `00` OK | 200 |
| `01` not found | 404 |
| `90` Db2 error | 500 |
| `98` bad request | 400 |
| `99` unknown request / policy type | 400 |

Non-200 responses have the body `{"returnCode":"<code>","message":"..."}`.

## Build and run

```
./mvnw -q verify          # build and run the parity tests
./mvnw spring-boot:run    # start on port 8080 with an in-memory H2 seeded from base/data/ksdspoly.txt
```

The build reads `../../base/data/ksdspoly.txt` and `../../base/cntl/db2cre.jcl`, so run it from inside a full
checkout of this repository. If `repo.maven.apache.org` is unreachable or rate-limited, set `MVNW_REPOURL` to a
Maven Central mirror for the wrapper download and configure the same mirror in `~/.m2/settings.xml`.

## Tests

- `PolicySchemaParityTest`: H2 `POLICY` columns, types, lengths and identity start match `db2cre.jcl`.
- `PolicySeedDataTest`: every `ksdspoly.txt` record has a `POLICY` row with the same key and type; other columns
  match the `db2cre.jcl` INSERTs.
- `PolicyInquiryValidationTest`: type-independent not-found and malformed-input cases.
- `PolicyInquiryErrorMappingTest`: branches sample data cannot trigger (Db2 error -> 500, unknown type -> 400/`99`).
