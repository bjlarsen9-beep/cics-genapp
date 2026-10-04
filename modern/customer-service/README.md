# customer-service

Java 21 / Spring Boot 3 replacement for GenApp's Customer Inquiry transaction (`base/src/lgicus01.cbl` ->
`base/src/lgicdb01.cbl`). See [MIGRATION.md](MIGRATION.md) for the paragraph-by-paragraph mapping and known
differences.

## API

`GET /customers/{customerNumber}`: `customerNumber` is 1 to 10 digits (`CA-CUSTOMER-NUM PIC 9(10)`), leading zeros optional.

```
$ curl localhost:8080/customers/1
{"customerNumber":"0000000001","firstName":"ANDREW","lastName":"PANDY","dateOfBirth":"1950-07-11",
 "houseName":"","houseNumber":"34","postcode":"PI101O","numberOfPolicies":0,
 "phoneMobile":"01962 811234","phoneHome":"07799 123456","emailAddress":"A.PANDY@BEEBHOUSE.COM"}
```

| COBOL `CA-RETURN-CODE` | HTTP status |
|---|---|
| `00` OK | 200 |
| `01` not found | 404 |
| `90` Db2 error | 500 |
| `98` bad request | 400 |

Non-200 responses have the body `{"returnCode":"<code>","message":"..."}`.

## Build and run

```
./mvnw -q verify          # build and run the parity tests
./mvnw spring-boot:run    # start on port 8080 with an in-memory H2 seeded from base/data/ksdscust.txt
```

The build reads `../../base/data/ksdscust.txt`, so run it from inside a full checkout of this repository.
If `repo.maven.apache.org` is unreachable or rate-limited, set `MVNW_REPOURL` to a Maven Central mirror for the
wrapper download and configure the same mirror in `~/.m2/settings.xml`.

## Tests

- `CustomerInquiryParityTest`: reads every record of `base/data/ksdscust.txt` with the `lgcmarea.cpy` offsets and
  checks each field of the REST response; also not-found and malformed-input cases.
- `CustomerInquiryErrorMappingTest`: SQLCODE branches that sample data cannot trigger (-913 -> 404, other -> 500).
- `CustomerSchemaParityTest`: H2 `CUSTOMER` columns, types, lengths and identity start match `db2cre.jcl`.
