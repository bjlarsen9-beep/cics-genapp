-- Derived from the MOTOR table DDL in base/cntl/db2cre.jcl.
-- H2 adjustments: VALUE is a reserved word in H2 (not in Db2), so the column is quoted as "VALUE" (stored upper-case,
-- the same name Db2 folds it to); Db2-only clauses CCSID EBCDIC and IN <DB2DBID>.GENATS05 are dropped; the separate
-- CREATE UNIQUE INDEX iMotor ... CLUSTER COPY YES is omitted because the primary key already provides it.
CREATE TABLE motor (
     policyNumber   INTEGER NOT NULL,
     make           CHAR(15),
     model          CHAR(15),
     "VALUE"        INTEGER,
     regNumber      CHAR(7),
     colour         CHAR(8),
     cc             SMALLINT,
     yearOfManufacture DATE,
     premium          INTEGER,
     accidents        INTEGER,
   PRIMARY KEY(policyNumber),
   FOREIGN KEY(policyNumber)
          REFERENCES policy (policyNumber) ON DELETE CASCADE);
