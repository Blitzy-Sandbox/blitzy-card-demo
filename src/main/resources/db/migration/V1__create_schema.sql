-- =============================================================================
-- Flyway V1 baseline schema -- CardDemo (legacy VSAM KSDS -> PostgreSQL 16)
-- 11 business tables derived from app/cpy copybooks + app/jcl VSAM DEFINE CLUSTER
-- jobs at source commit 27d6c6f. Indexes live in V2; seed data in V3.
-- =============================================================================

-- accounts: CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300) / ACCTFILE.jcl KEYS(11 0)
CREATE TABLE accounts (
    acct_id           BIGINT        NOT NULL,            -- ACCT-ID 9(11), bytes 1-11
    active_status     CHAR(1),                           -- ACCT-ACTIVE-STATUS X(01), byte 12
    curr_bal          NUMERIC(12,2),                     -- ACCT-CURR-BAL S9(10)V99, bytes 13-24
    credit_limit      NUMERIC(12,2),                     -- ACCT-CREDIT-LIMIT S9(10)V99, bytes 25-36
    cash_credit_limit NUMERIC(12,2),                     -- ACCT-CASH-CREDIT-LIMIT S9(10)V99, bytes 37-48
    open_date         VARCHAR(10),                       -- ACCT-OPEN-DATE X(10), bytes 49-58
    expiraion_date    VARCHAR(10),                       -- ACCT-EXPIRAION-DATE X(10), bytes 59-68 (COBOL spelling preserved)
    reissue_date      VARCHAR(10),                       -- ACCT-REISSUE-DATE X(10), bytes 69-78
    curr_cyc_credit   NUMERIC(12,2),                     -- ACCT-CURR-CYC-CREDIT S9(10)V99, bytes 79-90
    curr_cyc_debit    NUMERIC(12,2),                     -- ACCT-CURR-CYC-DEBIT S9(10)V99, bytes 91-102
    addr_zip          VARCHAR(10),                       -- ACCT-ADDR-ZIP X(10), bytes 103-112
    group_id          VARCHAR(10),                       -- ACCT-GROUP-ID X(10), bytes 113-122
    version           BIGINT        NOT NULL DEFAULT 0,  -- optimistic-lock backing (JPA @Version)
    CONSTRAINT pk_accounts PRIMARY KEY (acct_id)
);

-- cards: CVACT02Y.cpy (CARD-RECORD, RECLN 150) / CARDFILE.jcl KEYS(16 0)
CREATE TABLE cards (
    card_num       CHAR(16)      NOT NULL,               -- CARD-NUM X(16), bytes 1-16
    card_acct_id   BIGINT,                                -- CARD-ACCT-ID 9(11), bytes 17-27
    cvv_cd         INTEGER,                               -- CARD-CVV-CD 9(03), bytes 28-30
    embossed_name  VARCHAR(50),                           -- CARD-EMBOSSED-NAME X(50), bytes 31-80
    expiraion_date VARCHAR(10),                           -- CARD-EXPIRAION-DATE X(10), bytes 81-90 (COBOL spelling preserved)
    active_status  CHAR(1),                               -- CARD-ACTIVE-STATUS X(01), byte 91
    version        BIGINT        NOT NULL DEFAULT 0,      -- optimistic-lock backing (JPA @Version)
    CONSTRAINT pk_cards PRIMARY KEY (card_num)
);

-- card_xref: CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50) / XREFFILE.jcl KEYS(16 0)
CREATE TABLE card_xref (
    xref_card_num CHAR(16)       NOT NULL,                -- XREF-CARD-NUM X(16), bytes 1-16
    xref_cust_id  BIGINT,                                 -- XREF-CUST-ID 9(09), bytes 17-25
    xref_acct_id  BIGINT,                                 -- XREF-ACCT-ID 9(11), bytes 26-36
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num)
);

-- customers: CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500) / CUSTFILE.jcl KEYS(9 0)
CREATE TABLE customers (
    cust_id             BIGINT    NOT NULL,               -- CUST-ID 9(09), bytes 1-9
    first_name          VARCHAR(25),                      -- CUST-FIRST-NAME X(25), bytes 10-34
    middle_name         VARCHAR(25),                      -- CUST-MIDDLE-NAME X(25), bytes 35-59
    last_name           VARCHAR(25),                      -- CUST-LAST-NAME X(25), bytes 60-84
    addr_line_1         VARCHAR(50),                      -- CUST-ADDR-LINE-1 X(50), bytes 85-134
    addr_line_2         VARCHAR(50),                      -- CUST-ADDR-LINE-2 X(50), bytes 135-184
    addr_line_3         VARCHAR(50),                      -- CUST-ADDR-LINE-3 X(50), bytes 185-234
    addr_state_cd       CHAR(2),                          -- CUST-ADDR-STATE-CD X(02), bytes 235-236
    addr_country_cd     CHAR(3),                          -- CUST-ADDR-COUNTRY-CD X(03), bytes 237-239
    addr_zip            VARCHAR(10),                      -- CUST-ADDR-ZIP X(10), bytes 240-249
    phone_num_1         VARCHAR(15),                      -- CUST-PHONE-NUM-1 X(15), bytes 250-264
    phone_num_2         VARCHAR(15),                      -- CUST-PHONE-NUM-2 X(15), bytes 265-279
    ssn                 CHAR(9),                          -- CUST-SSN 9(09), bytes 280-288 (leading-zero sensitive)
    govt_issued_id      VARCHAR(20),                      -- CUST-GOVT-ISSUED-ID X(20), bytes 289-308
    dob                 VARCHAR(10),                      -- CUST-DOB-YYYY-MM-DD X(10), bytes 309-318
    eft_account_id      VARCHAR(10),                      -- CUST-EFT-ACCOUNT-ID X(10), bytes 319-328
    pri_card_holder_ind CHAR(1),                          -- CUST-PRI-CARD-HOLDER-IND X(01), byte 329
    fico_credit_score   INTEGER,                          -- CUST-FICO-CREDIT-SCORE 9(03), bytes 330-332
    version             BIGINT    NOT NULL DEFAULT 0,     -- optimistic-lock backing (JPA @Version)
    CONSTRAINT pk_customers PRIMARY KEY (cust_id)
);

-- transactions: CVTRA05Y.cpy (TRAN-RECORD, RECLN 350) / TRANFILE.jcl KEYS(16 0)
CREATE TABLE transactions (
    tran_id       CHAR(16)       NOT NULL,                -- TRAN-ID X(16), bytes 1-16
    tran_type_cd  CHAR(2),                                -- TRAN-TYPE-CD X(02), bytes 17-18
    tran_cat_cd   INTEGER,                                -- TRAN-CAT-CD 9(04), bytes 19-22
    tran_source   VARCHAR(10),                            -- TRAN-SOURCE X(10), bytes 23-32
    tran_desc     VARCHAR(100),                           -- TRAN-DESC X(100), bytes 33-132
    tran_amt      NUMERIC(11,2),                          -- TRAN-AMT S9(09)V99, bytes 133-143
    merchant_id   BIGINT,                                 -- TRAN-MERCHANT-ID 9(09), bytes 144-152
    merchant_name VARCHAR(50),                            -- TRAN-MERCHANT-NAME X(50), bytes 153-202
    merchant_city VARCHAR(50),                            -- TRAN-MERCHANT-CITY X(50), bytes 203-252
    merchant_zip  VARCHAR(10),                            -- TRAN-MERCHANT-ZIP X(10), bytes 253-262
    card_num      CHAR(16),                               -- TRAN-CARD-NUM X(16), bytes 263-278
    orig_ts       CHAR(26),                               -- TRAN-ORIG-TS X(26), bytes 279-304 (text, preserves YYYY-MM-DD HH:MM:SS.mmmmmm)
    proc_ts       CHAR(26),                               -- TRAN-PROC-TS X(26), bytes 305-330 (text, preserves YYYY-MM-DD HH:MM:SS.mmmmmm)
    CONSTRAINT pk_transactions PRIMARY KEY (tran_id)
);

-- daily_transaction: CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350) -- posting staging, seeded empty
CREATE TABLE daily_transaction (
    tran_id       CHAR(16)       NOT NULL,                -- DALYTRAN-ID X(16), bytes 1-16
    tran_type_cd  CHAR(2),                                -- DALYTRAN-TYPE-CD X(02), bytes 17-18
    tran_cat_cd   INTEGER,                                -- DALYTRAN-CAT-CD 9(04), bytes 19-22
    tran_source   VARCHAR(10),                            -- DALYTRAN-SOURCE X(10), bytes 23-32
    tran_desc     VARCHAR(100),                           -- DALYTRAN-DESC X(100), bytes 33-132
    tran_amt      NUMERIC(11,2),                          -- DALYTRAN-AMT S9(09)V99, bytes 133-143
    merchant_id   BIGINT,                                 -- DALYTRAN-MERCHANT-ID 9(09), bytes 144-152
    merchant_name VARCHAR(50),                            -- DALYTRAN-MERCHANT-NAME X(50), bytes 153-202
    merchant_city VARCHAR(50),                            -- DALYTRAN-MERCHANT-CITY X(50), bytes 203-252
    merchant_zip  VARCHAR(10),                            -- DALYTRAN-MERCHANT-ZIP X(10), bytes 253-262
    card_num      CHAR(16),                               -- DALYTRAN-CARD-NUM X(16), bytes 263-278
    orig_ts       CHAR(26),                               -- DALYTRAN-ORIG-TS X(26), bytes 279-304
    proc_ts       CHAR(26),                               -- DALYTRAN-PROC-TS X(26), bytes 305-330
    CONSTRAINT pk_daily_transaction PRIMARY KEY (tran_id)
);

-- transaction_category_balance: CVTRA01Y.cpy (RECLN 50) / TCATBALF.jcl KEYS(17 0), composite PK
CREATE TABLE transaction_category_balance (
    acct_id      BIGINT          NOT NULL,                -- TRANCAT-ACCT-ID 9(11), bytes 1-11
    type_cd      CHAR(2)         NOT NULL,                -- TRANCAT-TYPE-CD X(02), bytes 12-13
    cat_cd       INTEGER         NOT NULL,                -- TRANCAT-CD 9(04), bytes 14-17
    tran_cat_bal NUMERIC(11,2),                           -- TRAN-CAT-BAL S9(09)V99, bytes 18-28
    CONSTRAINT pk_transaction_category_balance PRIMARY KEY (acct_id, type_cd, cat_cd)
);

-- disclosure_group: CVTRA02Y.cpy (RECLN 50) / DISCGRP.jcl KEYS(16 0), composite PK
CREATE TABLE disclosure_group (
    acct_group_id CHAR(10)       NOT NULL,                -- DIS-ACCT-GROUP-ID X(10), bytes 1-10
    tran_type_cd  CHAR(2)        NOT NULL,                -- DIS-TRAN-TYPE-CD X(02), bytes 11-12
    tran_cat_cd   INTEGER        NOT NULL,                -- DIS-TRAN-CAT-CD 9(04), bytes 13-16
    dis_int_rate  NUMERIC(6,2),                           -- DIS-INT-RATE S9(04)V99, bytes 17-22
    CONSTRAINT pk_disclosure_group PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)
);

-- transaction_type: CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60) / TRANTYPE.jcl KEYS(2 0)
CREATE TABLE transaction_type (
    tran_type      CHAR(2)       NOT NULL,                -- TRAN-TYPE X(02), bytes 1-2
    tran_type_desc VARCHAR(50),                           -- TRAN-TYPE-DESC X(50), bytes 3-52
    CONSTRAINT pk_transaction_type PRIMARY KEY (tran_type)
);

-- transaction_category: CVTRA04Y.cpy (TRAN-CAT-RECORD, RECLN 60) / TRANCATG.jcl KEYS(6 0), composite PK
CREATE TABLE transaction_category (
    tran_type_cd       CHAR(2)   NOT NULL,                -- TRAN-TYPE-CD X(02), bytes 1-2
    tran_cat_cd        INTEGER   NOT NULL,                -- TRAN-CAT-CD 9(04), bytes 3-6
    tran_cat_type_desc VARCHAR(50),                       -- TRAN-CAT-TYPE-DESC X(50), bytes 7-56
    CONSTRAINT pk_transaction_category PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

-- users: CSUSR01Y.cpy (SEC-USER-DATA, RECLN 80) / DUSRSECJ.jcl KEYS(8 0)
CREATE TABLE users (
    user_id    CHAR(8)          NOT NULL,                 -- SEC-USR-ID X(08), bytes 1-8
    first_name VARCHAR(20),                               -- SEC-USR-FNAME X(20), bytes 9-28
    last_name  VARCHAR(20),                               -- SEC-USR-LNAME X(20), bytes 29-48
    password   VARCHAR(60),                               -- SEC-USR-PWD X(08) upgraded to BCrypt 60-char hash (C-003)
    user_type  CHAR(1),                                   -- SEC-USR-TYPE X(01), byte 57 (A=admin, U=user)
    CONSTRAINT pk_users PRIMARY KEY (user_id)
);
