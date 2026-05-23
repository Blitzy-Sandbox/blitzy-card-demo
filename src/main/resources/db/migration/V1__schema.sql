-- ============================================================================
-- V1__schema.sql — CardDemo PostgreSQL Schema (initial migration)
-- ============================================================================
--
-- Source-of-truth: AWS CardDemo COBOL/JCL VSAM file layouts under app/cpy/*.cpy
-- Target runtime:   PostgreSQL 16 (Testcontainers postgres:16-alpine)
-- Charset:          UTF-8
--
-- Design notes:
--   * Fixed-width COBOL fields (PIC X(N) / PIC 9(N)) -> CHAR(N) so PostgreSQL
--     preserves trailing space padding byte-for-byte. The DEFAULT and ZEROAPR
--     sentinels in discount_groups are 7 chars + 3 trailing spaces = 10 chars;
--     CHAR(10) is essential for findById parity. VARCHAR strips trailing space.
--   * Free-form text fields (names, addresses) use VARCHAR.
--   * Monetary fields are NUMERIC(p,s) at the scale derived from the COBOL
--     PIC clause (e.g., PIC S9(10)V99 -> NUMERIC(12,2)). Never float/double
--     per AAP §0.10.3.
--   * `version` BIGINT NOT NULL DEFAULT 0 on entities subject to optimistic
--     locking (Account, Card, Customer, SecurityUser) — JPA @Version field.
--   * Boolean column `locked` on security_users is a Java-migration addition
--     (no COBOL equivalent); see SecurityUser entity Javadoc.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. transaction_types — CVTRA03Y.cpy reference data (read-only catalog, 7 rows)
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_types (
    tran_type      CHAR(2)      NOT NULL,
    tran_type_desc VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_transaction_types PRIMARY KEY (tran_type)
);

-- ---------------------------------------------------------------------------
-- 2. transaction_categories — CVTRA04Y.cpy reference data (read-only, 18 rows)
-- Composite primary key (tran_type_cd, tran_cat_cd) mirrors COBOL key fields
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_categories (
    tran_type_cd       CHAR(2)     NOT NULL,
    tran_cat_cd        INTEGER     NOT NULL,
    tran_cat_type_desc VARCHAR(50) NOT NULL,
    CONSTRAINT pk_transaction_categories PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

-- ---------------------------------------------------------------------------
-- 3. discount_groups — CVACT01Y.cpy / discgrp.txt reference data (51 rows)
-- Composite PK (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd).
-- dis_acct_group_id is CHAR(10) so DEFAULT/ZEROAPR sentinels preserve 3-space trailing pad.
-- ---------------------------------------------------------------------------
CREATE TABLE discount_groups (
    dis_acct_group_id CHAR(10)      NOT NULL,
    dis_tran_type_cd  CHAR(2)       NOT NULL,
    dis_tran_cat_cd   INTEGER       NOT NULL,
    dis_int_rate      NUMERIC(6, 2) NOT NULL,
    CONSTRAINT pk_discount_groups PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

-- ---------------------------------------------------------------------------
-- 4. customers — CUSTREC.cpy / CVCUS01Y.cpy customer master
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    cust_id              CHAR(9)     NOT NULL,
    first_name           VARCHAR(25),
    middle_name          VARCHAR(25),
    last_name            VARCHAR(25),
    addr_line_1          VARCHAR(50),
    addr_line_2          VARCHAR(50),
    addr_line_3          VARCHAR(50),
    addr_state_cd        CHAR(2),
    addr_country_cd      CHAR(3),
    addr_zip             VARCHAR(10),
    phone_num_1          VARCHAR(15),
    phone_num_2          VARCHAR(15),
    ssn                  CHAR(9),
    govt_issued_id       VARCHAR(20),
    dob_yyyy_mm_dd       CHAR(10),
    eft_account_id       VARCHAR(10),
    pri_card_holder_ind  CHAR(1),
    fico_credit_score    INTEGER,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_customers PRIMARY KEY (cust_id)
);

-- ---------------------------------------------------------------------------
-- 5. accounts — CVACT01Y.cpy account master
-- All monetary fields NUMERIC(12,2) per COBOL PIC S9(10)V99.
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    acct_id            CHAR(11)       NOT NULL,
    active_status      CHAR(1)        NOT NULL,
    curr_bal           NUMERIC(12, 2) NOT NULL,
    credit_limit       NUMERIC(12, 2) NOT NULL,
    cash_credit_limit  NUMERIC(12, 2) NOT NULL,
    open_date          CHAR(10),
    expiration_date    CHAR(10),
    reissue_date       CHAR(10),
    curr_cyc_credit    NUMERIC(12, 2) NOT NULL,
    curr_cyc_debit     NUMERIC(12, 2) NOT NULL,
    addr_zip           VARCHAR(10),
    group_id           VARCHAR(10),
    customer_id        CHAR(9),
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (acct_id)
);

-- ---------------------------------------------------------------------------
-- 6. cards — CVACT02Y.cpy card master
-- ---------------------------------------------------------------------------
CREATE TABLE cards (
    card_num             CHAR(16)    NOT NULL,
    card_acct_id         CHAR(11)    NOT NULL,
    card_cvv_cd          CHAR(3),
    card_embossed_name   VARCHAR(50),
    card_expiration_date CHAR(10),
    card_active_status   CHAR(1)     NOT NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_cards PRIMARY KEY (card_num)
);

-- ---------------------------------------------------------------------------
-- 7. card_xref — CVACT03Y.cpy card cross-reference (PAN <-> customer/account)
-- ---------------------------------------------------------------------------
CREATE TABLE card_xref (
    xref_card_num CHAR(16) NOT NULL,
    xref_cust_id  CHAR(9)  NOT NULL,
    xref_acct_id  CHAR(11) NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num)
);

-- ---------------------------------------------------------------------------
-- 8. transactions — CVTRA05Y.cpy transaction master
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    transaction_id            CHAR(16)       NOT NULL,
    transaction_type_code     CHAR(2),
    transaction_category_code CHAR(4),
    source                    CHAR(10),
    description               CHAR(100),
    amount                    NUMERIC(11, 2),
    merchant_id               CHAR(9),
    merchant_name             VARCHAR(50),
    merchant_city             VARCHAR(50),
    merchant_zip              VARCHAR(10),
    card_number               CHAR(16),
    origin_timestamp          CHAR(26),
    process_timestamp         CHAR(26),
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id)
);

-- ---------------------------------------------------------------------------
-- 9. transaction_category_balances — CVTRA01Y.cpy TCATBAL (interest calc input)
-- Composite PK (trancat_acct_id, trancat_type_cd, trancat_cd)
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_category_balances (
    trancat_acct_id  CHAR(11)       NOT NULL,
    trancat_type_cd  CHAR(2)        NOT NULL,
    trancat_cd       INTEGER        NOT NULL,
    tran_cat_bal     NUMERIC(11, 2) NOT NULL,
    CONSTRAINT pk_transaction_category_balances
        PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);

-- ---------------------------------------------------------------------------
-- 10. security_users — CSUSR01Y.cpy USRSEC user catalog
-- password is widened to VARCHAR(60) for BCrypt hash storage per AAP §0.10.5.
-- locked is a Java-migration addition (no COBOL equivalent).
-- ---------------------------------------------------------------------------
CREATE TABLE security_users (
    user_id    CHAR(8)     NOT NULL,
    first_name VARCHAR(20),
    last_name  VARCHAR(20),
    password   VARCHAR(60) NOT NULL,
    user_type  CHAR(1)     NOT NULL,
    locked     BOOLEAN     NOT NULL DEFAULT FALSE,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_security_users PRIMARY KEY (user_id)
);
