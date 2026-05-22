-- =============================================================================
-- Flyway Migration: V013__seed_transaction_type.sql
-- Purpose:    Seed 7 canonical transaction-type lookup rows into tran_type.
--             These are the static, system-wide transaction-type codes
--             referenced by every transaction-related service in CardDemo
--             (TransactionPostingService, TransactionReportService,
--             TransactionAddService, InterestCalculationService) and required
--             as the FK target for the tran_type_cd column of the tran_category
--             table (seeded by V014) and for application-level tran_type_cd
--             lookups in the transactions and tran_cat_bal tables.
--
-- Source:     app/data/ASCII/trantype.txt   (7 fixed-width 60-byte records;
--                                            decoded with `cat -A` -- the
--                                            trailing "00000000" on each line
--                                            is the 8-byte COBOL FILLER and is
--                                            OMITTED in PostgreSQL)
--             app/jcl/TRANTYPE.jcl          (IDCAMS DEFINE CLUSTER + REPRO
--                                            step for AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
--                                            with KEYS(2 0), RECORDSIZE(60 60),
--                                            SHAREOPTIONS(1 4), INDEXED)
--             app/cpy/CVTRA03Y.cpy          (TRAN-TYPE-RECORD layout, RECLN 60,
--                                            primary key TRAN-TYPE(2))
--             app/catlg/LISTCAT.txt         (catalog confirmation: KEYLEN=2,
--                                            RKP=0, MAXLRECL=60, INDEXED KSDS,
--                                            SHROPTNS(1,4))
-- AAP Refs:   §0.4.1 (Flyway seed migrations replacing IDCAMS REPRO steps),
--             §0.6.2 (VSAM-to-RDS migration strategy: seed reference data via
--                     Flyway inline INSERTs),
--             §0.7.1 (refactor discipline -- preserve fixture text verbatim;
--                     codes and descriptions must NOT be altered).
--
-- Replaces:   IDCAMS REPRO step (STEP15) in app/jcl/TRANTYPE.jcl that loaded
--             AWS.M2.CARDDEMO.TRANTYPE.PS (the flat-file equivalent of
--             app/data/ASCII/trantype.txt) into the
--             AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS cluster. The CICS file
--             definition (CSD entry) and VSAM cluster are obsolete in the
--             target architecture; this static reference data lives in the
--             RDS PostgreSQL tran_type table created by V008.
--
-- Source Data Layout (60-byte fixed-width record per CVTRA03Y.cpy):
--   Pos 1-2:   TRAN-TYPE       (PIC X(02))  -> tran_type      (CHAR(2) PRIMARY KEY)
--   Pos 3-52:  TRAN-TYPE-DESC  (PIC X(50))  -> tran_type_desc (VARCHAR(50))
--   Pos 53-60: FILLER          (PIC X(08))  -> OMITTED in PostgreSQL
--
-- Notes:
--   - The 8-byte FILLER PIC X(08) suffix in the COBOL record is OMITTED here
--     (the source fixture has "00000000" in positions 53-60; this is unused
--     padding, not data, and has no relational equivalent).
--   - Description strings are stored trimmed of trailing padding spaces.
--     COBOL pads each description to 50 chars with spaces; PostgreSQL VARCHAR(50)
--     stores the trimmed values, which is the idiomatic relational approach.
--   - Codes are CHAR(2) primary-key values -- always exactly 2 characters,
--     always quoted as strings (e.g., '01', not the integer 1). Leading zeros
--     are SIGNIFICANT and MUST be preserved.
--   - Capitalization in descriptions follows the source fixture exactly
--     (Title Case). DO NOT normalize case -- regulatory output formats and
--     downstream consumers depend on the exact text.
--   - ON CONFLICT (tran_type) DO NOTHING permits safe idempotent re-runs
--     (e.g., `flyway migrate` on a partially-seeded DB or after a manual
--     row insert during development).
--   - Flyway manages the transaction boundary -- no explicit BEGIN/COMMIT here.
--
-- Row inventory (verbatim from app/data/ASCII/trantype.txt):
--   '01' Purchase       -- debit transaction (sales draft, cash advance, fees)
--   '02' Payment        -- customer payment received (cash/electronic/check)
--   '03' Credit         -- non-payment credit applied to account
--   '04' Authorization  -- pre-auth hold (zero-dollar, online, travel)
--   '05' Refund         -- merchant-initiated refund credit
--   '06' Reversal       -- transaction reversal (fraud or non-fraud)
--   '07' Adjustment     -- manual adjustment posted by operations
-- =============================================================================

insert into tran_type (tran_type, tran_type_desc) values
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment')
on conflict (tran_type) do nothing;
