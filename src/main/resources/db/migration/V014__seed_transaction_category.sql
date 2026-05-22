-- =============================================================================
-- Flyway Migration: V014__seed_transaction_category.sql
-- Purpose:    Seed 18 transaction-category lookup rows into tran_category.
--             These rows define the (type-code, category-code) pairs that
--             classify every transaction in the system. They are the FK
--             targets for application-level tran_cat_cd lookups in the
--             transactions and tran_cat_bal tables, and they participate in
--             disclosure_group composite-key joins for interest-rate lookups
--             (InterestCalculationService queries by (group, type, cat)).
--
-- Source:     app/data/ASCII/trancatg.txt  (18 fixed-width 60-byte records;
--                                           verified via `cat -A` -- trailing
--                                           "0000" on each line is the 4-byte
--                                           COBOL FILLER, omitted here)
--             app/jcl/TRANCATG.jcl         (IDCAMS DEFINE CLUSTER + REPRO step
--                                           for AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
--                                           with KEYS(6 0), RECORDSIZE(60 60),
--                                           SHAREOPTIONS(2 3))
--             app/cpy/CVTRA04Y.cpy         (TRAN-CAT-RECORD layout, RECLN 60,
--                                           composite key TYPE-CD(2) + CAT-CD(4))
--             app/catlg/LISTCAT.txt        (catalog confirmation: KEYLEN=6,
--                                           MAXLRECL=60, INDEXED KSDS)
-- AAP Refs:   §0.4.1 (Flyway seed migrations),
--             §0.7.1 (refactor discipline -- preserve fixture text verbatim).
--
-- Replaces:   IDCAMS REPRO step in app/jcl/TRANCATG.jcl that loaded
--             AWS.M2.CARDDEMO.TRANCATG.PS into AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS.
--             tran_category is a static lookup with composite PRIMARY KEY
--             (tran_type_cd, tran_cat_cd). The tran_type_cd column FKs to
--             tran_type (created by V008, seeded by V013); Flyway's
--             lexicographic ordering (V013 < V014) guarantees the parent
--             rows exist before this migration runs.
--
-- Source Data Layout (60-byte fixed-width record per CVTRA04Y.cpy):
--   Pos 1-2:   TRAN-TYPE-CD        (PIC X(02))  -> tran_type_cd (VARCHAR(2))
--   Pos 3-6:   TRAN-CAT-CD         (PIC 9(04))  -> tran_cat_cd  (NUMERIC(4))
--   Pos 7-56:  TRAN-CAT-TYPE-DESC  (PIC X(50))  -> tran_cat_type_desc (VARCHAR(50))
--   Pos 57-60: FILLER              (PIC X(04))  -> OMITTED in PostgreSQL
--
-- Notes:
--   - The 4-byte FILLER PIC X(04) suffix in the COBOL record is OMITTED.
--   - Description strings are stored trimmed of trailing padding spaces
--     (COBOL pads each to 50 chars; PostgreSQL VARCHAR(50) stores trimmed values).
--   - tran_cat_cd is NUMERIC(4) -- stored as integer (e.g., 1, 2, 3), NOT a
--     zero-padded string. The COBOL PIC 9(04) "0001" becomes the integer 1.
--   - Capitalization in descriptions follows the source fixture exactly:
--       * Type 01 entries use Title Case (e.g., "Regular Sales Draft").
--       * Type 02 entries use lowercase "payment" ("Cash payment", not
--         "Cash Payment"); this is intentional and reflects the fixture.
--       * Type 03-07 entries follow the source fixture's mixed-case spelling.
--     DO NOT normalize case -- regulatory output formats depend on exact text.
--   - ON CONFLICT (tran_type_cd, tran_cat_cd) DO NOTHING permits safe
--     idempotent re-runs (e.g., `flyway migrate` on a partially-seeded DB).
--   - Flyway manages the transaction boundary -- no explicit BEGIN/COMMIT here.
-- =============================================================================

insert into tran_category (tran_type_cd, tran_cat_cd, tran_cat_type_desc) values
    ('01', 1, 'Regular Sales Draft'),
    ('01', 2, 'Regular Cash Advance'),
    ('01', 3, 'Convenience Check Debit'),
    ('01', 4, 'ATM Cash Advance'),
    ('01', 5, 'Interest Amount'),
    ('02', 1, 'Cash payment'),
    ('02', 2, 'Electronic payment'),
    ('02', 3, 'Check payment'),
    ('03', 1, 'Credit to Account'),
    ('03', 2, 'Credit to Purchase balance'),
    ('03', 3, 'Credit to Cash balance'),
    ('04', 1, 'Zero dollar authorization'),
    ('04', 2, 'Online purchase authorization'),
    ('04', 3, 'Travel booking authorization'),
    ('05', 1, 'Refund credit'),
    ('06', 1, 'Fraud reversal'),
    ('06', 2, 'Non-fraud reversal'),
    ('07', 1, 'Sales draft credit adjustment')
on conflict (tran_type_cd, tran_cat_cd) do nothing;
