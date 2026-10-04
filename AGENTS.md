# AGENTS.md: GenApp migration conventions

This repository contains IBM's CICS General Insurance Application (GenApp) as a stand-in for a classic policy administration system. Migrated services go in `modern/`.

## Legacy layout
- `base/src`: COBOL programs. Naming: `LG<op><entity><layer>`, where op is A(dd), I(nquire), U(pdate) or D(elete); entity is C(ustomer) or P(olicy); layer is `OL01`/`US01` (business logic), `DB01` (Db2) or `VS01` (VSAM).
- `base/src/*.cpy`: copybooks. `lgcmarea.cpy` is the COMMAREA contract; `lgpolicy.cpy` holds the policy layouts.
- `base/cntl`: JCL, including the Db2 DDL in `db2cre.jcl`. `base/data`: sample VSAM data (`ksdscust.txt`, `ksdspoly.txt`).
- Return codes: `00` OK, `01` not found, `90` Db2 error, `98` bad request or COMMAREA too short, `99` unknown request.

## Migration target
- Java 21, Spring Boot 3, Maven wrapper (`./mvnw`). One service per domain: `modern/customer-service`, `modern/policy-service`.
- REST contracts mirror the COMMAREA fields; keep the COBOL field names in Javadoc.
- HTTP mapping: 00 to 200/201, 01 to 404, 98 to 400, 90 to 500, 99 to 400.
- Local tests use H2 with schemas derived from `db2cre.jcl`, seeded from `base/data`.

## Required for every PR
- Parity tests: for every sample record touched, the Java output matches the COBOL-defined fields exactly. Include not-found and malformed-input cases.
- `MIGRATION.md` in each service maps each COBOL paragraph to its Java method.
- `./mvnw -q verify` passes. Paste the test summary into the PR description.
- Don't modify `base/` unless the task says so. If a legacy fix is needed, change the COBOL and the Java side in the same PR.
- Never commit credentials, and never log customer PII.
