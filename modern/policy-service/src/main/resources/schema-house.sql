-- Derived from the HOUSE table DDL in base/cntl/db2cre.jcl.
-- H2 adjustments: Db2-only clauses CCSID EBCDIC and IN <DB2DBID>.GENATS04 are dropped; the separate
-- CREATE UNIQUE INDEX iHouse ... CLUSTER COPY YES is omitted because the primary key already provides it.
-- "value" is a reserved word in H2, so it is quoted; the column name is still VALUE.
CREATE TABLE house (
     policyNumber   INTEGER NOT NULL,
     propertyType   CHAR(15),
     bedrooms       SMALLINT,
     "VALUE"        INTEGER,
     houseName      CHAR(20),
     houseNumber    CHAR(4),
     postcode       CHAR(8),
   PRIMARY KEY(policyNumber),
   FOREIGN KEY(policyNumber)
          REFERENCES policy (policyNumber) ON DELETE CASCADE);
