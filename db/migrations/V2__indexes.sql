-- =============================================================================
-- V2__indexes.sql
-- Flyway V2 migration: B-tree indexes for CardDemo Aurora PostgreSQL
--
-- Creates indexes corresponding to 3 VSAM Alternate Index (AIX) definitions
-- from the original IBM z/OS mainframe application. Each AIX provided a
-- secondary access path into a VSAM KSDS cluster; in Aurora PostgreSQL these
-- become B-tree indexes on the corresponding columns, preserving the keyed
-- access patterns used by the original COBOL online (CICS) and batch programs.
--
-- VSAM AIX  -> PostgreSQL B-tree index mapping:
--   1. CARDDATA.VSAM.AIX   -> idx_cards_acct_id          (KEYS(11,16)  NONUNIQUEKEY)
--   2. CARDXREF.VSAM.AIX   -> idx_card_xref_acct_id      (KEYS(11,25)  NONUNIQUEKEY)
--   3. TRANSACT.VSAM.AIX   -> idx_transactions_proc_ts   (KEYS(26,304) NONUNIQUEKEY)
--
-- Source JCL (IDCAMS DEFINE ALTERNATEINDEX):
--   app/jcl/CARDFILE.jcl   (STEP40, lines 80-92)
--   app/jcl/XREFFILE.jcl   (STEP20, lines 69-82)
--   app/jcl/TRANIDX.jcl    (STEP20, lines 22-34) and app/jcl/TRANFILE.jcl (STEP20, lines 79-91)
--
-- Reference: app/catlg/LISTCAT.txt (IDCAMS catalog report, 209 entries,
--            confirming the 3 AIX definitions with their PATH and DATA/INDEX clusters).
--
-- Rules applied (per AAP section 0.7):
--   * Each VSAM AIX maps to exactly ONE PostgreSQL B-tree index (no speculative indexes).
--   * All 3 VSAM AIX definitions specify NONUNIQUEKEY, therefore none of the
--     PostgreSQL indexes declare UNIQUE -- multiple rows can share an index key.
--   * Standard (default) B-tree index type is used, which matches the ordered
--     key-sequence access provided by VSAM alternate indexes.
--   * Index naming convention: idx_{table}_{column}.
--   * Table and column references match exactly the DDL in V1__schema.sql.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Index 1 of 3: idx_cards_acct_id
--
-- Source: app/jcl/CARDFILE.jcl -- CARDDATA.VSAM.AIX
--   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX)
--     RELATE(AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS)
--     KEYS(11 16)        <-- 11-byte key starting at offset 16 of the record
--     NONUNIQUEKEY       <-- multiple records may share the same key value
--     UPGRADE ...)
--
-- COBOL record layout (app/cpy/CVACT02Y.cpy, CARD-RECORD, RECLN 150):
--   05 CARD-NUM       PIC X(16).   -- offset 0,  length 16  (primary key)
--   05 CARD-ACCT-ID   PIC 9(11).   -- offset 16, length 11  <== AIX key field
--   05 CARD-CVV-CD    PIC 9(03).
--   05 CARD-EMBOSSED-NAME PIC X(50).
--   05 CARD-EXPIRAION-DATE PIC X(10).
--   05 CARD-ACTIVE-STATUS  PIC X(01).
--   05 FILLER         PIC X(59).
--
-- Purpose: Supports fast lookup of all cards associated with a given account,
-- used by online programs COCRDLIC (card list) and COCRDSLC (card detail via
-- account navigation) and by batch programs that iterate cards by account.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_cards_acct_id
    ON cards (card_acct_id);


-- -----------------------------------------------------------------------------
-- Index 2 of 3: idx_card_xref_acct_id
--
-- Source: app/jcl/XREFFILE.jcl -- CARDXREF.VSAM.AIX
--   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX)
--     RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
--     KEYS(11,25)        <-- 11-byte key starting at offset 25 of the record
--     NONUNIQUEKEY       <-- multiple records may share the same key value
--     UPGRADE ...)
--
-- COBOL record layout (app/cpy/CVACT03Y.cpy, CARD-XREF-RECORD, RECLN 50):
--   05 XREF-CARD-NUM  PIC X(16).   -- offset 0,  length 16  (primary key)
--   05 XREF-CUST-ID   PIC 9(09).   -- offset 16, length 9
--   05 XREF-ACCT-ID   PIC 9(11).   -- offset 25, length 11  <== AIX key field
--   05 FILLER         PIC X(14).
--
-- Purpose: Supports fast lookup of all card cross-reference rows for a given
-- account, used by the 3-entity account-view join in COACTVWC and by
-- transaction-add flows (COTRN02C) that resolve card -> account via xref.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_card_xref_acct_id
    ON card_cross_references (acct_id);


-- -----------------------------------------------------------------------------
-- Index 3 of 3: idx_transactions_proc_ts
--
-- Source: app/jcl/TRANIDX.jcl (and duplicated in app/jcl/TRANFILE.jcl STEP20)
--   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
--     RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
--     KEYS(26 304)       <-- 26-byte key starting at offset 304 of the record
--     NONUNIQUEKEY       <-- multiple records may share the same key value
--     UPGRADE ...)
--
-- COBOL record layout (app/cpy/CVTRA05Y.cpy, TRAN-RECORD, RECLN 350):
--   Cumulative byte offsets:
--     05 TRAN-ID             PIC X(16).    -- offset   0
--     05 TRAN-TYPE-CD        PIC X(02).    -- offset  16
--     05 TRAN-CAT-CD         PIC 9(04).    -- offset  18
--     05 TRAN-SOURCE         PIC X(10).    -- offset  22
--     05 TRAN-DESC           PIC X(100).   -- offset  32
--     05 TRAN-AMT            PIC S9(09)V99.-- offset 132 (11 bytes signed zoned decimal)
--     05 TRAN-MERCHANT-ID    PIC 9(09).    -- offset 143
--     05 TRAN-MERCHANT-NAME  PIC X(50).    -- offset 152
--     05 TRAN-MERCHANT-CITY  PIC X(50).    -- offset 202
--     05 TRAN-MERCHANT-ZIP   PIC X(10).    -- offset 252
--     05 TRAN-CARD-NUM       PIC X(16).    -- offset 262
--     05 TRAN-ORIG-TS        PIC X(26).    -- offset 278
--     05 TRAN-PROC-TS        PIC X(26).    -- offset 304  <== AIX key field
--     05 FILLER              PIC X(20).    -- offset 330 (RECLN 350)
--
-- Purpose: Supports date-range-bounded reads of the transaction master by
-- processing timestamp. Used by the batch reporting program CBTRN03C (TRANREPT
-- stage, date-filtered 3-level totals), by the statement generator CBSTM03A
-- (CREASTMT stage), and by the online transaction list (COTRN00C) when
-- ordering by processing time.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_transactions_proc_ts
    ON transactions (tran_proc_ts);


-- =============================================================================
-- End of V2__indexes.sql -- 3 indexes created, one per VSAM AIX definition.
-- =============================================================================
