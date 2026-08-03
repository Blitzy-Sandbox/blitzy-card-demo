-- ******************************************************************
-- * Program     : V3__seed_data.sql
-- * Application : CardDemo
-- * Type        : Flyway migration - reference and fixture seed data
-- * Function    : Seeds the eleven-table schema created by V1 with
-- *               the 636 rows the legacy corpus actually contains:
-- *               50 accounts, 50 customers, 50 cards, 50 cross
-- *               references, 7 transaction types, 18 transaction
-- *               categories, 51 disclosure-group rates, 50 category
-- *               balances, 300 staged daily transactions and 10
-- *               users. Signed numerics are decoded from zoned
-- *               decimal position by position, driven by the PIC
-- *               clauses; the ten credentials are stored only as
-- *               BCrypt strength-10 digests. The posted-transaction
-- *               table is seeded EMPTY - no fixture exists for it.
-- * Source      : the nine ASCII fixtures of app/data/ASCII -
-- *               acctdata.txt, carddata.txt, cardxref.txt,
-- *               custdata.txt, dailytran.txt, discgrp.txt,
-- *               tcatbal.txt, trancatg.txt and trantype.txt; the
-- *               ten in-stream IEBGENER records of
-- *               app/jcl/DUSRSECJ.jcl:L35-L44; and the eleven
-- *               record-layout copybooks of app/cpy that fix every
-- *               field offset and PIC clause (CVACT01Y, CVACT02Y,
-- *               CVACT03Y, CVCUS01Y, CVTRA01Y, CVTRA02Y, CVTRA03Y,
-- *               CVTRA04Y, CVTRA05Y, CVTRA06Y, CSUSR01Y)
-- *               - all @ 7756d89
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
-- Inserts seed rows into ten of the eleven tables V1 created, and
-- inserts nothing into the eleventh. It creates no object, alters no
-- object and drops no object: every statement below is an INSERT.
--
-- The row budget is fixed by the sources and is not a target that may
-- drift. Nine fixtures live in app/data/ASCII, each one a fixed-width
-- LF-terminated text file whose byte count reconciles exactly as
-- rows x (width + 1):
--
--   acctdata.txt    50 x 300 =  15,050 B  -> account
--   carddata.txt    50 x 150 =   7,550 B  -> card
--   cardxref.txt    50 x  36 =   1,850 B  -> card_cross_reference
--   custdata.txt    50 x 500 =  25,050 B  -> customer
--   dailytran.txt  300 x 350 = 105,300 B  -> daily_transaction
--   discgrp.txt     51 x  50 =   2,601 B  -> disclosure_group
--   tcatbal.txt     50 x  50 =   2,550 B  -> transaction_category_balance
--   trancatg.txt    18 x  60 =   1,098 B  -> transaction_category
--   trantype.txt     7 x  60 =     427 B  -> transaction_type
--
-- The tenth source is not a fixture at all: the ten user records exist
-- only as in-stream IEBGENER data inside app/jcl/DUSRSECJ.jcl, between
-- the //SYSUT1 DD * card at :L34 and the /* terminator at :L45.
--
-- 50 + 50 + 50 + 50 + 300 + 51 + 50 + 18 + 7 + 10 = 636 rows, and the
-- OBJECT CENSUS at the foot of this file states the same arithmetic
-- per table so the two can be reconciled by inspection.
--
-- Two transformations are applied to the bytes on the way in, and no
-- others. Signed numerics are decoded from zoned-decimal trailing
-- overpunch, position by position, from the PIC clauses - see
-- ZONED-DECIMAL OVERPUNCH DECODING below. Credentials are replaced by
-- BCrypt strength-10 digests - see BLOCK 11. Everything else is
-- transcribed byte for byte, including trailing blanks, leading zeros
-- and negative signs.
--
-- HOW TO RUN, BUILD AND TEST
--
--   ./mvnw clean verify                  from the repository root
--
-- Flyway applies V1, then V2, then this file during application
-- start-up, before Hibernate validates the mapped entities against the
-- resulting schema. There is no separate seeding step, no loader
-- script and no second initialisation path: this file is the only
-- place seed rows enter the database.
--
-- The regression net is the test tree, not assertions written beside
-- this file. The repository tier exercises the seeded rows against a
-- Testcontainers PostgreSQL 16 instance under
-- src/test/java/com/cardemo/integration/repository, and all 300 rows
-- of app/data/ASCII/dailytran.txt are driven through the posting
-- pipeline by src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java.
-- Those are the tests to run and extend; none is created here.
--
-- To reconcile an applied seed by hand:
--   SELECT 'account' t, count(*) FROM account
--   UNION ALL SELECT 'daily_transaction', count(*) FROM daily_transaction
--   UNION ALL SELECT 'user_security', count(*) FROM user_security;
--   SELECT dalytran_type_cd, sign(dalytran_amt), count(*)
--     FROM daily_transaction GROUP BY 1, 2 ORDER BY 1, 2;
--
-- KEY CONFIGURATION AND DEFAULTS
--
-- Flyway, from the base Spring profile: locations
-- classpath:db/migration, enabled true, validate-on-migrate true,
-- clean-disabled true, out-of-order false, baseline-on-migrate false,
-- history table flyway_schema_history, encoding UTF-8. No migration
-- pattern is ignored and no error is tolerated and continued past.
-- Exactly three migrations exist and ordering keys on the V1__, V2__
-- and V3__ prefixes, so this file always applies third.
--
-- Jakarta Persistence and Hibernate, in EVERY profile: ddl-auto
-- validate - never create, create-drop or update; open-in-view false;
-- show-sql false. No SQL statement logging and no bind-parameter
-- logging is enabled in any profile, which matters more for this file
-- than for any other in the tree: the rows below carry customer names,
-- postal addresses, telephone numbers, government identifiers, dates
-- of birth and social security numbers. Statement logging would copy
-- all of it into the application log.
--
-- This file needs no secret and holds none. It contains no credential
-- in recoverable form, no connection string, no host name, no database
-- name, no key and no token. The only credential-derived values are
-- one-way salted digests.
--
-- Nothing here is configurable. There is no placeholder, no profile
-- switch and no conditional: the seed is the same 636 rows in every
-- environment, which is what makes the parity comparison meaningful.
--
-- COMMON FAILURE MODES AND TROUBLESHOOTING
--
-- "Migration checksum mismatch for migration version 3" - this file
-- was edited after being applied, and validate-on-migrate is true.
-- Revert the edit or recreate the target database from empty. Never
-- relax validate-on-migrate and never clean. Note that the digests in
-- BLOCK 11 are literals precisely so that the checksum is stable;
-- computing them at apply time would change it on every run.
--
-- "Detected resolved migration not applied to database" - out-of-order
-- and baseline-on-migrate are both false, so a partially migrated
-- database is a hard failure. Migrate from an empty schema.
--
-- A foreign key rejects a row - the eleven blocks below are ordered so
-- that every parent row exists before any child references it, and
-- reordering them is the only way to provoke this. See LOAD ORDER.
--
-- A CHECK constraint rejects a row - V1 declares exactly five, and
-- each encodes a domain the COBOL itself declares, so a rejection
-- means the value is wrong rather than the constraint. The usual cause
-- is overpunch decoding applied as a global text substitution instead
-- of position by position: a blanket replacement rewrites the letters
-- inside merchant names, customer names and city names as digits, and
-- the resulting row fails ck_customer_ssn_numeric or arrives with
-- silently corrupted text. See ZONED-DECIMAL OVERPUNCH DECODING.
-- There is no credit-score range constraint, so a low score is never
-- the cause: 21 of the 50 seeded customers legitimately score below
-- 300, and V1 records why from the schema side.
--
-- "invalid input syntax for type timestamp" - something has been
-- retyped. The four timestamp-shaped columns are CHAR(26) by design
-- and daily_transaction.dalytran_proc_ts is 26 blanks on all 300 rows,
-- which no temporal type can hold. See BLOCK 10.
--
-- A numeric value arrives unsigned or with the wrong magnitude - the
-- trailing overpunch byte was treated as a digit or dropped. 50 of the
-- 300 staged transactions are genuinely negative and must stay so; the
-- census in BLOCK 10 is the check.
--
-- Interest calculation abends with abend code 999 - a disclosure-group
-- row is missing. All 51 rows of discgrp.txt, and in particular all 17
-- of the DEFAULT group, are required. BLOCK 7 explains why.
--
-- NOT AVAILABLE
--
-- Two facts a reader may look for do not exist, and are stated rather
-- than invented, per Rule 1 Clause F.
--
-- There is no app/data/ASCII/usrsec.txt. The user-security fixture is
-- Not available: the ten records exist only as in-stream data inside
-- app/jcl/DUSRSECJ.jcl:L35-L44, fed to IEBGENER at :L32 and written
-- with DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0) at :L48. What would
-- be needed to replace this reading is an ASCII fixture under
-- app/data/ASCII carrying the CSUSR01Y layout; none exists at 7756d89,
-- so the JCL member is the authority and is cited as such in BLOCK 11.
--
-- There is no LISTCAT cluster entry for DALYTRAN. Its physical
-- specification is Not available from app/catlg/LISTCAT.txt, which
-- catalogues ten clusters and none of them is this dataset. What would
-- be needed is a catalogue entry reporting a key length and an average
-- record length; instead the geometry comes from the fixture itself
-- (300 x 350 bytes) corroborated by app/cpy/CVTRA06Y.cpy, whose
-- RECLN = 350 header and field list agree with it byte for byte. This
-- is the same absence V1 records for table 10, and it is why
-- daily_transaction is keyed on an ingest ordinal rather than on a
-- catalogued key.


-- SEED-WIDE CONVENTIONS
--
-- ZONED-DECIMAL OVERPUNCH DECODING. Signed numerics in the fixtures
-- carry their sign in the LAST byte of the field as a zoned-decimal
-- overpunch, so the byte is simultaneously the low-order digit and the
-- sign. The table, verified against every signed field of all nine
-- fixtures:
--
--   '{'         digit 0, positive        '}'         digit 0, negative
--   'A' .. 'I'  digits 1 .. 9, positive  'J' .. 'R'  digits 1 .. 9,
--                                                    negative
--
-- Worked examples, each independently checked byte by byte:
--
--   app/data/ASCII/acctdata.txt:L1 bytes [13-24]  00000001940{  ->
--     +194.00 ; bytes [25-36] 00000020200{ -> +2020.00 ; bytes
--     [37-48] 00000010200{ -> +1020.00 ; bytes [79-90] and [91-102]
--     00000000000{ -> 0.00 twice
--   app/data/ASCII/tcatbal.txt:L1 bytes [18-28]   0000000000{   ->
--     +0.00
--   app/data/ASCII/discgrp.txt:L18 bytes [17-22]  00150{        ->
--     +15.00
--   app/data/ASCII/dailytran.txt:L1 bytes [133-143] 0000005047G ->
--     +504.77
--
-- DECODING IS POSITION-AWARE AND MUST STAY SO. Each field is sliced at
-- the offset its PIC clause dictates and only that slice's final byte
-- is interpreted as an overpunch. A global search and replace over the
-- record would be shorter and is WRONG: the same letters occur
-- legitimately inside app/cpy/CVTRA06Y.cpy's DALYTRAN-DESC X(100),
-- DALYTRAN-MERCHANT-NAME X(50) and DALYTRAN-MERCHANT-CITY X(50), and
-- inside app/cpy/CVCUS01Y.cpy's three name fields and three address
-- lines. Rewriting those letters as digits corrupts the data with no
-- error anywhere. This is Rule 1 Clause A in its plainest form:
-- correctness ahead of cleverness.
--
-- SIGNS ARE PRESERVED EXACTLY. No absolute value is taken anywhere, on
-- any field, for any reason. 50 of the 300 rows of dailytran.txt carry
-- a negative overpunch, and they are load bearing:
-- app/cbl/CBTRN02C.cbl:547-552 adds a non-negative amount to
-- ACCT-CURR-CYC-CREDIT and a negative one to ACCT-CURR-CYC-DEBIT, so
-- the debit accumulator legitimately holds negative values, which is
-- exactly why the over-limit test at :403-405 computes
-- ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. Normalise
-- those 50 amounts and the cycle-debit branch never executes, the
-- over-limit arithmetic silently changes, and no compiler, constraint
-- or type check reports anything.
--
-- FIXED-WIDTH LITERALS AND TRAILING BLANKS. Every character column is
-- seeded at its full declared width, trailing blanks included, exactly
-- as the fixture holds it. Those blanks are DATA, not formatting, and
-- three cases would look like accidents to a later cleanup pass:
--
--   account.acct_group_id       ten blanks on all 50 rows
--   daily_transaction.dalytran_proc_ts
--                               twenty-six blanks on all 300 rows
--   disclosure_group.acct_group_id
--                               'DEFAULT' followed by three blanks
--
-- Trimming any of them changes behaviour: the first is what makes the
-- interest job's DEFAULT fallback fire, the second is what proves the
-- column cannot be temporal, and the third is the fallback's target
-- key. Blanks INSIDE a quoted literal are unrelated to the
-- trailing-whitespace ban in .editorconfig, which governs the ends of
-- LINES; no line in this file ends in whitespace, because every value
-- is followed by a comma or a closing parenthesis.
--
-- NO DATABASE-SIDE KEY ALLOCATION. Every key value below is a literal.
-- This file installs no key allocator of any kind: no sequence
-- object, no auto-incrementing column type, no auto-populated column,
-- no default expression, no row-level rule and no counter-advancing
-- function call, and none may be added. The legacy identifier
-- generator is a descending-key browse: MOVE HIGH-VALUES TO TRAN-ID,
-- STARTBR, READPREV, ENDBR, ADD 1, at app/cbl/COTRN02C.cbl:444-451 and
-- app/cbl/COBIL00C.cbl:212-219, with the empty-file case at
-- app/cbl/COBIL00C.cbl:487-488 moving zeros into the identifier so
-- that the first value allocated is 1. That algorithm races under
-- concurrency, and the race is part of the behaviour being reproduced.
-- A collision must therefore surface as a primary-key violation, which
-- it can only do while no database-side allocator is masking it.
--
-- OPTIMISTIC-LOCKING COUNTERS. V1 declares version BIGINT NOT NULL on
-- four tables - account, customer, card and "transaction" - and
-- declares no DEFAULT for it, so every seeded row supplies it
-- explicitly. The value is 0: these rows are the initial state, and
-- the counter is the store-level half of the two-layer concurrency
-- control that replaces app/cbl/COACTUPC.cbl:669-756. The other seven
-- tables have no such column and none is invented for them.
--
-- DETERMINISM AND ENVIRONMENT NEUTRALITY. Every value below is a
-- literal. No clock function, no session-time keyword, no
-- pseudo-random function, no unique-identifier generator and no other
-- non-deterministic expression appears anywhere, so two
-- applications of this file produce identical rows. Nothing is read
-- from the filesystem at apply time either: there is no bulk-load
-- statement, no program-piped variant of one and no server-side
-- file-reading function, which keeps the migration free of
-- environment-specific assumptions under Rule 1 Clause C and free of
-- the shell-injection surface Clause D names. The fixtures are
-- transcribed here rather than copied anywhere: they stay in the
-- frozen app/data/ASCII tree, unread by the build and unduplicated.
-- No database extension is installed, no storage location named,
-- no privilege conferred, no role created, no schema qualified
-- beyond the default search_path and no psql meta-command used.
--
-- FAILURE IS LOUD. Not one statement below carries a
-- conflict-suppressing clause, an existence guard or any other
-- construct that would let a rejected row pass unnoticed, and none
-- may be added. A duplicate key, a violated CHECK
-- or an unresolved foreign key must abort the migration and report the
-- offending row, because a silently half-applied seed is
-- indistinguishable from a correct one until a parity comparison
-- fails much later. The upsert semantics of
-- app/cbl/CBTRN02C.cbl:467-501 - which accepts file status '23' as
-- success and creates the row - belong to the posting service, not
-- here; reproducing them in this file would convert a seeding defect
-- into a silent no-op.
--
-- LOAD ORDER. The eleven blocks below run in the order V1's ten
-- foreign keys force, and reordering them provokes a violation:
--
--   1  transaction_type              no parent
--   2  transaction_category          -> transaction_type        FK09
--   3  account                       no parent
--   4  customer                      no parent
--   5  card                          -> account                 FK01
--   6  card_cross_reference          -> customer, account   FK02/FK03
--   7  disclosure_group              -> transaction_category    FK10
--   8  transaction_category_balance  -> account, category   FK07/FK08
--   9  "transaction"                 -> card, type, category
--                                                     FK04/FK05/FK06
--  10  daily_transaction             no parent, by design
--  11  user_security                 no parent
--
-- The binding edge is FK04, "transaction".tran_card_num referencing
-- card.card_num, which is why the posted-transaction block sits after
-- the card block even though it inserts nothing. daily_transaction has
-- ZERO foreign keys - deliberately, so that a staged row referencing a
-- missing card or account can be loaded and rejected at posting time
-- rather than at insert time - so its position is unconstrained; it is
-- kept at 10 only so the reading order matches V1's table order.
--
-- Referential sufficiency was checked against the fixtures rather than
-- assumed: every trancatg type code exists in trantype; every discgrp
-- and tcatbal type-and-category pair exists in trancatg; every
-- carddata account, cardxref account and tcatbal account exists in
-- acctdata; every cardxref customer exists in custdata; and every
-- primary-key candidate is unique within its fixture.
--
-- FORMATTING AND LINE WIDTH. One row per line, throughout. That is a
-- deliberate tradeoff and the only place this file exceeds the
-- 120-column hint .editorconfig sets for *.sql - a hint that file
-- itself describes as an editor convention rather than a formatter or
-- a build step, and which no tool in this build enforces. The
-- alternative, one column per line, would spread the 300 staged
-- transactions over more than four thousand lines and make a
-- single-row change unreadable in a diff. A fixed-width record cannot
-- be narrowed either: a CHAR(100) description is 102 characters before
-- any separator. One row per line keeps every record greppable by its
-- key and every change a one-line diff, which is the property that
-- matters for 636 rows of reference data. Column order in every
-- INSERT matches V1's CREATE TABLE order exactly, and the column list
-- is always written out rather than relying on positional order.


-- ==================================================================
-- BLOCK 1 of 11 - transaction_type
-- ==================================================================
--
-- Source fixture : app/data/ASCII/trantype.txt, 7 rows x 60 bytes = 427 B
-- Source layout  : app/cpy/CVTRA03Y.cpy, TRAN-TYPE-RECORD, RECLN = 60
--                  TRAN-TYPE      PIC X(02)  :L5  bytes [1-2]
--                  TRAN-TYPE-DESC PIC X(50)  :L6  bytes [3-52]
--                  FILLER         PIC X(08)  :L7  bytes [53-60]
-- Legacy cluster : AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS, key length 2
--                  app/jcl/TRANTYPE.jcl:L40 KEYS(2 0), :L41
--                  RECORDSIZE(60 60)
-- Row census     : 7 rows, codes 01 through 07, each unique
--
-- NOTE: THE KEY COLUMN IS tran_type, NOT tran_type_cd.
-- app/cpy/CVTRA03Y.cpy:L5 names the field plainly TRAN-TYPE, uniquely
-- among the copybooks; every other transaction-type field carries the
-- -CD suffix. V1 preserved that spelling and so does this INSERT.
-- Guessing the suffixed name fails at apply time with an undefined
-- column, and would fail schema validation at start-up even if it did
-- not. Corroborated by the unsuffixed target field FD-TRAN-TYPE at
-- app/cbl/CBTRN03C.cbl:189.
--
-- NOTE: the 8-byte FILLER at :L7 is ZERO-filled in this fixture, not
-- blank-filled. It is not modelled as a column, so this affects only
-- how bytes [53-60] are skipped while parsing.

INSERT INTO transaction_type (tran_type, tran_type_desc) VALUES
  ('01', 'Purchase                                          '),
  ('02', 'Payment                                           '),
  ('03', 'Credit                                            '),
  ('04', 'Authorization                                     '),
  ('05', 'Refund                                            '),
  ('06', 'Reversal                                          '),
  ('07', 'Adjustment                                        ');


-- ==================================================================
-- BLOCK 2 of 11 - transaction_category
-- ==================================================================
--
-- Source fixture : app/data/ASCII/trancatg.txt, 18 rows x 60 bytes = 1,098 B
-- Source layout  : app/cpy/CVTRA04Y.cpy, TRAN-CAT-RECORD, RECLN = 60
--                  TRAN-TYPE-CD       PIC X(02)  :L6  bytes [1-2]
--                  TRAN-CAT-CD        PIC 9(04)  :L7  bytes [3-6]
--                  TRAN-CAT-TYPE-DESC PIC X(50)  :L8  bytes [7-56]
--                  FILLER             PIC X(04)  :L9  bytes [57-60]
-- Legacy cluster : AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS, key length 6
-- Row census     : 18 rows over 7 type codes - 01 has 5 categories,
--                  02 has 3, 03 has 3, 04 has 3, 05 has 1, 06 has 2,
--                  07 has 1
--
-- Every type code here resolves to a row of BLOCK 1, which is what
-- FK09 requires and why this block runs second.
--
-- ONE COMBINATION IS DELIBERATELY UNMATCHED DOWNSTREAM. These 18 pairs
-- minus the 17 pairs of the DEFAULT disclosure group in BLOCK 7 leave
-- exactly 01/0005, 'Interest Amount', with no DEFAULT rate. That is not
-- a gap to fill: it is the combination the interest job itself
-- generates rather than reads, and because the category-balance
-- fixture in BLOCK 8 is entirely 01/0001 the seed set resolves
-- cleanly. A category-balance row at 01/0005 would send
-- app/cbl/CBACT04C.cbl down its second lookup, find nothing, and abend
-- at :458. Adding a 01/0005 DEFAULT rate row would equally be wrong -
-- it is not in the fixture.
--
-- NOTE: the 4-byte FILLER at :L9 is ZERO-filled, not blank-filled.

INSERT INTO transaction_category (
  tran_type_cd, tran_cat_cd, tran_cat_type_desc
) VALUES
  ('01', 1, 'Regular Sales Draft                               '),
  ('01', 2, 'Regular Cash Advance                              '),
  ('01', 3, 'Convenience Check Debit                           '),
  ('01', 4, 'ATM Cash Advance                                  '),
  ('01', 5, 'Interest Amount                                   '),
  ('02', 1, 'Cash payment                                      '),
  ('02', 2, 'Electronic payment                                '),
  ('02', 3, 'Check payment                                     '),
  ('03', 1, 'Credit to Account                                 '),
  ('03', 2, 'Credit to Purchase balance                        '),
  ('03', 3, 'Credit to Cash balance                            '),
  ('04', 1, 'Zero dollar authorization                         '),
  ('04', 2, 'Online purchase authorization                     '),
  ('04', 3, 'Travel booking authorization                      '),
  ('05', 1, 'Refund credit                                     '),
  ('06', 1, 'Fraud reversal                                    '),
  ('06', 2, 'Non-fraud reversal                                '),
  ('07', 1, 'Sales draft credit adjustment                     ');


-- ==================================================================
-- BLOCK 3 of 11 - account
-- ==================================================================
--
-- Source fixture : app/data/ASCII/acctdata.txt, 50 rows x 300 bytes = 15,050 B
-- Source layout  : app/cpy/CVACT01Y.cpy, ACCOUNT-RECORD, RECLN 300
--                  ACCT-ID                PIC 9(11)      :L5   [1-11]
--                  ACCT-ACTIVE-STATUS     PIC X(01)      :L6   [12]
--                  ACCT-CURR-BAL          PIC S9(10)V99  :L7   [13-24]
--                  ACCT-CREDIT-LIMIT      PIC S9(10)V99  :L8   [25-36]
--                  ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99  :L9   [37-48]
--                  ACCT-OPEN-DATE         PIC X(10)      :L10  [49-58]
--                  ACCT-EXPIRAION-DATE    PIC X(10)      :L11  [59-68]
--                  ACCT-REISSUE-DATE      PIC X(10)      :L12  [69-78]
--                  ACCT-CURR-CYC-CREDIT   PIC S9(10)V99  :L13  [79-90]
--                  ACCT-CURR-CYC-DEBIT    PIC S9(10)V99  :L14  [91-102]
--                  ACCT-ADDR-ZIP          PIC X(10)      :L15  [103-112]
--                  ACCT-GROUP-ID          PIC X(10)      :L16  [113-122]
--                  FILLER                 PIC X(178)     :L17  [123-300]
-- Legacy cluster : AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS, key length 11,
--                  average record length 300
-- Row census     : 50 rows, acct_id 1 through 50, each unique;
--                  acct_active_status 'Y' on all 50;
--                  acct_addr_zip 'A000000000' on all 50;
--                  acct_group_id ten blanks on all 50
--
-- FIVE SIGNED FIELDS PER ROW, EACH DECODED AT ITS OWN OFFSET. Balance,
-- credit limit, cash credit limit and both cycle accumulators are
-- PIC S9(10)V99 - twelve bytes, eleven digits plus the overpunch - and
-- land in NUMERIC(12,2) columns. Row 1 is the worked example in
-- ZONED-DECIMAL OVERPUNCH DECODING above.
--
-- THE BLANK GROUP IDENTIFIER IS THE MOST LOAD-BEARING VALUE IN THIS
-- BLOCK. All 50 rows carry ten blanks in bytes [113-122], so
-- app/cbl/CBACT04C.cbl:210 moves blanks into FD-DIS-ACCT-GROUP-ID and
-- the first disclosure-group READ at :416 misses on EVERY account. The
-- status is '23', which :422 accepts as success, so :436 detects it and
-- :437 substitutes the literal 'DEFAULT' before retrying at :438. The
-- retry at :444 accepts '00' ONLY, and :458 abends with code 999
-- otherwise. The consequence is stated plainly: the DEFAULT rows of
-- BLOCK 7 resolve the interest rate for all fifty accounts, and the
-- A000000000 rows in that same block are never reached from this seed.
-- Populating a group identifier here would silently reroute the whole
-- fixture down the first-lookup path.
--
-- Both cycle accumulators are 0.00 on all 50 rows, so the seeded state
-- is a cycle that has not yet been posted to. That is the starting
-- point the over-limit arithmetic at app/cbl/CBTRN02C.cbl:403-405
-- assumes, and it is why acct_curr_cyc_debit may legitimately become
-- negative later without ever being negative here.
--
-- The EXPIRAION misspelling in the column name is the copybook
-- spelling at :L11 and is deliberate. app/cbl/CBTRN02C.cbl:414
-- compares ACCT-EXPIRAION-DATE against the first ten characters of the
-- staged originating timestamp, so the field is in active use.
--
-- version is 0 on every row - V1 declares the column NOT NULL with no
-- DEFAULT. The 178-byte FILLER at :L17 is blank-filled and unmodelled.

INSERT INTO account (
  acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
  acct_cash_credit_limit, acct_open_date, acct_expiraion_date,
  acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit,
  acct_addr_zip, acct_group_id, version
) VALUES
  (1, 'Y', 194.00, 2020.00, 1020.00, '2014-11-20', '2025-05-20', '2025-05-20', 0.00, 0.00, 'A000000000', '          ', 0),
  (2, 'Y', 158.00, 6130.00, 5448.00, '2013-06-19', '2024-08-11', '2024-08-11', 0.00, 0.00, 'A000000000', '          ', 0),
  (3, 'Y', 147.00, 4909.00, 538.00, '2013-08-23', '2024-01-10', '2024-01-10', 0.00, 0.00, 'A000000000', '          ', 0),
  (4, 'Y', 40.00, 3503.00, 2789.00, '2012-11-17', '2023-12-16', '2023-12-16', 0.00, 0.00, 'A000000000', '          ', 0),
  (5, 'Y', 345.00, 3819.00, 2430.00, '2012-10-03', '2025-03-09', '2025-03-09', 0.00, 0.00, 'A000000000', '          ', 0),
  (6, 'Y', 218.00, 3584.00, 2948.00, '2017-12-23', '2025-10-08', '2025-10-08', 0.00, 0.00, 'A000000000', '          ', 0),
  (7, 'Y', 193.00, 2065.00, 264.00, '2012-10-12', '2024-12-13', '2024-12-13', 0.00, 0.00, 'A000000000', '          ', 0),
  (8, 'Y', 605.00, 6104.00, 1318.00, '2012-01-04', '2024-05-20', '2024-05-20', 0.00, 0.00, 'A000000000', '          ', 0),
  (9, 'Y', 560.00, 8201.00, 2065.00, '2016-08-27', '2024-12-27', '2024-12-27', 0.00, 0.00, 'A000000000', '          ', 0),
  (10, 'Y', 159.00, 5401.00, 4442.00, '2015-09-13', '2023-01-27', '2023-01-27', 0.00, 0.00, 'A000000000', '          ', 0),
  (11, 'Y', 212.00, 4998.00, 3175.00, '2014-09-12', '2025-03-12', '2025-03-12', 0.00, 0.00, 'A000000000', '          ', 0),
  (12, 'Y', 176.00, 4636.00, 388.00, '2009-06-17', '2023-07-07', '2023-07-07', 0.00, 0.00, 'A000000000', '          ', 0),
  (13, 'Y', 41.00, 7542.00, 4922.00, '2017-10-01', '2024-08-04', '2024-08-04', 0.00, 0.00, 'A000000000', '          ', 0),
  (14, 'Y', 15.00, 2254.00, 212.00, '2010-12-04', '2025-12-11', '2025-12-11', 0.00, 0.00, 'A000000000', '          ', 0),
  (15, 'Y', 489.00, 8441.00, 3833.00, '2009-10-06', '2025-06-09', '2025-06-09', 0.00, 0.00, 'A000000000', '          ', 0),
  (16, 'Y', 733.00, 8922.00, 2632.00, '2014-09-11', '2024-01-25', '2024-01-25', 0.00, 0.00, 'A000000000', '          ', 0),
  (17, 'Y', 33.00, 568.00, 510.00, '2014-05-17', '2025-03-01', '2025-03-01', 0.00, 0.00, 'A000000000', '          ', 0),
  (18, 'Y', 144.00, 2903.00, 1496.00, '2018-11-15', '2023-09-10', '2023-09-10', 0.00, 0.00, 'A000000000', '          ', 0),
  (19, 'Y', 480.00, 6986.00, 3723.00, '2011-12-14', '2025-07-23', '2025-07-23', 0.00, 0.00, 'A000000000', '          ', 0),
  (20, 'Y', 369.00, 3767.00, 1040.00, '2014-02-27', '2024-03-13', '2024-03-13', 0.00, 0.00, 'A000000000', '          ', 0),
  (21, 'Y', 112.00, 1264.00, 180.00, '2011-10-19', '2023-01-06', '2023-01-06', 0.00, 0.00, 'A000000000', '          ', 0),
  (22, 'Y', 55.00, 8599.00, 4712.00, '2016-11-21', '2025-12-28', '2025-12-28', 0.00, 0.00, 'A000000000', '          ', 0),
  (23, 'Y', 104.00, 3377.00, 2904.00, '2012-03-15', '2025-03-18', '2025-03-18', 0.00, 0.00, 'A000000000', '          ', 0),
  (24, 'Y', 400.00, 5174.00, 4129.00, '2015-08-08', '2025-02-11', '2025-02-11', 0.00, 0.00, 'A000000000', '          ', 0),
  (25, 'Y', 61.00, 8194.00, 6582.00, '2012-10-26', '2025-07-10', '2025-07-10', 0.00, 0.00, 'A000000000', '          ', 0),
  (26, 'Y', 46.00, 2181.00, 1375.00, '2009-04-20', '2024-12-19', '2024-12-19', 0.00, 0.00, 'A000000000', '          ', 0),
  (27, 'Y', 284.00, 5572.00, 2075.00, '2012-09-30', '2025-07-13', '2025-07-13', 0.00, 0.00, 'A000000000', '          ', 0),
  (28, 'Y', 68.00, 868.00, 547.00, '2015-05-20', '2024-05-09', '2024-05-09', 0.00, 0.00, 'A000000000', '          ', 0),
  (29, 'Y', 339.00, 5511.00, 4361.00, '2015-11-03', '2024-06-04', '2024-06-04', 0.00, 0.00, 'A000000000', '          ', 0),
  (30, 'Y', 2.00, 120.00, 93.00, '2011-08-26', '2024-06-27', '2024-06-27', 0.00, 0.00, 'A000000000', '          ', 0),
  (31, 'Y', 31.00, 1140.00, 1077.00, '2017-02-25', '2025-06-08', '2025-06-08', 0.00, 0.00, 'A000000000', '          ', 0),
  (32, 'Y', 30.00, 1175.00, 846.00, '2013-11-10', '2025-05-19', '2025-05-19', 0.00, 0.00, 'A000000000', '          ', 0),
  (33, 'Y', 410.00, 6404.00, 951.00, '2012-10-11', '2025-10-07', '2025-10-07', 0.00, 0.00, 'A000000000', '          ', 0),
  (34, 'Y', 253.00, 3642.00, 2770.00, '2009-05-10', '2025-10-06', '2025-10-06', 0.00, 0.00, 'A000000000', '          ', 0),
  (35, 'Y', 166.00, 1947.00, 1525.00, '2018-02-02', '2025-09-23', '2025-09-23', 0.00, 0.00, 'A000000000', '          ', 0),
  (36, 'Y', 110.00, 3328.00, 839.00, '2018-07-18', '2024-12-23', '2024-12-23', 0.00, 0.00, 'A000000000', '          ', 0),
  (37, 'Y', 7.00, 446.00, 166.00, '2016-09-10', '2023-10-24', '2023-10-24', 0.00, 0.00, 'A000000000', '          ', 0),
  (38, 'Y', 612.00, 6505.00, 3476.00, '2010-08-12', '2023-07-23', '2023-07-23', 0.00, 0.00, 'A000000000', '          ', 0),
  (39, 'Y', 843.00, 9750.00, 6212.00, '2018-08-26', '2025-09-08', '2025-09-08', 0.00, 0.00, 'A000000000', '          ', 0),
  (40, 'Y', 43.00, 5823.00, 1674.00, '2010-02-13', '2023-10-27', '2023-10-27', 0.00, 0.00, 'A000000000', '          ', 0),
  (41, 'Y', 375.00, 6721.00, 3429.00, '2015-02-07', '2023-04-24', '2023-04-24', 0.00, 0.00, 'A000000000', '          ', 0),
  (42, 'Y', 302.00, 6563.00, 5103.00, '2016-09-19', '2025-09-19', '2025-09-19', 0.00, 0.00, 'A000000000', '          ', 0),
  (43, 'Y', 610.00, 6168.00, 1206.00, '2012-04-09', '2025-08-29', '2025-08-29', 0.00, 0.00, 'A000000000', '          ', 0),
  (44, 'Y', 263.00, 6899.00, 4432.00, '2018-12-01', '2024-01-17', '2024-01-17', 0.00, 0.00, 'A000000000', '          ', 0),
  (45, 'Y', 186.00, 2719.00, 688.00, '2010-12-31', '2025-07-09', '2025-07-09', 0.00, 0.00, 'A000000000', '          ', 0),
  (46, 'Y', 396.00, 7007.00, 5438.00, '2013-09-06', '2025-06-20', '2025-06-20', 0.00, 0.00, 'A000000000', '          ', 0),
  (47, 'Y', 32.00, 2338.00, 159.00, '2014-04-03', '2025-08-23', '2025-08-23', 0.00, 0.00, 'A000000000', '          ', 0),
  (48, 'Y', 226.00, 2306.00, 612.00, '2017-03-18', '2025-02-06', '2025-02-06', 0.00, 0.00, 'A000000000', '          ', 0),
  (49, 'Y', 100.00, 9048.00, 4807.00, '2019-04-06', '2023-09-17', '2023-09-17', 0.00, 0.00, 'A000000000', '          ', 0),
  (50, 'Y', 492.00, 6169.00, 4587.00, '2011-04-22', '2023-03-09', '2023-03-09', 0.00, 0.00, 'A000000000', '          ', 0);


-- ==================================================================
-- BLOCK 4 of 11 - customer
-- ==================================================================
--
-- Source fixture : app/data/ASCII/custdata.txt, 50 rows x 500 bytes = 25,050 B
-- Source layout  : app/cpy/CVCUS01Y.cpy, CUSTOMER-RECORD, RECLN 500
--                  CUST-ID                  PIC 9(09)  :L5   [1-9]
--                  CUST-FIRST-NAME          PIC X(25)  :L6   [10-34]
--                  CUST-MIDDLE-NAME         PIC X(25)  :L7   [35-59]
--                  CUST-LAST-NAME           PIC X(25)  :L8   [60-84]
--                  CUST-ADDR-LINE-1         PIC X(50)  :L9   [85-134]
--                  CUST-ADDR-LINE-2         PIC X(50)  :L10  [135-184]
--                  CUST-ADDR-LINE-3         PIC X(50)  :L11  [185-234]
--                  CUST-ADDR-STATE-CD       PIC X(02)  :L12  [235-236]
--                  CUST-ADDR-COUNTRY-CD     PIC X(03)  :L13  [237-239]
--                  CUST-ADDR-ZIP            PIC X(10)  :L14  [240-249]
--                  CUST-PHONE-NUM-1         PIC X(15)  :L15  [250-264]
--                  CUST-PHONE-NUM-2         PIC X(15)  :L16  [265-279]
--                  CUST-SSN                 PIC 9(09)  :L17  [280-288]
--                  CUST-GOVT-ISSUED-ID      PIC X(20)  :L18  [289-308]
--                  CUST-DOB-YYYY-MM-DD      PIC X(10)  :L19  [309-318]
--                  CUST-EFT-ACCOUNT-ID      PIC X(10)  :L20  [319-328]
--                  CUST-PRI-CARD-HOLDER-IND PIC X(01)  :L21  [329]
--                  CUST-FICO-CREDIT-SCORE   PIC 9(03)  :L22  [330-332]
--                  FILLER                   PIC X(168) :L23  [333-500]
-- Legacy cluster : AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS, key length 9,
--                  average record length 500
-- Row census     : 50 rows, cust_id 1 through 50, each unique;
--                  cust_pri_card_holder_ind 'Y' on all 50;
--                  all 50 social security values exactly nine digits
--
-- THIS BLOCK CARRIES PERSONALLY IDENTIFIABLE DATA. Names, three
-- address lines, two telephone numbers, a government identifier, a
-- date of birth and a social security number are loaded as values
-- because the fixture holds them and parity requires them. They are
-- never exhibited: no comment in this file reproduces one as an
-- example, and fields are referred to by byte offset instead. That is
-- also why KEY CONFIGURATION AND DEFAULTS pins show-sql false and
-- forbids bind-parameter logging in every profile - a logged statement
-- would copy all of it into the application log.
--
-- THREE COLUMNS ARE CHARACTER, NOT NUMERIC, AND LEADING ZEROS DEPEND
-- ON IT. cust_ssn is CHAR(9) and 6 of the 50 values begin with a zero;
-- cust_fico_credit_score is CHAR(3) and 7 of the 50 begin with a zero.
-- A numeric column would drop those digits and change the stored value.
-- V1's ck_customer_ssn_numeric restores the PIC 9(09) digits-only
-- domain that the demotion to character discards; all 50 rows satisfy
-- it.
--
-- THE LOW CREDIT SCORES ARE SEEDED VERBATIM AND MUST NOT BE
-- FILTERED, CLAMPED OR CORRECTED. 21 of the 50 rows score below 300.
-- app/cbl/COACTUPC.cbl:848-849 does declare
-- 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850. with a validator at
-- :2514-2531, but it qualifies ACUP-NEW-CUST-FICO-SCORE, which
-- :845-847 declares inside the screen snapshot group - not a
-- record-layout field. It is an ONLINE INPUT rule, not a stored-data
-- invariant, which is why V1 adds no range CHECK and why every row
-- here is loaded as the fixture holds it. Clamping them would
-- fabricate data and mask the distinction.
--
-- version is 0 on every row. The 168-byte FILLER at :L23 is
-- blank-filled and unmodelled.

INSERT INTO customer (
  cust_id, cust_first_name, cust_middle_name, cust_last_name,
  cust_addr_line_1, cust_addr_line_2, cust_addr_line_3,
  cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip,
  cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,
  cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind,
  cust_fico_credit_score, version
) VALUES
  (1, 'Immanuel                 ', 'Madeline                 ', 'Kessler                  ', '618 Deshaun Route                                 ', 'Apt. 802                                          ', 'Altenwerthshire                                   ', 'NC', 'USA', '12546     ', '(908)119-8310  ', '(373)693-8684  ', '020973888', '00000000000049368437', '1961-06-08', '0053581756', 'Y', '274', 0),
  (2, 'Enrico                   ', 'April                    ', 'Rosenbaum                ', '4917 Myrna Flats                                  ', 'Apt. 453                                          ', 'West Bernita                                      ', 'IN', 'USA', '22770     ', '(429)706-9510  ', '(744)950-5272  ', '587518382', '00000000000506210371', '1961-10-08', '0069194009', 'Y', '268', 0),
  (3, 'Larry                    ', 'Cody                     ', 'Homenick                 ', '362 Esta Parks                                    ', 'Apt. 390                                          ', 'New Gladys                                        ', 'GA', 'USA', '19852-6716', '(950)396-9024  ', '(685)168-8826  ', '317460867', '00000000000052419303', '1987-11-30', '0006465789', 'Y', '616', 0),
  (4, 'Delbert                  ', 'Kaia                     ', 'Parisian                 ', '638 Blanda Gateway                                ', 'Apt. 076                                          ', 'Lake Virginie                                     ', 'MI', 'USA', '39035-0455', '(801)603-4121  ', '(156)074-6837  ', '660354258', '00000000000068579249', '1985-01-13', '0040802739', 'Y', '776', 0),
  (5, 'Treva                    ', 'Manley                   ', 'Schowalter               ', '5653 Legros Plaza                                 ', 'Apt. 968                                          ', 'Alvinaport                                        ', 'MI', 'USA', '02251-1698', '(978)775-4633  ', '(439)943-7644  ', '611264288', '00000000000639799754', '1971-09-29', '0006365573', 'Y', '529', 0),
  (6, 'Ignacio                  ', 'Emery                    ', 'Douglas                  ', '3963 Yasmin Port                                  ', 'Suite 756                                         ', 'Port Josephstad                                   ', 'VI', 'USA', '46713-5148', '(277)743-4266  ', '(519)010-8739  ', '880329521', '00000000000975535496', '1994-11-29', '0067163009', 'Y', '753', 0),
  (7, 'Cooper                   ', 'Dennis                   ', 'Mayert                   ', '6490 Zakary Locks                                 ', 'Apt. 765                                          ', 'Madieport                                         ', 'AL', 'USA', '34206-2974', '(698)282-4096  ', '(458)199-0016  ', '835138951', '00000000000959013170', '1977-05-06', '0024571415', 'Y', '499', 0),
  (8, 'Kelsie                   ', 'Jordyn                   ', 'Dicki                    ', '0925 Welch Streets                                ', 'Apt. 152                                          ', 'North Nanniestad                                  ', 'SC', 'USA', '27610     ', '(345)563-7159  ', '(443)197-1271  ', '295270759', '00000000000109746991', '1964-03-25', '0033132723', 'Y', '051', 0),
  (9, 'Melvin                   ', 'Regan                    ', 'Ondricka                 ', '87893 Samson Flats                                ', 'Apt. 135                                          ', 'New Braden                                        ', 'VI', 'USA', '21113     ', '(035)456-1404  ', '(412)440-3130  ', '842035847', '00000000000568299451', '1975-11-07', '0039446039', 'Y', '699', 0),
  (10, 'Maybell                  ', 'Creola                   ', 'Mann                     ', '77933 Adah Dale                                   ', 'Suite 343                                         ', 'Andersonfurt                                      ', 'CT', 'USA', '44803-4279', '(614)594-2619  ', '(667)057-0235  ', '754755746', '00000000000212824755', '1980-06-11', '0093803568', 'Y', '476', 0),
  (11, 'Hayden                   ', 'Ressie                   ', 'Pfannerstill             ', '14895 Everette Ridges                             ', 'Apt. 443                                          ', 'Julianneburgh                                     ', 'WA', 'USA', '24984     ', '(002)533-6980  ', '(553)586-7718  ', '493538586', '00000000000111190855', '1986-11-03', '0002650577', 'Y', '209', 0),
  (12, 'Maci                     ', 'Alan                     ', 'Robel                    ', '80501 Isac Cliffs                                 ', 'Suite 623                                         ', 'Predovicton                                       ', 'MN', 'USA', '78861     ', '(584)045-5200  ', '(610)244-0407  ', '666114218', '00000000000902143351', '1984-02-18', '0061317348', 'Y', '688', 0),
  (13, 'Mariane                  ', 'Oma                      ', 'Fadel                    ', '2689 Derick Mission                               ', 'Suite 055                                         ', 'Bruenfurt                                         ', 'OR', 'USA', '02322     ', '(875)943-7287  ', '(075)550-6435  ', '757924569', '00000000000181377220', '1999-03-09', '0044807431', 'Y', '053', 0),
  (14, 'Chelsea                  ', 'Ignacio                  ', 'Marks                    ', '747 Dino Lodge                                    ', 'Apt. 850                                          ', 'West Chase                                        ', 'RI', 'USA', '12914-8465', '(141)807-6571  ', '(284)088-9052  ', '655128548', '00000000000525955222', '1974-11-29', '0048306401', 'Y', '243', 0),
  (15, 'Aubree                   ', 'Elliot                   ', 'Hermann                  ', '36365 Ledner Drives                               ', 'Suite 882                                         ', 'Port Efrainland                                   ', 'DE', 'USA', '63205-7014', '(769)100-7971  ', '(366)310-2061  ', '033922034', '00000000000230369941', '1964-12-06', '0000634612', 'Y', '681', 0),
  (16, 'Carroll                  ', 'Cicero                   ', 'Bergstrom                ', '06988 Thiel Falls                                 ', 'Suite 148                                         ', 'Concepcionland                                    ', 'VT', 'USA', '84390     ', '(631)343-8667  ', '(938)648-3716  ', '649827971', '00000000000293265752', '1983-04-27', '0012556599', 'Y', '326', 0),
  (17, 'Sigrid                   ', 'Angeline                 ', 'Mann                     ', '95666 Dare Isle                                   ', 'Suite 286                                         ', 'New Presley                                       ', 'FM', 'USA', '56181-0584', '(087)314-2070  ', '(541)003-6606  ', '303334693', '00000000000497606357', '1979-01-26', '0052356071', 'Y', '054', 0),
  (18, 'Emile                    ', 'Jairo                    ', 'White                    ', '133 Bergnaum Square                               ', 'Apt. 328                                          ', 'Hansenville                                       ', 'AP', 'USA', '96003-5867', '(303)654-3323  ', '(520)186-2176  ', '385849271', '00000000000088341821', '1987-03-25', '0086459831', 'Y', '340', 0),
  (19, 'Hadley                   ', 'Sigrid                   ', 'Hamill                   ', '6273 Ondricka Meadows                             ', 'Apt. 130                                          ', 'New Arturoshire                                   ', 'RI', 'USA', '48161     ', '(817)452-4986  ', '(724)901-6019  ', '439569907', '00000000000270176387', '1991-01-07', '0036492057', 'Y', '259', 0),
  (20, 'Carter                   ', 'Oren                     ', 'Veum                     ', '5845 Allison Valleys                              ', 'Suite 934                                         ', 'Mitchellmouth                                     ', 'MH', 'USA', '72362     ', '(618)994-0531  ', '(571)695-4136  ', '717778238', '00000000000342661293', '1996-04-14', '0036749754', 'Y', '493', 0),
  (21, 'Jerrold                  ', 'Adolphus                 ', 'Maggio                   ', '401 Haylie Crest                                  ', 'Apt. 320                                          ', 'North Myrnaton                                    ', 'CA', 'USA', '72407     ', '(399)526-3254  ', '(326)193-1118  ', '336490822', '00000000000027656260', '1977-11-15', '0011744660', 'Y', '163', 0),
  (22, 'Allene                   ', 'Icie                     ', 'Brown                    ', '4467 Donnie Crossroad                             ', 'Apt. 437                                          ', 'Anabelton                                         ', 'MD', 'USA', '01993-9116', '(231)251-5792  ', '(494)652-0009  ', '292059024', '00000000000691159853', '1994-02-20', '0024791470', 'Y', '597', 0),
  (23, 'Johnson                  ', 'Blanca                   ', 'Ruecker                  ', '2433 Jacobi Forks                                 ', 'Apt. 845                                          ', 'Hendersonbury                                     ', 'KS', 'USA', '78239-9466', '(981)873-1589  ', '(131)638-5974  ', '944154289', '00000000000268967122', '1998-12-07', '0075158529', 'Y', '337', 0),
  (24, 'Stefanie                 ', 'Verla                    ', 'Dickinson                ', '6367 Stracke River                                ', 'Apt. 444                                          ', 'East Otho                                         ', 'KS', 'USA', '15414     ', '(617)348-9142  ', '(330)116-5634  ', '017590544', '00000000000439244633', '1996-01-24', '0005459662', 'Y', '711', 0),
  (25, 'Elliott                  ', 'Fermin                   ', 'Howell                   ', '9524 McKenzie Lakes                               ', 'Suite 245                                         ', 'West Alexa                                        ', 'NH', 'USA', '75721-7382', '(092)336-8599  ', '(311)969-1460  ', '788820436', '00000000000548223048', '1989-03-27', '0032297533', 'Y', '355', 0),
  (26, 'Marjory                  ', 'Damien                   ', 'Stracke                  ', '30161 Bogan Canyon                                ', 'Suite 916                                         ', 'Walshberg                                         ', 'IL', 'USA', '59945     ', '(584)772-2867  ', '(819)733-9809  ', '840478806', '00000000000947411626', '1990-03-17', '0060808858', 'Y', '001', 0),
  (27, 'Ward                     ', 'Henri                    ', 'Jones                    ', '210 Amaya Turnpike                                ', 'Suite 180                                         ', 'Port Dwight                                       ', 'GU', 'USA', '07923-8822', '(935)027-1145  ', '(103)537-5007  ', '980161210', '00000000000881558757', '1986-11-08', '0050024139', 'Y', '078', 0),
  (28, 'Hester                   ', 'Vesta                    ', 'Hane                     ', '06816 Ursula Meadows                              ', 'Suite 605                                         ', 'South Aurore                                      ', 'AS', 'USA', '77442-7954', '(122)357-7257  ', '(050)352-6579  ', '677986013', '00000000000514187796', '1991-06-05', '0026946180', 'Y', '114', 0),
  (29, 'Rickie                   ', 'Otho                     ', 'Daugherty                ', '676 Funk Curve                                    ', 'Apt. 375                                          ', 'Hayesstad                                         ', 'NH', 'USA', '01226     ', '(418)291-9023  ', '(795)634-7776  ', '015027332', '00000000000062745655', '1973-04-05', '0067736493', 'Y', '552', 0),
  (30, 'Layla                    ', 'Dannie                   ', 'Ullrich                  ', '269 Eleazar Circle                                ', 'Apt. 817                                          ', 'Kutchland                                         ', 'AK', 'USA', '64266     ', '(330)408-6966  ', '(413)347-7306  ', '866102152', '00000000000492021686', '1965-11-28', '0050520060', 'Y', '133', 0),
  (31, 'Lucious                  ', 'Otto                     ', 'O''Connell                ', '919 Swift Valleys                                 ', 'Suite 548                                         ', 'Hermanborough                                     ', 'MS', 'USA', '56133-5636', '(259)414-9625  ', '(118)946-9264  ', '357462348', '00000000000618310539', '1976-08-03', '0092999757', 'Y', '058', 0),
  (32, 'Stephany                 ', 'Meda                     ', 'Fisher                   ', '63452 Kenny Streets                               ', 'Apt. 116                                          ', 'Predovicburgh                                     ', 'AK', 'USA', '85943-7605', '(202)436-5156  ', '(246)296-3533  ', '146204208', '00000000000206200341', '1980-11-19', '0035970593', 'Y', '221', 0),
  (33, 'Bernice                  ', 'Norbert                  ', 'Herman                   ', '877 Kassandra Ranch                               ', 'Suite 956                                         ', 'Haleyport                                         ', 'AR', 'USA', '19113-4329', '(836)743-5487  ', '(640)208-1176  ', '144195105', '00000000000400605429', '1988-05-19', '0065245171', 'Y', '469', 0),
  (34, 'Faustino                 ', 'Jess                     ', 'Schmidt                  ', '44132 Michel Square                               ', 'Suite 007                                         ', 'South Margarettaburgh                             ', 'ME', 'USA', '49544-2869', '(179)036-5135  ', '(986)905-0112  ', '548088300', '00000000000159882533', '1994-03-21', '0067445089', 'Y', '104', 0),
  (35, 'Angelica                 ', 'Damaris                  ', 'Dach                     ', '396 Pearl Loop                                    ', 'Suite 383                                         ', 'Pfefferhaven                                      ', 'LA', 'USA', '46142     ', '(303)480-9098  ', '(637)710-7367  ', '220547115', '00000000000977144839', '1987-06-23', '0047435332', 'Y', '793', 0),
  (36, 'Toney                    ', 'Emerald                  ', 'Gerhold                  ', '35943 Raleigh Harbor                              ', 'Apt. 116                                          ', 'Lake Derekburgh                                   ', 'AL', 'USA', '10932-0480', '(034)271-9180  ', '(507)529-4523  ', '420360688', '00000000000942029210', '1991-03-31', '0066461979', 'Y', '266', 0),
  (37, 'Shany                    ', 'Darby                    ', 'Walker                   ', '91196 Heaney Turnpike                             ', 'Suite 814                                         ', 'Lubowitzberg                                      ', 'NV', 'USA', '11857-8177', '(052)759-5167  ', '(706)896-1282  ', '891897974', '00000000000524312632', '1984-12-09', '0066111704', 'Y', '653', 0),
  (38, 'Angela                   ', 'Ceasar                   ', 'Ankunding                ', '65482 Zoila Skyway                                ', 'Apt. 054                                          ', 'East Malachi                                      ', 'VA', 'USA', '63928-0008', '(316)640-2650  ', '(148)111-1148  ', '764307306', '00000000000335562141', '1990-05-28', '0018048939', 'Y', '446', 0),
  (39, 'Aliyah                   ', 'Horace                   ', 'Berge                    ', '5761 Pasquale Trail                               ', 'Apt. 616                                          ', 'New Sabryna                                       ', 'IA', 'USA', '74267     ', '(089)096-3287  ', '(768)959-4733  ', '510793388', '00000000000553254403', '1972-08-26', '0061869530', 'Y', '475', 0),
  (40, 'Davon                    ', 'Demond                   ', 'Emmerich                 ', '23499 Beer Views                                  ', 'Suite 816                                         ', 'Erniechester                                      ', 'TX', 'USA', '87156-8689', '(463)762-3017  ', '(419)414-2177  ', '054960660', '00000000000398353299', '1992-01-26', '0087069976', 'Y', '284', 0),
  (41, 'Lucinda                  ', 'Kiana                    ', 'Dach                     ', '3220 Yolanda Corner                               ', 'Suite 649                                         ', 'East Harmonystad                                  ', 'VT', 'USA', '72971-7481', '(284)052-5831  ', '(091)234-2144  ', '643942675', '00000000000919653442', '1967-02-20', '0007315287', 'Y', '725', 0),
  (42, 'Heather                  ', 'Ericka                   ', 'Nienow                   ', '5523 Archibald Club                               ', 'Apt. 358                                          ', 'Reillyland                                        ', 'FM', 'USA', '83589     ', '(640)954-4538  ', '(565)873-6897  ', '800455633', '00000000000997029966', '1964-11-03', '0079262985', 'Y', '044', 0),
  (43, 'Britney                  ', 'Jermain                  ', 'Waters                   ', '97765 Bernhard Fort                               ', 'Apt. 666                                          ', 'South Marisaview                                  ', 'OK', 'USA', '10050-7980', '(407)042-6952  ', '(438)659-6397  ', '262568593', '00000000000244555805', '1966-10-16', '0053043599', 'Y', '558', 0),
  (44, 'Irving                   ', 'Kiera                    ', 'Emard                    ', '978 Fatima Stream                                 ', 'Apt. 110                                          ', 'Lake King                                         ', 'ID', 'USA', '05704-0501', '(703)484-5840  ', '(537)392-5569  ', '318104527', '00000000000934420974', '1984-04-04', '0032076778', 'Y', '145', 0),
  (45, 'Dixie                    ', 'Norris                   ', 'Beier                    ', '441 Levi Prairie                                  ', 'Suite 749                                         ', 'Abbottshire                                       ', 'NV', 'USA', '09048     ', '(697)143-3221  ', '(499)287-7255  ', '352819961', '00000000000885743286', '2001-12-12', '0027833000', 'Y', '629', 0),
  (46, 'Cindy                    ', 'Kira                     ', 'Cremin                   ', '494 Lang Avenue                                   ', 'Apt. 937                                          ', 'Alexandroview                                     ', 'PW', 'USA', '63082-4520', '(358)349-2574  ', '(077)525-9966  ', '656405528', '00000000000762699577', '1987-12-14', '0017535749', 'Y', '514', 0),
  (47, 'Rigoberto                ', 'Savanna                  ', 'Hoeger                   ', '00097 Gleichner Spur                              ', 'Apt. 932                                          ', 'Port Aidanborough                                 ', 'GU', 'USA', '31329-6973', '(946)322-6160  ', '(973)443-8438  ', '029222192', '00000000000567601472', '1979-02-25', '0022102472', 'Y', '722', 0),
  (48, 'Lyric                    ', 'Mackenzie                ', 'Pacocha                  ', '453 Rosina Mountain                               ', 'Apt. 011                                          ', 'Albertville                                       ', 'OR', 'USA', '83985-4937', '(950)497-1005  ', '(004)244-7955  ', '635734407', '00000000000265392832', '1986-08-17', '0046317382', 'Y', '746', 0),
  (49, 'Immanuel                 ', 'Ellie                    ', 'Bednar                   ', '5423 Esther Locks                                 ', 'Apt. 142                                          ', 'Langoshstad                                       ', 'GA', 'USA', '12288-3495', '(843)095-2553  ', '(615)988-9038  ', '813044111', '00000000000424495981', '2000-01-05', '0058726120', 'Y', '148', 0),
  (50, 'Aniya                    ', 'Alba                     ', 'Von                      ', '1588 Nienow Cape                                  ', 'Suite 187                                         ', 'New Aricchester                                   ', 'OR', 'USA', '04257     ', '(325)301-0827  ', '(493)985-9283  ', '931248469', '00000000000030387824', '1960-12-01', '0074883577', 'Y', '623', 0);


-- ==================================================================
-- BLOCK 5 of 11 - card
-- ==================================================================
--
-- Source fixture : app/data/ASCII/carddata.txt, 50 rows x 150 bytes = 7,550 B
--                  91 bytes populated, bytes [92-150] blank;
--                  bytes [28-30] are read from the fixture and NOT loaded
-- Source layout  : app/cpy/CVACT02Y.cpy, CARD-RECORD, RECLN 150
--                  CARD-NUM            PIC X(16)  :L5   [1-16]
--                  CARD-ACCT-ID        PIC 9(11)  :L6   [17-27]
--                  CARD-CVV-CD         PIC 9(03)  :L7   [28-30]  NOT LOADED
--                  CARD-EMBOSSED-NAME  PIC X(50)  :L8   [31-80]
--                  CARD-EXPIRAION-DATE PIC X(10)  :L9   [81-90]
--                  CARD-ACTIVE-STATUS  PIC X(01)  :L10  [91]
--                  FILLER              PIC X(59)  :L11  [92-150]
-- Legacy cluster : AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS, key length 16,
--                  average record length 150; alternate index
--                  CARDDATA.VSAM.AIX on the account identifier at
--                  zero-based AXRKP 16, which is one-based byte 17 -
--                  V2's idx_card_acct_id
-- Row census     : 50 rows, card_num unique on all 50;
--                  card_active_status 'Y' on all 50;
--                  every card_acct_id resolves to a BLOCK 3 account
--
-- THE VERIFICATION VALUE COLUMN IS CHARACTER, NOT NUMERIC, AND 8
-- OF THE 50 VALUES BEGIN WITH A ZERO. app/cpy/CVACT02Y.cpy:L7 declares
-- PIC 9(03) but the leading zero is part of the value on a payment
-- card, so V1 declares CHAR(3) and this block seeds the three
-- characters verbatim. A numeric column would store 8 of these 50 rows
-- with a value one digit shorter than the card actually carries, and
-- nothing would report it.
--
-- FK01 requires the account to exist, which is why this block runs
-- after BLOCK 3. Every one of the 50 card_acct_id values was checked
-- against the acctdata fixture before this file was written.
--
-- The EXPIRAION misspelling is the copybook spelling at :L9, the second
-- of the two occurrences in the corpus, and is preserved.
--
-- version is 0 on every row. The 59-byte FILLER at :L11 is blank-filled
-- and unmodelled, as are the three verification-value bytes above, so
-- 88 of the 150 bytes of each fixture row reach the table.

INSERT INTO card (
  card_num, card_acct_id, card_embossed_name,
  card_expiraion_date, card_active_status, version
) VALUES
  ('0500024453765740', 50, 'Aniya Von                                         ', '2023-03-09', 'Y', 0),
  ('0683586198171516', 27, 'Ward Jones                                        ', '2025-07-13', 'Y', 0),
  ('0923877193247330', 2, 'Enrico Rosenbaum                                  ', '2024-08-11', 'Y', 0),
  ('0927987108636232', 20, 'Carter Veum                                       ', '2024-03-13', 'Y', 0),
  ('0982496213629795', 12, 'Maci Robel                                        ', '2023-07-07', 'Y', 0),
  ('1014086565224350', 44, 'Irving Emard                                      ', '2024-01-17', 'Y', 0),
  ('1142167692878931', 37, 'Shany Walker                                      ', '2023-10-24', 'Y', 0),
  ('1561409106491600', 35, 'Angelica Dach                                     ', '2025-09-23', 'Y', 0),
  ('2745303720002090', 39, 'Aliyah Berge                                      ', '2025-09-08', 'Y', 0),
  ('2760836797107565', 24, 'Stefanie Dickinson                                ', '2025-02-11', 'Y', 0),
  ('2871968252812490', 6, 'Ignacio Douglas                                   ', '2025-10-08', 'Y', 0),
  ('2940139362300449', 22, 'Allene Brown                                      ', '2025-12-28', 'Y', 0),
  ('2988091353094312', 4, 'Delbert Parisian                                  ', '2023-12-16', 'Y', 0),
  ('3260763612337560', 10, 'Maybell Mann                                      ', '2023-01-27', 'Y', 0),
  ('3766281984155154', 41, 'Lucinda Dach                                      ', '2023-04-24', 'Y', 0),
  ('3940246016141489', 19, 'Hadley Hamill                                     ', '2025-07-23', 'Y', 0),
  ('3999169246375885', 3, 'Larry Homenick                                    ', '2024-01-10', 'Y', 0),
  ('4011500891777367', 13, 'Mariane Fadel                                     ', '2024-08-04', 'Y', 0),
  ('4385271476627819', 34, 'Faustino Schmidt                                  ', '2025-10-06', 'Y', 0),
  ('4534784102713951', 36, 'Toney Gerhold                                     ', '2024-12-23', 'Y', 0),
  ('4859452612877065', 7, 'Cooper Mayert                                     ', '2024-12-13', 'Y', 0),
  ('5407099850479866', 21, 'Jerrold Maggio                                    ', '2023-01-06', 'Y', 0),
  ('5656830544981216', 46, 'Cindy Cremin                                      ', '2025-06-20', 'Y', 0),
  ('5671184478505844', 18, 'Emile White                                       ', '2023-09-10', 'Y', 0),
  ('5787351228879339', 47, 'Rigoberto Hoeger                                  ', '2025-08-23', 'Y', 0),
  ('5975117516616077', 42, 'Heather Nienow                                    ', '2025-09-19', 'Y', 0),
  ('6009619150674526', 5, 'Treva Schowalter                                  ', '2025-03-09', 'Y', 0),
  ('6349250331648509', 15, 'Aubree Hermann                                    ', '2025-06-09', 'Y', 0),
  ('6503535181795992', 48, 'Lyric Pacocha                                     ', '2025-02-06', 'Y', 0),
  ('6509230362553816', 30, 'Layla Ullrich                                     ', '2024-06-27', 'Y', 0),
  ('6723000463207764', 28, 'Hester Hane                                       ', '2024-05-09', 'Y', 0),
  ('6727055190616014', 16, 'Carroll Bergstrom                                 ', '2024-01-25', 'Y', 0),
  ('6832676047698087', 33, 'Bernice Herman                                    ', '2025-10-07', 'Y', 0),
  ('7026637615032277', 31, 'Lucious O''Connell                                 ', '2025-06-08', 'Y', 0),
  ('7058267261837752', 43, 'Britney Waters                                    ', '2025-08-29', 'Y', 0),
  ('7094142751055551', 32, 'Stephany Fisher                                   ', '2025-05-19', 'Y', 0),
  ('7251508149188883', 29, 'Rickie Daugherty                                  ', '2024-06-04', 'Y', 0),
  ('7379335634661142', 45, 'Dixie Beier                                       ', '2025-07-09', 'Y', 0),
  ('7427684863423209', 11, 'Hayden Pfannerstill                               ', '2025-03-12', 'Y', 0),
  ('7443870988897530', 38, 'Angela Ankunding                                  ', '2023-07-23', 'Y', 0),
  ('8040580410348680', 26, 'Marjory Stracke                                   ', '2024-12-19', 'Y', 0),
  ('8112545834239735', 23, 'Johnson Ruecker                                   ', '2025-03-18', 'Y', 0),
  ('8262593602473076', 49, 'Immanuel Bednar                                   ', '2023-09-17', 'Y', 0),
  ('8517866958206008', 14, 'Chelsea Marks                                     ', '2025-12-11', 'Y', 0),
  ('8931369351894783', 8, 'Kelsie Dicki                                      ', '2024-05-20', 'Y', 0),
  ('9056297931664011', 25, 'Elliott Howell                                    ', '2025-07-10', 'Y', 0),
  ('9349107475869214', 17, 'Sigrid Mann                                       ', '2025-03-01', 'Y', 0),
  ('9501733721429893', 9, 'Melvin Ondricka                                   ', '2024-12-27', 'Y', 0),
  ('9680294154603697', 1, 'Immanuel Kessler                                  ', '2025-05-20', 'Y', 0),
  ('9805583408996588', 40, 'Davon Emmerich                                    ', '2023-10-27', 'Y', 0);


-- ==================================================================
-- BLOCK 6 of 11 - card_cross_reference
-- ==================================================================
--
-- Source fixture : app/data/ASCII/cardxref.txt, 50 rows x 36 bytes = 1,850 B
-- Source layout  : app/cpy/CVACT03Y.cpy, CARD-XREF-RECORD, RECLN 50
--                  XREF-CARD-NUM PIC X(16)  :L5  [1-16]
--                  XREF-CUST-ID  PIC 9(09)  :L6  [17-25]
--                  XREF-ACCT-ID  PIC 9(11)  :L7  [26-36]
--                  FILLER        PIC X(14)  :L8  [37-50]
-- Legacy cluster : AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS, key length 16,
--                  average record length 50; alternate index
--                  CARDXREF.VSAM.AIX on the account identifier at
--                  zero-based AXRKP 25, which is one-based byte 26 -
--                  V2's idx_card_cross_reference_acct_id
-- Row census     : 50 rows, xref_card_num unique on all 50; every
--                  customer and account identifier resolves
--
-- THE 14-BYTE FILLER IS NOT IN THE FIXTURE AT ALL. The cluster record
-- is 50 bytes but the ASCII rows are 36: 1,850 bytes over 50 rows is 37
-- bytes per line, which is 36 data bytes plus the line terminator. The
-- trailing slack of the VSAM slot was simply never written to the
-- fixture, so there is nothing to skip and nothing to model - a
-- different situation from the other eight fixtures, which all carry
-- their FILLER explicitly.
--
-- This block is the card-to-customer-to-account bridge that
-- app/cbl/COACTVWC.cbl walks and that app/cbl/CBTRN02C.cbl:383-385
-- uses for its reject-100 lookup. FK02 and FK03 require the customer
-- and the account respectively, which is why it runs after BLOCK 3 and
-- BLOCK 4.
--
-- The table has no version column - V1 declares none, because the
-- cross reference is never the subject of an update conversation.

INSERT INTO card_cross_reference (
  xref_card_num, xref_cust_id, xref_acct_id
) VALUES
  ('0500024453765740', 50, 50),
  ('0683586198171516', 27, 27),
  ('0923877193247330', 2, 2),
  ('0927987108636232', 20, 20),
  ('0982496213629795', 12, 12),
  ('1014086565224350', 44, 44),
  ('1142167692878931', 37, 37),
  ('1561409106491600', 35, 35),
  ('2745303720002090', 39, 39),
  ('2760836797107565', 24, 24),
  ('2871968252812490', 6, 6),
  ('2940139362300449', 22, 22),
  ('2988091353094312', 4, 4),
  ('3260763612337560', 10, 10),
  ('3766281984155154', 41, 41),
  ('3940246016141489', 19, 19),
  ('3999169246375885', 3, 3),
  ('4011500891777367', 13, 13),
  ('4385271476627819', 34, 34),
  ('4534784102713951', 36, 36),
  ('4859452612877065', 7, 7),
  ('5407099850479866', 21, 21),
  ('5656830544981216', 46, 46),
  ('5671184478505844', 18, 18),
  ('5787351228879339', 47, 47),
  ('5975117516616077', 42, 42),
  ('6009619150674526', 5, 5),
  ('6349250331648509', 15, 15),
  ('6503535181795992', 48, 48),
  ('6509230362553816', 30, 30),
  ('6723000463207764', 28, 28),
  ('6727055190616014', 16, 16),
  ('6832676047698087', 33, 33),
  ('7026637615032277', 31, 31),
  ('7058267261837752', 43, 43),
  ('7094142751055551', 32, 32),
  ('7251508149188883', 29, 29),
  ('7379335634661142', 45, 45),
  ('7427684863423209', 11, 11),
  ('7443870988897530', 38, 38),
  ('8040580410348680', 26, 26),
  ('8112545834239735', 23, 23),
  ('8262593602473076', 49, 49),
  ('8517866958206008', 14, 14),
  ('8931369351894783', 8, 8),
  ('9056297931664011', 25, 25),
  ('9349107475869214', 17, 17),
  ('9501733721429893', 9, 9),
  ('9680294154603697', 1, 1),
  ('9805583408996588', 40, 40);


-- ==================================================================
-- BLOCK 7 of 11 - disclosure_group
-- ==================================================================
--
-- Source fixture : app/data/ASCII/discgrp.txt, 51 rows x 50 bytes = 2,601 B
-- Source layout  : app/cpy/CVTRA02Y.cpy, DIS-GROUP-RECORD, RECLN = 50
--                  DIS-ACCT-GROUP-ID PIC X(10)      :L6  [1-10]
--                  DIS-TRAN-TYPE-CD  PIC X(02)      :L7  [11-12]
--                  DIS-TRAN-CAT-CD   PIC 9(04)      :L8  [13-16]
--                  DIS-INT-RATE      PIC S9(04)V99  :L9  [17-22]
--                  FILLER            PIC X(28)      :L10 [23-50]
-- Legacy cluster : AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS, key length 16,
--                  average record length 50. Batch-only: DISCGRP has
--                  no DEFINE FILE in app/csd/CARDDEMO.CSD
-- Row census     : 51 rows in three groups of exactly 17 each -
--                  'A000000000', 'DEFAULT   ' and 'ZEROAPR   '.
--                  All 51 rate overpunches are the positive-zero
--                  character, so no rate is negative; 7 of the 17
--                  DEFAULT rates are zero
--
-- ALL 17 DEFAULT ROWS ARE REQUIRED. OMITTING ANY ONE ABENDS
-- THE INTEREST JOB. app/cbl/CBACT04C.cbl:415-460 is a two-query
-- fallback and only the second query is fatal:
--
--   :416  first READ DISCGRP-FILE, keyed from the account group
--   :422  IF DISCGRP-STATUS = '00' OR '23' - BOTH accepted, so a miss
--         is not an error
--   :436  IF DISCGRP-STATUS = '23' -> :437 MOVE 'DEFAULT' TO
--         FD-DIS-ACCT-GROUP-ID -> :438 retry. ONLY the group
--         identifier changes; the type and category keep the values
--         :210-212 set from the category-balance key
--   :444  second READ; :446 IF DISCGRP-STATUS = '00' - '00' ONLY -
--         else :458 PERFORM 9999-ABEND-PROGRAM, abend code 999,
--         return code 12
--
-- Because every account seeded in BLOCK 3 carries a BLANK group
-- identifier, the first lookup misses on all fifty and the DEFAULT
-- path is what actually resolves the rate for the entire fixture. One
-- missing DEFAULT row is therefore not a degraded lookup; it is a dead
-- batch job.
--
-- THE GROUP IDENTIFIER IS STORED BLANK-PADDED TO TEN CHARACTERS.
-- 'DEFAULT' followed by three blanks, exactly as bytes [1-10] hold it.
-- V1 declares the column CHAR(10) rather than VARCHAR(10) precisely so
-- that the comparison is trailing-blank insensitive and :437's
-- seven-character literal matches this ten-character key either way.
-- The blanks are data; see FIXED-WIDTH LITERALS AND TRAILING BLANKS.
--
-- A ZERO RATE IS LEGITIMATE AND ALL SEVEN ARE SEEDED.
-- app/cbl/CBACT04C.cbl:214 IF DIS-INT-RATE NOT = 0 merely skips
-- generating an interest transaction; it is not an error path. The mix
-- of zero and non-zero DEFAULT rates is what makes both the
-- fallback-success outcome and the abend outcome reachable from one
-- seed, so removing the zero-rate rows would remove test coverage as
-- well as data.
--
-- The rate is the only NUMERIC(6,2) column in the schema -
-- PIC S9(04)V99 is six bytes, five digits plus the overpunch, not the
-- eleven- or twelve-digit money tiers used elsewhere.
--
-- FK10 requires the type-and-category pair, which is why this block
-- runs after BLOCK 2. LOW - the 28-byte FILLER at :L10 is ZERO-filled,
-- not blank-filled.

INSERT INTO disclosure_group (
  acct_group_id, tran_type_cd, tran_cat_cd, dis_int_rate
) VALUES
  ('A000000000', '01', 1, 15.00),
  ('A000000000', '01', 2, 25.00),
  ('A000000000', '01', 3, 25.00),
  ('A000000000', '01', 4, 25.00),
  ('A000000000', '02', 1, 0.00),
  ('A000000000', '02', 2, 0.00),
  ('A000000000', '02', 3, 0.00),
  ('A000000000', '03', 1, 0.00),
  ('A000000000', '03', 2, 0.00),
  ('A000000000', '03', 3, 0.00),
  ('A000000000', '04', 1, 15.00),
  ('A000000000', '04', 2, 15.00),
  ('A000000000', '04', 3, 15.00),
  ('A000000000', '05', 1, 15.00),
  ('A000000000', '06', 1, 15.00),
  ('A000000000', '06', 2, 15.00),
  ('A000000000', '07', 1, 15.00),
  ('DEFAULT   ', '01', 1, 15.00),
  ('DEFAULT   ', '01', 2, 25.00),
  ('DEFAULT   ', '01', 3, 25.00),
  ('DEFAULT   ', '01', 4, 25.00),
  ('DEFAULT   ', '02', 1, 0.00),
  ('DEFAULT   ', '02', 2, 0.00),
  ('DEFAULT   ', '02', 3, 0.00),
  ('DEFAULT   ', '03', 1, 0.00),
  ('DEFAULT   ', '03', 2, 0.00),
  ('DEFAULT   ', '03', 3, 0.00),
  ('DEFAULT   ', '04', 1, 15.00),
  ('DEFAULT   ', '04', 2, 15.00),
  ('DEFAULT   ', '04', 3, 15.00),
  ('DEFAULT   ', '05', 1, 15.00),
  ('DEFAULT   ', '06', 1, 15.00),
  ('DEFAULT   ', '06', 2, 15.00),
  ('DEFAULT   ', '07', 1, 0.00),
  ('ZEROAPR   ', '01', 1, 0.00),
  ('ZEROAPR   ', '01', 2, 0.00),
  ('ZEROAPR   ', '01', 3, 0.00),
  ('ZEROAPR   ', '01', 4, 0.00),
  ('ZEROAPR   ', '02', 1, 0.00),
  ('ZEROAPR   ', '02', 2, 0.00),
  ('ZEROAPR   ', '02', 3, 0.00),
  ('ZEROAPR   ', '03', 1, 0.00),
  ('ZEROAPR   ', '03', 2, 0.00),
  ('ZEROAPR   ', '03', 3, 0.00),
  ('ZEROAPR   ', '04', 1, 0.00),
  ('ZEROAPR   ', '04', 2, 0.00),
  ('ZEROAPR   ', '04', 3, 0.00),
  ('ZEROAPR   ', '05', 1, 0.00),
  ('ZEROAPR   ', '06', 1, 0.00),
  ('ZEROAPR   ', '06', 2, 0.00),
  ('ZEROAPR   ', '07', 1, 0.00);


-- ==================================================================
-- BLOCK 8 of 11 - transaction_category_balance
-- ==================================================================
--
-- Source fixture : app/data/ASCII/tcatbal.txt, 50 rows x 50 bytes = 2,550 B
-- Source layout  : app/cpy/CVTRA01Y.cpy, TRAN-CAT-BAL-RECORD, RECLN = 50
--                  TRAN-CAT-KEY                     :L5
--                    TRANCAT-ACCT-ID PIC 9(11)      :L6  [1-11]
--                    TRANCAT-TYPE-CD PIC X(02)      :L7  [12-13]
--                    TRANCAT-CD      PIC 9(04)      :L8  [14-17]
--                  TRAN-CAT-BAL      PIC S9(09)V99  :L9  [18-28]
--                  FILLER            PIC X(22)      :L10 [29-50]
-- Legacy cluster : AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS, key length 17,
--                  average record length 50; the 11 + 2 + 4 key
--                  arithmetic of :L6-:L8 corroborates KEYLEN 17 at
--                  app/catlg/LISTCAT.txt:L1371. Batch-only: TCATBALF
--                  has no DEFINE FILE in app/csd/CARDDEMO.CSD
-- Row census     : 50 rows, one per account, every one type 01 and
--                  category 0001 with a balance of exactly 0.00
--
-- THE FIXTURE IS UNIFORM AND THAT UNIFORMITY IS WHAT MAKES THE SEED
-- RESOLVE. Every row is 01/0001, and 01/0001 is one of the 17 DEFAULT
-- combinations in BLOCK 7, so the interest job finds a rate for every
-- account it visits. The one combination BLOCK 2 has that the DEFAULT
-- group does not is 01/0005, 'Interest Amount' - and no row here uses
-- it. A single 01/0005 row would drive
-- app/cbl/CBACT04C.cbl:210-212 to build a key the DEFAULT group cannot
-- satisfy, and :458 would abend the job. The absence is therefore
-- load bearing and is not an oversight in the fixture.
--
-- The balance is PIC S9(09)V99 - eleven bytes, ten digits plus the
-- overpunch - and lands in NUMERIC(11,2), the eleven-digit tier, not
-- the twelve-digit tier the account money columns use. All 50 rows
-- decode to +0.00 from the positive-zero overpunch; see
-- ZONED-DECIMAL OVERPUNCH DECODING for the worked example.
--
-- A zero opening balance is the correct starting state: the posting
-- job at app/cbl/CBTRN02C.cbl:467-501 adds each transaction amount to
-- this balance, and its own upsert semantics - which accept a
-- not-found status as success and create the row - belong to that
-- service and not to this migration.
--
-- Component order in the key is account, type, category, exactly as
-- :L6-:L8 declare it. FK07 requires the account and FK08 the
-- type-and-category pair, which is why this block runs after BLOCK 3
-- and BLOCK 2. LOW - the 22-byte FILLER at :L10 is ZERO-filled.

INSERT INTO transaction_category_balance (
  acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal
) VALUES
  (1, '01', 1, 0.00),
  (2, '01', 1, 0.00),
  (3, '01', 1, 0.00),
  (4, '01', 1, 0.00),
  (5, '01', 1, 0.00),
  (6, '01', 1, 0.00),
  (7, '01', 1, 0.00),
  (8, '01', 1, 0.00),
  (9, '01', 1, 0.00),
  (10, '01', 1, 0.00),
  (11, '01', 1, 0.00),
  (12, '01', 1, 0.00),
  (13, '01', 1, 0.00),
  (14, '01', 1, 0.00),
  (15, '01', 1, 0.00),
  (16, '01', 1, 0.00),
  (17, '01', 1, 0.00),
  (18, '01', 1, 0.00),
  (19, '01', 1, 0.00),
  (20, '01', 1, 0.00),
  (21, '01', 1, 0.00),
  (22, '01', 1, 0.00),
  (23, '01', 1, 0.00),
  (24, '01', 1, 0.00),
  (25, '01', 1, 0.00),
  (26, '01', 1, 0.00),
  (27, '01', 1, 0.00),
  (28, '01', 1, 0.00),
  (29, '01', 1, 0.00),
  (30, '01', 1, 0.00),
  (31, '01', 1, 0.00),
  (32, '01', 1, 0.00),
  (33, '01', 1, 0.00),
  (34, '01', 1, 0.00),
  (35, '01', 1, 0.00),
  (36, '01', 1, 0.00),
  (37, '01', 1, 0.00),
  (38, '01', 1, 0.00),
  (39, '01', 1, 0.00),
  (40, '01', 1, 0.00),
  (41, '01', 1, 0.00),
  (42, '01', 1, 0.00),
  (43, '01', 1, 0.00),
  (44, '01', 1, 0.00),
  (45, '01', 1, 0.00),
  (46, '01', 1, 0.00),
  (47, '01', 1, 0.00),
  (48, '01', 1, 0.00),
  (49, '01', 1, 0.00),
  (50, '01', 1, 0.00);


-- ==================================================================
-- BLOCK 9 of 11 - "transaction" - SEEDED EMPTY, DELIBERATELY
-- ==================================================================
--
-- Source fixture : NONE. There is no transaction fixture in
--                  app/data/ASCII, and none is invented here
-- Source layout  : app/cpy/CVTRA05Y.cpy, TRAN-RECORD, RECLN = 350
-- Legacy cluster : AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS, key length 16,
--                  average record length 350; alternate index
--                  TRANSACT.VSAM.AIX on the processing timestamp at
--                  zero-based AXRKP 304, which is one-based byte 305 -
--                  V2's idx_transaction_proc_ts
-- Row census     : 0 rows. This block contains no INSERT statement
--
-- THE TABLE IS LEFT EMPTY BECAUSE THE CORPUS HAS NOTHING TO PUT IN IT.
-- The nine ASCII fixtures cover accounts, cards, cross references,
-- customers, staged daily transactions, disclosure rates, category
-- balances, categories and types. Not one of them is a posted
-- transaction file. Seeding a fabricated row would put a record into
-- the parity oracle that the legacy system never produced.
--
-- Rows arrive at run time instead, from three producers, each of which
-- writes the identifier itself:
--
--   the posting job, app/cbl/CBTRN02C.cbl:424-465, which moves
--   thirteen fields from a staged record and writes the transaction
--   after updating the category balance and the account
--
--   the online transaction-add conversation,
--   app/cbl/COTRN02C.cbl:444-451, and bill payment,
--   app/cbl/COBIL00C.cbl:212-219, both of which allocate the next
--   identifier by descending-key browse
--
--   the combine job, which bulk loads the interest-calculation
--   sequential output
--
-- This block still occupies position 9 in the load order rather than
-- being omitted, for two reasons. It documents the deliberate absence
-- where a reader looks for it, and it marks the point in the sequence
-- where FK04 to card, FK05 to transaction_type and FK06 to
-- transaction_category would bind if a row were ever added here - after
-- BLOCK 5 and BLOCK 1 and BLOCK 2, never before.
--
-- The empty state is also what makes the first allocated identifier
-- observable. app/cbl/COBIL00C.cbl:487-488 moves zeros into the
-- identifier on an end-of-file response, so the browse of an empty
-- table yields zero and the first identifier allocated is 1. Seeding
-- even one row would hide that boundary case behind fixture data.



-- ==================================================================
-- BLOCK 10 of 11 - daily_transaction
-- ==================================================================
--
-- Source fixture : app/data/ASCII/dailytran.txt, 300 rows x 350 bytes
--                  = 105,300 B. NOTE THE SPELLING: the fixture is
--                  dailytran.txt with the word in full. The mainframe
--                  DD name and dataset are DALYTRAN, which is why the
--                  columns are spelled dalytran_*, but no file in
--                  the repository carries that abbreviated stem
-- Source layout  : app/cpy/CVTRA06Y.cpy, DALYTRAN-RECORD, RECLN = 350
--                  DALYTRAN-ID            PIC X(16)      :L5  [1-16]
--                  DALYTRAN-TYPE-CD       PIC X(02)      :L6  [17-18]
--                  DALYTRAN-CAT-CD        PIC 9(04)      :L7  [19-22]
--                  DALYTRAN-SOURCE        PIC X(10)      :L8  [23-32]
--                  DALYTRAN-DESC          PIC X(100)     :L9  [33-132]
--                  DALYTRAN-AMT           PIC S9(09)V99  :L10 [133-143]
--                  DALYTRAN-MERCHANT-ID   PIC 9(09)      :L11 [144-152]
--                  DALYTRAN-MERCHANT-NAME PIC X(50)      :L12 [153-202]
--                  DALYTRAN-MERCHANT-CITY PIC X(50)      :L13 [203-252]
--                  DALYTRAN-MERCHANT-ZIP  PIC X(10)      :L14 [253-262]
--                  DALYTRAN-CARD-NUM      PIC X(16)      :L15 [263-278]
--                  DALYTRAN-ORIG-TS       PIC X(26)      :L16 [279-304]
--                  DALYTRAN-PROC-TS       PIC X(26)      :L17 [305-330]
--                  FILLER                 PIC X(20)      :L18 [331-350]
-- Legacy dataset : AWS.M2.CARDDEMO.DALYTRAN.PS, a physical sequential
--                  dataset - app/jcl/POSTTRAN.jcl:L31. A LISTCAT
--                  cluster entry is Not available; see NOT AVAILABLE
--                  in the docstring
-- Row census     : 300 rows. 250 are type 01 with source POS TERM and
--                  50 are type 03 with source OPERATOR, and the
--                  correlation is exact in both directions. Category
--                  is 0001 on all 300. The originating timestamp is
--                  one single value on all 300; the processing
--                  timestamp is twenty-six blanks on all 300
--
-- THE 50 NEGATIVE AMOUNTS ARE LOAD BEARING AND MUST NEVER BE
-- NORMALISED. Sign and type are perfectly partitioned in this fixture:
-- all 250 rows whose amount is non-negative are type 01, and all 50 rows
-- whose amount is negative are type 03. The overpunch census of byte
-- 143 accounts for every row exactly - 25 positive-zero, 225 positive
-- digits, 6 negative-zero and 44 negative digits, summing to 300.
--
-- Those 50 rows are the ONLY thing in the seed that exercises the
-- cycle-debit branch. app/cbl/CBTRN02C.cbl:547-552 adds a non-negative
-- amount to ACCT-CURR-CYC-CREDIT and a negative one to
-- ACCT-CURR-CYC-DEBIT, so the debit accumulator legitimately holds
-- negative values - which is precisely why the over-limit test at
-- :403-405 computes credit MINUS debit PLUS amount. Take absolute
-- values here and the else branch never runs, the over-limit
-- arithmetic quietly changes, and no compiler, constraint or type
-- check reports a thing. The parity comparison would fail much later
-- and for a reason that looks nothing like its cause.
--
-- THE FOUR TIMESTAMP-SHAPED COLUMNS ARE CHAR(26) AND NOT ONE OF THEM
-- MAY BECOME TEMPORAL. Three producers write mutually incompatible
-- text into this shape:
--
--   this fixture writes a space-separated value with six fractional
--   digits in bytes [279-304]
--
--   the posting job generates a 26-character value whose final four
--   digits are ALWAYS zeros - millisecond precision followed by four
--   zeros, never nanoseconds. app/cbl/CBTRN02C.cbl:149 documents the
--   layout, :159 declares DB2-FORMAT-TS PIC X(26), :173 DB2-MIL
--   PIC 9(002) and :174 DB2-REST PIC X(04), and :700-701 assembles it
--
--   this fixture writes TWENTY-SIX BLANKS into bytes [305-330] on all
--   300 rows
--
-- No temporal type can hold twenty-six blanks. Retyping the column
-- would force a fabricated value onto every staged row and destroy the
-- distinction between a transaction that has been processed and one
-- that has not - which is the distinction the posting job exists to
-- change. The blanks are seeded literally; see FIXED-WIDTH LITERALS
-- AND TRAILING BLANKS.
--
-- ingest_seq IS SUPPLIED EXPLICITLY, 1 THROUGH 300, IN FIXTURE ORDER.
-- V1 makes it the primary key because the staged input is an unkeyed
-- sequential dataset whose own identifier may legitimately repeat, and
-- declares it plainly NOT NULL with no DEFAULT and no auto-populated
-- property. It models WS-TRANSACTION-COUNT PIC 9(09) at
-- app/cbl/CBTRN02C.cbl:185, which :206 increments once per accepted
-- READ, so the value is the record ordinal and the read order is the
-- file order. Supplying it here rather than generating it is what keeps
-- NO DATABASE-SIDE KEY ALLOCATION true for this table as well.
--
-- THE TABLE HAS ZERO FOREIGN KEYS, BY DESIGN. A staged row must be
-- loadable while referencing a card that does not exist, which is
-- reject 100 at app/cbl/CBTRN02C.cbl:383-385, and an account that does
-- not exist, which is reject 101 at :394-397. A constraint would make
-- both rejects unstageable and would delete two of the five reject
-- codes from the reachable set. Its position at 10 in the load order is
-- therefore free, and is chosen only to match V1 table order.
--
-- The amount is PIC S9(09)V99 in NUMERIC(11,2) - the eleven-digit
-- tier. The 20-byte FILLER at :L18 is blank-filled and unmodelled. The
-- table has no version column: staged rows are read and consumed, never
-- updated in place.

INSERT INTO daily_transaction (
  ingest_seq, dalytran_id, dalytran_type_cd, dalytran_cat_cd,
  dalytran_source, dalytran_desc, dalytran_amt, dalytran_merchant_id,
  dalytran_merchant_name, dalytran_merchant_city,
  dalytran_merchant_zip, dalytran_card_num, dalytran_orig_ts,
  dalytran_proc_ts
) VALUES
  (1, '0000000000683580', '01', 1, 'POS TERM  ', 'Purchase at Abshire-Lowe                                                                            ', 504.77, 800000000, 'Abshire-Lowe                                      ', 'North Enoshaven                                   ', '72112     ', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (2, '0000000001774260', '03', 1, 'OPERATOR  ', 'Return item at Nitzsche, Nicolas and Lowe                                                           ', -919.00, 800000000, 'Nitzsche, Nicolas and Lowe                        ', 'Fidelshire                                        ', '53378     ', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (3, '0000000006292564', '01', 1, 'POS TERM  ', 'Purchase at Ernser, Roob and Gleason                                                                ', 67.88, 800000000, 'Ernser, Roob and Gleason                          ', 'North Makenziemouth                               ', '78487-7965', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (4, '0000000009101861', '01', 1, 'POS TERM  ', 'Purchase at Guann LLC                                                                               ', 281.77, 800000000, 'Guann LLC                                         ', 'South Lynn                                        ', '51508-9166', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (5, '0000000010142252', '01', 1, 'POS TERM  ', 'Purchase at Kertzmann-Schoen                                                                        ', 454.66, 800000000, 'Kertzmann-Schoen                                  ', 'East Eulahstad                                    ', '98754-1089', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (6, '0000000010229018', '01', 1, 'POS TERM  ', 'Purchase at Gislason-Medhurst                                                                       ', 849.99, 800000000, 'Gislason-Medhurst                                 ', 'Colleenburgh                                      ', '23712-2080', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (7, '0000000016259484', '03', 1, 'OPERATOR  ', 'Return item at Sipes Inc                                                                            ', -56.77, 800000000, 'Sipes Inc                                         ', 'Emilioside                                        ', '93329     ', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (8, '0000000017874199', '01', 1, 'POS TERM  ', 'Purchase at Legros Group                                                                            ', 373.66, 800000000, 'Legros Group                                      ', 'Carmeloborough                                    ', '34849-5127', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (9, '0000000019065428', '03', 1, 'OPERATOR  ', 'Return item at Turcotte Group                                                                       ', -535.88, 800000000, 'Turcotte Group                                    ', 'Andrewfurt                                        ', '41346-3789', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (10, '0000000021711604', '01', 1, 'POS TERM  ', 'Purchase at Gleason, Shanahan and Reynolds                                                          ', 416.11, 800000000, 'Gleason, Shanahan and Reynolds                    ', 'Myrticeport                                       ', '21768-0823', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (11, '0000000025430891', '01', 1, 'POS TERM  ', 'Purchase at Beatty-Hessel                                                                           ', 94.33, 800000000, 'Beatty-Hessel                                     ', 'Simonisport                                       ', '52595     ', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
  (12, '0000000028097268', '01', 1, 'POS TERM  ', 'Purchase at Wolf, Cruickshank and Bode                                                              ', 250.22, 800000000, 'Wolf, Cruickshank and Bode                        ', 'Fritzchester                                      ', '20195-5156', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (13, '0000000030755266', '01', 1, 'POS TERM  ', 'Purchase at Ratke LLC                                                                               ', 829.55, 800000000, 'Ratke LLC                                         ', 'Brendenfort                                       ', '35302-6495', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (14, '0000000032979555', '01', 1, 'POS TERM  ', 'Purchase at Treutel-Leffler                                                                         ', 29.44, 800000000, 'Treutel-Leffler                                   ', 'New Nicolette                                     ', '65014-0045', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (15, '0000000033688127', '01', 1, 'POS TERM  ', 'Purchase at Schinner-Steuber                                                                        ', 958.99, 800000000, 'Schinner-Steuber                                  ', 'Schmittchester                                    ', '50777-5535', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (16, '0000000040455859', '01', 1, 'POS TERM  ', 'Purchase at Brekke, Bradtke and Weimann                                                             ', 715.44, 800000000, 'Brekke, Bradtke and Weimann                       ', 'Veummouth                                         ', '18481-5013', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (17, '0000000043636099', '03', 1, 'OPERATOR  ', 'Return item at Nader-Bayer                                                                          ', -945.66, 800000000, 'Nader-Bayer                                       ', 'Goyetteville                                      ', '35324     ', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (18, '0000000051205286', '01', 1, 'POS TERM  ', 'Purchase at Goodwin, Von and Krajcik                                                                ', 649.33, 800000000, 'Goodwin, Von and Krajcik                          ', 'Ericmouth                                         ', '03874     ', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (19, '0000000054288996', '01', 1, 'POS TERM  ', 'Purchase at Cremin and Sons                                                                         ', 502.66, 800000000, 'Cremin and Sons                                   ', 'Bartonside                                        ', '08677     ', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (20, '0000000054727064', '01', 1, 'POS TERM  ', 'Purchase at McDermott, Lockman and Weimann                                                          ', 303.11, 800000000, 'McDermott, Lockman and Weimann                    ', 'West Nedra                                        ', '05293     ', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (21, '0000000058866561', '01', 1, 'POS TERM  ', 'Purchase at Blick-Rippin                                                                            ', 183.88, 800000000, 'Blick-Rippin                                      ', 'East Julien                                       ', '87157     ', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (22, '0000000060921254', '01', 1, 'POS TERM  ', 'Purchase at Kihn-Quigley                                                                            ', 779.33, 800000000, 'Kihn-Quigley                                      ', 'New Katrine                                       ', '42756-0584', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (23, '0000000061394789', '03', 1, 'OPERATOR  ', 'Return item at Heaney-Raynor                                                                        ', -70.99, 800000000, 'Heaney-Raynor                                     ', 'North Daisy                                       ', '28696     ', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (24, '0000000070754800', '01', 1, 'POS TERM  ', 'Purchase at Blick, Kris and Gerlach                                                                 ', 355.11, 800000000, 'Blick, Kris and Gerlach                           ', 'Lake Shawnabury                                   ', '65183-0963', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (25, '0000000072220498', '01', 1, 'POS TERM  ', 'Purchase at Graham LLC                                                                              ', 660.11, 800000000, 'Graham LLC                                        ', 'Ozellaside                                        ', '89313-0747', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (26, '0000000084515950', '01', 1, 'POS TERM  ', 'Purchase at Bradtke Group                                                                           ', 325.00, 800000000, 'Bradtke Group                                     ', 'Gerardland                                        ', '63873     ', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (27, '0000000085824369', '01', 1, 'POS TERM  ', 'Purchase at Pollich-Mosciski                                                                        ', 999.77, 800000000, 'Pollich-Mosciski                                  ', 'Georgettemouth                                    ', '85890     ', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (28, '0000000095706092', '01', 1, 'POS TERM  ', 'Purchase at Swift, Wolf and Goldner                                                                 ', 482.44, 800000000, 'Swift, Wolf and Goldner                           ', 'Keeblerborough                                    ', '31923-4503', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (29, '0000000099965527', '01', 1, 'POS TERM  ', 'Purchase at Jaskolski-Rolfson                                                                       ', 555.22, 800000000, 'Jaskolski-Rolfson                                 ', 'Lake Arjuntown                                    ', '90924-2951', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (30, '0000000100915314', '01', 1, 'POS TERM  ', 'Purchase at Gislason and Daughters                                                                  ', 356.22, 800000000, 'Gislason and Daughters                            ', 'Torphyville                                       ', '09737     ', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (31, '0000000107748365', '01', 1, 'POS TERM  ', 'Purchase at Waelchi and Daughters                                                                   ', 274.00, 800000000, 'Waelchi and Daughters                             ', 'Dickensborough                                    ', '86052-1154', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (32, '0000000108402349', '01', 1, 'POS TERM  ', 'Purchase at Lynch-Bode                                                                              ', 633.00, 800000000, 'Lynch-Bode                                        ', 'New Cieloberg                                     ', '85766     ', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (33, '0000000109340521', '01', 1, 'POS TERM  ', 'Purchase at Runte and Sons                                                                          ', 840.55, 800000000, 'Runte and Sons                                    ', 'Lake Chesleyfurt                                  ', '94215     ', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (34, '0000000109506921', '01', 1, 'POS TERM  ', 'Purchase at Will, Frami and Lynch                                                                   ', 769.55, 800000000, 'Will, Frami and Lynch                             ', 'South Cadefort                                    ', '47040-3550', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (35, '0000000111054243', '01', 1, 'POS TERM  ', 'Purchase at Pollich and Sons                                                                        ', 948.44, 800000000, 'Pollich and Sons                                  ', 'West Burdetteburgh                                ', '51061-7710', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (36, '0000000115716061', '01', 1, 'POS TERM  ', 'Purchase at Bednar, Marvin and Kozey                                                                ', 401.22, 800000000, 'Bednar, Marvin and Kozey                          ', 'Port Marisolshire                                 ', '89976-0867', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (37, '0000000130111733', '01', 1, 'POS TERM  ', 'Purchase at Rogahn Group                                                                            ', 777.33, 800000000, 'Rogahn Group                                      ', 'Keltonton                                         ', '18842     ', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (38, '0000000132831571', '03', 1, 'OPERATOR  ', 'Return item at Boehm-Sanford                                                                        ', -215.33, 800000000, 'Boehm-Sanford                                     ', 'Winifredville                                     ', '93238-7169', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (39, '0000000137879630', '01', 1, 'POS TERM  ', 'Purchase at Wiza-Langworth                                                                          ', 46.66, 800000000, 'Wiza-Langworth                                    ', 'South Jayson                                      ', '83135     ', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (40, '0000000139910093', '01', 1, 'POS TERM  ', 'Purchase at Harris, Johnston and Harris                                                             ', 570.66, 800000000, 'Harris, Johnston and Harris                       ', 'New Aurelia                                       ', '81068     ', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (41, '0000000142315472', '01', 1, 'POS TERM  ', 'Purchase at Kutch-Farrell                                                                           ', 843.66, 800000000, 'Kutch-Farrell                                     ', 'Letatown                                          ', '39869-9537', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (42, '0000000143386237', '01', 1, 'POS TERM  ', 'Purchase at Blanda, Nienow and Hilpert                                                              ', 559.88, 800000000, 'Blanda, Nienow and Hilpert                        ', 'Leuschkestad                                      ', '24074-5513', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (43, '0000000148803688', '01', 1, 'POS TERM  ', 'Purchase at Crist Inc                                                                               ', 203.44, 800000000, 'Crist Inc                                         ', 'Spencerchester                                    ', '18577     ', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (44, '0000000152467982', '01', 1, 'POS TERM  ', 'Purchase at Kreiger and Sons                                                                        ', 168.33, 800000000, 'Kreiger and Sons                                  ', 'North Lue                                         ', '30616-5176', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (45, '0000000160469204', '01', 1, 'POS TERM  ', 'Purchase at Greenfelder-Larson                                                                      ', 864.44, 800000000, 'Greenfelder-Larson                                ', 'New Mertie                                        ', '06860     ', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (46, '0000000166444519', '01', 1, 'POS TERM  ', 'Purchase at Wyman, Feest and Moen                                                                   ', 183.22, 800000000, 'Wyman, Feest and Moen                             ', 'Haleyborough                                      ', '83262-3068', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (47, '0000000169879332', '01', 1, 'POS TERM  ', 'Purchase at Buckridge, Fisher and Schroeder                                                         ', 256.00, 800000000, 'Buckridge, Fisher and Schroeder                   ', 'Port Kiraport                                     ', '29568     ', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (48, '0000000173069364', '01', 1, 'POS TERM  ', 'Purchase at Runte-Schmidt                                                                           ', 985.33, 800000000, 'Runte-Schmidt                                     ', 'Krajcikshire                                      ', '03491-5716', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (49, '0000000174748684', '01', 1, 'POS TERM  ', 'Purchase at McLaughlin-Reichel                                                                      ', 19.99, 800000000, 'McLaughlin-Reichel                                ', 'Rippinville                                       ', '32264-6952', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (50, '0000000183226769', '01', 1, 'POS TERM  ', 'Purchase at Conroy and Daughters                                                                    ', 907.55, 800000000, 'Conroy and Daughters                              ', 'Greenholtborough                                  ', '24059-8704', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (51, '0000000184933166', '01', 1, 'POS TERM  ', 'Purchase at Walker LLC                                                                              ', 989.77, 800000000, 'Walker LLC                                        ', 'East Tavares                                      ', '25508     ', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (52, '0000000187573156', '01', 1, 'POS TERM  ', 'Purchase at Cruickshank and Daughters                                                               ', 579.77, 800000000, 'Cruickshank and Daughters                         ', 'Bobbieberg                                        ', '45382     ', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (53, '0000000189414937', '03', 1, 'OPERATOR  ', 'Return item at Treutel-Douglas                                                                      ', -358.44, 800000000, 'Treutel-Douglas                                   ', 'Port Mittiestad                                   ', '12880-0185', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (54, '0000000191360674', '01', 1, 'POS TERM  ', 'Purchase at Wyman, Breitenberg and Gusikowski                                                       ', 848.33, 800000000, 'Wyman, Breitenberg and Gusikowski                 ', 'Rosettaberg                                       ', '51594-3147', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (55, '0000000192039153', '03', 1, 'OPERATOR  ', 'Return item at Smith-Upton                                                                          ', -243.00, 800000000, 'Smith-Upton                                       ', 'Vandervortburgh                                   ', '15012-1007', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (56, '0000000194189303', '01', 1, 'POS TERM  ', 'Purchase at Dickinson and Sons                                                                      ', 59.33, 800000000, 'Dickinson and Sons                                ', 'Port Hunter                                       ', '93555-8843', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (57, '0000000196728331', '03', 1, 'OPERATOR  ', 'Return item at Hane and Sons                                                                        ', -744.77, 800000000, 'Hane and Sons                                     ', 'Erdmanberg                                        ', '80151     ', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (58, '0000000198494663', '01', 1, 'POS TERM  ', 'Purchase at Dietrich-Ledner                                                                         ', 385.77, 800000000, 'Dietrich-Ledner                                   ', 'Lilastad                                          ', '79844-4976', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (59, '0000000201032783', '01', 1, 'POS TERM  ', 'Purchase at Heidenreich-Feil                                                                        ', 326.44, 800000000, 'Heidenreich-Feil                                  ', 'North Christybury                                 ', '32759     ', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (60, '0000000202217428', '01', 1, 'POS TERM  ', 'Purchase at Simonis and Sons                                                                        ', 299.33, 800000000, 'Simonis and Sons                                  ', 'Joanieview                                        ', '81755-5489', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (61, '0000000202886897', '01', 1, 'POS TERM  ', 'Purchase at Ryan-Homenick                                                                           ', 175.88, 800000000, 'Ryan-Homenick                                     ', 'North Franciscaside                               ', '14400     ', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (62, '0000000203305494', '01', 1, 'POS TERM  ', 'Purchase at Kunze, Koss and Erdman                                                                  ', 479.22, 800000000, 'Kunze, Koss and Erdman                            ', 'West Lempi                                        ', '60316-4620', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (63, '0000000204143988', '01', 1, 'POS TERM  ', 'Purchase at Buckridge-Stiedemann                                                                    ', 76.33, 800000000, 'Buckridge-Stiedemann                              ', 'Kuvalishaven                                      ', '15327     ', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (64, '0000000211219588', '01', 1, 'POS TERM  ', 'Purchase at Cummings, Nitzsche and Bosco                                                            ', 553.00, 800000000, 'Cummings, Nitzsche and Bosco                      ', 'Cordeliamouth                                     ', '55811     ', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (65, '0000000218186931', '03', 1, 'OPERATOR  ', 'Return item at Reichert and Daughters                                                               ', -835.11, 800000000, 'Reichert and Daughters                            ', 'Amaliafort                                        ', '31060-9178', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (66, '0000000220001505', '01', 1, 'POS TERM  ', 'Purchase at Schmeler Group                                                                          ', 929.77, 800000000, 'Schmeler Group                                    ', 'New Kennediburgh                                  ', '39202-2380', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (67, '0000000220745261', '01', 1, 'POS TERM  ', 'Purchase at Swaniawski, Torphy and Bruen                                                            ', 495.66, 800000000, 'Swaniawski, Torphy and Bruen                      ', 'East Devenborough                                 ', '70124     ', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (68, '0000000223138231', '01', 1, 'POS TERM  ', 'Purchase at Prohaska, Grant and Hirthe                                                              ', 851.33, 800000000, 'Prohaska, Grant and Hirthe                        ', 'Kennyview                                         ', '79664     ', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (69, '0000000224060323', '01', 1, 'POS TERM  ', 'Purchase at Kunze and Sons                                                                          ', 343.77, 800000000, 'Kunze and Sons                                    ', 'Port Genoveva                                     ', '96001     ', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (70, '0000000226849749', '03', 1, 'OPERATOR  ', 'Return item at Smith, Cummings and Medhurst                                                         ', -428.99, 800000000, 'Smith, Cummings and Medhurst                      ', 'South Adriannaland                                ', '54229-7459', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (71, '0000000232640164', '01', 1, 'POS TERM  ', 'Purchase at Blick LLC                                                                               ', 160.99, 800000000, 'Blick LLC                                         ', 'East Ali                                          ', '23808     ', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (72, '0000000238329981', '03', 1, 'OPERATOR  ', 'Return item at Effertz, Ortiz and Gusikowski                                                        ', -930.33, 800000000, 'Effertz, Ortiz and Gusikowski                     ', 'Harrisonfurt                                      ', '89418-4999', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (73, '0000000241121967', '03', 1, 'OPERATOR  ', 'Return item at Kulas and Daughters                                                                  ', -445.55, 800000000, 'Kulas and Daughters                               ', 'Billybury                                         ', '68626-4996', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (74, '0000000246307558', '01', 1, 'POS TERM  ', 'Purchase at Jacobi and Sons                                                                         ', 816.77, 800000000, 'Jacobi and Sons                                   ', 'Lake Hoseaside                                    ', '45822     ', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (75, '0000000246312084', '01', 1, 'POS TERM  ', 'Purchase at Weimann-Graham                                                                          ', 848.77, 800000000, 'Weimann-Graham                                    ', 'Thielburgh                                        ', '41063-5412', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (76, '0000000246549911', '01', 1, 'POS TERM  ', 'Purchase at Kulas, Reichert and O''Conner                                                            ', 339.55, 800000000, 'Kulas, Reichert and O''Conner                      ', 'Travishaven                                       ', '59094-4283', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (77, '0000000248557079', '01', 1, 'POS TERM  ', 'Purchase at Strosin-Fadel                                                                           ', 905.00, 800000000, 'Strosin-Fadel                                     ', 'Krajcikmouth                                      ', '25843     ', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (78, '0000000250062442', '01', 1, 'POS TERM  ', 'Purchase at Willms, Abshire and Daugherty                                                           ', 346.99, 800000000, 'Willms, Abshire and Daugherty                     ', 'Shieldston                                        ', '97909-1233', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (79, '0000000252891459', '01', 1, 'POS TERM  ', 'Purchase at Nitzsche, Feil and Bergstrom                                                            ', 944.99, 800000000, 'Nitzsche, Feil and Bergstrom                      ', 'Carriebury                                        ', '40432-2594', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (80, '0000000253579636', '01', 1, 'POS TERM  ', 'Purchase at D''Amore-Batz                                                                            ', 257.55, 800000000, 'D''Amore-Batz                                      ', 'Collierview                                       ', '97716     ', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (81, '0000000253685514', '01', 1, 'POS TERM  ', 'Purchase at Von-Schmeler                                                                            ', 496.33, 800000000, 'Von-Schmeler                                      ', 'Lake Maximillian                                  ', '85711     ', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (82, '0000000263663553', '01', 1, 'POS TERM  ', 'Purchase at Wehner, Turcotte and Nikolaus                                                           ', 526.11, 800000000, 'Wehner, Turcotte and Nikolaus                     ', 'Fritschfort                                       ', '75845-0688', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (83, '0000000263926805', '01', 1, 'POS TERM  ', 'Purchase at Batz-Gaylord                                                                            ', 210.33, 800000000, 'Batz-Gaylord                                      ', 'Beahanhaven                                       ', '00022     ', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (84, '0000000265935610', '01', 1, 'POS TERM  ', 'Purchase at Morar-Cartwright                                                                        ', 461.99, 800000000, 'Morar-Cartwright                                  ', 'Lake Sanfordmouth                                 ', '93080-1107', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (85, '0000000272698228', '01', 1, 'POS TERM  ', 'Purchase at Schultz-Morissette                                                                      ', 457.55, 800000000, 'Schultz-Morissette                                ', 'East Jakaylashire                                 ', '84498-8609', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
  (86, '0000000273137276', '01', 1, 'POS TERM  ', 'Purchase at Lowe-Blick                                                                              ', 764.22, 800000000, 'Lowe-Blick                                        ', 'Boyerchester                                      ', '15468-8924', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (87, '0000000274427056', '03', 1, 'OPERATOR  ', 'Return item at Gibson-Maggio                                                                        ', -763.00, 800000000, 'Gibson-Maggio                                     ', 'Port Genevieveberg                                ', '92794-6457', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (88, '0000000274596018', '01', 1, 'POS TERM  ', 'Purchase at Renner LLC                                                                              ', 40.00, 800000000, 'Renner LLC                                        ', 'Sengerport                                        ', '73531     ', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (89, '0000000277916619', '01', 1, 'POS TERM  ', 'Purchase at Champlin and Sons                                                                       ', 996.88, 800000000, 'Champlin and Sons                                 ', 'North Dale                                        ', '85808-4638', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (90, '0000000283702177', '01', 1, 'POS TERM  ', 'Purchase at Bradtke-Considine                                                                       ', 89.99, 800000000, 'Bradtke-Considine                                 ', 'Geovannyville                                     ', '39499-2169', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (91, '0000000292939458', '01', 1, 'POS TERM  ', 'Purchase at O''Hara, Ledner and Runte                                                                ', 49.55, 800000000, 'O''Hara, Ledner and Runte                          ', 'Port Fleta                                        ', '42362-4038', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (92, '0000000298764221', '01', 1, 'POS TERM  ', 'Purchase at Zboncak, Kohler and Ziemann                                                             ', 706.11, 800000000, 'Zboncak, Kohler and Ziemann                       ', 'Gilesmouth                                        ', '93998-8946', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (93, '0000000298806396', '01', 1, 'POS TERM  ', 'Purchase at Powlowski-Greenholt                                                                     ', 936.22, 800000000, 'Powlowski-Greenholt                               ', 'Naderfort                                         ', '19262-4706', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (94, '0000000307523903', '03', 1, 'OPERATOR  ', 'Return item at Hamill, Sawayn and O''Conner                                                          ', -585.44, 800000000, 'Hamill, Sawayn and O''Conner                       ', 'Vonview                                           ', '83262     ', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (95, '0000000312054308', '01', 1, 'POS TERM  ', 'Purchase at Bogan LLC                                                                               ', 717.00, 800000000, 'Bogan LLC                                         ', 'Josiahhaven                                       ', '59167     ', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (96, '0000000312439873', '01', 1, 'POS TERM  ', 'Purchase at Rowe and Daughters                                                                      ', 745.33, 800000000, 'Rowe and Daughters                                ', 'New Adriannamouth                                 ', '89172-7486', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (97, '0000000312675172', '01', 1, 'POS TERM  ', 'Purchase at Hermiston Inc                                                                           ', 728.77, 800000000, 'Hermiston Inc                                     ', 'Port Bennyburgh                                   ', '34656     ', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (98, '0000000313527007', '01', 1, 'POS TERM  ', 'Purchase at Ebert-Grimes                                                                            ', 948.22, 800000000, 'Ebert-Grimes                                      ', 'New Kelleyton                                     ', '51492-3272', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (99, '0000000325686503', '01', 1, 'POS TERM  ', 'Purchase at Cruickshank-Marvin                                                                      ', 569.99, 800000000, 'Cruickshank-Marvin                                ', 'Russelshire                                       ', '66858     ', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (100, '0000000328781772', '01', 1, 'POS TERM  ', 'Purchase at Hayes and Daughters                                                                     ', 859.44, 800000000, 'Hayes and Daughters                               ', 'Beahanville                                       ', '08781     ', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (101, '0000000329446511', '01', 1, 'POS TERM  ', 'Purchase at Ernser, Ward and Lehner                                                                 ', 667.55, 800000000, 'Ernser, Ward and Lehner                           ', 'Lake Rita                                         ', '78140-9470', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (102, '0000000329724245', '01', 1, 'POS TERM  ', 'Purchase at Reichel Group                                                                           ', 14.00, 800000000, 'Reichel Group                                     ', 'Port Romanfort                                    ', '95843     ', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (103, '0000000338128146', '03', 1, 'OPERATOR  ', 'Return item at Terry-Mertz                                                                          ', -742.99, 800000000, 'Terry-Mertz                                       ', 'Enidview                                          ', '31259     ', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (104, '0000000341155503', '01', 1, 'POS TERM  ', 'Purchase at Parker-Erdman                                                                           ', 990.88, 800000000, 'Parker-Erdman                                     ', 'New Khalid                                        ', '72240     ', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (105, '0000000341634875', '01', 1, 'POS TERM  ', 'Purchase at Medhurst, Bogisich and Schmeler                                                         ', 997.88, 800000000, 'Medhurst, Bogisich and Schmeler                   ', 'Dickensport                                       ', '29931-9313', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (106, '0000000357518499', '03', 1, 'OPERATOR  ', 'Return item at Willms-Beier                                                                         ', -852.33, 800000000, 'Willms-Beier                                      ', 'Nathanfurt                                        ', '70715-7333', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (107, '0000000358543876', '01', 1, 'POS TERM  ', 'Purchase at Zulauf Group                                                                            ', 552.77, 800000000, 'Zulauf Group                                      ', 'Schowalterland                                    ', '26981     ', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (108, '0000000361866674', '01', 1, 'POS TERM  ', 'Purchase at Mayer and Daughters                                                                     ', 446.00, 800000000, 'Mayer and Daughters                               ', 'North Keeley                                      ', '40519     ', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (109, '0000000366513257', '01', 1, 'POS TERM  ', 'Purchase at Klein, Buckridge and Johnson                                                            ', 965.55, 800000000, 'Klein, Buckridge and Johnson                      ', 'Shieldsbury                                       ', '79412-9462', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (110, '0000000373973344', '01', 1, 'POS TERM  ', 'Purchase at Wehner LLC                                                                              ', 958.33, 800000000, 'Wehner LLC                                        ', 'South Harmonmouth                                 ', '92575     ', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (111, '0000000375247552', '01', 1, 'POS TERM  ', 'Purchase at Guann Group                                                                             ', 115.11, 800000000, 'Guann Group                                       ', 'Port Grant                                        ', '76360-6457', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (112, '0000000378710702', '03', 1, 'OPERATOR  ', 'Return item at Wilderman, Koepp and Ledner                                                          ', -344.77, 800000000, 'Wilderman, Koepp and Ledner                       ', 'Wuckerthaven                                      ', '29965     ', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (113, '0000000379084859', '03', 1, 'OPERATOR  ', 'Return item at Lebsack-Treutel                                                                      ', -75.22, 800000000, 'Lebsack-Treutel                                   ', 'Kennedyside                                       ', '66077-1463', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (114, '0000000380632461', '01', 1, 'POS TERM  ', 'Purchase at Beahan, Little and Sanford                                                              ', 428.33, 800000000, 'Beahan, Little and Sanford                        ', 'East Ebonyville                                   ', '17826-0999', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (115, '0000000382018782', '01', 1, 'POS TERM  ', 'Purchase at Hackett-Kautzer                                                                         ', 884.99, 800000000, 'Hackett-Kautzer                                   ', 'East Cristopherfurt                               ', '10894-9358', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (116, '0000000382291356', '01', 1, 'POS TERM  ', 'Purchase at Jacobi and Daughters                                                                    ', 860.77, 800000000, 'Jacobi and Daughters                              ', 'Carterland                                        ', '70592-5640', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (117, '0000000392772234', '01', 1, 'POS TERM  ', 'Purchase at Williamson LLC                                                                          ', 351.66, 800000000, 'Williamson LLC                                    ', 'Runteville                                        ', '18400-6845', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (118, '0000000397282953', '03', 1, 'OPERATOR  ', 'Return item at Ankunding Group                                                                      ', -396.22, 800000000, 'Ankunding Group                                   ', 'Adrainton                                         ', '59712-6451', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (119, '0000000399296572', '01', 1, 'POS TERM  ', 'Purchase at McGlynn Inc                                                                             ', 254.77, 800000000, 'McGlynn Inc                                       ', 'New Berenice                                      ', '76608     ', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (120, '0000000400022505', '01', 1, 'POS TERM  ', 'Purchase at Klocko LLC                                                                              ', 385.44, 800000000, 'Klocko LLC                                        ', 'Taniatown                                         ', '25662     ', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (121, '0000000400762013', '01', 1, 'POS TERM  ', 'Purchase at Will-Murazik                                                                            ', 617.00, 800000000, 'Will-Murazik                                      ', 'New Estefania                                     ', '36903-3350', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (122, '0000000402032668', '03', 1, 'OPERATOR  ', 'Return item at Torphy, Collins and Witting                                                          ', -756.66, 800000000, 'Torphy, Collins and Witting                       ', 'Lake Augusttown                                   ', '06644     ', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (123, '0000000407178785', '01', 1, 'POS TERM  ', 'Purchase at Cole-Wyman                                                                              ', 949.77, 800000000, 'Cole-Wyman                                        ', 'Olenmouth                                         ', '47296     ', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (124, '0000000407637739', '03', 1, 'OPERATOR  ', 'Return item at Price LLC                                                                            ', -501.44, 800000000, 'Price LLC                                         ', 'New Annabell                                      ', '91216     ', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (125, '0000000412515011', '01', 1, 'POS TERM  ', 'Purchase at Wehner-Ebert                                                                            ', 214.77, 800000000, 'Wehner-Ebert                                      ', 'Ednaville                                         ', '70885     ', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (126, '0000000415671623', '01', 1, 'POS TERM  ', 'Purchase at Kshlerin, Schulist and Oberbrunner                                                      ', 0.99, 800000000, 'Kshlerin, Schulist and Oberbrunner                ', 'Dallinmouth                                       ', '19897-9097', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (127, '0000000416848414', '01', 1, 'POS TERM  ', 'Purchase at Medhurst-Feeney                                                                         ', 995.22, 800000000, 'Medhurst-Feeney                                   ', 'New Terrance                                      ', '87377     ', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (128, '0000000420768809', '01', 1, 'POS TERM  ', 'Purchase at Bartell-Rempel                                                                          ', 674.99, 800000000, 'Bartell-Rempel                                    ', 'Alexanderport                                     ', '08405     ', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (129, '0000000424689871', '01', 1, 'POS TERM  ', 'Purchase at Rempel and Daughters                                                                    ', 648.11, 800000000, 'Rempel and Daughters                              ', 'Aliyachester                                      ', '08642     ', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (130, '0000000429557611', '01', 1, 'POS TERM  ', 'Purchase at Pagac, Funk and Kiehn                                                                   ', 545.66, 800000000, 'Pagac, Funk and Kiehn                             ', 'Krajcikshire                                      ', '32088-0940', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (131, '0000000432231260', '03', 1, 'OPERATOR  ', 'Return item at Schuster-Bashirian                                                                   ', -962.77, 800000000, 'Schuster-Bashirian                                ', 'New Gageton                                       ', '47405-2362', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (132, '0000000433607101', '01', 1, 'POS TERM  ', 'Purchase at VonRueden Inc                                                                           ', 851.22, 800000000, 'VonRueden Inc                                     ', 'Lake Gailland                                     ', '82720-3055', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (133, '0000000434343718', '01', 1, 'POS TERM  ', 'Purchase at Vandervort-McClure                                                                      ', 793.22, 800000000, 'Vandervort-McClure                                ', 'Kaydenborough                                     ', '73288-4151', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (134, '0000000435144487', '01', 1, 'POS TERM  ', 'Purchase at Pacocha, Goyette and Leuschke                                                           ', 408.88, 800000000, 'Pacocha, Goyette and Leuschke                     ', 'Gutkowskiport                                     ', '64919-4953', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (135, '0000000445507761', '01', 1, 'POS TERM  ', 'Purchase at Rice, Luettgen and Aufderhar                                                            ', 129.33, 800000000, 'Rice, Luettgen and Aufderhar                      ', 'O''Reillychester                                   ', '75844     ', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (136, '0000000446945803', '01', 1, 'POS TERM  ', 'Purchase at Yost and Daughters                                                                      ', 241.22, 800000000, 'Yost and Daughters                                ', 'Lake Manley                                       ', '52896-0448', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (137, '0000000450622695', '01', 1, 'POS TERM  ', 'Purchase at Schmitt, Kohler and Skiles                                                              ', 28.33, 800000000, 'Schmitt, Kohler and Skiles                        ', 'Farrellhaven                                      ', '00796     ', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (138, '0000000458331136', '01', 1, 'POS TERM  ', 'Purchase at Howe, Rippin and Watsica                                                                ', 437.11, 800000000, 'Howe, Rippin and Watsica                          ', 'West Kianachester                                 ', '75201     ', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (139, '0000000462765346', '01', 1, 'POS TERM  ', 'Purchase at Marquardt, Ward and Brekke                                                              ', 587.11, 800000000, 'Marquardt, Ward and Brekke                        ', 'Lake Nataliastad                                  ', '61706-7915', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (140, '0000000474475283', '01', 1, 'POS TERM  ', 'Purchase at Pouros Inc                                                                              ', 174.66, 800000000, 'Pouros Inc                                        ', 'East Jerald                                       ', '35802     ', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (141, '0000000475609951', '01', 1, 'POS TERM  ', 'Purchase at Predovic-Deckow                                                                         ', 913.88, 800000000, 'Predovic-Deckow                                   ', 'West Gunnar                                       ', '46493-9443', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (142, '0000000475746885', '01', 1, 'POS TERM  ', 'Purchase at Adams-Watsica                                                                           ', 967.44, 800000000, 'Adams-Watsica                                     ', 'Ratkemouth                                        ', '55474-0373', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (143, '0000000482016448', '01', 1, 'POS TERM  ', 'Purchase at Becker Group                                                                            ', 310.00, 800000000, 'Becker Group                                      ', 'Sanfordhaven                                      ', '50166     ', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (144, '0000000482116790', '01', 1, 'POS TERM  ', 'Purchase at Erdman-Cartwright                                                                       ', 820.11, 800000000, 'Erdman-Cartwright                                 ', 'Lake Lavonne                                      ', '06930     ', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (145, '0000000486159054', '01', 1, 'POS TERM  ', 'Purchase at Christiansen-Jacobi                                                                     ', 319.88, 800000000, 'Christiansen-Jacobi                               ', 'West Conor                                        ', '53124     ', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (146, '0000000490043047', '01', 1, 'POS TERM  ', 'Purchase at Yost-Kertzmann                                                                          ', 584.88, 800000000, 'Yost-Kertzmann                                    ', 'Lake Josh                                         ', '59545     ', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (147, '0000000496711357', '01', 1, 'POS TERM  ', 'Purchase at Koepp-Wiegand                                                                           ', 161.99, 800000000, 'Koepp-Wiegand                                     ', 'Cristianstad                                      ', '23187-0329', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (148, '0000000497995808', '01', 1, 'POS TERM  ', 'Purchase at Beier and Daughters                                                                     ', 649.00, 800000000, 'Beier and Daughters                               ', 'Norbertstad                                       ', '48162-5331', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (149, '0000000498548061', '01', 1, 'POS TERM  ', 'Purchase at Bernier and Daughters                                                                   ', 209.00, 800000000, 'Bernier and Daughters                             ', 'Lake Rosefurt                                     ', '83724-5529', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (150, '0000000498615524', '03', 1, 'OPERATOR  ', 'Return item at Powlowski LLC                                                                        ', -907.00, 800000000, 'Powlowski LLC                                     ', 'New Aprilstad                                     ', '57040-5493', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (151, '0000000498857207', '01', 1, 'POS TERM  ', 'Purchase at Friesen, Murphy and Beier                                                               ', 293.44, 800000000, 'Friesen, Murphy and Beier                         ', 'Dallasberg                                        ', '02275     ', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
  (152, '0000000499424514', '01', 1, 'POS TERM  ', 'Purchase at Schumm-Stamm                                                                            ', 952.55, 800000000, 'Schumm-Stamm                                      ', 'Imogeneburgh                                      ', '12605     ', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (153, '0000000500479019', '01', 1, 'POS TERM  ', 'Purchase at Hilpert, Purdy and Kilback                                                              ', 654.99, 800000000, 'Hilpert, Purdy and Kilback                        ', 'Schummshire                                       ', '49771-2616', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (154, '0000000500885895', '03', 1, 'OPERATOR  ', 'Return item at Klein-Stark                                                                          ', -579.88, 800000000, 'Klein-Stark                                       ', 'West Arlo                                         ', '35478     ', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (155, '0000000502617711', '01', 1, 'POS TERM  ', 'Purchase at Klocko LLC                                                                              ', 955.11, 800000000, 'Klocko LLC                                        ', 'Winonaland                                        ', '07626     ', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (156, '0000000503557384', '01', 1, 'POS TERM  ', 'Purchase at Casper Group                                                                            ', 81.44, 800000000, 'Casper Group                                      ', 'Millsborough                                      ', '57690     ', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (157, '0000000504099546', '01', 1, 'POS TERM  ', 'Purchase at Jewess, Sauer and Runolfsson                                                            ', 655.11, 800000000, 'Jewess, Sauer and Runolfsson                      ', 'Parkermouth                                       ', '00391     ', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (158, '0000000508429766', '01', 1, 'POS TERM  ', 'Purchase at Kassulke, Reynolds and Runolfsson                                                       ', 534.11, 800000000, 'Kassulke, Reynolds and Runolfsson                 ', 'Seamuston                                         ', '13633-3156', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
  (159, '0000000519771423', '01', 1, 'POS TERM  ', 'Purchase at Herman, Swift and Nikolaus                                                              ', 670.00, 800000000, 'Herman, Swift and Nikolaus                        ', 'Durganport                                        ', '31302     ', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
  (160, '0000000519935575', '01', 1, 'POS TERM  ', 'Purchase at Gaylord, Kuhlman and Reichert                                                           ', 164.99, 800000000, 'Gaylord, Kuhlman and Reichert                     ', 'West Reillymouth                                  ', '05765     ', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (161, '0000000522130011', '01', 1, 'POS TERM  ', 'Purchase at D''Amore, Conroy and Wilkinson                                                           ', 699.44, 800000000, 'D''Amore, Conroy and Wilkinson                     ', 'East Larissatown                                  ', '12025-5362', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (162, '0000000523110055', '01', 1, 'POS TERM  ', 'Purchase at Purdy-King                                                                              ', 736.88, 800000000, 'Purdy-King                                        ', 'Port Maximusshire                                 ', '95835     ', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (163, '0000000528007115', '03', 1, 'OPERATOR  ', 'Return item at Trantow-Sipes                                                                        ', -113.11, 800000000, 'Trantow-Sipes                                     ', 'Reubentown                                        ', '12694     ', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (164, '0000000532120892', '03', 1, 'OPERATOR  ', 'Return item at Frami-Hyatt                                                                          ', -70.77, 800000000, 'Frami-Hyatt                                       ', 'Jamilside                                         ', '14372-1790', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (165, '0000000539225404', '03', 1, 'OPERATOR  ', 'Return item at Hamill, Blick and Kling                                                              ', -372.00, 800000000, 'Hamill, Blick and Kling                           ', 'Sporerview                                        ', '52731     ', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
  (166, '0000000540034453', '01', 1, 'POS TERM  ', 'Purchase at Baumbach-Mohr                                                                           ', 202.44, 800000000, 'Baumbach-Mohr                                     ', 'Kovacekhaven                                      ', '88690-1442', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (167, '0000000540260014', '03', 1, 'OPERATOR  ', 'Return item at McCullough-Gottlieb                                                                  ', -880.22, 800000000, 'McCullough-Gottlieb                               ', 'Clarissaside                                      ', '80982-4072', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (168, '0000000544248006', '01', 1, 'POS TERM  ', 'Purchase at Mann Inc                                                                                ', 659.44, 800000000, 'Mann Inc                                          ', 'Koeppton                                          ', '40246-5957', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (169, '0000000547488622', '01', 1, 'POS TERM  ', 'Purchase at Reichert, Kemmer and Funk                                                               ', 403.66, 800000000, 'Reichert, Kemmer and Funk                         ', 'North Destinibury                                 ', '84879     ', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (170, '0000000549364593', '01', 1, 'POS TERM  ', 'Purchase at Waters, Considine and Borer                                                             ', 195.44, 800000000, 'Waters, Considine and Borer                       ', 'Lake Lillianaville                                ', '60590-4967', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (171, '0000000550089732', '03', 1, 'OPERATOR  ', 'Return item at Sanford-Gleichner                                                                    ', -537.44, 800000000, 'Sanford-Gleichner                                 ', 'Dorisberg                                         ', '29319     ', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
  (172, '0000000550860982', '01', 1, 'POS TERM  ', 'Purchase at Bogan LLC                                                                               ', 850.22, 800000000, 'Bogan LLC                                         ', 'Lilyberg                                          ', '56494     ', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (173, '0000000554096178', '01', 1, 'POS TERM  ', 'Purchase at Turner, Dickinson and Grant                                                             ', 722.33, 800000000, 'Turner, Dickinson and Grant                       ', 'Lucianofort                                       ', '91006-9381', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (174, '0000000555363230', '01', 1, 'POS TERM  ', 'Purchase at Metz, Blanda and Homenick                                                               ', 484.99, 800000000, 'Metz, Blanda and Homenick                         ', 'North Linwood                                     ', '41398     ', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (175, '0000000561673599', '01', 1, 'POS TERM  ', 'Purchase at Bergnaum and Sons                                                                       ', 430.55, 800000000, 'Bergnaum and Sons                                 ', 'Leuschkeberg                                      ', '87213-5400', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (176, '0000000564257675', '01', 1, 'POS TERM  ', 'Purchase at Jast LLC                                                                                ', 623.11, 800000000, 'Jast LLC                                          ', 'Lednermouth                                       ', '82698     ', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (177, '0000000569807281', '03', 1, 'OPERATOR  ', 'Return item at Kiehn, Russel and Schaefer                                                           ', -998.33, 800000000, 'Kiehn, Russel and Schaefer                        ', 'New Loren                                         ', '41813     ', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (178, '0000000570013846', '01', 1, 'POS TERM  ', 'Purchase at Parker, Pfannerstill and Donnelly                                                       ', 352.33, 800000000, 'Parker, Pfannerstill and Donnelly                 ', 'Mohrport                                          ', '18642-6726', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (179, '0000000570880433', '01', 1, 'POS TERM  ', 'Purchase at Kuvalis-Leffler                                                                         ', 161.77, 800000000, 'Kuvalis-Leffler                                   ', 'East Tiffany                                      ', '09856-1749', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (180, '0000000573732499', '01', 1, 'POS TERM  ', 'Purchase at Ortiz, Langworth and Feeney                                                             ', 237.44, 800000000, 'Ortiz, Langworth and Feeney                       ', 'New Deonte                                        ', '32314     ', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (181, '0000000575834812', '01', 1, 'POS TERM  ', 'Purchase at Ritchie and Sons                                                                        ', 689.88, 800000000, 'Ritchie and Sons                                  ', 'O''Haraberg                                        ', '45500-2911', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
  (182, '0000000576344938', '01', 1, 'POS TERM  ', 'Purchase at Smith and Sons                                                                          ', 425.11, 800000000, 'Smith and Sons                                    ', 'Lake Dallinfurt                                   ', '26352-2649', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (183, '0000000577165878', '01', 1, 'POS TERM  ', 'Purchase at Macejkovic-Mohr                                                                         ', 621.22, 800000000, 'Macejkovic-Mohr                                   ', 'Trantowberg                                       ', '59291     ', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (184, '0000000577826814', '03', 1, 'OPERATOR  ', 'Return item at DuBuque, Wuckert and Mraz                                                            ', -47.88, 800000000, 'DuBuque, Wuckert and Mraz                         ', 'South Lurline                                     ', '37081     ', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (185, '0000000585883106', '01', 1, 'POS TERM  ', 'Purchase at Heathcote Inc                                                                           ', 435.44, 800000000, 'Heathcote Inc                                     ', 'Marlenemouth                                      ', '72239-5071', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
  (186, '0000000588642606', '01', 1, 'POS TERM  ', 'Purchase at Marks and Daughters                                                                     ', 568.88, 800000000, 'Marks and Daughters                               ', 'New Berryton                                      ', '84059-0476', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (187, '0000000598262041', '01', 1, 'POS TERM  ', 'Purchase at Terry-Rohan                                                                             ', 39.55, 800000000, 'Terry-Rohan                                       ', 'Rempelview                                        ', '34789-4591', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (188, '0000000600564499', '01', 1, 'POS TERM  ', 'Purchase at Schmeler, Crooks and Barton                                                             ', 736.33, 800000000, 'Schmeler, Crooks and Barton                       ', 'Hodkiewiczville                                   ', '09147-9690', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (189, '0000000601274842', '01', 1, 'POS TERM  ', 'Purchase at Yost, Hoppe and Heathcote                                                               ', 744.11, 800000000, 'Yost, Hoppe and Heathcote                         ', 'Heathermouth                                      ', '15216-7718', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (190, '0000000601496057', '01', 1, 'POS TERM  ', 'Purchase at Ortiz-Douglas                                                                           ', 900.22, 800000000, 'Ortiz-Douglas                                     ', 'Rosaleemouth                                      ', '64903     ', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (191, '0000000603071214', '01', 1, 'POS TERM  ', 'Purchase at Schinner-Feeney                                                                         ', 166.99, 800000000, 'Schinner-Feeney                                   ', 'North Wilfred                                     ', '36776-9392', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
  (192, '0000000605048564', '01', 1, 'POS TERM  ', 'Purchase at Schamberger, O''Reilly and Wintheiser                                                    ', 95.99, 800000000, 'Schamberger, O''Reilly and Wintheiser              ', 'West Bernadineland                                ', '74526     ', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (193, '0000000606140907', '01', 1, 'POS TERM  ', 'Purchase at Yost-Schaefer                                                                           ', 598.33, 800000000, 'Yost-Schaefer                                     ', 'Barrowsfurt                                       ', '88050     ', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (194, '0000000606716618', '01', 1, 'POS TERM  ', 'Purchase at Barton, Schmidt and Hodkiewicz                                                          ', 715.66, 800000000, 'Barton, Schmidt and Hodkiewicz                    ', 'Sethtown                                          ', '63152     ', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (195, '0000000606830191', '03', 1, 'OPERATOR  ', 'Return item at Bogisich-O''Connell                                                                   ', -71.66, 800000000, 'Bogisich-O''Connell                                ', 'New Bennie                                        ', '00871     ', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (196, '0000000614267358', '03', 1, 'OPERATOR  ', 'Return item at Gibson-Abbott                                                                        ', -132.88, 800000000, 'Gibson-Abbott                                     ', 'New Kodyton                                       ', '82751     ', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (197, '0000000618712102', '01', 1, 'POS TERM  ', 'Purchase at Hahn-Lueilwitz                                                                          ', 21.11, 800000000, 'Hahn-Lueilwitz                                    ', 'Deondreville                                      ', '55366-2298', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (198, '0000000621178666', '01', 1, 'POS TERM  ', 'Purchase at Orn-Dach                                                                                ', 639.22, 800000000, 'Orn-Dach                                          ', 'Maxineville                                       ', '39263-8392', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (199, '0000000624335286', '01', 1, 'POS TERM  ', 'Purchase at Crona, Turner and Hane                                                                  ', 598.44, 800000000, 'Crona, Turner and Hane                            ', 'Glenton                                           ', '32966-6359', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (200, '0000000627601011', '03', 1, 'OPERATOR  ', 'Return item at Stokes Inc                                                                           ', -538.22, 800000000, 'Stokes Inc                                        ', 'Koeppfurt                                         ', '91991     ', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
  (201, '0000000628524597', '01', 1, 'POS TERM  ', 'Purchase at Wiegand-Weimann                                                                         ', 269.22, 800000000, 'Wiegand-Weimann                                   ', 'East Arnomouth                                    ', '21317     ', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (202, '0000000634329004', '01', 1, 'POS TERM  ', 'Purchase at Durgan-Nader                                                                            ', 89.11, 800000000, 'Durgan-Nader                                      ', 'Robynmouth                                        ', '39869     ', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (203, '0000000635121182', '01', 1, 'POS TERM  ', 'Purchase at Douglas and Daughters                                                                   ', 629.55, 800000000, 'Douglas and Daughters                             ', 'Yvettetown                                        ', '03935     ', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
  (204, '0000000641694180', '01', 1, 'POS TERM  ', 'Purchase at Schumm-Reinger                                                                          ', 554.22, 800000000, 'Schumm-Reinger                                    ', 'Antoniatown                                       ', '52581     ', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (205, '0000000643758597', '01', 1, 'POS TERM  ', 'Purchase at Bins Inc                                                                                ', 744.22, 800000000, 'Bins Inc                                          ', 'Port Georgianaside                                ', '24098-5082', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (206, '0000000647754915', '01', 1, 'POS TERM  ', 'Purchase at Gerlach-Jaskolski                                                                       ', 718.55, 800000000, 'Gerlach-Jaskolski                                 ', 'New Kalistad                                      ', '51103-7932', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (207, '0000000652713581', '01', 1, 'POS TERM  ', 'Purchase at Pagac-Hackett                                                                           ', 633.55, 800000000, 'Pagac-Hackett                                     ', 'New Hans                                          ', '35901     ', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (208, '0000000667157384', '01', 1, 'POS TERM  ', 'Purchase at Stamm and Sons                                                                          ', 952.77, 800000000, 'Stamm and Sons                                    ', 'Hayleybury                                        ', '33611     ', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (209, '0000000669905307', '01', 1, 'POS TERM  ', 'Purchase at Schneider and Daughters                                                                 ', 425.00, 800000000, 'Schneider and Daughters                           ', 'Blandafurt                                        ', '74767-7107', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (210, '0000000672061881', '03', 1, 'OPERATOR  ', 'Return item at Rippin-Gibson                                                                        ', -435.00, 800000000, 'Rippin-Gibson                                     ', 'Hansenstad                                        ', '16980-8789', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (211, '0000000672573296', '03', 1, 'OPERATOR  ', 'Return item at Veum-Treutel                                                                         ', -710.66, 800000000, 'Veum-Treutel                                      ', 'Amelybury                                         ', '60686     ', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (212, '0000000673250360', '01', 1, 'POS TERM  ', 'Purchase at Ebert-Gleason                                                                           ', 191.00, 800000000, 'Ebert-Gleason                                     ', 'Altenwerthbury                                    ', '89085     ', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (213, '0000000676149118', '01', 1, 'POS TERM  ', 'Purchase at Sauer-Ruecker                                                                           ', 697.44, 800000000, 'Sauer-Ruecker                                     ', 'Port Nestor                                       ', '24148-9894', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (214, '0000000685488982', '01', 1, 'POS TERM  ', 'Purchase at Williamson Group                                                                        ', 94.77, 800000000, 'Williamson Group                                  ', 'Lake Bradyport                                    ', '32996     ', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (215, '0000000686167627', '01', 1, 'POS TERM  ', 'Purchase at Gibson, Beahan and Reichert                                                             ', 81.44, 800000000, 'Gibson, Beahan and Reichert                       ', 'Maeveland                                         ', '51385-6031', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (216, '0000000689276136', '01', 1, 'POS TERM  ', 'Purchase at Bins Group                                                                              ', 192.00, 800000000, 'Bins Group                                        ', 'North Anabellehaven                               ', '61914-3232', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (217, '0000000700096853', '01', 1, 'POS TERM  ', 'Purchase at Pollich Group                                                                           ', 329.99, 800000000, 'Pollich Group                                     ', 'Nikolausburgh                                     ', '88031     ', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (218, '0000000703553020', '01', 1, 'POS TERM  ', 'Purchase at Gleason-Streich                                                                         ', 77.00, 800000000, 'Gleason-Streich                                   ', 'New Huntermouth                                   ', '60103-7370', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (219, '0000000717135758', '03', 1, 'OPERATOR  ', 'Return item at Crona, Veum and D''Amore                                                              ', -762.44, 800000000, 'Crona, Veum and D''Amore                           ', 'South Nashland                                    ', '13804-5608', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (220, '0000000727152111', '01', 1, 'POS TERM  ', 'Purchase at Von, Klein and Cremin                                                                   ', 983.55, 800000000, 'Von, Klein and Cremin                             ', 'Evansfurt                                         ', '36814-9049', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (221, '0000000731515153', '03', 1, 'OPERATOR  ', 'Return item at Gleichner, Mitchell and Schmidt                                                      ', -25.99, 800000000, 'Gleichner, Mitchell and Schmidt                   ', 'North Vincent                                     ', '89467-9263', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (222, '0000000734452614', '01', 1, 'POS TERM  ', 'Purchase at Stokes-Mueller                                                                          ', 358.22, 800000000, 'Stokes-Mueller                                    ', 'Ambroseland                                       ', '19819-9298', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (223, '0000000735823935', '01', 1, 'POS TERM  ', 'Purchase at Johnston and Daughters                                                                  ', 910.11, 800000000, 'Johnston and Daughters                            ', 'Delaneymouth                                      ', '49269-2667', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (224, '0000000741999667', '01', 1, 'POS TERM  ', 'Purchase at Corkery, Boehm and Hudson                                                               ', 643.44, 800000000, 'Corkery, Boehm and Hudson                         ', 'Walkermouth                                       ', '83831     ', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (225, '0000000742447110', '01', 1, 'POS TERM  ', 'Purchase at Hauck Inc                                                                               ', 746.77, 800000000, 'Hauck Inc                                         ', 'Estellville                                       ', '11000     ', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (226, '0000000747646163', '03', 1, 'OPERATOR  ', 'Return item at Watsica LLC                                                                          ', -499.99, 800000000, 'Watsica LLC                                       ', 'Durgantown                                        ', '74690-6183', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (227, '0000000749066680', '01', 1, 'POS TERM  ', 'Purchase at O''Reilly LLC                                                                            ', 805.77, 800000000, 'O''Reilly LLC                                      ', 'Jerelport                                         ', '39298-3605', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
  (228, '0000000749493129', '01', 1, 'POS TERM  ', 'Purchase at Pollich-Kuhn                                                                            ', 13.88, 800000000, 'Pollich-Kuhn                                      ', 'Kelliview                                         ', '98624-6791', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
  (229, '0000000751145919', '01', 1, 'POS TERM  ', 'Purchase at Rohan-Jacobson                                                                          ', 140.00, 800000000, 'Rohan-Jacobson                                    ', 'East Delmer                                       ', '37476     ', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (230, '0000000751696292', '01', 1, 'POS TERM  ', 'Purchase at Smith, Hansen and Waelchi                                                               ', 653.55, 800000000, 'Smith, Hansen and Waelchi                         ', 'Jerrodport                                        ', '37182-0090', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
  (231, '0000000755736377', '01', 1, 'POS TERM  ', 'Purchase at Torp-Stark                                                                              ', 906.44, 800000000, 'Torp-Stark                                        ', 'North Edison                                      ', '41040-7099', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (232, '0000000760577632', '01', 1, 'POS TERM  ', 'Purchase at Kulas-Hayes                                                                             ', 735.99, 800000000, 'Kulas-Hayes                                       ', 'Prohaskaview                                      ', '38756     ', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
  (233, '0000000762269241', '01', 1, 'POS TERM  ', 'Purchase at Kuvalis Group                                                                           ', 988.88, 800000000, 'Kuvalis Group                                     ', 'Lake Cierrashire                                  ', '92525     ', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
  (234, '0000000767081090', '01', 1, 'POS TERM  ', 'Purchase at Bergnaum, Effertz and Wilkinson                                                         ', 671.11, 800000000, 'Bergnaum, Effertz and Wilkinson                   ', 'Lake Twila                                        ', '39210-3581', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (235, '0000000767308626', '01', 1, 'POS TERM  ', 'Purchase at Shields, DuBuque and Wyman                                                              ', 856.44, 800000000, 'Shields, DuBuque and Wyman                        ', 'South Christelle                                  ', '94060-3050', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (236, '0000000767314476', '01', 1, 'POS TERM  ', 'Purchase at Renner Inc                                                                              ', 273.11, 800000000, 'Renner Inc                                        ', 'Lednerberg                                        ', '11838     ', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (237, '0000000768119840', '01', 1, 'POS TERM  ', 'Purchase at Towne, Hickle and Orn                                                                   ', 65.44, 800000000, 'Towne, Hickle and Orn                             ', 'Toybury                                           ', '15228     ', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (238, '0000000770707563', '01', 1, 'POS TERM  ', 'Purchase at Leffler-Hilll                                                                           ', 309.44, 800000000, 'Leffler-Hilll                                     ', 'Lake Samantha                                     ', '94910     ', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (239, '0000000772421231', '01', 1, 'POS TERM  ', 'Purchase at Pfeffer, Rogahn and Hessel                                                              ', 405.33, 800000000, 'Pfeffer, Rogahn and Hessel                        ', 'Christborough                                     ', '21176-4420', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (240, '0000000776200014', '01', 1, 'POS TERM  ', 'Purchase at Lebsack and Sons                                                                        ', 985.22, 800000000, 'Lebsack and Sons                                  ', 'Otisbury                                          ', '32545     ', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
  (241, '0000000778829157', '01', 1, 'POS TERM  ', 'Purchase at Renner, Mertz and Ondricka                                                              ', 270.55, 800000000, 'Renner, Mertz and Ondricka                        ', 'South Emeliatown                                  ', '37065-2088', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (242, '0000000781205695', '01', 1, 'POS TERM  ', 'Purchase at Beer, Goldner and Armstrong                                                             ', 489.66, 800000000, 'Beer, Goldner and Armstrong                       ', 'South Madelynnland                                ', '21570     ', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
  (243, '0000000781512834', '01', 1, 'POS TERM  ', 'Purchase at Koch-Pouros                                                                             ', 319.00, 800000000, 'Koch-Pouros                                       ', 'Daytonstad                                        ', '13199-2463', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (244, '0000000784621975', '01', 1, 'POS TERM  ', 'Purchase at Kunde-Howe                                                                              ', 227.22, 800000000, 'Kunde-Howe                                        ', 'New Darylberg                                     ', '34409     ', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (245, '0000000793409700', '01', 1, 'POS TERM  ', 'Purchase at Douglas Inc                                                                             ', 168.66, 800000000, 'Douglas Inc                                       ', 'South Keyshawnton                                 ', '15099     ', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (246, '0000000796832699', '01', 1, 'POS TERM  ', 'Purchase at Schroeder, Bergnaum and Waters                                                          ', 803.99, 800000000, 'Schroeder, Bergnaum and Waters                    ', 'Jaskolskimouth                                    ', '83332-8357', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (247, '0000000802663079', '01', 1, 'POS TERM  ', 'Purchase at Zulauf-O''Keefe                                                                          ', 975.11, 800000000, 'Zulauf-O''Keefe                                    ', 'Rauview                                           ', '52467-2350', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (248, '0000000803014982', '01', 1, 'POS TERM  ', 'Purchase at Effertz-Abbott                                                                          ', 53.55, 800000000, 'Effertz-Abbott                                    ', 'Claudiechester                                    ', '95970-2683', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (249, '0000000806255008', '03', 1, 'OPERATOR  ', 'Return item at Wisoky, Jacobs and Sanford                                                           ', -439.33, 800000000, 'Wisoky, Jacobs and Sanford                        ', 'New Alanaview                                     ', '05488-3195', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (250, '0000000812607213', '03', 1, 'OPERATOR  ', 'Return item at Volkman-Goodwin                                                                      ', -641.77, 800000000, 'Volkman-Goodwin                                   ', 'Gulgowskifort                                     ', '59834-6801', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
  (251, '0000000821287727', '01', 1, 'POS TERM  ', 'Purchase at Russel LLC                                                                              ', 34.99, 800000000, 'Russel LLC                                        ', 'New Sarah                                         ', '49041     ', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
  (252, '0000000822135100', '01', 1, 'POS TERM  ', 'Purchase at Larkin, Hills and Becker                                                                ', 796.00, 800000000, 'Larkin, Hills and Becker                          ', 'Coleton                                           ', '54392-1073', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
  (253, '0000000823157599', '01', 1, 'POS TERM  ', 'Purchase at Miller, Hudson and Ziemann                                                              ', 111.11, 800000000, 'Miller, Hudson and Ziemann                        ', 'West Jasmin                                       ', '72736     ', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
  (254, '0000000824152956', '01', 1, 'POS TERM  ', 'Purchase at Weissnat-Sanford                                                                        ', 594.77, 800000000, 'Weissnat-Sanford                                  ', 'Schuppeton                                        ', '25158-3242', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
  (255, '0000000828072981', '03', 1, 'OPERATOR  ', 'Return item at Hayes Inc                                                                            ', -362.22, 800000000, 'Hayes Inc                                         ', 'Dinoville                                         ', '72795-6502', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (256, '0000000835855923', '03', 1, 'OPERATOR  ', 'Return item at Pouros and Sons                                                                      ', -41.77, 800000000, 'Pouros and Sons                                   ', 'Kerlukechester                                    ', '32347     ', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
  (257, '0000000838587312', '01', 1, 'POS TERM  ', 'Purchase at Abbott-Gerlach                                                                          ', 241.66, 800000000, 'Abbott-Gerlach                                    ', 'McClureburgh                                      ', '95049     ', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
  (258, '0000000838796166', '03', 1, 'OPERATOR  ', 'Return item at Bauch-Crooks                                                                         ', -457.55, 800000000, 'Bauch-Crooks                                      ', 'Stokesberg                                        ', '30306     ', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
  (259, '0000000840146978', '01', 1, 'POS TERM  ', 'Purchase at Gutkowski-Bayer                                                                         ', 702.22, 800000000, 'Gutkowski-Bayer                                   ', 'Baileyville                                       ', '48332-1913', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (260, '0000000841555701', '01', 1, 'POS TERM  ', 'Purchase at Kub, Gislason and Haraann                                                               ', 281.55, 800000000, 'Kub, Gislason and Haraann                         ', 'Port Bryonfurt                                    ', '16314-3731', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (261, '0000000845039454', '01', 1, 'POS TERM  ', 'Purchase at Borer, Farrell and Doyle                                                                ', 45.66, 800000000, 'Borer, Farrell and Doyle                          ', 'Evelineborough                                    ', '36781     ', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
  (262, '0000000855260493', '03', 1, 'OPERATOR  ', 'Return item at Cassin, Huel and Conroy                                                              ', -270.99, 800000000, 'Cassin, Huel and Conroy                           ', 'East Kurtborough                                  ', '83037     ', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
  (263, '0000000858238426', '01', 1, 'POS TERM  ', 'Purchase at Thiel Group                                                                             ', 80.66, 800000000, 'Thiel Group                                       ', 'New Martineberg                                   ', '27981     ', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
  (264, '0000000858501945', '01', 1, 'POS TERM  ', 'Purchase at Braun, Schulist and Kreiger                                                             ', 198.66, 800000000, 'Braun, Schulist and Kreiger                       ', 'Port Tamiamouth                                   ', '52536     ', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (265, '0000000865987685', '03', 1, 'OPERATOR  ', 'Return item at Kovacek-Beatty                                                                       ', -322.99, 800000000, 'Kovacek-Beatty                                    ', 'Cecilemouth                                       ', '18917     ', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (266, '0000000869383367', '01', 1, 'POS TERM  ', 'Purchase at Thompson-Streich                                                                        ', 768.88, 800000000, 'Thompson-Streich                                  ', 'Port Estrella                                     ', '21832-3751', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (267, '0000000873232405', '01', 1, 'POS TERM  ', 'Purchase at Bins, Boehm and Casper                                                                  ', 720.99, 800000000, 'Bins, Boehm and Casper                            ', 'South Lon                                         ', '87054     ', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
  (268, '0000000874953803', '01', 1, 'POS TERM  ', 'Purchase at McLaughlin-Blick                                                                        ', 399.44, 800000000, 'McLaughlin-Blick                                  ', 'Wintheisermouth                                   ', '03064     ', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
  (269, '0000000882360848', '01', 1, 'POS TERM  ', 'Purchase at Tromp-Kuhlman                                                                           ', 298.33, 800000000, 'Tromp-Kuhlman                                     ', 'Jerdeshire                                        ', '78699     ', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
  (270, '0000000884070277', '01', 1, 'POS TERM  ', 'Purchase at Bayer-O''Reilly                                                                          ', 194.33, 800000000, 'Bayer-O''Reilly                                    ', 'Stammmouth                                        ', '54961-5499', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (271, '0000000885437581', '01', 1, 'POS TERM  ', 'Purchase at Marquardt-Deckow                                                                        ', 818.00, 800000000, 'Marquardt-Deckow                                  ', 'Schmittport                                       ', '16465     ', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (272, '0000000887771179', '01', 1, 'POS TERM  ', 'Purchase at Jakubowski and Sons                                                                     ', 242.22, 800000000, 'Jakubowski and Sons                               ', 'Port Tyramouth                                    ', '68202-7796', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (273, '0000000889986293', '01', 1, 'POS TERM  ', 'Purchase at Littel-Jacobson                                                                         ', 973.11, 800000000, 'Littel-Jacobson                                   ', 'Lestertown                                        ', '36198     ', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
  (274, '0000000898285002', '01', 1, 'POS TERM  ', 'Purchase at Gislason-Price                                                                          ', 958.77, 800000000, 'Gislason-Price                                    ', 'North Maverickbury                                ', '09515-7261', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
  (275, '0000000899241176', '01', 1, 'POS TERM  ', 'Purchase at Beier, Larson and Schultz                                                               ', 462.33, 800000000, 'Beier, Larson and Schultz                         ', 'North Gudrunville                                 ', '59436-8470', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
  (276, '0000000900382063', '01', 1, 'POS TERM  ', 'Purchase at Bechtelar Group                                                                         ', 86.00, 800000000, 'Bechtelar Group                                   ', 'Mandybury                                         ', '49970-7370', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (277, '0000000903281896', '01', 1, 'POS TERM  ', 'Purchase at Schmitt, Mills and Yundt                                                                ', 932.55, 800000000, 'Schmitt, Mills and Yundt                          ', 'West Marlin                                       ', '92662-1169', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (278, '0000000909001545', '01', 1, 'POS TERM  ', 'Purchase at Crist Group                                                                             ', 893.33, 800000000, 'Crist Group                                       ', 'South Creola                                      ', '20922-4303', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
  (279, '0000000909315074', '01', 1, 'POS TERM  ', 'Purchase at Abbott and Sons                                                                         ', 759.22, 800000000, 'Abbott and Sons                                   ', 'East Cydney                                       ', '03808-7468', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (280, '0000000910081354', '01', 1, 'POS TERM  ', 'Purchase at Gerlach Group                                                                           ', 130.11, 800000000, 'Gerlach Group                                     ', 'Tannerburgh                                       ', '30389-8741', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
  (281, '0000000925687557', '03', 1, 'OPERATOR  ', 'Return item at Zboncak-Franecki                                                                     ', -372.99, 800000000, 'Zboncak-Franecki                                  ', 'Aldenport                                         ', '24426-3401', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
  (282, '0000000926624843', '01', 1, 'POS TERM  ', 'Purchase at Schowalter, Pagac and Welch                                                             ', 684.33, 800000000, 'Schowalter, Pagac and Welch                       ', 'West Isacton                                      ', '46573-2355', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
  (283, '0000000929059536', '01', 1, 'POS TERM  ', 'Purchase at Glover, Block and Huel                                                                  ', 920.11, 800000000, 'Glover, Block and Huel                            ', 'Lake Dasiabury                                    ', '92661     ', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
  (284, '0000000934798061', '03', 1, 'OPERATOR  ', 'Return item at Gottlieb, VonRueden and Raynor                                                       ', -260.11, 800000000, 'Gottlieb, VonRueden and Raynor                    ', 'East Darryl                                       ', '94703     ', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
  (285, '0000000934945079', '01', 1, 'POS TERM  ', 'Purchase at Boyle, O''Conner and Gorczany                                                            ', 222.44, 800000000, 'Boyle, O''Conner and Gorczany                      ', 'South Kirstin                                     ', '23487     ', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
  (286, '0000000942960329', '01', 1, 'POS TERM  ', 'Purchase at Bartoletti, Lehner and Johnston                                                         ', 711.66, 800000000, 'Bartoletti, Lehner and Johnston                   ', 'North Virginie                                    ', '63690     ', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
  (287, '0000000943918566', '01', 1, 'POS TERM  ', 'Purchase at Walker, Mohr and Wyman                                                                  ', 437.77, 800000000, 'Walker, Mohr and Wyman                            ', 'Kamronville                                       ', '93454     ', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (288, '0000000946277676', '01', 1, 'POS TERM  ', 'Purchase at Kemmer, Wyman and Ondricka                                                              ', 220.00, 800000000, 'Kemmer, Wyman and Ondricka                        ', 'Marcellechester                                   ', '28632     ', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (289, '0000000956474921', '01', 1, 'POS TERM  ', 'Purchase at Gottlieb, Turner and Ruecker                                                            ', 223.33, 800000000, 'Gottlieb, Turner and Ruecker                      ', 'Brettland                                         ', '98831-6582', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
  (290, '0000000957695517', '01', 1, 'POS TERM  ', 'Purchase at Schoen-Marvin                                                                           ', 573.22, 800000000, 'Schoen-Marvin                                     ', 'West Anastacio                                    ', '10111-5026', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
  (291, '0000000957864065', '01', 1, 'POS TERM  ', 'Purchase at Prohaska-Douglas                                                                        ', 314.66, 800000000, 'Prohaska-Douglas                                  ', 'North Leathahaven                                 ', '92680-2418', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
  (292, '0000000961186055', '01', 1, 'POS TERM  ', 'Purchase at Bartell-Fadel                                                                           ', 548.33, 800000000, 'Bartell-Fadel                                     ', 'Lebsackchester                                    ', '88382-6538', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (293, '0000000961714986', '01', 1, 'POS TERM  ', 'Purchase at West and Sons                                                                           ', 694.55, 800000000, 'West and Sons                                     ', 'Lawrencefort                                      ', '06664-6090', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
  (294, '0000000971342087', '03', 1, 'OPERATOR  ', 'Return item at Johnston Inc                                                                         ', -835.44, 800000000, 'Johnston Inc                                      ', 'Bergstromchester                                  ', '69737     ', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
  (295, '0000000973907278', '01', 1, 'POS TERM  ', 'Purchase at Klocko-Rice                                                                             ', 784.11, 800000000, 'Klocko-Rice                                       ', 'Shayneville                                       ', '50038-5154', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
  (296, '0000000974167587', '01', 1, 'POS TERM  ', 'Purchase at Moore and Sons                                                                          ', 402.22, 800000000, 'Moore and Sons                                    ', 'Parkerchester                                     ', '69137     ', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
  (297, '0000000976770816', '01', 1, 'POS TERM  ', 'Purchase at Corkery-Barton                                                                          ', 917.44, 800000000, 'Corkery-Barton                                    ', 'North Walterchester                               ', '08815-3649', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
  (298, '0000000982241353', '01', 1, 'POS TERM  ', 'Purchase at Bins, Gorczany and Denesik                                                              ', 765.66, 800000000, 'Bins, Gorczany and Denesik                        ', 'Elveraville                                       ', '52528     ', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
  (299, '0000000992103545', '01', 1, 'POS TERM  ', 'Purchase at Dickens, Bartoletti and Ferry                                                           ', 635.99, 800000000, 'Dickens, Bartoletti and Ferry                     ', 'Lesleyville                                       ', '89308-8479', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
  (300, '0000000996722787', '01', 1, 'POS TERM  ', 'Purchase at Kilback LLC                                                                             ', 603.22, 800000000, 'Kilback LLC                                       ', 'Cummeratamouth                                    ', '53200-7529', '3260763612337560', '2022-06-10 19:27:53.000000', '                          ');


-- ==================================================================
-- BLOCK 11 of 11 - user_security
-- ==================================================================
--
-- Source fixture : NONE - there is no app/data/ASCII/usrsec.txt. See
--                  NOT AVAILABLE in the docstring
-- Source records : app/jcl/DUSRSECJ.jcl:L35-L44, ten in-stream
--                  records between the //SYSUT1 DD * card at :L34 and
--                  the /* terminator at :L45, fed to
--                  //STEP01 EXEC PGM=IEBGENER at :L32 and written
--                  with DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0) at
--                  :L48
-- Source layout  : app/cpy/CSUSR01Y.cpy, SEC-USER-DATA, 80 bytes
--                  SEC-USR-ID     PIC X(08)  :L18  [1-8]
--                  SEC-USR-FNAME  PIC X(20)  :L19  [9-28]
--                  SEC-USR-LNAME  PIC X(20)  :L20  [29-48]
--                  SEC-USR-PWD    PIC X(08)  :L21  [49-56]
--                  SEC-USR-TYPE   PIC X(01)  :L22  [57]
--                  SEC-USR-FILLER PIC X(23)  :L23  [58-80]
--                  Each in-stream record is 57 characters, which
--                  IEBGENER pads to the LRECL of 80 - exactly the
--                  23-byte FILLER at :L23
-- Legacy cluster : AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS,
--                  app/jcl/DUSRSECJ.jcl:L65 KEYS(8,0), :L66
--                  RECORDSIZE(80,80)
-- Row census     : 10 rows - 5 of type A and 5 of type U
--
-- NO CREDENTIAL IS SEEDED IN RECOVERABLE FORM, AND THE
-- LEGACY PLAINTEXT VALUE APPEARS NOWHERE IN THIS FILE. The ten
-- in-stream records at app/jcl/DUSRSECJ.jcl:L35-L44 each carry a
-- credential in bytes [49-56], and all ten carry the same single
-- literal value. That value is not reproduced here as a value, as a
-- comment, as an example or as a test input; it is referred to only by
-- that locator. Reading it requires reading the frozen JCL member,
-- which is exactly where it already is.
--
-- What is stored instead is ten BCrypt digests at strength 10, one per
-- user, each computed once from the source literal and embedded as a
-- literal here. Each is sixty characters, carries the cost factor 10
-- in its prefix, and was verified to authenticate the source value and
-- to reject a different one before being written. Because BCrypt salts
-- each digest independently, the ten differ from one another even
-- though they derive from one input - which is the property that makes
-- a stolen table useless for cross-user comparison.
--
-- sec_usr_pwd is VARCHAR(60) and holds a digest ONLY. V1 declares no
-- plaintext credential column at all, so there is nowhere in this
-- schema for a recoverable credential to live. That is what the
-- security gate's every-credential-hashed assertion is provable
-- against: the assertion is a property of the schema, not a promise
-- about the data. The digest SHAPE - the version tag, the cost factor
-- and the sixty-character width - is enforced by the persistence
-- invariant on com.cardemo.model.entity.UserSecurity rather than by a
-- sixth CHECK constraint, and all ten values below satisfy it.
--
-- THE DIGESTS ARE LITERALS RATHER THAN COMPUTED AT APPLY TIME, AND
-- THAT IS DELIBERATE. A salt is random, so computing them during the
-- migration would produce different bytes on every run and a different
-- Flyway checksum with them, which validate-on-migrate would then
-- reject on the next start-up. Literals keep the migration
-- deterministic under Rule 1 Clause A and its checksum stable.
--
-- The user-type domain is app/cpy/COCOM01Y.cpy:26-28 -
-- 10 CDEMO-USER-TYPE PIC X(01). with
-- 88 CDEMO-USRTYP-ADMIN VALUE 'A'. and
-- 88 CDEMO-USRTYP-USER VALUE 'U'. - chained to THIS column by
-- app/cbl/COSGN00C.cbl:227 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE, which
-- moves the stored field straight into the field carrying those
-- 88-levels. All ten values below are 'A' or 'U' and satisfy V1's
-- ck_user_security_type.
--
-- Names and identifiers are transcribed at their full declared widths
-- from bytes [1-8], [9-28] and [29-48], blanks included. The table has
-- no version column.

-- THESE TEN ROWS ARE GATED, AND THE GATE IS CLOSED BY DEFAULT.
--
-- Every one of the ten digests below derives from ONE plaintext that is
-- recorded in a frozen file in this repository, and the ten identifiers
-- are recorded there too. That makes them demonstration credentials, not
-- credentials: anyone who can read the repository can authenticate as a
-- seeded administrator and hold administrative authority. Hashing does
-- not change that - the digests are correct, and correctness of a digest
-- is irrelevant when its input is published. Nor does the schema help:
-- it declares no locked, expired or must-reset state for a principal to
-- be parked in, and none is invented here.
--
-- So the rows are installed only where a demonstration corpus is the
-- point. The gate is the Flyway placeholder seeddemousers, and the
-- shape of the guard matters:
--
--   * The DEFAULT IS FALSE, set in application.yml, so a profile that
--     says nothing installs nothing. A guard that had to be remembered
--     in each new profile would be one forgotten profile away from
--     being no guard at all.
--   * TRUE is set only in application-local.yml and
--     application-test.yml. application-prod.yml sets FALSE explicitly
--     as well, so the intent is stated where a reader looks for it
--     rather than inherited silently.
--   * The gate is INSIDE the script, not a separate migration or an
--     environment-specific edit. Flyway checksums the RAW script text,
--     before placeholder substitution, so one file with one checksum
--     applies everywhere and validate-on-migrate stays meaningful. A
--     per-environment variant of this file would make the checksum
--     environment-dependent and defeat validation entirely.
--   * A closed gate leaves user_security EMPTY rather than seeding a
--     substitute principal. There is deliberately no bootstrap account:
--     the alternative to a known credential is no credential, and an
--     operator provisions one out of band.
--
-- The row census below counts ten because that is what the gated
-- statement installs where the gate is open, which is where the
-- fixture-validation and security gates are exercised.
-- The guard is expressed in plain SQL rather than in a PL/pgSQL DO
-- block deliberately. A DO block body is dollar-quoted, and every digest
-- below carries the substrings $2a$ and $10$; a $$ body would therefore
-- put dollar-sign runs inside a dollar-quoted region and make correct
-- parsing depend on the parser rejecting $2a$ as a tag - which
-- PostgreSQL does, since a tag may not begin with a digit, but which is
-- a property of two parsers rather than one. Plain SQL removes the
-- question: the digests stay in ordinary single-quoted literals, and the
-- placeholder appears once, in a WHERE clause.
INSERT INTO user_security (
  sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type
)
SELECT sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type
FROM (VALUES
  ('ADMIN001', 'MARGARET            ', 'GOLD                ', '$2a$10$NgAX2NHjhyCZyUAa0rlw2u/eRTfdAHTinmFLZv5ZF67bj6HfqqbjK', 'A'),
  ('ADMIN002', 'RUSSELL             ', 'RUSSELL             ', '$2a$10$9J55mmJQ0VdwwFPpxC1WIOmWpFiwJJOmf/k8fNcLHZjn1uCO0IX.i', 'A'),
  ('ADMIN003', 'RAYMOND             ', 'WHITMORE            ', '$2a$10$iEf/lBQUccNoOD76CW1bC.kF9UsGpcVbhtvxUK5Kkb.6uWANLhsMe', 'A'),
  ('ADMIN004', 'EMMANUEL            ', 'CASGRAIN            ', '$2a$10$nZFRvV.uMNk1fYW7jmW0AurGIpwMdJWtX0c36nUt9yjF3U2ZSNbJ2', 'A'),
  ('ADMIN005', 'GRANVILLE           ', 'LACHAPELLE          ', '$2a$10$3qkUC8b.2qL5rydZU51eGOP.4knhmIicBomYDmqYsRbtW7dgd2Ij.', 'A'),
  ('USER0001', 'LAWRENCE            ', 'THOMAS              ', '$2a$10$yC2yWBCVvR2MpALAjIxBj.PhUCay2jxyYaHiIJDtOC95LotiiHoD2', 'U'),
  ('USER0002', 'AJITH               ', 'KUMAR               ', '$2a$10$Fy0wtIw2a/47TYD368qxfuPNrnYtuQhf7yaGFthfZBlES6GVMIpJO', 'U'),
  ('USER0003', 'LAURITZ             ', 'ALME                ', '$2a$10$orV8FG4W1dzvv5BWnSFfPu9fwCACzd0RmwxFvS/1GiKYM5tmcrbeq', 'U'),
  ('USER0004', 'AVERARDO            ', 'MAZZI               ', '$2a$10$iP.tnqAFfsYOTMlpXSEYG.iDsxU344aS7lvy1Bi9qQerIYW5mmRM.', 'U'),
  ('USER0005', 'LEE                 ', 'TING                ', '$2a$10$fEh2UvITmbqSQZs5y.E5hO07iGLdvxAz6abGAcMCfhr4djZyuuZ/W', 'U')
) AS demo_users(sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
WHERE ${seeddemousers} = TRUE;


-- ==================================================================
-- OBJECT CENSUS
-- ==================================================================
--
-- Stated so that the row budget can be reconciled by inspection rather
-- than by trusting the prose. Left column is the seeded count; right is
-- the source that fixes it.
--
--   transaction_type                7   trantype.txt, 7 x 60
--   transaction_category           18   trancatg.txt, 18 x 60
--   account                        50   acctdata.txt, 50 x 300
--   customer                       50   custdata.txt, 50 x 500
--   card                           50   carddata.txt, 50 x 150
--   card_cross_reference           50   cardxref.txt, 50 x 36
--   disclosure_group               51   discgrp.txt, 51 x 50
--   transaction_category_balance   50   tcatbal.txt, 50 x 50
--   "transaction"                   0   no fixture exists
--   daily_transaction             300   dailytran.txt, 300 x 350
--   user_security                  10   DUSRSECJ.jcl:L35-L44
--                                ----
--   total                         636
--
-- Statement census: 10 INSERT statements over 11 tables - BLOCK 9 has
-- none. Zero CREATE, zero ALTER, zero DROP, zero GRANT, zero sequence,
-- zero row-level rule, zero function, zero extension.
--
-- Distribution checks that must also hold after a clean apply:
--
--   disclosure_group    17 rows per group across exactly 3 groups;
--                       17 of them in the DEFAULT group, 7 of those
--                       at a zero rate
--   daily_transaction   250 rows type 01 with a non-negative amount
--                       and 50 rows type 03 with a negative amount;
--                       26 blanks in dalytran_proc_ts on all 300
--   user_security       5 rows of type A and 5 of type U; every
--                       credential column exactly 60 characters
--   account             ten blanks in acct_group_id on all 50 rows
--   transaction_category_balance
--                       all 50 rows at type 01 category 0001


-- ==================================================================
-- WAYS THIS SEED GETS BROKEN
-- ==================================================================
--
-- Each entry below is a mistake this file is written to prevent, with
-- the source evidence that settles it. They are recorded here because a
-- seed is read where it is applied.
--
-- ABSOLUTE-VALUE NORMALISING THE 50 NEGATIVE STAGED AMOUNTS.
-- Never: preserve every sign exactly as the overpunch
-- byte encodes it. Those rows are the only thing in the seed that
-- reaches the cycle-debit branch at app/cbl/CBTRN02C.cbl:547-552, and
-- they are why the over-limit test at :403-405 subtracts the debit
-- accumulator. Normalising them disables the branch silently: nothing
-- fails to compile, no constraint is violated, and the divergence
-- surfaces only as a parity mismatch much later. See BLOCK 10.
--
-- SEEDING, QUOTING OR EXEMPLIFYING THE LEGACY PLAINTEXT
-- CREDENTIAL. Never: store ten BCrypt strength-10
-- digests and nothing else, and refer to the source literal only by
-- its locator, app/jcl/DUSRSECJ.jcl:L35-L44. The value appears nowhere
-- in this file - not as a value, not in a comment, not as an example.
-- V1 declares no plaintext credential column, so the schema itself
-- forbids the alternative. See BLOCK 11.
--
-- FILTERING, CLAMPING OR CORRECTING THE 21 CREDIT SCORES
-- BELOW 300. Never: seed all 50 rows verbatim. The
-- 300-through-850 rule at app/cbl/COACTUPC.cbl:848-849 qualifies
-- ACUP-NEW-CUST-FICO-SCORE, declared at :845-847 inside the screen
-- snapshot group. It is an online input rule on a screen field, not an
-- invariant on stored data, which is why V1 declares no range CHECK.
-- V1 records the same constraint from the schema side. See BLOCK 4.
--
-- transaction_type's key column is tran_type, not
-- tran_type_cd. Use tran_type. Evidence
-- app/cpy/CVTRA03Y.cpy:L5, where the field is named plainly TRAN-TYPE,
-- uniquely among the copybooks; corroborated by the unsuffixed target
-- field FD-TRAN-TYPE at app/cbl/CBTRN03C.cbl:189. The suffixed
-- spelling fails at apply time with an undefined column. See BLOCK 1.
--
-- THE FIXTURE IS dailytran.txt, WITH THE WORD IN FULL. No
-- file in the repository carries the abbreviated stem the DD name
-- would suggest, so the full spelling is used in
-- the banner, in BLOCK 10 and in the census. The dataset and DD
-- name ARE DALYTRAN, which is why the columns are dalytran_*, and that
-- asymmetry is what makes the mistake easy. It is also silent: a
-- mis-spelled path is a missing test resource, never a compile error.
--
-- OMITTING ANY OF THE 17 DEFAULT DISCLOSURE-GROUP ROWS. All 51 rows
-- are seeded, including all 17 of the
-- DEFAULT group and all 7 of its zero-rate rows. Every seeded account
-- carries a blank group identifier, so the first lookup at
-- app/cbl/CBACT04C.cbl:416 misses on all fifty and the DEFAULT retry at
-- :444 is what resolves every rate. That retry accepts file status
-- '00' only; a missing row abends at :458 with code 999 and return
-- code 12. See BLOCK 7.
--
-- A TEMPORAL TYPE FOR ANY OF THE FOUR TIMESTAMP-SHAPED
-- COLUMNS. Never: they stay CHAR(26), as V1 declares
-- them, and the twenty-six blanks of dalytran_proc_ts are seeded
-- literally on all 300 rows. No temporal type can hold them, so
-- retyping the column would force a fabricated value onto every staged
-- row. See BLOCK 10.
--
-- ONE PROHIBITED-CONSTRUCT CHECK CANNOT REACH A LITERAL ZERO.
-- The check for the privilege-granting keyword matches three rows of
-- BLOCK 10. Rows 68, 111 and 173 of app/data/ASCII/dailytran.txt each
-- carry that word inside a merchant name, as an ordinary surname and
-- place name, and this file transcribes all three verbatim inside
-- quoted literals. Editing them to satisfy a text scan would corrupt
-- frozen fixture data and break the Gate 1 parity comparison, so they
-- stand. This file contains no privilege statement, and every other
-- prohibited-construct check matches nothing at all. Anchor any such
-- check to statement position, for example
--   grep -icE '(^|;)[[:space:]]*grant[[:space:]]' V3__seed_data.sql
-- which returns 0. Evidence: all three matching lines are data rows
-- whose match falls between apostrophes, never at statement position.
--
-- ASSUMING EVERY FIXTURE PADS ITS TRAILING FILLER WITH BLANKS.
-- Never: parse each fixture by offset and never by
-- trimming. acctdata, custdata, carddata and dailytran are blank-filled;
-- discgrp, tcatbal, trancatg and trantype are ZERO-filled with the
-- character 0. No FILLER is modelled as a column, so the asymmetry
-- affects only how the bytes are skipped - but a parser that trims
-- zeros as though they were padding corrupts the last real field.
--
-- cardxref.txt DOES NOT CARRY THE 14-BYTE FILLER ITS CLUSTER
-- RECORD RESERVES. Read 36 data bytes, not 50.
-- 1,850 bytes over 50 rows is 37 per line, which is 36 plus the
-- terminator. It is the one fixture of the nine whose row is narrower
-- than its VSAM slot. See BLOCK 6.
-- ==================================================================
