-- ******************************************************************
-- * Program     : V1__create_schema.sql
-- * Application : CardDemo
-- * Type        : Flyway migration - relational schema DDL
-- * Function    : Creates the eleven-table PostgreSQL schema replacing
-- *               the ten VSAM KSDS clusters and the one physical
-- *               sequential staging dataset of the legacy z/OS
-- *               system: 11 tables, 5 CHECK constraints, 10 foreign
-- *               keys and 4 optimistic-locking version columns.
-- * Source      : app/catlg/LISTCAT.txt, the authoritative physical
-- *               specification; the twelve IDCAMS DEFINE CLUSTER
-- *               members of app/jcl (ACCTFILE, CARDFILE, CUSTFILE,
-- *               XREFFILE, TRANFILE, TRANBKP, TCATBALF, DISCGRP,
-- *               TRANCATG, TRANTYPE, DUSRSECJ, DEFCUST); and the
-- *               eleven record-layout copybooks of app/cpy
-- *               (CVACT01Y, CVACT02Y, CVACT03Y, CVCUS01Y, CVTRA01Y,
-- *               CVTRA02Y, CVTRA03Y, CVTRA04Y, CVTRA05Y, CVTRA06Y,
-- *               CSUSR01Y) - all @ 7756d89
-- ******************************************************************
-- * Copyright Amazon.com, Inc. or its affiliates.
-- * All Rights Reserved.
-- *
-- * Licensed under the Apache License, Version 2.0 (the "License").
-- * You may not use this file except in compliance with the License.
-- * You may obtain a copy of the License at
-- *
-- *    http://www.apache.org/licenses/LICENSE-2.0
-- *
-- * Unless required by applicable law or agreed to in writing,
-- * software distributed under the License is distributed on an
-- * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
-- * either express or implied. See the License for the specific
-- * language governing permissions and limitations under the License
-- ******************************************************************

-- MODULE DOCSTRING
--
-- WHAT IT DOES
--
-- Creates exactly eleven tables, five CHECK constraints, ten foreign
-- keys and an optimistic-locking version column on four tables. It
-- creates nothing else.
--
-- The eleven-table arithmetic: app/catlg/LISTCAT.txt:L3940 reports
-- "CLUSTER --------------10", and those ten clusters are ACCTDATA,
-- CARDDATA, CARDXREF, CUSTDATA, DISCGRP, TCATBALF, TRANCATG,
-- TRANSACT, TRANTYPE and USRSEC. TRANSACT is DEFINEd twice in the job
-- stream - app/jcl/TRANFILE.jcl:L49 and app/jcl/TRANBKP.jcl - but it
-- is one cluster. The eleventh table stages the DALYTRAN dataset,
-- whose layout is app/cpy/CVTRA06Y.cpy and which has no cluster
-- definition at all. Ten plus one is eleven.
--
-- Secondary indexes are NOT created here: the three VSAM alternate
-- indexes catalogued at app/catlg/LISTCAT.txt:L3938 ("AIX ------3")
-- belong to V2__create_indexes.sql. Seed data belongs to
-- V3__seed_data.sql. Spring Batch metadata tables are created by the
-- framework's own schema script through
-- spring.batch.jdbc.initialize-schema and must never appear here or
-- in a fourth migration.
--
-- HOW TO RUN, BUILD AND TEST
--
--   ./mvnw clean verify                  from the repository root
--
-- Flyway applies this migration during application start-up, before
-- Hibernate validates the mapped schema against it. The integration
-- tier exercises it against a Testcontainers PostgreSQL 16 instance
-- under src/test/java/com/cardemo/integration/repository, and all 300
-- records of app/data/ASCII/dailytran.txt are driven through the
-- posting pipeline by src/test/java/com/cardemo/e2e. Those tests are
-- the regression net for this file: a schema is tested through the
-- repositories and jobs that use it, not through DDL assertions
-- written beside it.
--
-- To inspect an applied schema by hand:
--   \d+ account
--   SELECT conname, contype FROM pg_constraint
--    WHERE connamespace = 'public'::regnamespace ORDER BY contype, conname;
--
-- KEY CONFIGURATION AND DEFAULTS
--
-- Flyway, from the base Spring profile: locations
-- classpath:db/migration, enabled true, validate-on-migrate true,
-- clean-disabled true, out-of-order false, baseline-on-migrate false.
-- No migration pattern is ignored and no error is tolerated and
-- continued past. Exactly three migrations exist and ordering keys on
-- the V1__, V2__ and V3__ prefixes. The PostgreSQL dialect resolves
-- through the separate flyway-database-postgresql artefact, which is
-- a distinct dependency from flyway-core because the
-- database-specific modules were split out of the core.
--
-- Jakarta Persistence and Hibernate, in EVERY profile: ddl-auto
-- validate - never create, create-drop or update; open-in-view false;
-- show-sql false. No bind-parameter logging is enabled anywhere,
-- because the customer table holds personally identifiable data and
-- statement logging would put it in the application log.
--
-- This file needs no secret: it contains no credential, no connection
-- string, no host name and no database name.
--
-- COMMON FAILURE MODES AND TROUBLESHOOTING
--
-- "Migration checksum mismatch for migration version 1" - editing this
-- file after it has been applied is rejected because
-- validate-on-migrate is true. Revert the edit or recreate the target
-- database from empty; never relax validate-on-migrate and never clean.
--
-- "Detected resolved migration not applied to database" - out-of-order
-- and baseline-on-migrate are both false, so a partially migrated
-- database is a hard failure. Migrate from an empty schema.
--
-- "Schema-validation: missing column [...]" or "wrong column type
-- encountered in column [...]" - ddl-auto is validate, so ANY
-- divergence in column name or SQL type between this migration and the
-- Jakarta Persistence mappings stops start-up outright, with no silent
-- degradation and no partial start. Reconcile against the entity, key
-- and enum classes under src/main/java/com/cardemo/model, which are the
-- normative column contract; see the TYPE-CODE CONTRACT below. Never
-- widen a column arbitrarily to make the error disappear.
--
-- A CHECK constraint rejects a row during seeding - only five exist and
-- each encodes a domain the COBOL itself declares, so a rejection means
-- the loaded value is wrong rather than the constraint. No credit-score
-- range constraint exists, so a low score is never the cause.
--
-- A foreign key rejects a row during seeding - loading out of the order
-- the ten edges force is the usual cause; that order is the order the
-- tables are created below. daily_transaction has no foreign key by
-- design and can be staged at any point.


-- SCHEMA-WIDE CONVENTIONS
--
-- NULLABILITY. EVERY column in EVERY table is NOT NULL. A fixed-width
-- COBOL record has no concept of absence: an unset field is spaces or
-- zeros, never null. That is why two legitimately blank-looking columns
-- are still NOT NULL - account.acct_group_id and
-- daily_transaction.dalytran_proc_ts.
--
-- TRAILING FILLER. Every layout ends in a FILLER item padding the
-- record to its catalogued length, and no FILLER is ever modelled as a
-- column - including the one carrying a name of its own, SEC-USR-FILLER
-- at app/cpy/CSUSR01Y.cpy:L23. Padding is dataset geometry, not data.
-- The arithmetic proving each layout adds up is recorded per table.
--
-- NUMERIC PRECISION - THREE TIERS THAT ARE NEVER COLLAPSED. Each is
-- taken from its PIC clause and no two are interchangeable:
--   PIC S9(10)V99  ->  NUMERIC(12,2)
--                      the five account balance and cycle columns
--   PIC S9(09)V99  ->  NUMERIC(11,2)
--                      "transaction".tran_amt,
--                      daily_transaction.dalytran_amt,
--                      transaction_category_balance.tran_cat_bal
--   PIC S9(04)V99  ->  NUMERIC(6,2)
--                      disclosure_group.dis_int_rate, that column
--                      alone in the whole schema
-- No approximate numeric type appears anywhere: no binary
-- floating-point column and no currency-typed column. All monetary and
-- rate values are exact decimal, because the source arithmetic is
-- packed and zoned decimal and half-even rounding at a fixed scale is
-- part of the behaviour being reproduced.
--
-- SIGNED COLUMNS STAY SIGNED. Every S-prefixed PIC clause maps to a
-- signed column and no non-negative constraint is added to any of them.
-- account.acct_curr_cyc_debit legitimately holds negative values:
-- app/cbl/CBTRN02C.cbl:547-552 adds a negative transaction amount into
-- the debit accumulator, and the over-limit test at :403-405 computes
-- ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT, which is
-- correct precisely because the subtrahend may be negative. Normalising
-- the sign would break the over-limit branch.
--
-- IDENTIFIERS: WHEN 9(nn) IS NOT NUMERIC. A PIC 9(nn) field maps to a
-- NUMERIC(nn) column over a Java integral type - never to a decimal
-- type - EXCEPT where the seed fixtures carry a significant leading
-- zero, which a numeric type would silently destroy. Three columns are
-- demoted to fixed-width character for that reason, counted over the
-- fifty-row fixtures:
--   customer.cust_ssn               6 of 50 rows lead with a zero
--   customer.cust_fico_credit_score 7 of 50 rows lead with a zero
--   card.card_cvv_cd                8 of 50 rows lead with a zero
-- customer.cust_id, also PIC 9(09), has no such row and stays numeric.
-- For cust_ssn the demotion is compensated by a CHECK restoring the
-- numeric domain the copybook declared.
--
-- DATE AND TIME COLUMNS ARE TEXT. Every X(10) date and every X(26)
-- date-and-time field stays fixed-width character, for three
-- independent reasons:
--   1. The source compares dates by substring, not as dates:
--      app/cbl/COACTUPC.cbl:4127-4137 compares (1:4), (6:2) and (9:2)
--      of the account dates, which both proves the yyyy-MM-dd shape
--      WITH dashes and shows that component-wise comparison, not
--      temporal ordering, is the behaviour.
--   2. Three producers write mutually incompatible text into the
--      X(26) fields. app/data/ASCII/dailytran.txt carries
--      2022-06-10 19:27:53.000000 in the origination field on all 300
--      rows. The posting job builds a 26-character value whose last
--      four digits are always zeros - hundredths-of-a-second precision
--      followed by a literal '0000', never milliseconds (which would
--      give 27 characters) and never nanoseconds (29): see the layout
--      comment at app/cbl/CBTRN02C.cbl:149, which spells the shape
--      EEEE-MM-DD-UU.MM.SS.HH0000, the PIC X(26) declaration at
--      :159, the two-digit PIC 9(002) hundredths subfield DB2-MIL at
--      :173 and the PIC X(04) tail at :174, assigned at :700 and :701
--      where the tail receives the literal '0000'.
--   3. The processing field of that same fixture is 26 blanks on all
--      300 rows. No temporal column type can hold 26 blanks, so it
--      would force a fabricated value and break parity.
--
-- TABLE NAME QUOTING. PostgreSQL classifies TRANSACTION as a
-- non-reserved keyword, so an unquoted lowercase spelling would also be
-- legal; the quoted lowercase form "transaction" is emitted
-- consistently here and in V2 to match the mapped name - never mixed
-- case, and never quoted in one migration and bare in the other.
--
-- COLUMN NAME REUSE IS INTENTIONAL. tran_type_cd and tran_cat_cd each
-- appear in several tables. They are independent columns that carry the
-- same COBOL-derived name because they hold the same business code,
-- they are not evidence of a shared type, and they are not abstracted
-- into one.


-- TYPE-CODE CONTRACT - WHY SOME COLUMNS ARE NOT THE OBVIOUS TYPE
--
-- Hibernate 6.6's schema validator compares JDBC TYPE CODES. It
-- inspects every mapped column unconditionally and checks the type only
-- - never the length, never the precision, never the nullability. A
-- column is accepted when the dialect declares the expected and the
-- database-reported type codes equivalent, or when the mapping's own
-- type spelling with parenthesised arguments stripped and lowercased
-- equals the type name the database reports. A third path, a
-- dialect-resolved default code, was measured to be inert for
-- PostgreSQL and never rescues a mismatch here.
--
-- Equivalences measured on Hibernate 6.6.42 with the PostgreSQL
-- dialect: INTEGER against reported BIGINT is equivalent and NUMERIC
-- against DECIMAL is equivalent, while NUMERIC against INTEGER, NUMERIC
-- against BIGINT and CHAR against VARCHAR are NOT.
--
-- The rule the columns below obey is therefore: an attribute pinned to
-- the CHAR type code needs CHAR(n); a text attribute pinned to nothing
-- needs VARCHAR(n); a 64-bit integral attribute pinned to nothing needs
-- BIGINT; a 32-bit integral attribute pinned to nothing needs INTEGER;
-- and an attribute pinned to the NUMERIC type code, or one declaring
-- its own numeric column spelling, needs NUMERIC.
--
-- Five columns are consequently NOT the type their PIC clause alone
-- would suggest. Every one is an embedded composite-key component whose
-- key class pins no type code:
--   transaction_category_balance.acct_id       BIGINT
--   transaction_category_balance.tran_type_cd  VARCHAR(2)
--   transaction_category_balance.tran_cat_cd   INTEGER
--   transaction_category.tran_type_cd          VARCHAR(2)
--   disclosure_group.tran_cat_cd               INTEGER
-- The three PIC-derived precision tiers above are untouched: the
-- twelve-, eleven- and six-digit decimal columns are exactly as their
-- PIC clauses dictate.
--
-- The corresponding foreign keys were confirmed creatable against
-- PostgreSQL 16 by direct measurement: BIGINT to a NUMERIC(11) parent,
-- VARCHAR(2) to CHAR(2), CHAR(2) to VARCHAR(2) and INTEGER to
-- NUMERIC(4) are all accepted. PostgreSQL refuses exactly one
-- combination, NUMERIC referencing INTEGER, reporting "Key columns are
-- of incompatible types: numeric and integer". That refusal is what
-- forces transaction_category.tran_cat_cd to NUMERIC(4): the
-- referencing column "transaction".tran_cat_cd is pinned to the NUMERIC
-- type code by its mapped attribute, so an INTEGER parent would make
-- foreign key 6 uncreatable. The mapped key component declares its own
-- numeric column spelling to match, as the disclosure-group key class
-- already does for two of its components.


-- DETERMINISM AND ENVIRONMENT NEUTRALITY
--
-- This migration is pure, deterministic DDL: no clock-reading,
-- pseudo-random or unique-value function and no default expression; no
-- auto-numbering type or clause, no sequence and no computed column; no
-- procedural hook and no stored routine; no existence-tolerant guard,
-- no conflict-tolerant insert and no pre-emptive drop; no
-- storage-location clause, absolute path, privilege statement, role
-- creation, extension installation, hard-coded database or host name,
-- schema qualification beyond the default search path, psql
-- meta-command, shell-invoking bulk-load construct or server-side
-- file-reading function. Applying it twice is an error, and that is
-- correct: Flyway's schema history, not defensive DDL, is what makes
-- the operation repeatable, and a migration that cannot do what it was
-- asked must fail loudly with its root cause intact.
--
-- NO DATABASE-SIDE KEY ALLOCATION. Severity Blocker if added. The
-- legacy transaction identifier is produced by browsing the keyed file
-- backwards from HIGH-VALUES to the maximum key and adding one:
-- app/cbl/COTRN02C.cbl:444-451 and app/cbl/COBIL00C.cbl:212-219 both
-- perform MOVE HIGH-VALUES, STARTBR, READPREV, ENDBR, ADD 1. The
-- empty-file case is explicit at app/cbl/COBIL00C.cbl:487-488, where
-- an end-of-file response moves zeros into the identifier, so the
-- first identifier ever issued is 1. That algorithm is inherently
-- racy, exactly as the browse was, and the race is preserved
-- deliberately: a database-side allocator would change the values
-- issued and break comparison against the legacy baseline. A
-- concurrent collision must therefore surface as a primary key
-- violation, which is why the primary keys below are plain declared
-- keys over the natural COBOL key fields.
--
-- The rule holds for the one synthetic key too, and for the same
-- reason rather than by exception. daily_transaction stages an unkeyed
-- physical sequential dataset and so has no COBOL key field to
-- promote; its key is the ingestion ordinal ingest_seq. That ordinal
-- is nevertheless assigned by the LOADER, in read order, modelling
-- WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:185 and :206 - not by
-- the database. So the count of GENERATED clauses, IDENTITY columns
-- and sequences created by this migration is zero, without exception.
-- ==================================================================


-- CITATION DISCIPLINE FOR app/catlg/LISTCAT.txt
--
-- Every cluster block in that file has the same five-line shape, where
-- K is the line carrying KEYLEN: L(K-2) the cluster name with the DATA
-- component's ASSOCIATIONS back-reference, L(K-1) the ATTRIBUTES label,
-- L(K) KEYLEN----nn AVGLRECL----nnn, and L(K+1) RKP--------0
-- MAXLRECL----nnn. Every cluster in this application has RKP 0: the key
-- is the record prefix. L(K) is the DATA COMPONENT ATTRIBUTE LINE, and
-- it - not the cluster entry header, which sits far above it - is what
-- the per-table key and record-length citations point at. Each table
-- below cites both lines and says which is which. L202, L403 and L896
-- all report KEYLEN 16 for three different clusters, so a citation of
-- "KEYLEN 16" without its line number is ambiguous and is never used.
--
-- Two byte-numbering bases are in play and are always stated
-- explicitly, because mixing them silently shifts an index by one.
-- IDCAMS KEYS(len offset) and LISTCAT AXRKP are ZERO-based; the
-- record-position ranges quoted from the copybooks are ONE-based. So
-- AXRKP 16 is byte 17, AXRKP 25 is byte 26 and AXRKP 304 is byte 305.


-- LEGACY DATASET NOTES
--
-- FIVE DATASETS HAVE NO ONLINE DEFINITION. app/csd/CARDDEMO.CSD defines
-- exactly eight files, and that is the whole online file control table:
-- ACCTDAT at L1, CARDAIX at L13, CARDDAT at L25, CCXREF at L37, CUSTDAT
-- at L50, CXACAIX at L63, TRANSACT at L76 and USRSEC at L88. TCATBALF,
-- DISCGRP, TRANCATG, TRANTYPE and DALYTRAN appear nowhere in that file,
-- which is the evidence that those five are batch-only - the tables
-- standing for them are reached by batch jobs and never by an online
-- endpoint. They are still ordinary tables here; the distinction is an
-- authorisation and test-surface concern, not a schema one.
--
-- TWO CLUSTER DEFINITIONS DELIBERATELY GET NO TABLE, because a table
-- for either would be dead weight. app/jcl/DEFCUST.jcl defines
-- AWS.CUSTDATA.CLUSTER at :L35 with KEYS(10 0) at :L37 and
-- RECORDSIZE(500 500) at :L38; no program in app/cbl opens it, and its
-- ten-byte key differs from CUSTDATA's nine, so it is an orphan rather
-- than a duplicate of the customer cluster. app/jcl/CREASTMT.JCL
-- defines AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS at :L29 with KEYS(32 0) at
-- :L30 and RECORDSIZE(350 350) at :L32, deleted at :L25-L26 and
-- rebuilt on every run: an in-job work cluster never persisted between
-- jobs, whose 32-byte key corroborates the composite TRNX-KEY of
-- app/cpy/COSTM01.CPY. Excluding these two is why the provisioning-job
-- count for this schema is twelve rather than thirteen.
--
-- RECORD LENGTHS THE WIDER SYSTEM DEPENDS ON, for context only - none
-- is a column width here and none is enforced by this file: the
-- transaction and daily-transaction record is 350; the posting reject
-- record is 430, the 350-byte transaction image plus an 80-byte trailer
-- of a 4-digit reason code and a 76-character description; the report
-- line is 133; the statement text and HTML lines are 80 and 100; the
-- report-job queue parameter record is 80; the statement work-cluster
-- key is 32.


-- TABLE 1 of 11 - transaction_type
--
-- Source layout : app/cpy/CVTRA03Y.cpy, 60-byte record, 2-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L3742 cluster entry header;
--                 :L3779 DATA component attribute line, KEYLEN 2
--                 AVGLRECL 60; RKP 0 at :L3780
-- Provisioning  : app/jcl/TRANTYPE.jcl:L36 DEFINE CLUSTER,
--                 :L40 KEYS(2 0), :L41 RECORDSIZE(60 60)
-- Online access : none. TRANTYPE has no DEFINE FILE in
--                 app/csd/CARDDEMO.CSD - it is batch-only, and
--                 app/cbl/CBTRN03C.cbl is the only program in the
--                 corpus that opens it.
--
-- NAMING EXCEPTION. The key column is tran_type, NOT tran_type_cd.
-- app/cpy/CVTRA03Y.cpy:L5 names the field plainly TRAN-TYPE, uniquely
-- among the copybooks; every other transaction-type field carries the
-- -CD suffix (app/cpy/CVTRA05Y.cpy:L6, CVTRA06Y.cpy:L6,
-- CVTRA01Y.cpy:L7, CVTRA02Y.cpy:L7, CVTRA04Y.cpy:L6). Corroborated by
-- app/cbl/CBTRN03C.cbl:189, MOVE TRAN-TYPE-CD OF TRAN-RECORD TO
-- FD-TRAN-TYPE, whose TARGET field carries no suffix. Harmonising this
-- column to tran_type_cd would fail schema validation and stop
-- start-up. It is left as the source has it.
--
-- Geometry: 2 + 50 = 52 modelled bytes, plus the FILLER X(08) at
-- app/cpy/CVTRA03Y.cpy:L7 = 60. Matches AVGLRECL 60.
CREATE TABLE transaction_type (
  -- TRAN-TYPE       PIC X(02)  app/cpy/CVTRA03Y.cpy:L5  bytes [1-2]
  tran_type       CHAR(2)  NOT NULL,
  -- TRAN-TYPE-DESC  PIC X(50)  app/cpy/CVTRA03Y.cpy:L6  bytes [3-52]
  tran_type_desc  CHAR(50) NOT NULL,
  CONSTRAINT pk_transaction_type PRIMARY KEY (tran_type)
);


-- TABLE 2 of 11 - transaction_category
--
-- Source layout : app/cpy/CVTRA04Y.cpy, 60-byte record, 6-byte
--                 composite key
-- Legacy cluster: AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L1440 cluster entry header;
--                 :L1475 DATA component attribute line, KEYLEN 6
--                 AVGLRECL 60
-- Provisioning  : app/jcl/TRANCATG.jcl:L36 DEFINE CLUSTER,
--                 :L40 KEYS(6 0), :L41 RECORDSIZE(60 60)
-- Online access : none - batch-only, same evidence as table 1.
--
-- THE TRAN-CAT-KEY ARITY COLLISION. The COBOL group name TRAN-CAT-KEY
-- is declared TWICE in the corpus over two entirely different keys:
--   app/cpy/CVTRA04Y.cpy:L5  6 bytes, TWO fields, TRAN- prefix,
--                            over TRANCATG KEYLEN 6 at
--                            app/catlg/LISTCAT.txt:L1475 - this table
--   app/cpy/CVTRA01Y.cpy:L5  17 bytes, THREE fields, TRANCAT- prefix,
--                            over TCATBALF KEYLEN 17 at
--                            app/catlg/LISTCAT.txt:L1371 - table 8
-- They are two distinct composite keys of different arity and are
-- deliberately NOT merged, abstracted or harmonised.
--
-- Composite key order is taken from app/cpy/CVTRA04Y.cpy:L6-L7 in
-- declaration order: type code then category code.
--
-- Column types: tran_type_cd is VARCHAR(2) and NOT CHAR(2) because the
-- embedded key class declares a plain text component with no pinned
-- type code. tran_cat_cd is NUMERIC(4) because the referencing column
-- "transaction".tran_cat_cd is pinned to the NUMERIC type code and
-- PostgreSQL refuses a NUMERIC-to-INTEGER foreign key outright. Both
-- rules are set out in the TYPE-CODE CONTRACT above.
--
-- Geometry: 2 + 4 = 6 key bytes, matching KEYLEN 6; + 50 = 56
-- modelled bytes, plus the FILLER X(04) at app/cpy/CVTRA04Y.cpy:L9
-- = 60. Matches AVGLRECL 60.
CREATE TABLE transaction_category (
  -- TRAN-TYPE-CD        PIC X(02)  app/cpy/CVTRA04Y.cpy:L6  bytes [1-2]
  tran_type_cd        VARCHAR(2) NOT NULL,
  -- TRAN-CAT-CD         PIC 9(04)  app/cpy/CVTRA04Y.cpy:L7  bytes [3-6]
  tran_cat_cd         NUMERIC(4) NOT NULL,
  -- TRAN-CAT-TYPE-DESC  PIC X(50)  app/cpy/CVTRA04Y.cpy:L8  bytes [7-56]
  tran_cat_type_desc  CHAR(50)   NOT NULL,
  CONSTRAINT pk_transaction_category PRIMARY KEY (tran_type_cd, tran_cat_cd),
  -- FK 9 of 10. The transaction type of a category must exist.
  -- Evidence: app/cpy/CVTRA04Y.cpy:L6 shares TRAN-TYPE-CD with the
  -- type cluster's key, and app/cbl/CBTRN03C.cbl performs the paired
  -- keyed reads that prove the relationship - :495 READ TRANTYPE-FILE
  -- INTO TRAN-TYPE-RECORD and :505 READ TRANCATG-FILE INTO
  -- TRAN-CAT-RECORD, both keyed from the same transaction record.
  CONSTRAINT fk09_category_type FOREIGN KEY (tran_type_cd)
    REFERENCES transaction_type (tran_type)
);


-- TABLE 3 of 11 - account
--
-- Source layout : app/cpy/CVACT01Y.cpy, 300-byte record, 11-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L22 cluster entry header;
--                 :L59 DATA component attribute line, KEYLEN 11
--                 AVGLRECL 300
-- Provisioning  : app/jcl/ACCTFILE.jcl:L36 DEFINE CLUSTER,
--                 :L40 KEYS(11 0), :L41 RECORDSIZE(300 300)
-- Online access : app/csd/CARDDEMO.CSD:L1 DEFINE FILE(ACCTDAT)
--
-- THE MISSPELLING IS PRESERVED. acct_expiraion_date reproduces
-- ACCT-EXPIRAION-DATE exactly as app/cpy/CVACT01Y.cpy:L11 spells it.
-- The misspelling is part of the field contract and the field is in
-- active use: app/cbl/COACTUPC.cbl:3836-3839 and :4131-4133 read and
-- compare it, and app/cbl/CBTRN02C.cbl:414 tests it against the
-- transaction origination value. Correcting the spelling would break
-- schema validation against the mapped attribute and silently diverge
-- from the source of record. It stays misspelled.
--
-- THE THREE DATE COLUMNS ARE TEXT, NEVER A TEMPORAL TYPE:
-- app/cbl/COACTUPC.cbl:4127-4137 compares acct-open, expiraion and
-- reissue by substring, as DATE AND TIME COLUMNS ARE TEXT sets out.
--
-- acct_curr_cyc_debit IS SIGNED AND HOLDS NEGATIVE VALUES.
-- app/cbl/CBTRN02C.cbl:547-552 adds the transaction amount to the
-- credit accumulator when it is non-negative and to the DEBIT
-- accumulator otherwise, so a negative amount lands in the debit
-- column as a negative number, and the over-limit test at
-- app/cbl/CBTRN02C.cbl:403-405 subtracts that column. No non-negative
-- constraint is added, and no absolute-value normalisation is
-- permitted anywhere on this column.
--
-- acct_group_id IS TEN SPACES ON ALL FIFTY FIXTURE ROWS AND IS NEVER
-- NULL - bytes 113-122 of every row of app/data/ASCII/acctdata.txt,
-- one distinct value. No blank-rejecting constraint is added and the
-- column is never trimmed in a predicate, because that blank value is
-- exactly what makes the disclosure-group DEFAULT fallback at
-- app/cbl/CBACT04C.cbl:436-438 the path that resolves interest rates
-- for the entire fixture set.
--
-- acct_addr_zip CARRIES A GROUP-IDENTIFIER-SHAPED LITERAL ON ALL FIFTY
-- ROWS - bytes 103-112 of app/data/ASCII/acctdata.txt, one distinct
-- value, and it is also one of the three group identifiers present in
-- app/data/ASCII/discgrp.txt. app/cpy/CVACT01Y.cpy nevertheless places
-- ACCT-ADDR-ZIP at :L15 over bytes 103-112 and ACCT-GROUP-ID at :L16
-- over bytes 113-122, in that order. The copybook is authoritative:
-- the value loads verbatim into the column the layout assigns it and
-- is not corrected.
--
-- Geometry: 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10
-- = 122 modelled bytes, plus the FILLER X(178) at
-- app/cpy/CVACT01Y.cpy:L17 = 300. Matches AVGLRECL 300.
CREATE TABLE account (
  -- ACCT-ID                 PIC 9(11)      app/cpy/CVACT01Y.cpy:L5   bytes [1-11]
  acct_id                 NUMERIC(11)   NOT NULL,
  -- ACCT-ACTIVE-STATUS      PIC X(01)      app/cpy/CVACT01Y.cpy:L6   byte  [12]
  acct_active_status      CHAR(1)       NOT NULL,
  -- ACCT-CURR-BAL           PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L7   bytes [13-24]
  acct_curr_bal           NUMERIC(12,2) NOT NULL,
  -- ACCT-CREDIT-LIMIT       PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L8   bytes [25-36]
  acct_credit_limit       NUMERIC(12,2) NOT NULL,
  -- ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L9   bytes [37-48]
  acct_cash_credit_limit  NUMERIC(12,2) NOT NULL,
  -- ACCT-OPEN-DATE          PIC X(10)      app/cpy/CVACT01Y.cpy:L10  bytes [49-58]
  acct_open_date          CHAR(10)      NOT NULL,
  -- ACCT-EXPIRAION-DATE     PIC X(10)      app/cpy/CVACT01Y.cpy:L11  bytes [59-68]
  -- Source misspelling preserved verbatim - see the note above.
  acct_expiraion_date     CHAR(10)      NOT NULL,
  -- ACCT-REISSUE-DATE       PIC X(10)      app/cpy/CVACT01Y.cpy:L12  bytes [69-78]
  acct_reissue_date       CHAR(10)      NOT NULL,
  -- ACCT-CURR-CYC-CREDIT    PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L13  bytes [79-90]
  acct_curr_cyc_credit    NUMERIC(12,2) NOT NULL,
  -- ACCT-CURR-CYC-DEBIT     PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L14  bytes [91-102]
  -- Signed, legitimately negative - see the note above.
  acct_curr_cyc_debit     NUMERIC(12,2) NOT NULL,
  -- ACCT-ADDR-ZIP           PIC X(10)      app/cpy/CVACT01Y.cpy:L15  bytes [103-112]
  acct_addr_zip           CHAR(10)      NOT NULL,
  -- ACCT-GROUP-ID           PIC X(10)      app/cpy/CVACT01Y.cpy:L16  bytes [113-122]
  acct_group_id           CHAR(10)      NOT NULL,
  -- Optimistic-locking counter. No COBOL counterpart: it is the
  -- store-level half of the two-layer concurrency control that
  -- replaces the READ ... UPDATE plus snapshot comparison idiom of
  -- app/cbl/COACTUPC.cbl:669-756. The business-level half is a
  -- field-by-field snapshot comparison in the service layer, because a
  -- counter detects THAT a row changed while the source detects WHICH
  -- fields changed.
  version                 BIGINT        NOT NULL,
  CONSTRAINT pk_account PRIMARY KEY (acct_id),
  -- CHECK 1 of 5. Domain from app/cbl/COACTUPC.cbl:193
  -- 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'. with the paired error
  -- message condition ACCT-STATUS-MUST-BE-YES-NO at :503. No 88-level
  -- for this field exists anywhere in app/cpy, so the online validator
  -- is the authority. Corroborated by byte 12 of every row of
  -- app/data/ASCII/acctdata.txt: fifty occurrences of 'Y'.
  CONSTRAINT ck_account_active_status CHECK (acct_active_status IN ('Y', 'N'))
);


-- TABLE 4 of 11 - customer
--
-- Source layout : app/cpy/CVCUS01Y.cpy AND app/cpy/CUSTREC.cpy,
--                 500-byte record, 9-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L595 cluster entry header;
--                 :L632 DATA component attribute line, KEYLEN 9
--                 AVGLRECL 500
-- Provisioning  : app/jcl/CUSTFILE.jcl:L46 DEFINE CLUSTER,
--                 :L50 KEYS(9 0), :L51 RECORDSIZE(500 500)
-- Online access : app/csd/CARDDEMO.CSD:L50 DEFINE FILE(CUSTDAT)
--
-- ONE TABLE SERVES TWO PROVEN-IDENTICAL COPYBOOKS. diff -w over
-- app/cpy/CVCUS01Y.cpy and app/cpy/CUSTREC.cpy yields exactly two
-- hunks: at :L19 the field is named CUST-DOB-YYYY-MM-DD in one and
-- CUST-DOB-YYYYMMDD in the other, both PIC X(10) and therefore
-- identical in width and offset; and at :L25 a version comment differs
-- by one second. The remaining raw difference is tab-versus-space
-- indentation only. Two tables would be duplication with no
-- behavioural difference, so one table serves both and the column
-- takes the CVCUS01Y spelling that the mapped attribute uses.
--
-- cust_dob_yyyy_mm_dd IS DASH-SEPARATED X(10) AND MUST NOT BE
-- COLLAPSED WITH ITS SNAPSHOT COUNTERPART. app/cbl/COACTUPC.cbl:746
-- declares the screen-snapshot field as PIC X(08), separator-free, and
-- the source consequently compares the stored value's components at
-- offsets 1, 6 and 9 against the snapshot's at offsets 1, 5 and 7. The
-- two representations differ by design. This column holds the stored,
-- dash-separated form of width 10; the compact form belongs to the
-- request payload, not to this table.
--
-- cust_ssn AND cust_fico_credit_score ARE FIXED-WIDTH CHARACTER, NOT
-- NUMERIC, despite PIC 9(09) and PIC 9(03): leading zeros occur in the
-- fixture - 6 of 50 rows for the first and 7 of 50 for the second -
-- and a numeric column would destroy them silently. cust_id, also
-- PIC 9(09), has no such row and stays numeric.
--
-- THIS TABLE IS THE PERSONALLY-IDENTIFIABLE-DATA EPICENTRE. No
-- national identifier, telephone number or date of birth is quoted
-- anywhere in this file as an example value: such values load as data
-- and are never exhibited in source. It is also why statement logging
-- is disabled in every profile and why the logging configuration masks
-- these fields.
--
-- Geometry: 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15
-- + 9 + 20 + 10 + 10 + 1 + 3 = 332 modelled bytes, plus the
-- FILLER X(168) at app/cpy/CVCUS01Y.cpy:L23 = 500. Matches
-- AVGLRECL 500.
CREATE TABLE customer (
  -- CUST-ID                    PIC 9(09)  app/cpy/CVCUS01Y.cpy:L5   bytes [1-9]
  cust_id                   NUMERIC(9) NOT NULL,
  -- CUST-FIRST-NAME            PIC X(25)  app/cpy/CVCUS01Y.cpy:L6   bytes [10-34]
  cust_first_name           CHAR(25)   NOT NULL,
  -- CUST-MIDDLE-NAME           PIC X(25)  app/cpy/CVCUS01Y.cpy:L7   bytes [35-59]
  cust_middle_name          CHAR(25)   NOT NULL,
  -- CUST-LAST-NAME             PIC X(25)  app/cpy/CVCUS01Y.cpy:L8   bytes [60-84]
  cust_last_name            CHAR(25)   NOT NULL,
  -- CUST-ADDR-LINE-1           PIC X(50)  app/cpy/CVCUS01Y.cpy:L9   bytes [85-134]
  cust_addr_line_1          CHAR(50)   NOT NULL,
  -- CUST-ADDR-LINE-2           PIC X(50)  app/cpy/CVCUS01Y.cpy:L10  bytes [135-184]
  cust_addr_line_2          CHAR(50)   NOT NULL,
  -- CUST-ADDR-LINE-3           PIC X(50)  app/cpy/CVCUS01Y.cpy:L11  bytes [185-234]
  cust_addr_line_3          CHAR(50)   NOT NULL,
  -- CUST-ADDR-STATE-CD         PIC X(02)  app/cpy/CVCUS01Y.cpy:L12  bytes [235-236]
  cust_addr_state_cd        CHAR(2)    NOT NULL,
  -- CUST-ADDR-COUNTRY-CD       PIC X(03)  app/cpy/CVCUS01Y.cpy:L13  bytes [237-239]
  cust_addr_country_cd      CHAR(3)    NOT NULL,
  -- CUST-ADDR-ZIP              PIC X(10)  app/cpy/CVCUS01Y.cpy:L14  bytes [240-249]
  cust_addr_zip             CHAR(10)   NOT NULL,
  -- CUST-PHONE-NUM-1           PIC X(15)  app/cpy/CVCUS01Y.cpy:L15  bytes [250-264]
  cust_phone_num_1          CHAR(15)   NOT NULL,
  -- CUST-PHONE-NUM-2           PIC X(15)  app/cpy/CVCUS01Y.cpy:L16  bytes [265-279]
  cust_phone_num_2          CHAR(15)   NOT NULL,
  -- CUST-SSN                   PIC 9(09)  app/cpy/CVCUS01Y.cpy:L17  bytes [280-288]
  -- Character, not numeric: leading zeros must survive the load.
  cust_ssn                  CHAR(9)    NOT NULL,
  -- CUST-GOVT-ISSUED-ID        PIC X(20)  app/cpy/CVCUS01Y.cpy:L18  bytes [289-308]
  cust_govt_issued_id       CHAR(20)   NOT NULL,
  -- CUST-DOB-YYYY-MM-DD        PIC X(10)  app/cpy/CVCUS01Y.cpy:L19  bytes [309-318]
  cust_dob_yyyy_mm_dd       CHAR(10)   NOT NULL,
  -- CUST-EFT-ACCOUNT-ID        PIC X(10)  app/cpy/CVCUS01Y.cpy:L20  bytes [319-328]
  cust_eft_account_id       CHAR(10)   NOT NULL,
  -- CUST-PRI-CARD-HOLDER-IND   PIC X(01)  app/cpy/CVCUS01Y.cpy:L21  byte  [329]
  cust_pri_card_holder_ind  CHAR(1)    NOT NULL,
  -- CUST-FICO-CREDIT-SCORE     PIC 9(03)  app/cpy/CVCUS01Y.cpy:L22  bytes [330-332]
  -- Character, not numeric: leading zeros must survive the load.
  cust_fico_credit_score    CHAR(3)    NOT NULL,
  -- Optimistic-locking counter. Paired with account.version: the two
  -- rewrites of app/cbl/COACTUPC.cbl:4065-4091 are one unit of work.
  version                   BIGINT     NOT NULL,
  CONSTRAINT pk_customer PRIMARY KEY (cust_id),
  -- CHECK 3 of 5. Domain from app/cbl/COACTUPC.cbl:350
  -- 88 FLG-PRI-CARDHOLDER-ISVALID VALUES 'Y', 'N'. No 88-level for
  -- this field exists in app/cpy, so the online validator is the
  -- authority. Corroborated by byte 329 of every row of
  -- app/data/ASCII/custdata.txt: fifty occurrences of 'Y'.
  CONSTRAINT ck_customer_pri_card_holder_ind
    CHECK (cust_pri_card_holder_ind IN ('Y', 'N')),
  -- CHECK 5 of 5. app/cpy/CVCUS01Y.cpy:L17 declares CUST-SSN as
  -- PIC 9(09), a numeric domain of exactly nine digits. The demotion to
  -- fixed-width character above discards that guarantee, so this
  -- constraint restores exactly what the source declared and nothing
  -- more. The redefinition at app/cbl/COACTUPC.cbl:130-131 and the
  -- active validator invoked at :1530-1531 confirm the digits-only
  -- reading. All 50 fixture rows are nine digits, so seeding is clean.
  CONSTRAINT ck_customer_ssn_numeric CHECK (cust_ssn ~ '^[0-9]{9}$')
);


-- TABLE 5 of 11 - card
--
-- Source layout : app/cpy/CVACT02Y.cpy, 150-byte record, 16-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L164 cluster entry header;
--                 :L202 DATA component attribute line, KEYLEN 16
--                 AVGLRECL 150
-- Provisioning  : app/jcl/CARDFILE.jcl:L50 DEFINE CLUSTER,
--                 :L54 KEYS(16 0), :L55 RECORDSIZE(150 150)
-- Online access : app/csd/CARDDEMO.CSD:L25 DEFINE FILE(CARDDAT) and
--                 :L13 DEFINE FILE(CARDAIX), the alternate-index path
--
-- THE SAME MISSPELLING RECURS. card_expiraion_date reproduces
-- CARD-EXPIRAION-DATE exactly as app/cpy/CVACT02Y.cpy:L9 spells it.
-- Preserved for the same reason as the account column.
--
-- ALTERNATE-INDEX ANCHOR. card_acct_id begins at one-based byte 17,
-- which is the zero-based AXRKP 16 that app/catlg/LISTCAT.txt records
-- for CARDDATA.VSAM.AIX. That alternate index becomes a B-tree index
-- in V2__create_indexes.sql over this column; it is deliberately NOT
-- created here, and it is deliberately NOT unique - a customer may
-- hold several cards on one account.
--
-- card_cvv_cd IS FIXED-WIDTH CHARACTER, NOT NUMERIC, despite
-- PIC 9(03): 8 of the 50 rows of app/data/ASCII/carddata.txt carry a
-- significant leading zero. No digits-only constraint is added, because
-- there is ZERO card-verification validation anywhere in app/cbl - the
-- only occurrences of the field are PIC X(03) over PIC 9(03)
-- redefinition pairs used for numeric conversion, at
-- app/cbl/COCRDLIC.cbl:102-103, app/cbl/COCRDUPC.cbl:107-108, :294,
-- :306 and :317, and app/cbl/COCRDSLC.cbl:76-77. No 88-level, no range
-- test and no digits test exists, so a constraint would be an
-- invention.
--
-- Geometry: 16 + 11 + 3 + 50 + 10 + 1 = 91 modelled bytes, plus the
-- FILLER X(59) at app/cpy/CVACT02Y.cpy:L11 = 150. Matches
-- AVGLRECL 150.
CREATE TABLE card (
  -- CARD-NUM             PIC X(16)  app/cpy/CVACT02Y.cpy:L5   bytes [1-16]
  card_num             CHAR(16)    NOT NULL,
  -- CARD-ACCT-ID         PIC 9(11)  app/cpy/CVACT02Y.cpy:L6   bytes [17-27]
  -- One-based byte 17 = zero-based AXRKP 16, the CARDDATA alternate
  -- index offset. Mapped as a plain scalar, never as an association,
  -- so this is a bare column with a declared foreign key and no
  -- join-side machinery.
  card_acct_id         NUMERIC(11) NOT NULL,
  -- CARD-CVV-CD          PIC 9(03)  app/cpy/CVACT02Y.cpy:L7   bytes [28-30]
  -- Character, not numeric: leading zeros must survive the load.
  card_cvv_cd          CHAR(3)     NOT NULL,
  -- CARD-EMBOSSED-NAME   PIC X(50)  app/cpy/CVACT02Y.cpy:L8   bytes [31-80]
  card_embossed_name   CHAR(50)    NOT NULL,
  -- CARD-EXPIRAION-DATE  PIC X(10)  app/cpy/CVACT02Y.cpy:L9   bytes [81-90]
  -- Source misspelling preserved verbatim - see the note above.
  card_expiraion_date  CHAR(10)    NOT NULL,
  -- CARD-ACTIVE-STATUS   PIC X(01)  app/cpy/CVACT02Y.cpy:L10  byte  [91]
  card_active_status   CHAR(1)     NOT NULL,
  -- Optimistic-locking counter for the card update conversation,
  -- app/cbl/COCRDUPC.cbl, which mirrors the account update pattern.
  version              BIGINT      NOT NULL,
  CONSTRAINT pk_card PRIMARY KEY (card_num),
  -- CHECK 2 of 5. Domain from app/cbl/COCRDUPC.cbl:91
  -- 88 FLG-YES-NO-VALID VALUES 'Y', 'N'. applied to this field at :861
  -- and tested at :863; the validated value reaches the record at
  -- :1475, and the paired error message condition is at :195.
  -- Corroborated by byte 91 of every row of
  -- app/data/ASCII/carddata.txt: fifty occurrences of 'Y'.
  CONSTRAINT ck_card_active_status CHECK (card_active_status IN ('Y', 'N')),
  -- FK 1 of 10. A card must belong to an existing account.
  -- Evidence: app/cpy/CVACT02Y.cpy:L6 carries the account key inside
  -- the card record, and app/cbl/COACTVWC.cbl:691
  -- MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID performs the keyed read
  -- of the card file by account identifier through the CARDAIX path.
  CONSTRAINT fk01_card_account FOREIGN KEY (card_acct_id)
    REFERENCES account (acct_id)
);


-- TABLE 6 of 11 - card_cross_reference
--
-- Source layout : app/cpy/CVACT03Y.cpy, 36 populated bytes in a
--                 50-byte record, 16-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L365 cluster entry header;
--                 :L403 DATA component attribute line, KEYLEN 16
--                 AVGLRECL 50
-- Provisioning  : app/jcl/XREFFILE.jcl:L39 DEFINE CLUSTER,
--                 :L43 KEYS(16 0), :L44 RECORDSIZE(50 50)
-- Online access : app/csd/CARDDEMO.CSD:L37 DEFINE FILE(CCXREF) and
--                 :L63 DEFINE FILE(CXACAIX), the alternate-index path
--
-- THE FILLER IS ABSENT FROM THE FIXTURE ENTIRELY, not merely
-- unmodelled: app/data/ASCII/cardxref.txt is 1,850 bytes over 50 lines
-- = 37 bytes per line = 36 data bytes plus a line terminator. The
-- FILLER X(14) at app/cpy/CVACT03Y.cpy:L8 exists only in the VSAM
-- record geometry, which is why the catalogued record length is 50 while
-- the layout populates 36.
--
-- ALTERNATE-INDEX ANCHOR. xref_acct_id begins at one-based byte 26, the
-- zero-based AXRKP 25 that app/catlg/LISTCAT.txt records for
-- CARDXREF.VSAM.AIX, and 25 is exactly 16 + 9. That alternate index
-- becomes a non-unique B-tree index in V2__create_indexes.sql.
--
-- NO VERSION COLUMN: this table is read to resolve a card to its
-- customer and account and is never edited online, so it is never the
-- subject of a read-for-update plus snapshot comparison conversation.
--
-- Geometry: 16 + 9 + 11 = 36 modelled bytes, plus the FILLER X(14) at
-- app/cpy/CVACT03Y.cpy:L8 = 50. Matches AVGLRECL 50.
CREATE TABLE card_cross_reference (
  -- XREF-CARD-NUM  PIC X(16)  app/cpy/CVACT03Y.cpy:L5  bytes [1-16]
  xref_card_num  CHAR(16)    NOT NULL,
  -- XREF-CUST-ID   PIC 9(09)  app/cpy/CVACT03Y.cpy:L6  bytes [17-25]
  -- Plain scalar, never an association.
  xref_cust_id   NUMERIC(9)  NOT NULL,
  -- XREF-ACCT-ID   PIC 9(11)  app/cpy/CVACT03Y.cpy:L7  bytes [26-36]
  -- One-based byte 26 = zero-based AXRKP 25. Plain scalar, never an
  -- association.
  xref_acct_id   NUMERIC(11) NOT NULL,
  CONSTRAINT pk_card_cross_reference PRIMARY KEY (xref_card_num),
  -- FK 2 of 10. The cross reference is the card-to-customer-to-account
  -- bridge of the account view lookup chain in app/cbl/COACTVWC.cbl,
  -- so its customer identifier must resolve.
  -- Evidence: app/cpy/CVACT03Y.cpy:L6.
  CONSTRAINT fk02_xref_customer FOREIGN KEY (xref_cust_id)
    REFERENCES customer (cust_id),
  -- FK 3 of 10. The account reached through the cross reference must
  -- exist. Evidence: app/cbl/CBTRN02C.cbl:394-395
  -- MOVE XREF-ACCT-ID TO FD-ACCT-ID followed by READ ACCOUNT-FILE,
  -- whose INVALID KEY path at :396-397 assigns reject code 101; and
  -- app/cbl/CBACT04C.cbl:204 and :394, which read the cross reference
  -- by account identifier.
  CONSTRAINT fk03_xref_account FOREIGN KEY (xref_acct_id)
    REFERENCES account (acct_id)
);


-- TABLE 7 of 11 - disclosure_group
--
-- Source layout : app/cpy/CVTRA02Y.cpy, 50-byte record, 16-byte
--                 composite key
-- Legacy cluster: AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L859 cluster entry header;
--                 :L896 DATA component attribute line, KEYLEN 16
--                 AVGLRECL 50. Cite L896 specifically: L202 and L403
--                 also report KEYLEN 16, for CARDDATA and CARDXREF.
-- Provisioning  : app/jcl/DISCGRP.jcl:L36 DEFINE CLUSTER,
--                 :L40 KEYS(16 0), :L41 RECORDSIZE(50 50)
-- Online access : none - batch-only, no DEFINE FILE in
--                 app/csd/CARDDEMO.CSD
--
-- acct_group_id IS CHAR(10) AND NEVER VARCHAR(10). PostgreSQL
-- comparison of fixed-width character values ignores trailing blanks,
-- and that is exactly what lets the interest job's fallback lookup for
-- 'DEFAULT' match the stored ten-byte value 'DEFAULT   '.
-- app/cbl/CBACT04C.cbl:436-438 detects a not-found status, moves the
-- literal 'DEFAULT' into the lookup key and retries; a variable-width
-- column would make that retry miss and the fallback would silently
-- stop resolving rates.
--
-- dis_int_rate IS SIX DIGITS WITH SCALE TWO, from PIC S9(04)V99 at
-- app/cpy/CVTRA02Y.cpy:L9 - the only column in the whole schema at
-- that precision. Widening it to the eleven- or twelve-digit tier
-- would misrepresent the field.
--
-- A RATE OF ZERO IS LEGITIMATE AND LOAD-BEARING. app/cbl/CBACT04C.cbl
-- :214 tests IF DIS-INT-RATE NOT = 0 and skips interest accrual
-- entirely when the rate is zero, so zero rows are a genuine control
-- path rather than bad data. No positivity constraint and no minimum
-- constraint is added. app/data/ASCII/discgrp.txt bears this out: its
-- 51 rows split evenly across three group identifiers, 17 rows each,
-- and include zero-rate combinations.
--
-- COMPOSITE KEY ORDER COMES FROM THE COPYBOOK, NOT FROM THE MOVE
-- STATEMENTS. app/cpy/CVTRA02Y.cpy:L6-L8 declares group identifier,
-- then type code, then category code. app/cbl/CBACT04C.cbl:210-212
-- populates the lookup fields in a DIFFERENT order - group identifier,
-- then CATEGORY code, then TYPE code - because the order of MOVE
-- statements into a group is irrelevant in COBOL. Inferring key order
-- from those statements would produce a wrong key. The copybook is
-- authoritative.
--
-- tran_cat_cd IS INTEGER, NOT NUMERIC(4). The embedded key class
-- declares a plain 32-bit integral component with no pinned type code,
-- so schema validation demands INTEGER. Its sibling components
-- acct_group_id and tran_type_cd DO declare their own fixed-width
-- character spellings, which is why those two are CHAR while the
-- equivalent component of table 8 is VARCHAR. The referencing foreign
-- key from INTEGER to the NUMERIC(4) parent was measured to be
-- accepted by PostgreSQL 16.
--
-- NO VERSION COLUMN and no index beyond the primary key: this is
-- reference data, read by the interest job and never updated online.
--
-- Geometry: 10 + 2 + 4 = 16 key bytes, matching KEYLEN 16 at
-- app/catlg/LISTCAT.txt:L896; + 6 = 22 modelled bytes, plus the
-- FILLER X(28) at app/cpy/CVTRA02Y.cpy:L10 = 50. Matches AVGLRECL 50.
CREATE TABLE disclosure_group (
  -- DIS-ACCT-GROUP-ID  PIC X(10)      app/cpy/CVTRA02Y.cpy:L6  bytes [1-10]
  -- Fixed-width so the 'DEFAULT' fallback matches trailing blanks.
  acct_group_id  CHAR(10)     NOT NULL,
  -- DIS-TRAN-TYPE-CD   PIC X(02)      app/cpy/CVTRA02Y.cpy:L7  bytes [11-12]
  tran_type_cd   CHAR(2)      NOT NULL,
  -- DIS-TRAN-CAT-CD    PIC 9(04)      app/cpy/CVTRA02Y.cpy:L8  bytes [13-16]
  tran_cat_cd    INTEGER      NOT NULL,
  -- DIS-INT-RATE       PIC S9(04)V99  app/cpy/CVTRA02Y.cpy:L9  bytes [17-22]
  -- The only six-digit, scale-two column in the schema.
  dis_int_rate   NUMERIC(6,2) NOT NULL,
  CONSTRAINT pk_disclosure_group
    PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd),
  -- FK 10 of 10. A rate row must describe an existing type and
  -- category pair. Evidence: app/cbl/CBACT04C.cbl:210-212 populates
  -- FD-DIS-ACCT-GROUP-ID, FD-DIS-TRAN-CAT-CD and FD-DIS-TRAN-TYPE-CD
  -- from the account group and the category-balance key, then :416
  -- READ DISCGRP-FILE INTO DIS-GROUP-RECORD performs the keyed lookup.
  CONSTRAINT fk10_discgrp_category FOREIGN KEY (tran_type_cd, tran_cat_cd)
    REFERENCES transaction_category (tran_type_cd, tran_cat_cd)
);


-- TABLE 8 of 11 - transaction_category_balance
--
-- Source layout : app/cpy/CVTRA01Y.cpy, 50-byte record, 17-byte
--                 composite key
-- Legacy cluster: AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L1334 cluster entry header;
--                 :L1371 DATA component attribute line, KEYLEN 17
--                 AVGLRECL 50; RKP 0 at :L1372; UNIQUE and INDEXED at
--                 :L1373
-- Provisioning  : app/jcl/TCATBALF.jcl:L36 DEFINE CLUSTER,
--                 :L40 KEYS(17 0), :L41 RECORDSIZE(50 50)
-- Online access : none - batch-only, no DEFINE FILE in
--                 app/csd/CARDDEMO.CSD
--
-- PRIMARY KEY COMPONENT ORDER IS LOAD-BEARING. The order is
-- account, then type code, then category code, exactly as
-- app/cpy/CVTRA01Y.cpy:L6-L8 declares it inside the 17-byte
-- TRAN-CAT-KEY group. app/cbl/CBACT04C.cbl:188-222 walks this dataset
-- in key order and performs an ACCOUNT-LEVEL control break at :194
-- IF TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM, which is correct ONLY
-- because the account identifier is the leading component of the key.
-- Any sequential read of this table must order by
-- (acct_id, tran_type_cd, tran_cat_cd). Reordering the key would
-- silently break the interest job's control break and its
-- end-of-data flush.
--
-- THE THIRD KEY FIELD IS NAMED TRANCAT-CD, not TRANCAT-CAT-CD, at
-- app/cpy/CVTRA01Y.cpy:L8; the column takes the schema-wide
-- tran_cat_cd spelling the mapped key component uses. The enclosing
-- group name TRAN-CAT-KEY also names table 2's entirely different
-- 6-byte two-field key - see the arity collision note there.
--
-- tran_cat_bal IS ELEVEN DIGITS WITH SCALE TWO, from
-- PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9, and NOT the twelve-digit
-- tier used for the account balance columns.
--
-- UPSERT SEMANTICS BELONG TO THE APPLICATION, NOT TO THIS SCHEMA.
-- app/cbl/CBTRN02C.cbl:467-501 treats a not-found status as an ACCEPTED
-- CREATE PATH: :481 tests IF TCATBALF-STATUS = '00' OR '23' and
-- dispatches to a create or a rewrite branch, both adding the
-- transaction amount to the balance. This table must therefore support
-- insert-or-update, but no conflict-tolerant clause and no procedural
-- hook expresses it here: whether a row is created or updated is
-- business logic, and it lives in the service layer where the
-- create-versus-rewrite distinction the source draws stays visible.
--
-- COLUMN TYPES ARE DRIVEN BY THE EMBEDDED KEY CLASS. All three key
-- components are plain Java types with no pinned type code and no
-- column spelling of their own, so schema validation demands BIGINT,
-- VARCHAR(2) and INTEGER rather than the NUMERIC and fixed-width
-- character types the PIC clauses alone would suggest. Both outbound
-- foreign keys from these types were measured to be accepted by
-- PostgreSQL 16: BIGINT to a NUMERIC(11) parent, and INTEGER to a
-- NUMERIC(4) parent.
--
-- NO VERSION COLUMN and no index beyond the primary key: the batch
-- upsert is the only writer, and it runs inside one transaction.
--
-- Geometry: 11 + 2 + 4 = 17 key bytes, matching KEYLEN 17 at
-- app/catlg/LISTCAT.txt:L1371; + 11 = 28 modelled bytes, plus the
-- FILLER X(22) at app/cpy/CVTRA01Y.cpy:L10 = 50. Matches AVGLRECL 50.
-- The 17-byte key is independently corroborated by
-- app/data/ASCII/tcatbal.txt, whose 50 rows are 50 bytes wide.
CREATE TABLE transaction_category_balance (
  -- TRANCAT-ACCT-ID  PIC 9(11)      app/cpy/CVTRA01Y.cpy:L6  bytes [1-11]
  acct_id       BIGINT        NOT NULL,
  -- TRANCAT-TYPE-CD  PIC X(02)      app/cpy/CVTRA01Y.cpy:L7  bytes [12-13]
  tran_type_cd  VARCHAR(2)    NOT NULL,
  -- TRANCAT-CD       PIC 9(04)      app/cpy/CVTRA01Y.cpy:L8  bytes [14-17]
  tran_cat_cd   INTEGER       NOT NULL,
  -- TRAN-CAT-BAL     PIC S9(09)V99  app/cpy/CVTRA01Y.cpy:L9  bytes [18-28]
  tran_cat_bal  NUMERIC(11,2) NOT NULL,
  -- Component order is account, type, category - see the note above.
  CONSTRAINT pk_transaction_category_balance
    PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd),
  -- FK 7 of 10. A category balance must belong to an existing account.
  -- Evidence: app/cbl/CBACT04C.cbl:202
  -- MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID followed by :373
  -- READ ACCOUNT-FILE INTO ACCOUNT-RECORD; and
  -- app/cbl/CBTRN02C.cbl:469, which keys this dataset from the account
  -- identifier resolved through the cross reference.
  CONSTRAINT fk07_tcatbal_account FOREIGN KEY (acct_id)
    REFERENCES account (acct_id),
  -- FK 8 of 10. A category balance must name an existing type and
  -- category pair. Evidence: app/cbl/CBTRN02C.cbl:470-471
  -- MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD and
  -- MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD, then :474
  -- READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD.
  CONSTRAINT fk08_tcatbal_category FOREIGN KEY (tran_type_cd, tran_cat_cd)
    REFERENCES transaction_category (tran_type_cd, tran_cat_cd)
);


-- TABLE 9 of 11 - "transaction"
--
-- Source layout : app/cpy/CVTRA05Y.cpy, 350-byte record, 16-byte key
-- Legacy cluster: AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
--                 app/catlg/LISTCAT.txt:L3555 cluster entry header;
--                 :L3593 DATA component attribute line, KEYLEN 16
--                 AVGLRECL 350
-- Provisioning  : app/jcl/TRANFILE.jcl:L49 DEFINE CLUSTER,
--                 :L53 KEYS(16 0), :L54 RECORDSIZE(350 350). The same
--                 cluster is DEFINEd a second time by
--                 app/jcl/TRANBKP.jcl; it is one cluster, which is why
--                 the cluster census is ten and not eleven.
-- Online access : app/csd/CARDDEMO.CSD:L76 DEFINE FILE(TRANSACT)
--
-- THE TABLE NAME IS QUOTED LOWERCASE. See TABLE NAME QUOTING in the
-- conventions above: PostgreSQL treats TRANSACTION as non-reserved so
-- a bare spelling would also work, but the quoted lowercase form is
-- held consistently here and in V2 to match the mapped name.
--
-- tran_amt IS ELEVEN DIGITS WITH SCALE TWO, from PIC S9(09)V99 at
-- app/cpy/CVTRA05Y.cpy:L10. It is NOT the twelve-digit tier used by
-- the account balance columns; confusing the two is the easiest
-- precision error to make in this schema.
--
-- tran_source IS FIXED-WIDTH TEXT AND NEVER AN ENUMERATED COLUMN. The
-- 300 rows of app/data/ASCII/dailytran.txt carry two distinct values
-- in bytes 23-32: 250 rows of 'POS TERM  ' and 50 rows of
-- 'OPERATOR  '. 'OPERATOR' appears nowhere in app/cbl as a literal
-- MOVE target, so it is pure data with no code-side membership, and an
-- enumerated column would fail to load 50 of the 300 records the
-- boundary-parity comparison depends on. The batch job that writes
-- synthetic interest rows uses its own literal source value, which
-- likewise arrives as data.
--
-- ALTERNATE-INDEX ANCHOR. tran_proc_ts begins at one-based byte 305,
-- the zero-based AXRKP 304 that app/catlg/LISTCAT.txt records for
-- TRANSACT.VSAM.AIX, and becomes a B-tree index in
-- V2__create_indexes.sql. Corroborated by the DFSORT symbol definitions
-- in app/proc/TRANREPT.prc, which declare TRAN-PROC-DT at offset 305 for
-- 10 characters and TRAN-CARD-NUM at offset 263 for 16. Those SYMNAMES
-- type the card number as zoned decimal, but that typing is a DFSORT
-- concern only: the copybook is authoritative and the column is
-- fixed-width character.
--
-- PROVEN 350-BYTE OFFSET MAP, relied on wherever this record is
-- emitted or sorted at fixed width: identifier 1-16, type 17-18,
-- category 19-22, source 23-32, description 33-132, amount 133-143,
-- merchant identifier 144-152, merchant name 153-202, merchant city
-- 203-252, merchant postal code 253-262, card number 263-278,
-- origination 279-304, processing 305-330, filler 331-350.
--
-- Geometry: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26
-- + 26 = 330 modelled bytes, plus the FILLER X(20) at
-- app/cpy/CVTRA05Y.cpy:L18 = 350. Matches AVGLRECL 350.
CREATE TABLE "transaction" (
  -- TRAN-ID             PIC X(16)      app/cpy/CVTRA05Y.cpy:L5   bytes [1-16]
  -- Natural key. No database-side allocation - see NO DATABASE-SIDE
  -- KEY ALLOCATION in the conventions above.
  tran_id             CHAR(16)      NOT NULL,
  -- TRAN-TYPE-CD        PIC X(02)      app/cpy/CVTRA05Y.cpy:L6   bytes [17-18]
  tran_type_cd        CHAR(2)       NOT NULL,
  -- TRAN-CAT-CD         PIC 9(04)      app/cpy/CVTRA05Y.cpy:L7   bytes [19-22]
  -- Pinned to the NUMERIC type code by the mapped attribute, which is
  -- what forces the parent column of table 2 to be NUMERIC as well.
  tran_cat_cd         NUMERIC(4)    NOT NULL,
  -- TRAN-SOURCE         PIC X(10)      app/cpy/CVTRA05Y.cpy:L8   bytes [23-32]
  -- Fixed-width text, never enumerated - see the note above.
  tran_source         CHAR(10)      NOT NULL,
  -- TRAN-DESC           PIC X(100)     app/cpy/CVTRA05Y.cpy:L9   bytes [33-132]
  tran_desc           CHAR(100)     NOT NULL,
  -- TRAN-AMT            PIC S9(09)V99  app/cpy/CVTRA05Y.cpy:L10  bytes [133-143]
  -- Eleven digits, scale two - NOT the twelve-digit account tier.
  tran_amt            NUMERIC(11,2) NOT NULL,
  -- TRAN-MERCHANT-ID    PIC 9(09)      app/cpy/CVTRA05Y.cpy:L11  bytes [144-152]
  tran_merchant_id    NUMERIC(9)    NOT NULL,
  -- TRAN-MERCHANT-NAME  PIC X(50)      app/cpy/CVTRA05Y.cpy:L12  bytes [153-202]
  tran_merchant_name  CHAR(50)      NOT NULL,
  -- TRAN-MERCHANT-CITY  PIC X(50)      app/cpy/CVTRA05Y.cpy:L13  bytes [203-252]
  tran_merchant_city  CHAR(50)      NOT NULL,
  -- TRAN-MERCHANT-ZIP   PIC X(10)      app/cpy/CVTRA05Y.cpy:L14  bytes [253-262]
  tran_merchant_zip   CHAR(10)      NOT NULL,
  -- TRAN-CARD-NUM       PIC X(16)      app/cpy/CVTRA05Y.cpy:L15  bytes [263-278]
  tran_card_num       CHAR(16)      NOT NULL,
  -- TRAN-ORIG-TS        PIC X(26)      app/cpy/CVTRA05Y.cpy:L16  bytes [279-304]
  -- Fixed-width character, never a temporal type - see DATE AND TIME
  -- COLUMNS ARE TEXT in the conventions above.
  tran_orig_ts        CHAR(26)      NOT NULL,
  -- TRAN-PROC-TS        PIC X(26)      app/cpy/CVTRA05Y.cpy:L17  bytes [305-330]
  -- One-based byte 305 = zero-based AXRKP 304. Fixed-width character,
  -- never a temporal type.
  tran_proc_ts        CHAR(26)      NOT NULL,
  -- Optimistic-locking counter.
  version             BIGINT        NOT NULL,
  CONSTRAINT pk_transaction PRIMARY KEY (tran_id),
  -- FK 4 of 10. A posted transaction must name an existing card.
  -- Evidence: app/cpy/CVTRA05Y.cpy:L15 carries the card key inside the
  -- transaction record, and app/cbl/COCRDSLC.cbl:740 performs the keyed
  -- read of the card file by card number.
  CONSTRAINT fk04_transaction_card FOREIGN KEY (tran_card_num)
    REFERENCES card (card_num),
  -- FK 5 of 10. The transaction type must exist. Evidence:
  -- app/cbl/CBTRN03C.cbl:189
  -- MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE followed by :495
  -- READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD.
  CONSTRAINT fk05_transaction_type FOREIGN KEY (tran_type_cd)
    REFERENCES transaction_type (tran_type),
  -- FK 6 of 10. The type and category pair must exist. Evidence:
  -- app/cbl/CBTRN03C.cbl:505 READ TRANCATG-FILE INTO TRAN-CAT-RECORD,
  -- keyed from the same transaction record as the type lookup above.
  CONSTRAINT fk06_transaction_category FOREIGN KEY (tran_type_cd, tran_cat_cd)
    REFERENCES transaction_category (tran_type_cd, tran_cat_cd)
);


-- TABLE 10 of 11 - daily_transaction
--
-- Source layout : app/cpy/CVTRA06Y.cpy, 350-byte record
-- Legacy dataset: AWS.M2.CARDDEMO.DALYTRAN.PS, a PHYSICAL SEQUENTIAL
--                 dataset, at app/jcl/POSTTRAN.jcl:L31, with the
--                 initial-load companion
--                 AWS.M2.CARDDEMO.DALYTRAN.PS.INIT at
--                 app/jcl/TRANFILE.jcl:L70
-- Cluster entry : none exists, and none is invented. There is NO
--                 cluster block for DALYTRAN in app/catlg/LISTCAT.txt
--                 and NO IDCAMS KEYS card for it anywhere in app/jcl -
--                 app/jcl/POSTTRAN.jcl carries no KEYS card at all. No
--                 key length or catalogued record length can be quoted
--                 for it, so the 350-byte record length below comes
--                 from the copybook alone.
-- Online access : none. DALYTRAN appears zero times in
--                 app/csd/CARDDEMO.CSD.
--
-- BLOCKER - dalytran_id IS NOT THE PRIMARY KEY AND MUST NEVER BE MADE
-- ONE. The dataset is unkeyed, so DALYTRAN-ID carries no uniqueness
-- guarantee of any kind, and nothing in the corpus supplies one.
-- Exactly two programs open this file - app/cbl/CBTRN01C.cbl and
-- app/cbl/CBTRN02C.cbl - and both declare it identically,
-- ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS SEQUENTIAL at
-- CBTRN02C:30-31 and CBTRN01C:30-31. Their entire verb inventory
-- against it is OPEN INPUT (CBTRN02C:238, CBTRN01C:254), a bare
-- READ ... INTO inside 1000-DALYTRAN-GET-NEXT (CBTRN02C:346,
-- CBTRN01C:203) whose only two accepted statuses are '00' continue
-- and '10' end of file, and CLOSE (CBTRN02C:584, CBTRN01C:363).
-- There is no STARTBR, no keyed READ, no READ ... KEY IS and no
-- INVALID KEY path against this file anywhere in app/cbl, because a
-- physical sequential dataset has no key to browse. A flat file may
-- therefore legitimately carry the same transaction identifier twice
-- and the source posts both records without complaint.
--
-- The failure mode of getting this wrong is the worst kind available.
-- app/data/ASCII/dailytran.txt is both unique on that field AND
-- already ascending by it, so a unique key over dalytran_id passes
-- every test built from the shipped fixture and only then rejects
-- real input. The fixture masks the defect completely, which is why
-- the correct treatment is stated here rather than left to inference.
--
-- THE IDENTITY IS AN INGESTION SEQUENCE, ingest_seq. For a sequential
-- dataset the record's position in the file IS its identity, and the
-- source already counts precisely that: WS-TRANSACTION-COUNT,
-- declared PIC 9(09) VALUE 0 at app/cbl/CBTRN02C.cbl:185,
-- incremented once per accepted READ by ADD 1 TO
-- WS-TRANSACTION-COUNT at :206, and reported at :227. ingest_seq is
-- that ordinal - one-based, dense, in read order. NUMERIC(9) is not a
-- guess: it is the declared precision of the counter being modelled,
-- so the domain is 1 through 999,999,999.
--
-- ingest_seq IS THE ONE COLUMN IN THIS TABLE WITH NO COPYBOOK LINE,
-- AND ITS NAME SAYS SO. Every column derived from
-- app/cpy/CVTRA06Y.cpy carries the dalytran_ prefix, per the mixed
-- naming rule below. This column deliberately does NOT, because it is
-- not a record field: it occupies none of the 350 bytes and appears in
-- no byte-offset map. The prefix is the marker that separates the
-- thirteen copybook columns from the one synthetic column, so a reader
-- can tell them apart by name alone without consulting this note.
--
-- THE ORDINAL IS ASSIGNED BY THE APPLICATION, NOT BY THE DATABASE.
-- This migration contains no GENERATED clause, no IDENTITY column and
-- no CREATE SEQUENCE, and this table introduces none. Three reasons,
-- in order of weight. First, determinism: re-staging the same input
-- file must yield the same ordinals, which a database allocator cannot
-- promise because it keeps counting across a truncate-and-reload.
-- Second, parity: the ordinal must equal the source's own read counter
-- for the two to be comparable at all, and only the loader knows the
-- read position. Third, load shape: staged rows arrive through
-- JdbcTemplate.batchUpdate, and a database-side identity forces a
-- per-row round trip to retrieve the generated value.
--
-- CONSEQUENCE FOR READERS. Ordering a staged read by ingest_seq
-- reproduces the flat-file read order exactly; ordering it by
-- dalytran_id does not, and would silently reorder real input.
-- dalytran_id stays an ordinary, non-unique CHAR(16) data column, and
-- NO UNIQUE CONSTRAINT AND NO UNIQUE INDEX MAY BE ADDED OVER IT -
-- neither here nor in V2__create_indexes.sql, whose entire index
-- budget is the three non-unique alternate-index B-trees catalogued
-- as "AIX -------------------3" at app/catlg/LISTCAT.txt:L3938, none
-- of which belongs to this table.
--
-- BLOCKER - THIS TABLE HAS NO FOREIGN KEY, AND ADDING ONE BREAKS THE
-- POSTING JOB. A staged row must be loadable while referencing a card
-- that does not exist and an account that does not exist, because
-- those are precisely the two reject outcomes the posting job is
-- built to produce: app/cbl/CBTRN02C.cbl:383-385 reads the cross
-- reference by card number and assigns reject code 100 on the INVALID
-- KEY path, and :394-397 reads the account and assigns reject code 101
-- on its INVALID KEY path. A referential constraint would make those
-- rows impossible to stage and would disable the reject engine
-- outright. The ten foreign keys of this migration therefore include
-- none from this table, and this is also the schema's clearest
-- expression of treating input as untrusted: validation happens in the
-- job, where a rejection can be recorded, not at the boundary, where
-- it can only be refused.
--
-- MIXED NAMING IS DELIBERATE. The TABLE name follows Java convention -
-- daily_transaction - while every COLUMN name follows the COBOL field
-- names verbatim, dalytran_*, including the contraction the copybook
-- uses. Renaming the columns to match the table would break schema
-- validation against the mapped attributes; renaming the table would
-- break the package's naming convention.
--
-- dalytran_proc_ts MUST ACCEPT 26 BLANKS. All 300 rows of
-- app/data/ASCII/dailytran.txt carry 26 spaces in bytes 305-330: the
-- processing value does not exist until the posting job assigns it.
-- That single fact makes any temporal column type impossible here.
--
-- IDENTICAL GEOMETRY TO TABLE 9, DELIBERATELY NOT SHARED. Field order,
-- widths and PIC clauses match app/cpy/CVTRA05Y.cpy exactly with the
-- DALYTRAN- prefix substituted, yet the two tables share no mapped
-- superclass and no inherited structure, because their lifecycles
-- differ: one is a keyed cluster of posted records with an
-- optimistic-locking counter and six relationships, the other an
-- unconstrained staging area with neither. Abstracting them together
-- would couple those lifecycles.
--
-- NO VERSION COLUMN: staged rows are written once by the loader and
-- read once by the job. They are never edited.
--
-- Geometry: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26
-- + 26 = 330 modelled bytes, plus the FILLER X(20) at
-- app/cpy/CVTRA06Y.cpy:L18 = 350. Corroborated by
-- app/data/ASCII/dailytran.txt: 105,300 bytes over 300 rows = 351
-- bytes per row = 350 data bytes plus a line terminator. ingest_seq
-- contributes ZERO bytes to that arithmetic and must never be added
-- to it: it is table metadata, not record content, so the fixed-width
-- reader and writer neither consume nor emit it.
-- ==================================================================
CREATE TABLE daily_transaction (
  -- ingest_seq - NO COPYBOOK LINE, NO RECORD BYTES. The one-based
  -- ordinal of this record within the staged file, modelling
  -- WS-TRANSACTION-COUNT PIC 9(09) at app/cbl/CBTRN02C.cbl:185 which
  -- :206 increments once per accepted READ. Assigned by the loader in
  -- read order; deliberately NOT a GENERATED or IDENTITY column. See
  -- the identity BLOCKER note above.
  ingest_seq              NUMERIC(9)    NOT NULL,
  -- DALYTRAN-ID             PIC X(16)      app/cpy/CVTRA06Y.cpy:L5   bytes [1-16]
  -- ORDINARY NON-UNIQUE DATA, not the key. The unkeyed PS input may
  -- repeat it - see the identity BLOCKER note above.
  dalytran_id             CHAR(16)      NOT NULL,
  -- DALYTRAN-TYPE-CD        PIC X(02)      app/cpy/CVTRA06Y.cpy:L6   bytes [17-18]
  dalytran_type_cd        CHAR(2)       NOT NULL,
  -- DALYTRAN-CAT-CD         PIC 9(04)      app/cpy/CVTRA06Y.cpy:L7   bytes [19-22]
  dalytran_cat_cd         NUMERIC(4)    NOT NULL,
  -- DALYTRAN-SOURCE         PIC X(10)      app/cpy/CVTRA06Y.cpy:L8   bytes [23-32]
  -- Fixed-width text, never enumerated: 250 rows of one value and 50
  -- of another in the fixture, and the second has no code-side
  -- membership at all.
  dalytran_source         CHAR(10)      NOT NULL,
  -- DALYTRAN-DESC           PIC X(100)     app/cpy/CVTRA06Y.cpy:L9   bytes [33-132]
  dalytran_desc           CHAR(100)     NOT NULL,
  -- DALYTRAN-AMT            PIC S9(09)V99  app/cpy/CVTRA06Y.cpy:L10  bytes [133-143]
  -- Eleven digits, scale two. Signed, and genuinely negative in the
  -- fixture - it carries both overpunch signs, which is what exercises
  -- the cycle-debit branch of the posting logic.
  dalytran_amt            NUMERIC(11,2) NOT NULL,
  -- DALYTRAN-MERCHANT-ID    PIC 9(09)      app/cpy/CVTRA06Y.cpy:L11  bytes [144-152]
  dalytran_merchant_id    NUMERIC(9)    NOT NULL,
  -- DALYTRAN-MERCHANT-NAME  PIC X(50)      app/cpy/CVTRA06Y.cpy:L12  bytes [153-202]
  dalytran_merchant_name  CHAR(50)      NOT NULL,
  -- DALYTRAN-MERCHANT-CITY  PIC X(50)      app/cpy/CVTRA06Y.cpy:L13  bytes [203-252]
  dalytran_merchant_city  CHAR(50)      NOT NULL,
  -- DALYTRAN-MERCHANT-ZIP   PIC X(10)      app/cpy/CVTRA06Y.cpy:L14  bytes [253-262]
  dalytran_merchant_zip   CHAR(10)      NOT NULL,
  -- DALYTRAN-CARD-NUM       PIC X(16)      app/cpy/CVTRA06Y.cpy:L15  bytes [263-278]
  -- Deliberately NOT a foreign key - see the note above.
  dalytran_card_num       CHAR(16)      NOT NULL,
  -- DALYTRAN-ORIG-TS        PIC X(26)      app/cpy/CVTRA06Y.cpy:L16  bytes [279-304]
  dalytran_orig_ts        CHAR(26)      NOT NULL,
  -- DALYTRAN-PROC-TS        PIC X(26)      app/cpy/CVTRA06Y.cpy:L17  bytes [305-330]
  -- 26 blanks on all 300 fixture rows - see the note above.
  dalytran_proc_ts        CHAR(26)      NOT NULL,
  CONSTRAINT pk_daily_transaction PRIMARY KEY (ingest_seq)
);


-- TABLE 11 of 11 - user_security
--
-- Source layout : app/cpy/CSUSR01Y.cpy, 80-byte record, 8-byte key.
--                 This copybook carries the Apache banner at :L1-L16,
--                 so its field definitions begin at :L17 with
--                 01 SEC-USER-DATA.
-- Legacy cluster: AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS. This cluster is
--                 both catalogued AND defined in JCL, so cite BOTH:
--                 app/catlg/LISTCAT.txt:L3846 cluster entry header,
--                 :L3881 the DATA component ASSOCIATIONS
--                 back-reference, and :L3883 the DATA component
--                 attribute line carrying KEYLEN 8 AVGLRECL 80;
--                 app/jcl/DUSRSECJ.jcl:L64 DEFINE CLUSTER
--                 (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS), :L65
--                 KEYS(8,0), :L66 RECORDSIZE(80,80), :L67 REUSE,
--                 :L68 INDEXED. Both sources report 8 and 80.
-- Online access : app/csd/CARDDEMO.CSD:L88 DEFINE FILE(USRSEC)
--
-- sec_usr_pwd HOLDS A BCRYPT HASH AND NOTHING ELSE. The source field
-- SEC-USR-PWD is PIC X(08) and the ten seeded users all carry the same
-- eight-character plaintext value inline in app/jcl/DUSRSECJ.jcl, fed
-- through IEBGENER. That value is NEVER stored. The column is
-- variable-width at 60 characters, the width of a BCrypt strength-10
-- digest, and the mapped attribute is named for a hash rather than for
-- a password. There is no plaintext credential column in this schema
-- and no credential literal anywhere in this file; the seed migration
-- hashes the ten values as it loads them. Never narrow this to the
-- source's eight characters and never make it fixed-width - a
-- fixed-width column would blank-pad the digest and change it.
--
-- WHERE THE DIGEST SHAPE IS ENFORCED, AND WHY NOT HERE. The column
-- declaration below constrains the width and the nullability and
-- NOTHING MORE - a bare VARCHAR(60) NOT NULL accepts 59 characters, a
-- run of 60 spaces, a plaintext value padded to 60, and a digest at
-- any cost factor. Every one of those is an unauthenticatable row that
-- looks like valid data to every reader of this table, so the shape is
-- enforced as an EQUIVALENT PERSISTENCE INVARIANT on the mapped entity
-- com.cardemo.model.entity.UserSecurity instead:
--   - a private static guard rejects anything that is not exactly 60
--     characters carrying a 2a, 2b or 2y version tag, a cost factor of
--     10 and a radix-64 salt and digest;
--   - the all-columns constructor and setPasswordHash both run it, so
--     no application path can assign a malformed value;
--   - a @PrePersist and @PreUpdate callback re-runs it, which closes
--     field access, reflection and a provider-materialised instance
--     that was never populated. That callback is the reason the
--     invariant holds AT THIS BOUNDARY and not merely upstream of it:
--     nothing can reach an INSERT or UPDATE on this table without it
--     having passed.
-- The accepted version-tag set is not a convention. It is exactly the
-- set the verifier accepts: BCryptPasswordEncoder.BCryptVersion of
-- spring-security-crypto 6.5.8 declares three constants, for 2a, 2y
-- and 2b, and BCrypt.gensalt(String, int, SecureRandom) rejects any
-- other third character with IllegalArgumentException("Invalid
-- prefix"). The historical 2x tag is therefore refused too.
--
-- A SIXTH CHECK CONSTRAINT IS NOT THE MECHANISM, DELIBERATELY. The
-- constraint budget for this migration is fixed at exactly five CHECK
-- constraints - see the OBJECT CENSUS and DELIBERATE CONSTRAINT
-- EXCLUSIONS sections below, which name all five and record every
-- candidate that was considered and excluded to hold that number.
-- Adding a shape CHECK here would make six and would contradict the
-- schema contract this file implements. The entity invariant is the
-- equivalent, and it is strictly better on two counts besides: it is
-- portable across dialects, and it is provable without a database,
-- which is what lets the unit tier assert it.
--
-- SEC-USR-FILLER IS A NAMED TRAILING FILLER AND IS STILL NOT MODELLED.
-- app/cpy/CSUSR01Y.cpy:L23 gives the padding a name, unlike every
-- other layout in the corpus. A name does not make padding data.
--
-- NO VERSION COLUMN and no index beyond the primary key: the user
-- administration conversations read, modify and write whole records
-- under the administrator role and hold no snapshot.
--
-- Geometry: 8 + 20 + 20 + 8 + 1 = 57 modelled source bytes, plus the
-- SEC-USR-FILLER X(23) at app/cpy/CSUSR01Y.cpy:L23 = 80. Matches
-- AVGLRECL 80 at app/catlg/LISTCAT.txt:L3883. The hash column is wider
-- than its source field by design, so this table is the one place where
-- the modelled width intentionally exceeds the record geometry.
CREATE TABLE user_security (
  -- SEC-USR-ID     PIC X(08)  app/cpy/CSUSR01Y.cpy:L18  bytes [1-8]
  sec_usr_id     CHAR(8)     NOT NULL,
  -- SEC-USR-FNAME  PIC X(20)  app/cpy/CSUSR01Y.cpy:L19  bytes [9-28]
  sec_usr_fname  CHAR(20)    NOT NULL,
  -- SEC-USR-LNAME  PIC X(20)  app/cpy/CSUSR01Y.cpy:L20  bytes [29-48]
  sec_usr_lname  CHAR(20)    NOT NULL,
  -- SEC-USR-PWD    PIC X(08)  app/cpy/CSUSR01Y.cpy:L21  bytes [49-56]
  -- BCrypt strength-10 digest only - see the BLOCKER note above. This
  -- declaration carries the width and the nullability; the digest
  -- SHAPE is enforced by the persistence invariant on
  -- com.cardemo.model.entity.UserSecurity, not by a sixth CHECK.
  sec_usr_pwd    VARCHAR(60) NOT NULL,
  -- SEC-USR-TYPE   PIC X(01)  app/cpy/CSUSR01Y.cpy:L22  byte  [57]
  sec_usr_type   CHAR(1)     NOT NULL,
  CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id),
  -- CHECK 4 of 5. Domain from app/cpy/COCOM01Y.cpy:26-28, which
  -- declares 10 CDEMO-USER-TYPE PIC X(01). with
  -- 88 CDEMO-USRTYP-ADMIN VALUE 'A'. and
  -- 88 CDEMO-USRTYP-USER VALUE 'U'. The chain to THIS column is made
  -- by app/cbl/COSGN00C.cbl:227 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE:
  -- the stored record field is moved directly into the field carrying
  -- those 88-levels, so the two share one domain. The ten seeded users
  -- of app/jcl/DUSRSECJ.jcl are five of type 'A' and five of type 'U'.
  CONSTRAINT ck_user_security_type CHECK (sec_usr_type IN ('A', 'U'))
);


-- OBJECT CENSUS
--
-- Exactly 11 tables, 11 primary keys of which 3 are composite, 4
-- optimistic-locking version columns, 5 CHECK constraints and 10
-- foreign keys - and nothing else. There are 0 foreign keys on
-- daily_transaction and 0 on user_security; 0 secondary indexes, which
-- belong to V2__create_indexes.sql; 0 rows inserted, which belongs to
-- V3__seed_data.sql; and 0 framework metadata tables, since the batch
-- schema is created by the framework's own script through
-- spring.batch.jdbc.initialize-schema, never here and never as a fourth
-- migration. The creation order above is also the order seeding must
-- follow, because it is the order the ten edges force.
--
-- 11 primary keys, one per table. Three are composite:
--   transaction_category           (tran_type_cd, tran_cat_cd)
--   disclosure_group               (acct_group_id, tran_type_cd, tran_cat_cd)
--   transaction_category_balance   (acct_id, tran_type_cd, tran_cat_cd)
-- Each composite key is in COBOL declaration order, which is
-- load-bearing for the sequential control-break reads.
--
-- Ten of the eleven are NATURAL keys over the catalogued VSAM cluster
-- key fields, for the reason given under "NO DATABASE-SIDE KEY
-- ALLOCATION" above - the descending-browse allocator must collide
-- against a declared key. Exactly ONE is synthetic:
-- daily_transaction (ingest_seq), the ingestion ordinal, because its
-- dataset is unkeyed physical sequential and has no key field to
-- promote. That is the only synthetic key in the schema and the only
-- column in the schema with no copybook line; see the identity
-- BLOCKER note on TABLE 10. Still zero GENERATED clauses, zero
-- IDENTITY columns and zero sequences: the ordinal is
-- application-assigned in read order.
--
-- 4 optimistic-locking version columns, on account, card, customer and
-- "transaction" - the four tables an online conversation reads for
-- update. The other seven have none.
--
-- 5 CHECK constraints, and no sixth:
--   ck_account_active_status           <- COACTUPC.cbl:193
--   ck_card_active_status              <- COCRDUPC.cbl:91
--   ck_customer_pri_card_holder_ind    <- COACTUPC.cbl:350
--   ck_user_security_type              <- COCOM01Y.cpy:26-28 via
--                                         COSGN00C.cbl:227
--   ck_customer_ssn_numeric            <- CVCUS01Y.cpy:L17 PIC 9(09)
--
-- 10 FOREIGN KEY constraints, and no eleventh:
--   fk01_card_account            card -> account
--   fk02_xref_customer           card_cross_reference -> customer
--   fk03_xref_account            card_cross_reference -> account
--   fk04_transaction_card        "transaction" -> card
--   fk05_transaction_type        "transaction" -> transaction_type
--   fk06_transaction_category    "transaction" -> transaction_category
--   fk07_tcatbal_account         transaction_category_balance -> account
--   fk08_tcatbal_category        transaction_category_balance ->
--                                transaction_category
--   fk09_category_type           transaction_category -> transaction_type
--   fk10_discgrp_category        disclosure_group -> transaction_category
--
-- 0 foreign keys on daily_transaction and 0 on user_security.
-- 0 secondary indexes - those belong to V2__create_indexes.sql.
-- 0 rows inserted - seeding belongs to V3__seed_data.sql.
-- 0 framework metadata tables - the batch schema is created by the
--   framework's own script through spring.batch.jdbc.initialize-schema,
--   never here and never as a fourth migration.
--
-- DECISIVE NEGATIVE EVIDENCE FOR THE FOREIGN KEY SET. The derivation
-- rule is that an edge exists where the COBOL performs a keyed lookup
-- into another dataset on that field, and the absence of such a lookup
-- is evidence too. The posting job app/cbl/CBTRN02C.cbl performs
-- EXACTLY THREE keyed reads: :383 the cross reference by card number,
-- whose failure is reject 100; :395 the account by account identifier,
-- whose failure is reject 101; and :474 the category balance by its
-- composite key, whose not-found status is an accepted create path. It
-- NEVER reads the transaction-type or transaction-category datasets.
-- app/cbl/CBTRN03C.cbl is the ONLY program in app/cbl that opens
-- either of them, which is why edges 5, 6 and 9 cite the report job
-- rather than the posting job. Inventing an edge from the posting job
-- to those two datasets would assert a lookup the source does not
-- perform.
-- ==================================================================


-- DELIBERATE CONSTRAINT EXCLUSIONS
--
-- The five CHECK constraints above are the complete set. Each exclusion
-- below was considered and rejected with its reason, so that no
-- omission reads as an oversight.
--
-- NO CREDIT-SCORE RANGE CONSTRAINT, although app/cbl/COACTUPC.cbl:848-849
-- declares 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850. and the
-- validator at :2514-2531 emits a message naming that range. It is not a
-- stored-data invariant for two independent reasons. The field it
-- validates is declared at :845-847 as a SCREEN SNAPSHOT field over a
-- redefinition pair rather than a record-layout field, so it is an
-- online input rule; and, decisively, 21 of the 50 fixture customer
-- rows carry a score below 300, so a range constraint would make
-- seeding fail on 42 percent of the customer table. Seeding must
-- likewise not filter, clamp or correct those rows: they are the data
-- of record.
--
-- NO SECOND NATIONAL-IDENTIFIER CONSTRAINT. app/cbl/COACTUPC.cbl:121-123
-- declares the administration-authority exclusions for the first
-- component of the number, which is an online input rule on a screen
-- field; encoding it here would duplicate service-layer validation.
--
-- NO BCRYPT-SHAPE CONSTRAINT ON user_security.sec_usr_pwd. This is the
-- one exclusion whose rule IS a stored-data invariant rather than an
-- online input rule, so it is recorded with its enforcement point
-- rather than merely refused. The shape - exactly 60 characters, a 2a,
-- 2b or 2y version tag, a cost factor of 10, and a radix-64 salt and
-- digest - is enforced as an equivalent persistence invariant on
-- com.cardemo.model.entity.UserSecurity: a private static guard run by
-- the all-columns constructor and by setPasswordHash, plus a
-- @PrePersist and @PreUpdate callback that also covers field access,
-- reflection and an unpopulated provider-materialised instance. Three
-- reasons the schema is not the enforcement point. First, the budget:
-- exactly five CHECK constraints exist and a sixth would contradict
-- the schema contract this file implements. Second, portability: a
-- POSIX regular-expression CHECK is dialect-specific, whereas the
-- entity invariant holds against any dialect and against no database
-- at all. Third, provability: the invariant is asserted by the unit
-- tier, which needs no container - the tier that can actually run in
-- this environment. Excluded HERE, enforced THERE, and never merely
-- dropped.
--
-- NO LOOKUP-TABLE DOMAIN CONSTRAINTS on cust_addr_state_cd,
-- cust_addr_zip, cust_phone_num_1, cust_phone_num_2 or
-- cust_addr_country_cd. Those domains come from app/cpy/CSLKPCDY.cpy and
-- their membership validation is a service-layer concern over
-- src/main/resources/validation/*.json. A single-column constraint
-- provably CANNOT express the semantics either: app/cpy/CSLKPCDY.cpy
-- declares 56 valid state codes at :1013-1069 but 62 distinct
-- postal-code prefixes at :1073-1313, the six extras being the military
-- and diplomatic codes and the Freely Associated State codes, which are
-- prefix-valid but not state-code-valid.
--
-- NO DIGITS-ONLY CONSTRAINT ON card.card_cvv_cd or on
-- customer.cust_fico_credit_score. For the first, exhaustive search of
-- app/cbl finds ZERO validation of the field, so a constraint would be
-- an invention. The second is left unconstrained while the
-- national-identifier column gets CHECK 5, and the asymmetry is
-- deliberate: that column is the one whose numeric domain the source
-- both declares AND actively validates, at
-- app/cbl/COACTUPC.cbl:1530-1531.
--
-- THE Y/N WORK-FIELD TRAP - NOT A FOURTH Y/N CONSTRAINT.
-- app/cbl/COACTUPC.cbl:74-80 declares WS-EDIT-YES-NO PIC X(1) VALUE 'N'
-- with 88 FLG-YES-NO-ISVALID VALUES 'Y','N'. That is a GENERIC REUSABLE
-- VALIDATOR WORK FIELD, not a column of any record layout. Exactly
-- three stored columns carry a Y/N domain - account.acct_active_status,
-- card.card_active_status and customer.cust_pri_card_holder_ind - and
-- each has its own constraint above. Mistaking the work field for a
-- fourth would add a constraint on nothing.
--
-- NO BLANK-REJECTING CONSTRAINT ANYWHERE. Two columns are legitimately
-- all-blank in the data of record and both are still NOT NULL:
-- account.acct_group_id is ten spaces on all 50 fixture rows and
-- daily_transaction.dalytran_proc_ts is 26 spaces on all 300.
--
-- NO CONSTRAINT THE COBOL DOES NOT IMPOSE. No range test, no sign
-- test, no length test beyond the declared column widths, and no
-- referential edge without a named keyed read behind it.
-- ==================================================================


-- ==================================================================
-- DISCREPANCY REGISTER
--
-- Findings carried forward from authoring this migration, classified
-- Blocker, High, Medium and Low, each with its remediation. Recorded
-- here because a schema is read where it is applied; the project-level
-- decision log and validation-gate document carry the same entries.
--
-- BLOCKER - A NUMERIC CHILD COLUMN CANNOT REFERENCE AN INTEGER PARENT
-- IN POSTGRESQL. Measured directly against PostgreSQL 16.10 before
-- this file was written: declaring a foreign key from a NUMERIC(4)
-- column to an INTEGER column is refused with "foreign key constraint
-- cannot be implemented ... Key columns are of incompatible types:
-- numeric and integer". The same refusal applies to NUMERIC(4)
-- referencing BIGINT. This matters because "transaction".tran_cat_cd
-- is pinned to the NUMERIC type code by its mapped attribute, so
-- edge 6 forces transaction_category.tran_cat_cd to be NUMERIC(4) -
-- whereas the embedded key class TransactionCategoryId declared a
-- plain 32-bit integral component, which schema validation would have
-- required to be INTEGER. The two requirements were mutually
-- impossible as authored.
-- Remediation applied, minimal and in scope: the key class component
-- now declares its own numeric column spelling, exactly as its sibling
-- DisclosureGroupId already declares fixed-width character spellings
-- for two of its components. That makes schema validation accept
-- NUMERIC for the component and lets edge 6 be created. No column was
-- widened arbitrarily and no edge was dropped. The alternative -
-- demoting "transaction".tran_cat_cd to INTEGER - was rejected because
-- it would have contradicted an explicit type-code pin on a mapped
-- entity attribute and changed the reported type of a posted-record
-- column.
--
-- BLOCKER - ANY FOREIGN KEY ON daily_transaction. Remediation: omit,
-- permanently. Staged rows must load while referencing a non-existent
-- card, which is reject 100 at app/cbl/CBTRN02C.cbl:383-385, and a
-- non-existent account, which is reject 101 at :394-397. A constraint
-- would make both rejects unstageable.
--
-- BLOCKER - A CREDIT-SCORE RANGE CONSTRAINT. Remediation: omit.
-- app/cbl/COACTUPC.cbl:848-849 declares 300 through 850, but 21 of the
-- 50 fixture rows fall below 300 and the rule governs a screen
-- snapshot field, not a stored one.
--
-- HIGH - transaction_type's key column is tran_type, not
-- tran_type_cd. Remediation: use tran_type. Evidence
-- app/cpy/CVTRA03Y.cpy:L5, corroborated by the FD-TRAN-TYPE target
-- field at app/cbl/CBTRN03C.cbl:189. Guessing the suffixed spelling
-- stops application start-up under schema validation.
--
-- HIGH - fabricating a cluster definition for DALYTRAN. Remediation:
-- none exists; cite the physical sequential dataset at
-- app/jcl/POSTTRAN.jcl:L31 and state Not available for a cluster
-- definition, as table 10 does.
--
-- HIGH - TRAN-CAT-KEY is declared twice with different arity.
-- Remediation: document both and merge neither.
-- app/cpy/CVTRA01Y.cpy:L5 is 17 bytes over three fields;
-- app/cpy/CVTRA04Y.cpy:L5 is 6 bytes over two.
--
-- HIGH - both occurrences of the EXPIRAION misspelling must survive.
-- Remediation: preserve. app/cpy/CVACT01Y.cpy:L11 and
-- app/cpy/CVACT02Y.cpy:L9. The account field is in active use at
-- app/cbl/COACTUPC.cbl:3836-3839 and :4131-4133 and at
-- app/cbl/CBTRN02C.cbl:414.
--
-- MEDIUM - zero-based AXRKP and KEYS offsets mixed with one-based
-- record-byte prose. Remediation: always state the base. AXRKP 16 is
-- byte 17, AXRKP 25 is byte 26 and AXRKP 304 is byte 305. Applied
-- throughout this file.
--
-- MEDIUM - the specification claims USRSEC is not catalogued.
-- Remediation: it is. Cite app/catlg/LISTCAT.txt:L3881 and :L3883
-- alongside app/jcl/DUSRSECJ.jcl:L65-L66; both report key 8 and record
-- length 80.
--
-- MEDIUM - Hibernate compares JDBC type codes, so a plausible-looking
-- widening can fail schema validation. Remediation: pair the Java and
-- SQL types consistently and reconcile with the mapped attribute,
-- never widen a column to silence an error. The measured rules are in
-- the TYPE-CODE CONTRACT above.
--
-- MEDIUM - an unqualified "KEYLEN 16" citation is ambiguous.
-- Remediation: cite the line. app/catlg/LISTCAT.txt:L896 is DISCGRP;
-- :L202 is CARDDATA and :L403 is CARDXREF, and all three report 16.
--
-- MEDIUM - inferring composite key order from MOVE statements.
-- Remediation: read the copybook. app/cbl/CBACT04C.cbl:210-212
-- populates the disclosure-group lookup key in group, category, type
-- order while app/cpy/CVTRA02Y.cpy:L6-L8 declares group, type,
-- category. The copybook governs.
--
-- LOW - the record-layout copybooks lack the Apache banner, so the
-- full-form exemplar for this file's header was taken from
-- app/cbl/CBACT04C.cbl:L1-L21 rather than from a copybook. Only 12 of
-- the 28 members of app/cpy carry a banner at all;
-- app/cpy/CSUSR01Y.cpy is one of them, at :L1-L16, and its :L15 is the
-- proof that the closing Apache line carries no trailing period.
--
-- LOW - a file's total line count is a fragile citation.
-- Remediation: cite fields by line number, which is exact and stable,
-- and never by the enclosing file's length.
-- ==================================================================
