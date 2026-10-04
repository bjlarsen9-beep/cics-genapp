-- Derived from the ENDOWMENT table DDL in base/cntl/db2cre.jcl (same columns, order, types and lengths).
-- H2 adjustments: Db2-only clauses CCSID EBCDIC and IN <DB2DBID>.GENATS03 are dropped; the separate
-- CREATE UNIQUE INDEX iEndowment ... CLUSTER COPY YES is omitted because the primary key already provides it.
CREATE TABLE endowment (
     policyNumber   INTEGER NOT NULL,
     equities       CHAR(1),
     withProfits    CHAR(1),
     managedFund    CHAR(1),
     fundName       CHAR(10),
     term           SMALLINT,
     sumAssured     INTEGER,
     lifeAssured    CHAR(31),
     paddingData    VARCHAR(32606),
   PRIMARY KEY(policyNumber),
   FOREIGN KEY(policyNumber)
          REFERENCES policy (policyNumber)
          ON DELETE CASCADE);
