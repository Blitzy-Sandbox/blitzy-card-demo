-- =============================================================================
-- Flyway Migration: V012__seed_disclosure_group.sql
-- Purpose:    Seed 51 disclosure-group interest-rate rules into disclosure_group.
--             These rows define the per-(account-group, transaction-type,
--             transaction-category) interest rate matrix consumed by the
--             InterestCalculationService (the Java equivalent of COBOL
--             CBACT04C). The DEFAULT and ZEROAPR rows are the fallback groups
--             invoked by the DisclosureGroupRepository when an account's
--             explicit group ID is not found (per AAP §0.4.1 and §0.6.1).
--
-- Source:     app/data/ASCII/discgrp.txt   (51 fixed-width 50-byte records;
--                                           DIS-INT-RATE field positions 17-22
--                                           are EBCDIC zoned-decimal with
--                                           overpunch sign in the last byte)
--             app/jcl/DISCGRP.jcl          (IDCAMS DEFINE CLUSTER + REPRO step
--                                           for AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
--                                           with KEYS(16 0), RECORDSIZE(50 50),
--                                           SHAREOPTIONS(2 3), INDEXED)
--             app/cpy/CVTRA02Y.cpy         (DIS-GROUP-RECORD layout, RECLN 50,
--                                           composite key 16 bytes:
--                                           DIS-ACCT-GROUP-ID(10)
--                                           + DIS-TRAN-TYPE-CD(2)
--                                           + DIS-TRAN-CAT-CD(4))
--             app/catlg/LISTCAT.txt        (catalog confirmation for the
--                                           DISCGRP VSAM KSDS cluster)
-- AAP Refs:   §0.4.1 (Flyway seed migrations replacing IDCAMS REPRO steps),
--             §0.6.1 (BigDecimal/NUMERIC precision -- decimal rates only,
--                     no float/double; banker's rounding HALF_EVEN at runtime),
--             §0.7.1 (refactor discipline -- preserve fixture values verbatim;
--                     no plaintext sensitive data; these are public reference
--                     rates, not PII or PAN).
--
-- Replaces:   IDCAMS REPRO step (STEP15) in app/jcl/DISCGRP.jcl that loaded
--             AWS.M2.CARDDEMO.DISCGRP.PS (the flat-file equivalent of
--             app/data/ASCII/discgrp.txt) into the
--             AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS cluster. The CICS file
--             definition (CSD entry) and the VSAM cluster itself are obsolete
--             in the target AWS-native architecture; this static reference
--             data now lives in the RDS PostgreSQL disclosure_group table
--             created by V007.
--
-- Source Data Layout (50-byte fixed-width record per CVTRA02Y.cpy):
--   Pos  1-10:  DIS-ACCT-GROUP-ID (PIC X(10))      -> dis_acct_group_id (VARCHAR(10))
--   Pos 11-12:  DIS-TRAN-TYPE-CD  (PIC X(02))      -> dis_tran_type_cd  (CHAR(2)/VARCHAR(2))
--   Pos 13-16:  DIS-TRAN-CAT-CD   (PIC 9(04))      -> dis_tran_cat_cd   (NUMERIC(4))
--   Pos 17-22:  DIS-INT-RATE      (PIC S9(04)V99)  -> dis_int_rate      (NUMERIC(6,2))
--                                                    (5 zoned digits +
--                                                     1 overpunch sign char)
--   Pos 23-50:  FILLER            (PIC X(28))      -> OMITTED in PostgreSQL
--
-- EBCDIC Zoned-Decimal Overpunch Decoding (per AAP §0.6.1):
--   The 6-character rate field is zoned decimal where the sign character is
--   overpunched into the last digit. The fixture uses only the positive-zero
--   overpunch ('{') so all decoded values are non-negative:
--     '{' = positive zero (last digit = 0, sign = '+')
--     'A'..'I' = positive 1..9
--     '}' = negative zero
--     'J'..'R' = negative 1..9
--   Example decodings (verified against the fixture):
--     '00150{' -> digits = 001500, sign = '+', V99 implied decimal -> +15.00
--     '00250{' -> digits = 002500, sign = '+', V99 implied decimal -> +25.00
--     '00000{' -> digits = 000000, sign = '+', V99 implied decimal ->  +0.00
--   The only three distinct rates present in all 51 records are
--   {0.00, 15.00, 25.00}.
--
-- Notes:
--   - The 28-byte FILLER PIC X(28) suffix in the COBOL record is OMITTED here
--     (the fixture has "00000000000000000000000000" in positions 23-50; this
--     is unused padding and has no relational equivalent).
--   - Group IDs 'DEFAULT' and 'ZEROAPR' are stored TRIMMED of trailing
--     padding spaces (the fixture pads them with 3 trailing spaces to fit
--     the 10-char COBOL field). Java services look up by the trimmed value
--     'DEFAULT' (not 'DEFAULT   '), so storing the trimmed form improves
--     query ergonomics and avoids subtle WHERE-clause mismatches.
--     The 'A000000000' group ID is already exactly 10 characters in the
--     fixture and requires no trimming.
--   - dis_tran_type_cd is a CHAR/VARCHAR string value (e.g., '01') -- always
--     exactly 2 characters with significant leading zeros. It is the same
--     value space as tran_type (V008/V013).
--   - dis_tran_cat_cd is NUMERIC(4) -- stored as an integer (e.g., 1, 2, 3),
--     NOT a zero-padded string. The COBOL PIC 9(04) "0001" becomes the
--     integer 1, matching the convention used by V014 (tran_category) for
--     its tran_cat_cd column.
--   - dis_int_rate is NUMERIC(6,2) and is stored as the decoded decimal
--     value (e.g., 15.00, not 1500). Every literal below uses the explicit
--     ".00" form to make the scale unambiguous and to mirror the runtime
--     BigDecimal scale=2 contract (per AAP §0.6.1).
--   - ON CONFLICT (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
--     DO NOTHING permits safe idempotent re-runs (e.g., `flyway migrate`
--     against a partially-seeded DB, or repeated `flyway:migrate` after a
--     manual development insert).
--   - Flyway manages the transaction boundary for each migration -- no
--     explicit BEGIN/COMMIT is included here.
-- =============================================================================

insert into disclosure_group (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) values
    -- -------------------------------------------------------------------------
    -- Block A: account group 'A000000000' (17 rows) -- lines 1-17 of discgrp.txt
    -- -------------------------------------------------------------------------
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
    -- -------------------------------------------------------------------------
    -- Block B: account group 'DEFAULT' (17 rows) -- lines 18-34 of discgrp.txt
    -- Fallback rate set used when an account's explicit group ID is not found.
    -- -------------------------------------------------------------------------
    ('DEFAULT',    '01', 1, 15.00),
    ('DEFAULT',    '01', 2, 25.00),
    ('DEFAULT',    '01', 3, 25.00),
    ('DEFAULT',    '01', 4, 25.00),
    ('DEFAULT',    '02', 1, 0.00),
    ('DEFAULT',    '02', 2, 0.00),
    ('DEFAULT',    '02', 3, 0.00),
    ('DEFAULT',    '03', 1, 0.00),
    ('DEFAULT',    '03', 2, 0.00),
    ('DEFAULT',    '03', 3, 0.00),
    ('DEFAULT',    '04', 1, 15.00),
    ('DEFAULT',    '04', 2, 15.00),
    ('DEFAULT',    '04', 3, 15.00),
    ('DEFAULT',    '05', 1, 15.00),
    ('DEFAULT',    '06', 1, 15.00),
    ('DEFAULT',    '06', 2, 15.00),
    ('DEFAULT',    '07', 1, 0.00),
    -- -------------------------------------------------------------------------
    -- Block C: account group 'ZEROAPR' (17 rows) -- lines 35-51 of discgrp.txt
    -- Zero-APR promotional group: every (type, category) yields a 0.00 rate.
    -- -------------------------------------------------------------------------
    ('ZEROAPR',    '01', 1, 0.00),
    ('ZEROAPR',    '01', 2, 0.00),
    ('ZEROAPR',    '01', 3, 0.00),
    ('ZEROAPR',    '01', 4, 0.00),
    ('ZEROAPR',    '02', 1, 0.00),
    ('ZEROAPR',    '02', 2, 0.00),
    ('ZEROAPR',    '02', 3, 0.00),
    ('ZEROAPR',    '03', 1, 0.00),
    ('ZEROAPR',    '03', 2, 0.00),
    ('ZEROAPR',    '03', 3, 0.00),
    ('ZEROAPR',    '04', 1, 0.00),
    ('ZEROAPR',    '04', 2, 0.00),
    ('ZEROAPR',    '04', 3, 0.00),
    ('ZEROAPR',    '05', 1, 0.00),
    ('ZEROAPR',    '06', 1, 0.00),
    ('ZEROAPR',    '06', 2, 0.00),
    ('ZEROAPR',    '07', 1, 0.00)
on conflict (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd) do nothing;
