-- V1__create_schema.sql
-- PostgreSQL 16+ schema for CardDemo application
-- Migrated from COBOL/VSAM datasets defined in app/jcl/*.jcl
-- Record layouts from COBOL copybooks in app/cpy/*.cpy
-- Source: aws-samples/carddemo commit 27d6c6f
--
-- CRITICAL: All monetary columns use NUMERIC(n,s) — zero floating-point types
-- CRITICAL: Composite primary keys for tcatbal, discgrp, trancatg tables
-- CRITICAL: version columns for JPA @Version optimistic locking on accounts, cards
--
-- Table creation order satisfies foreign key dependencies:
--   1. transaction_types      (no FK deps; referenced by transaction_categories)
--   2. transaction_categories (FK → transaction_types)
--   3. accounts               (no FK deps; referenced by cards, card_cross_references)
--   4. customers              (no FK deps; referenced by card_cross_references)
--   5. cards                  (FK → accounts)
--   6. card_cross_references  (FK → accounts, customers)
--   7. transactions           (no enforced FK — COBOL compatibility)
--   8. user_security          (no FK deps)
--   9. transaction_category_balances (composite PK, no enforced FK)
--  10. disclosure_groups      (composite PK, no FK deps)
--  11. daily_transactions     (staging table, no FK deps)

-- ============================================================================
-- Table 1: transaction_types
-- Mapped from TRANTYPE.VSAM.KSDS (TRANTYPE.jcl), CVTRA03Y.cpy (60 bytes)
-- VSAM KEYS(2,0) RECORDSIZE(60,60)
-- ============================================================================
CREATE TABLE transaction_types (
    type_cd      CHAR(2)         NOT NULL,
    type_desc    VARCHAR(50)     NOT NULL,
    CONSTRAINT pk_tran_type PRIMARY KEY (type_cd)
);

COMMENT ON TABLE transaction_types IS 'Transaction type reference data. Source: TRANTYPE.VSAM.KSDS, CVTRA03Y.cpy (60 bytes)';

-- ============================================================================
-- Table 2: transaction_categories
-- Mapped from TRANCATG.VSAM.KSDS (TRANCATG.jcl), CVTRA04Y.cpy (60 bytes)
-- VSAM KEYS(6,0) RECORDSIZE(60,60)
-- Composite PK: type_cd(2) + cat_cd(4) = 6 bytes (VSAM KEYS(6,0))
-- ============================================================================
CREATE TABLE transaction_categories (
    type_cd      CHAR(2)         NOT NULL,
    cat_cd       SMALLINT        NOT NULL,
    cat_desc     VARCHAR(50)     NOT NULL,
    CONSTRAINT pk_tran_cat PRIMARY KEY (type_cd, cat_cd),
    CONSTRAINT fk_tran_cat_type FOREIGN KEY (type_cd)
        REFERENCES transaction_types (type_cd)
);

COMMENT ON TABLE transaction_categories IS 'Transaction category reference data. Source: TRANCATG.VSAM.KSDS, CVTRA04Y.cpy (60 bytes). Composite PK: type_cd(2) + cat_cd(4) = 6 bytes';

-- ============================================================================
-- Table 3: accounts
-- Mapped from ACCTDATA.VSAM.KSDS (ACCTFILE.jcl), CVACT01Y.cpy (300 bytes)
-- VSAM KEYS(11,0) RECORDSIZE(300,300)
-- PIC S9(10)V99 → NUMERIC(12,2): 10 integer + 2 decimal = 12 precision, 2 scale
-- version column for JPA @Version optimistic locking (AAP §0.8.4)
-- ============================================================================
CREATE TABLE accounts (
    acct_id             VARCHAR(11)     NOT NULL,
    active_status       CHAR(1)         NOT NULL DEFAULT 'Y',
    curr_bal            NUMERIC(12,2)   NOT NULL DEFAULT 0.00,
    credit_limit        NUMERIC(12,2)   NOT NULL DEFAULT 0.00,
    cash_credit_limit   NUMERIC(12,2)   NOT NULL DEFAULT 0.00,
    open_date           DATE,
    expiration_date     DATE,
    reissue_date        DATE,
    curr_cyc_credit     NUMERIC(12,2)   NOT NULL DEFAULT 0.00,
    curr_cyc_debit      NUMERIC(12,2)   NOT NULL DEFAULT 0.00,
    addr_zip            VARCHAR(10),
    group_id            VARCHAR(10),
    version             INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (acct_id)
);

COMMENT ON TABLE accounts IS 'Credit card accounts. Source: ACCTDATA.VSAM.KSDS, CVACT01Y.cpy (300 bytes). PK: ACCT-ID PIC 9(11)';

-- ============================================================================
-- Table 4: customers
-- Mapped from CUSTDATA.VSAM.KSDS (CUSTFILE.jcl), CVCUS01Y.cpy (500 bytes)
-- VSAM KEYS(9,0) RECORDSIZE(500,500)
-- PIC 9(09) IDs → VARCHAR(9) to preserve leading zeros
-- PIC 9(03) FICO → SMALLINT (integer, no decimals, range 300-850)
-- ============================================================================
CREATE TABLE customers (
    cust_id              VARCHAR(9)      NOT NULL,
    first_name           VARCHAR(25)     NOT NULL,
    middle_name          VARCHAR(25),
    last_name            VARCHAR(25)     NOT NULL,
    addr_line_1          VARCHAR(50),
    addr_line_2          VARCHAR(50),
    addr_line_3          VARCHAR(50),
    addr_state_cd        CHAR(2),
    addr_country_cd      CHAR(3),
    addr_zip             VARCHAR(10),
    phone_num_1          VARCHAR(15),
    phone_num_2          VARCHAR(15),
    ssn                  VARCHAR(9),
    govt_issued_id       VARCHAR(20),
    dob                  DATE,
    eft_account_id       VARCHAR(10),
    pri_card_holder_ind  CHAR(1),
    fico_credit_score    SMALLINT,
    CONSTRAINT pk_customers PRIMARY KEY (cust_id)
);

COMMENT ON TABLE customers IS 'Customer master data. Source: CUSTDATA.VSAM.KSDS, CVCUS01Y.cpy (500 bytes). PK: CUST-ID PIC 9(09)';

-- ============================================================================
-- Table 5: cards
-- Mapped from CARDDATA.VSAM.KSDS (CARDFILE.jcl), CVACT02Y.cpy (150 bytes)
-- VSAM KEYS(16,0) RECORDSIZE(150,150)
-- AIX on CARD-ACCT-ID: KEYS(11,16) NONUNIQUEKEY
-- PIC 9(11) → VARCHAR(11) for FK (preserve leading zeros)
-- PIC 9(03) → VARCHAR(3) for CVV (preserve leading zeros)
-- version column for JPA @Version optimistic locking (AAP §0.8.4)
-- ============================================================================
CREATE TABLE cards (
    card_num            VARCHAR(16)     NOT NULL,
    card_acct_id        VARCHAR(11)     NOT NULL,
    card_cvv_cd         VARCHAR(3)      NOT NULL,
    card_embossed_name  VARCHAR(50),
    expiration_date     DATE,
    active_status       CHAR(1)         NOT NULL DEFAULT 'Y',
    version             INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT pk_cards PRIMARY KEY (card_num),
    CONSTRAINT fk_cards_account FOREIGN KEY (card_acct_id)
        REFERENCES accounts (acct_id)
);

COMMENT ON TABLE cards IS 'Credit cards. Source: CARDDATA.VSAM.KSDS, CVACT02Y.cpy (150 bytes). PK: CARD-NUM PIC X(16). AIX on CARD-ACCT-ID';

-- ============================================================================
-- Table 6: card_cross_references
-- Mapped from CARDXREF.VSAM.KSDS (XREFFILE.jcl), CVACT03Y.cpy (50 bytes)
-- VSAM KEYS(16,0) RECORDSIZE(50,50)
-- AIX (CXACAIX) on XREF-ACCT-ID: KEYS(11,25) NONUNIQUEKEY
-- account_id column name aligns with CXACAIX alternate index access pattern
-- ============================================================================
CREATE TABLE card_cross_references (
    card_num        VARCHAR(16)     NOT NULL,
    cust_id         VARCHAR(9)      NOT NULL,
    account_id      VARCHAR(11)     NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (card_num),
    CONSTRAINT fk_card_xref_account FOREIGN KEY (account_id)
        REFERENCES accounts (acct_id),
    CONSTRAINT fk_card_xref_customer FOREIGN KEY (cust_id)
        REFERENCES customers (cust_id)
);

COMMENT ON TABLE card_cross_references IS 'Card-to-account-to-customer cross-reference. Source: CARDXREF.VSAM.KSDS, CVACT03Y.cpy (50 bytes). AIX (CXACAIX) on account_id';

-- ============================================================================
-- Table 7: transactions
-- Mapped from TRANSACT.VSAM.KSDS (TRANFILE.jcl), CVTRA05Y.cpy (350 bytes)
-- VSAM KEYS(16,0) RECORDSIZE(350,350)
-- AIX on TRAN-PROC-TS: KEYS(26,304) NONUNIQUEKEY
-- PIC S9(09)V99 → NUMERIC(11,2): 9 integer + 2 decimal = 11 precision, 2 scale
-- PIC X(26) timestamps → TIMESTAMP (YYYY-MM-DD-HH.MM.SS.mmmmmm format)
-- card_num is a logical reference, not an enforced FK (COBOL compatibility)
-- ============================================================================
CREATE TABLE transactions (
    tran_id          VARCHAR(16)     NOT NULL,
    type_cd          CHAR(2)         NOT NULL,
    cat_cd           SMALLINT        NOT NULL,
    source           VARCHAR(10),
    description      VARCHAR(100),
    amount           NUMERIC(11,2)   NOT NULL DEFAULT 0.00,
    merchant_id      VARCHAR(9),
    merchant_name    VARCHAR(50),
    merchant_city    VARCHAR(50),
    merchant_zip     VARCHAR(10),
    card_num         VARCHAR(16)     NOT NULL,
    orig_ts          TIMESTAMP,
    proc_ts          TIMESTAMP,
    CONSTRAINT pk_transactions PRIMARY KEY (tran_id)
);

COMMENT ON TABLE transactions IS 'Transaction master. Source: TRANSACT.VSAM.KSDS, CVTRA05Y.cpy (350 bytes). AIX on proc_ts';

-- ============================================================================
-- Table 8: user_security
-- Mapped from USRSEC.VSAM.KSDS (DUSRSECJ.jcl), CSUSR01Y.cpy (80 bytes)
-- VSAM KEYS(8,0) RECORDSIZE(80,80)
-- password_hash upgraded from plaintext PIC X(08) to BCrypt VARCHAR(60) per AAP §0.8.1
-- CHECK constraint on usr_type: 'A' (admin) or 'U' (regular user)
-- ============================================================================
CREATE TABLE user_security (
    usr_id          VARCHAR(8)      NOT NULL,
    usr_fname       VARCHAR(20)     NOT NULL,
    usr_lname       VARCHAR(20)     NOT NULL,
    password_hash   VARCHAR(60)     NOT NULL,
    usr_type        CHAR(1)         NOT NULL,
    CONSTRAINT pk_user_security PRIMARY KEY (usr_id),
    CONSTRAINT chk_usr_type CHECK (usr_type IN ('A', 'U'))
);

COMMENT ON TABLE user_security IS 'User authentication and authorization. Source: USRSEC.VSAM.KSDS, CSUSR01Y.cpy (80 bytes). password_hash: BCrypt (upgraded from plaintext)';

-- ============================================================================
-- Table 9: transaction_category_balances
-- Mapped from TCATBALF.VSAM.KSDS (TCATBALF.jcl), CVTRA01Y.cpy (50 bytes)
-- VSAM KEYS(17,0) RECORDSIZE(50,50)
-- Composite PK: acct_id(11) + type_cd(2) + cat_cd(4) = 17 bytes (VSAM KEYS(17,0))
-- PIC S9(09)V99 → NUMERIC(11,2) for balance
-- ============================================================================
CREATE TABLE transaction_category_balances (
    acct_id      VARCHAR(11)     NOT NULL,
    type_cd      CHAR(2)         NOT NULL,
    cat_cd       SMALLINT        NOT NULL,
    balance      NUMERIC(11,2)   NOT NULL DEFAULT 0.00,
    CONSTRAINT pk_tcat_bal PRIMARY KEY (acct_id, type_cd, cat_cd)
);

COMMENT ON TABLE transaction_category_balances IS 'Running balance per account/type/category. Source: TCATBALF.VSAM.KSDS, CVTRA01Y.cpy (50 bytes). Composite PK: 11+2+4 = 17 bytes';

-- ============================================================================
-- Table 10: disclosure_groups
-- Mapped from DISCGRP.VSAM.KSDS (DISCGRP.jcl), CVTRA02Y.cpy (50 bytes)
-- VSAM KEYS(16,0) RECORDSIZE(50,50)
-- Composite PK: group_id(10) + type_cd(2) + cat_cd(4) = 16 bytes (VSAM KEYS(16,0))
-- PIC S9(04)V99 → NUMERIC(6,2) for interest rate: 4 integer + 2 decimal = 6 precision
-- group_id values: per-account ('A000000000xx'), fallback ('DEFAULT'), zero ('ZEROAPR')
-- ============================================================================
CREATE TABLE disclosure_groups (
    group_id     VARCHAR(10)     NOT NULL,
    type_cd      CHAR(2)         NOT NULL,
    cat_cd       SMALLINT        NOT NULL,
    int_rate     NUMERIC(6,2)    NOT NULL DEFAULT 0.00,
    CONSTRAINT pk_disc_group PRIMARY KEY (group_id, type_cd, cat_cd)
);

COMMENT ON TABLE disclosure_groups IS 'Interest rate disclosure groups. Source: DISCGRP.VSAM.KSDS, CVTRA02Y.cpy (50 bytes). Composite PK: 10+2+4 = 16 bytes. group_id: per-account, DEFAULT, ZEROAPR';

-- ============================================================================
-- Table 11: daily_transactions
-- Staging table for daily transaction batch processing (POSTTRAN.jcl)
-- Mapped from DALYTRAN.PS sequential file, CVTRA06Y.cpy (350 bytes)
-- Mirrors transactions table layout exactly (CVTRA06Y mirrors CVTRA05Y)
-- No FK constraints — batch populates from S3 file, processes, then clears
-- ============================================================================
CREATE TABLE daily_transactions (
    tran_id          VARCHAR(16)     NOT NULL,
    type_cd          CHAR(2)         NOT NULL,
    cat_cd           SMALLINT        NOT NULL,
    source           VARCHAR(10),
    description      VARCHAR(100),
    amount           NUMERIC(11,2)   NOT NULL DEFAULT 0.00,
    merchant_id      VARCHAR(9),
    merchant_name    VARCHAR(50),
    merchant_city    VARCHAR(50),
    merchant_zip     VARCHAR(10),
    card_num         VARCHAR(16)     NOT NULL,
    orig_ts          TIMESTAMP,
    proc_ts          TIMESTAMP,
    CONSTRAINT pk_daily_transactions PRIMARY KEY (tran_id)
);

COMMENT ON TABLE daily_transactions IS 'Staging table for daily transaction batch processing. Source: DALYTRAN.PS, CVTRA06Y.cpy (350 bytes). Mirrors transactions layout';
