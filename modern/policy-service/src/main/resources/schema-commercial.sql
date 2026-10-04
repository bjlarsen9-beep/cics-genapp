-- Derived from the COMMERCIAL table DDL in base/cntl/db2cre.jcl (same column names, order, types and lengths).
-- H2 adjustments: Db2-only clauses CCSID EBCDIC and IN <DB2DBID>.GENATS06 are dropped; the separate
-- CREATE UNIQUE INDEX iCommercial is omitted because the primary key already provides it.
CREATE TABLE commercial (
  PolicyNumber       INTEGER      NOT NULL,
  RequestDate        TimeStamp            ,
  StartDate          date                 ,
  RenewalDate        date                 ,
  Address            Char(255)            ,
  Zipcode            Char(8)              ,
  LatitudeN          Char(11)             ,
  LongitudeW         Char(11)             ,
  Customer           Char(255)            ,
  PropertyType       Char(255)            ,
  FirePeril          SmallINT             ,
  FirePremium        INTEGER              ,
  CrimePeril         SmallINT             ,
  CrimePremium       INTEGER              ,
  FloodPeril         SmallINT             ,
  FloodPremium       INTEGER              ,
  WeatherPeril       SmallINT             ,
  WeatherPremium     INTEGER              ,
  Status             SmallINT             ,
  RejectionReason    Char(255)            ,
  PRIMARY KEY(policyNumber),
  FOREIGN KEY(policyNumber)
         REFERENCES policy (policyNumber) ON DELETE CASCADE);
