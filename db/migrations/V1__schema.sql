-- =============================================================================
-- V1__schema.sql
-- Flyway V1 migration: Schema creation for CardDemo Aurora PostgreSQL
--
-- Creates 11 tables migrated from VSAM KSDS datasets:
--   transaction_types, transaction_categories, accounts, customers, cards,
--   card_cross_references, transactions, transaction_category_balances,
--   daily_transactions, disclosure_groups, user_security
--
-- Source: app/jcl/*.jcl (IDCAMS DEFINE CLUSTER) + app/cpy/*.cpy (record layouts)
-- Transformation: VSAM KSDS DEFINE CLUSTER -> PostgreSQL CREATE TABLE
--
-- ----- COBOL -> PostgreSQL data type mapping rules -----
--   PIC S9(10)V99  -> NUMERIC(12,2)   (signed, 10 integer + 2 decimal digits)
--   PIC S9(09)V99  -> NUMERIC(11,2)   (signed, 9 integer + 2 decimal digits)
--   PIC S9(04)V99  -> NUMERIC(6,2)    (signed, 4 integer + 2 decimal digits)
--   PIC X(n)       -> CHAR(n) for fixed-width keys / status indicators
--                     VARCHAR(n) for variable-length text (names, addresses, ...)
--   PIC 9(n)       -> CHAR(n) for ID fields (preserves leading zeros from VSAM)
--                     SMALLINT for small countable values (e.g., FICO score 0-999)
--   FILLER         -> NOT migrated (record padding only, no semantic value)
--
-- ----- Architectural notes -----
--   * Per AAP, NO FOREIGN KEY constraints are defined.  VSAM has no native FK
--     concept; relational integrity was historically enforced by COBOL business
--     logic.  In the target system, integrity is enforced at the application
--     (FastAPI / SQLAlchemy / PySpark) layer.  Tables are nevertheless created
--     in logical-dependency order so that future FK constraints could be added.
--
--   * The password column SEC-USR-PWD is expanded from CHAR(8) (the original
--     VSAM length) to VARCHAR(100).  BCrypt password hashes are 60 characters;
--     the wider column is required by the AAP-mandated migration to BCrypt
--     hashing in the target authentication service.
--
--   * COBOL field-name typos (e.g., ACCT-EXPIRAION-DATE, CARD-EXPIRAION-DATE)
--     are corrected in PostgreSQL column names per the AAP specification.
--
--   * Date fields stored as CHAR(10) text strings preserve the COBOL PIC X(10)
--     YYYY-MM-DD representation exactly, avoiding any timezone or locale
--     ambiguity during the migration.  Conversion to native DATE/TIMESTAMP
--     types may be performed in a future migration if required.
--
--   * VSAM Alternate Indexes (AIX) become B-tree indexes in V2__indexes.sql.
--     Specifically: cards.card_acct_id (CARDDATA AIX), transactions.tran_proc_ts
--     (TRANSACT AIX), card_cross_references.acct_id (CARDXREF AIX).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Table 1: transaction_types
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   TRANTYPE.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/TRANTYPE.jcl
-- Source COBOL copybook: app/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60)
-- VSAM key definition:   KEYS(2,0)  -> 2-byte key at offset 0 (TRAN-TYPE)
-- Record size:           RECORDSIZE(60,60)
-- Excluded FILLER:       PIC X(08) at end of record (record padding only)
--
-- Purpose: Reference table mapping 2-character transaction type codes to their
--          human-readable descriptions (e.g., '01' = 'PURCHASE', '02' = 'REFUND').
-- Loaded first because it is referenced conceptually by transaction_categories,
-- transactions, transaction_category_balances and disclosure_groups.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_types (
    type_code       CHAR(2)     NOT NULL,    -- TRAN-TYPE       PIC X(02)
    tran_type_desc  VARCHAR(50),              -- TRAN-TYPE-DESC  PIC X(50)
    PRIMARY KEY (type_code)
);


-- -----------------------------------------------------------------------------
-- Table 2: transaction_categories
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   TRANCATG.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/TRANCATG.jcl
-- Source COBOL copybook: app/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD, RECLN 60)
-- VSAM key definition:   KEYS(6,0)  -> 6-byte composite key at offset 0
-- Record size:           RECORDSIZE(60,60)
-- Excluded FILLER:       PIC X(04) at end of record (record padding only)
--
-- Composite key components (TRAN-CAT-KEY):
--   TRAN-TYPE-CD  PIC X(02) - 2 bytes (links to transaction_types.type_code)
--   TRAN-CAT-CD   PIC 9(04) - 4 bytes (numeric category, leading zeros preserved)
--
-- Purpose: Reference table mapping (type_code, cat_code) pairs to category
--          descriptions used for transaction posting and reporting.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_categories (
    type_code           CHAR(2)     NOT NULL, -- TRAN-TYPE-CD       PIC X(02)
    cat_code            CHAR(4)     NOT NULL, -- TRAN-CAT-CD        PIC 9(04)
    tran_cat_type_desc  VARCHAR(50),           -- TRAN-CAT-TYPE-DESC PIC X(50)
    PRIMARY KEY (type_code, cat_code)
);


-- -----------------------------------------------------------------------------
-- Table 3: accounts
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   ACCTDATA.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/ACCTFILE.jcl
-- Source COBOL copybook: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300)
-- VSAM key definition:   KEYS(11,0) -> 11-byte key at offset 0 (ACCT-ID)
-- Record size:           RECORDSIZE(300,300)
-- Excluded FILLER:       PIC X(178) at end of record (record padding only)
--
-- Purpose: Core entity representing a credit-card account.  Holds current and
--          cycle balances, credit limits, lifecycle dates, billing zip and the
--          disclosure-group identifier used by the interest-calculation batch.
--
-- COBOL field rename:    ACCT-EXPIRAION-DATE (typo in copybook) is normalised
--                        to acct_expiration_date in PostgreSQL.
-- -----------------------------------------------------------------------------
CREATE TABLE accounts (
    acct_id                 CHAR(11)      NOT NULL,                 -- ACCT-ID                PIC 9(11)
    acct_active_status      CHAR(1)       NOT NULL DEFAULT 'Y',     -- ACCT-ACTIVE-STATUS     PIC X(01)  ('Y'/'N')
    acct_curr_bal           NUMERIC(12,2) NOT NULL DEFAULT 0,       -- ACCT-CURR-BAL          PIC S9(10)V99
    acct_credit_limit       NUMERIC(12,2) NOT NULL DEFAULT 0,       -- ACCT-CREDIT-LIMIT      PIC S9(10)V99
    acct_cash_credit_limit  NUMERIC(12,2) NOT NULL DEFAULT 0,       -- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
    acct_open_date          VARCHAR(10),                            -- ACCT-OPEN-DATE         PIC X(10)  YYYY-MM-DD
    acct_expiration_date    VARCHAR(10),                            -- ACCT-EXPIRAION-DATE    PIC X(10)  YYYY-MM-DD (typo normalised)
    acct_reissue_date       VARCHAR(10),                            -- ACCT-REISSUE-DATE      PIC X(10)  YYYY-MM-DD
    acct_curr_cyc_credit    NUMERIC(12,2) NOT NULL DEFAULT 0,       -- ACCT-CURR-CYC-CREDIT   PIC S9(10)V99
    acct_curr_cyc_debit     NUMERIC(12,2) NOT NULL DEFAULT 0,       -- ACCT-CURR-CYC-DEBIT    PIC S9(10)V99
    acct_addr_zip           VARCHAR(10),                            -- ACCT-ADDR-ZIP          PIC X(10)
    acct_group_id           CHAR(10),                               -- ACCT-GROUP-ID          PIC X(10)  links to disclosure_groups
    PRIMARY KEY (acct_id)
);


-- -----------------------------------------------------------------------------
-- Table 4: customers
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   CUSTDATA.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/CUSTFILE.jcl
-- Source COBOL copybook: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500)
-- VSAM key definition:   KEYS(9,0) -> 9-byte key at offset 0 (CUST-ID)
-- Record size:           RECORDSIZE(500,500)
-- Excluded FILLER:       PIC X(168) at end of record (record padding only)
--
-- Purpose: Core entity representing the customer (cardholder) demographic and
--          contact information.  Linked to accounts via card_cross_references.
--
-- Sensitive data:
--   * cust_ssn               -> Social Security Number, treat as PII
--   * cust_govt_issued_id    -> Government-issued ID number, treat as PII
--   Encryption / masking is performed at the application layer per AAP
--   security requirements (no column-level encryption at the schema layer).
-- -----------------------------------------------------------------------------
CREATE TABLE customers (
    cust_id                   CHAR(9)     NOT NULL, -- CUST-ID                  PIC 9(09)
    cust_first_name           VARCHAR(25),           -- CUST-FIRST-NAME          PIC X(25)
    cust_middle_name          VARCHAR(25),           -- CUST-MIDDLE-NAME         PIC X(25)
    cust_last_name            VARCHAR(25),           -- CUST-LAST-NAME           PIC X(25)
    cust_addr_line_1          VARCHAR(50),           -- CUST-ADDR-LINE-1         PIC X(50)
    cust_addr_line_2          VARCHAR(50),           -- CUST-ADDR-LINE-2         PIC X(50)
    cust_addr_line_3          VARCHAR(50),           -- CUST-ADDR-LINE-3         PIC X(50)
    cust_addr_state_cd        CHAR(2),               -- CUST-ADDR-STATE-CD       PIC X(02)
    cust_addr_country_cd      CHAR(3),               -- CUST-ADDR-COUNTRY-CD     PIC X(03)
    cust_addr_zip             VARCHAR(10),           -- CUST-ADDR-ZIP            PIC X(10)
    cust_phone_num_1          VARCHAR(15),           -- CUST-PHONE-NUM-1         PIC X(15)
    cust_phone_num_2          VARCHAR(15),           -- CUST-PHONE-NUM-2         PIC X(15)
    cust_ssn                  CHAR(9),               -- CUST-SSN                 PIC 9(09)  PII
    cust_govt_issued_id       VARCHAR(20),           -- CUST-GOVT-ISSUED-ID      PIC X(20)  PII
    cust_dob_yyyy_mm_dd       VARCHAR(10),           -- CUST-DOB-YYYY-MM-DD      PIC X(10)
    cust_eft_account_id       VARCHAR(10),           -- CUST-EFT-ACCOUNT-ID      PIC X(10)
    cust_pri_card_holder_ind  CHAR(1),               -- CUST-PRI-CARD-HOLDER-IND PIC X(01)
    cust_fico_credit_score    SMALLINT,              -- CUST-FICO-CREDIT-SCORE   PIC 9(03)  range 0-999
    PRIMARY KEY (cust_id)
);


-- -----------------------------------------------------------------------------
-- Table 5: cards
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   CARDDATA.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/CARDFILE.jcl
-- Source COBOL copybook: app/cpy/CVACT02Y.cpy (CARD-RECORD, RECLN 150)
-- VSAM key definition:   KEYS(16,0) -> 16-byte key at offset 0 (CARD-NUM)
-- Record size:           RECORDSIZE(150,150)
-- Excluded FILLER:       PIC X(59) at end of record (record padding only)
--
-- VSAM AIX:              KEYS(11,16) NONUNIQUEKEY on CARD-ACCT-ID
--                        Migrated as a non-unique B-tree index in V2.
--
-- Purpose: Represents a physical/virtual credit card associated with an account.
--          One account may have many cards (the AIX provides the access path).
--
-- COBOL field rename:    CARD-EXPIRAION-DATE (typo in copybook) is normalised
--                        to card_expiration_date in PostgreSQL.
-- -----------------------------------------------------------------------------
CREATE TABLE cards (
    card_num              CHAR(16)    NOT NULL,                 -- CARD-NUM             PIC X(16)
    card_acct_id          CHAR(11)    NOT NULL,                 -- CARD-ACCT-ID         PIC 9(11)  AIX target
    card_cvv_cd           CHAR(3),                              -- CARD-CVV-CD          PIC 9(03)
    card_embossed_name    VARCHAR(50),                          -- CARD-EMBOSSED-NAME   PIC X(50)
    card_expiration_date  VARCHAR(10),                          -- CARD-EXPIRAION-DATE  PIC X(10)  YYYY-MM-DD (typo normalised)
    card_active_status    CHAR(1)     NOT NULL DEFAULT 'Y',     -- CARD-ACTIVE-STATUS   PIC X(01)
    PRIMARY KEY (card_num)
);


-- -----------------------------------------------------------------------------
-- Table 6: card_cross_references
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   CARDXREF.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/XREFFILE.jcl
-- Source COBOL copybook: app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50)
-- VSAM key definition:   KEYS(16,0) -> 16-byte key at offset 0 (XREF-CARD-NUM)
-- Record size:           RECORDSIZE(50,50)
-- Excluded FILLER:       PIC X(14) at end of record (record padding only)
--
-- VSAM AIX:              KEYS(11,25) NONUNIQUEKEY on XREF-ACCT-ID
--                        Migrated as a non-unique B-tree index in V2.
--
-- Purpose: Three-way cross-reference linking a card number to its owning
--          customer and account.  Used as the primary lookup table for online
--          authentication and transaction-add programs (CICS COSGN00C,
--          COTRN02C) and the batch transaction-posting job (CBTRN02C).
-- -----------------------------------------------------------------------------
CREATE TABLE card_cross_references (
    card_num  CHAR(16) NOT NULL,                 -- XREF-CARD-NUM PIC X(16)
    cust_id   CHAR(9)  NOT NULL,                 -- XREF-CUST-ID  PIC 9(09)
    acct_id   CHAR(11) NOT NULL,                 -- XREF-ACCT-ID  PIC 9(11)  AIX target
    PRIMARY KEY (card_num)
);


-- -----------------------------------------------------------------------------
-- Table 7: transactions
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   TRANSACT.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/TRANFILE.jcl
-- Source COBOL copybook: app/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350)
-- VSAM key definition:   KEYS(16,0) -> 16-byte key at offset 0 (TRAN-ID)
-- Record size:           RECORDSIZE(350,350)
-- Excluded FILLER:       PIC X(20) at end of record (record padding only)
--
-- VSAM AIX:              KEYS(26,304) NONUNIQUEKEY on TRAN-PROC-TS
--                        Migrated as a non-unique B-tree index in V2 to support
--                        date-range scans by the TRANREPT batch job (CBTRN03C).
--
-- Purpose: Posted-transaction master record produced by the transaction-posting
--          batch job (CBTRN02C) and read by online detail/list programs
--          (COTRN00C, COTRN01C, COTRN02C, COBIL00C) and reporting batch jobs
--          (CBSTM03A, CBTRN03C).
--
-- Timestamp storage:     TRAN-ORIG-TS / TRAN-PROC-TS preserved as VARCHAR(26)
--                        to retain the COBOL EXEC CICS ASKTIME / FORMATTIME
--                        ABSTIME representation 'YYYY-MM-DD HH:MM:SS.uuuuuu'.
-- -----------------------------------------------------------------------------
CREATE TABLE transactions (
    tran_id             CHAR(16)      NOT NULL,                 -- TRAN-ID             PIC X(16)
    tran_type_cd        CHAR(2),                                -- TRAN-TYPE-CD        PIC X(02)
    tran_cat_cd         CHAR(4),                                -- TRAN-CAT-CD         PIC 9(04)
    tran_source         VARCHAR(10),                            -- TRAN-SOURCE         PIC X(10)
    tran_desc           VARCHAR(100),                           -- TRAN-DESC           PIC X(100)
    tran_amt            NUMERIC(11,2) NOT NULL DEFAULT 0,       -- TRAN-AMT            PIC S9(09)V99
    tran_merchant_id    CHAR(9),                                -- TRAN-MERCHANT-ID    PIC 9(09)
    tran_merchant_name  VARCHAR(50),                            -- TRAN-MERCHANT-NAME  PIC X(50)
    tran_merchant_city  VARCHAR(50),                            -- TRAN-MERCHANT-CITY  PIC X(50)
    tran_merchant_zip   VARCHAR(10),                            -- TRAN-MERCHANT-ZIP   PIC X(10)
    tran_card_num       CHAR(16),                               -- TRAN-CARD-NUM       PIC X(16)
    tran_orig_ts        VARCHAR(26),                            -- TRAN-ORIG-TS        PIC X(26)
    tran_proc_ts        VARCHAR(26),                            -- TRAN-PROC-TS        PIC X(26)  AIX target
    PRIMARY KEY (tran_id)
);


-- -----------------------------------------------------------------------------
-- Table 8: transaction_category_balances
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   TCATBALF.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/TCATBALF.jcl
-- Source COBOL copybook: app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD, RECLN 50)
-- VSAM key definition:   KEYS(17,0) -> 17-byte composite key at offset 0
-- Record size:           RECORDSIZE(50,50)
-- Excluded FILLER:       PIC X(22) at end of record (record padding only)
--
-- Composite key components (TRAN-CAT-KEY):
--   TRANCAT-ACCT-ID  PIC 9(11) - 11 bytes
--   TRANCAT-TYPE-CD  PIC X(02) - 2 bytes
--   TRANCAT-CD       PIC 9(04) - 4 bytes
--
-- Purpose: Per-account, per-category running balance updated by the transaction
--          posting batch job (CBTRN02C) and read by the interest calculation
--          batch job (CBACT04C).  The interest formula is:
--              interest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
--          where DIS-INT-RATE comes from disclosure_groups.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_category_balances (
    acct_id       CHAR(11)      NOT NULL,                       -- TRANCAT-ACCT-ID  PIC 9(11)
    type_code     CHAR(2)       NOT NULL,                       -- TRANCAT-TYPE-CD  PIC X(02)
    cat_code      CHAR(4)       NOT NULL,                       -- TRANCAT-CD       PIC 9(04)
    tran_cat_bal  NUMERIC(11,2) NOT NULL DEFAULT 0,             -- TRAN-CAT-BAL     PIC S9(09)V99
    PRIMARY KEY (acct_id, type_code, cat_code)
);


-- -----------------------------------------------------------------------------
-- Table 9: daily_transactions
-- -----------------------------------------------------------------------------
-- Source COBOL copybook: app/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350)
-- Layout:                Identical to CVTRA05Y.cpy (TRAN-RECORD) but with the
--                        DALYTRAN- prefix on every field.
-- Excluded FILLER:       PIC X(20) at end of record (record padding only)
--
-- Note:                  This table is a STAGING table for the POSTTRAN batch
--                        pipeline (CBTRN02C / app/jcl/POSTTRAN.jcl).  In the
--                        original mainframe implementation the daily transaction
--                        file was a sequential PS dataset (DALYTRAN); in the
--                        target Aurora implementation it is materialised as a
--                        regular table populated by S3-based ingest before
--                        POSTTRAN runs.
--
-- Purpose: Holds the unposted daily transaction batch consumed by the
--          transaction-posting Glue job (src/batch/jobs/posttran_job.py).
--          Records are validated, posted into transactions, and either committed
--          or written to a reject log per the 4-stage validation cascade
--          (reject codes 100-109 preserved from CBTRN02C).
-- -----------------------------------------------------------------------------
CREATE TABLE daily_transactions (
    dalytran_id             CHAR(16)      NOT NULL,             -- DALYTRAN-ID             PIC X(16)
    dalytran_type_cd        CHAR(2),                            -- DALYTRAN-TYPE-CD        PIC X(02)
    dalytran_cat_cd         CHAR(4),                            -- DALYTRAN-CAT-CD         PIC 9(04)
    dalytran_source         VARCHAR(10),                        -- DALYTRAN-SOURCE         PIC X(10)
    dalytran_desc           VARCHAR(100),                       -- DALYTRAN-DESC           PIC X(100)
    dalytran_amt            NUMERIC(11,2) NOT NULL DEFAULT 0,   -- DALYTRAN-AMT            PIC S9(09)V99
    dalytran_merchant_id    CHAR(9),                            -- DALYTRAN-MERCHANT-ID    PIC 9(09)
    dalytran_merchant_name  VARCHAR(50),                        -- DALYTRAN-MERCHANT-NAME  PIC X(50)
    dalytran_merchant_city  VARCHAR(50),                        -- DALYTRAN-MERCHANT-CITY  PIC X(50)
    dalytran_merchant_zip   VARCHAR(10),                        -- DALYTRAN-MERCHANT-ZIP   PIC X(10)
    dalytran_card_num       CHAR(16),                           -- DALYTRAN-CARD-NUM       PIC X(16)
    dalytran_orig_ts        VARCHAR(26),                        -- DALYTRAN-ORIG-TS        PIC X(26)
    dalytran_proc_ts        VARCHAR(26),                        -- DALYTRAN-PROC-TS        PIC X(26)
    PRIMARY KEY (dalytran_id)
);


-- -----------------------------------------------------------------------------
-- Table 10: disclosure_groups
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   DISCGRP.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/DISCGRP.jcl
-- Source COBOL copybook: app/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD, RECLN 50)
-- VSAM key definition:   KEYS(16,0) -> 16-byte composite key at offset 0
-- Record size:           RECORDSIZE(50,50)
-- Excluded FILLER:       PIC X(28) at end of record (record padding only)
--
-- Composite key components (DIS-GROUP-KEY):
--   DIS-ACCT-GROUP-ID  PIC X(10) - 10 bytes
--                      ('A000000000', 'DEFAULT   ', 'ZEROAPR   ', ...)
--   DIS-TRAN-TYPE-CD   PIC X(02) - 2 bytes
--   DIS-TRAN-CAT-CD    PIC 9(04) - 4 bytes
--
-- Purpose: Provides the interest rate (APR/12 expressed as a percentage) used
--          by the interest-calculation batch (CBACT04C / app/jcl/INTCALC.jcl).
--          The DEFAULT and ZEROAPR special groups provide fallback rates when
--          an account's specific (group_id, type, cat) tuple is not found.
--          The fallback chain is preserved exactly per AAP business-logic rule.
-- -----------------------------------------------------------------------------
CREATE TABLE disclosure_groups (
    dis_acct_group_id  CHAR(10)     NOT NULL,                   -- DIS-ACCT-GROUP-ID  PIC X(10)
    dis_tran_type_cd   CHAR(2)      NOT NULL,                   -- DIS-TRAN-TYPE-CD   PIC X(02)
    dis_tran_cat_cd    CHAR(4)      NOT NULL,                   -- DIS-TRAN-CAT-CD    PIC 9(04)
    dis_int_rate       NUMERIC(6,2) NOT NULL DEFAULT 0,         -- DIS-INT-RATE       PIC S9(04)V99
    PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);


-- -----------------------------------------------------------------------------
-- Table 11: user_security
-- -----------------------------------------------------------------------------
-- Source VSAM cluster:   USRSEC.VSAM.KSDS
-- Source IDCAMS JCL:     app/jcl/DUSRSECJ.jcl
-- Source COBOL copybook: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, RECLN 80)
-- VSAM key definition:   KEYS(8,0) -> 8-byte key at offset 0 (SEC-USR-ID)
-- Record size:           RECORDSIZE(80,80)
-- Excluded FILLER:       SEC-USR-FILLER PIC X(23) (record padding only)
--
-- Purpose: Authentication and authorisation table for the online API.
--          Replaces the CICS-era VSAM credentials store.  The COSGN00C sign-on
--          program (and its target FastAPI auth service) verifies (user_id,
--          password) against this table to issue a JWT session token.
--
-- Password column expansion:
--   SEC-USR-PWD was PIC X(08) (8 bytes) in the COBOL copybook, which stored
--   plaintext passwords.  In the migrated system this column is widened to
--   VARCHAR(100) to accommodate BCrypt hashes (60 characters), satisfying the
--   AAP requirement to "preserve BCrypt password hashing for user
--   authentication (matching existing COBOL behavior)".  Hash generation is
--   performed at the application layer via passlib[bcrypt].
--
-- User type:
--   sec_usr_type values: 'A' = Administrator, 'U' = Standard User
--   This drives the admin/user menu split (COMEN01C vs COADM01C) in the
--   original CICS application and the corresponding /admin vs /menu routes
--   in the migrated FastAPI service.
-- -----------------------------------------------------------------------------
CREATE TABLE user_security (
    user_id        CHAR(8)      NOT NULL,                       -- SEC-USR-ID    PIC X(08)
    sec_usr_fname  VARCHAR(20),                                 -- SEC-USR-FNAME PIC X(20)
    sec_usr_lname  VARCHAR(20),                                 -- SEC-USR-LNAME PIC X(20)
    sec_usr_pwd    VARCHAR(100) NOT NULL,                       -- SEC-USR-PWD   PIC X(08) widened for BCrypt
    sec_usr_type   CHAR(1)      NOT NULL DEFAULT 'U',           -- SEC-USR-TYPE  PIC X(01)  ('A'/'U')
    PRIMARY KEY (user_id)
);


-- =============================================================================
-- End of V1__schema.sql
-- 11 tables created successfully.
-- Next migration: V2__indexes.sql (B-tree indexes for VSAM AIX targets)
-- =============================================================================
