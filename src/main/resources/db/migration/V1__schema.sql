-- CardDemo V1 schema — 11 domain tables (COBOL VSAM record layouts -> PostgreSQL 16)
-- Source copybooks (read-only, commit 27d6c6f / 7756d895ffeb65f7ea72aaa609e356d9899afcec):
--   app/cpy/CVCUS01Y, CVACT01Y, CVACT02Y, CVACT03Y, CVTRA01Y, CVTRA05Y, CVTRA06Y,
--   CVTRA03Y, CVTRA04Y, CVTRA02Y, CSUSR01Y.cpy; record lengths per app/catlg/LISTCAT.txt.
-- Flyway applies this FIRST (V1). Hibernate spring.jpa.hibernate.ddl-auto=validate =>
--   table/column names, SQL types, precision/scale, and primary keys MUST match the
--   com.carddemo.entity.* @Entity mappings exactly or application boot fails.
-- Money/rate fields = NUMERIC(p,2) (COBOL PIC S9(n)V99; sign + scale 2 preserved) — never
--   float/double/real/money. COBOL FILLER padding is excluded. Secondary indexes live in V2,
--   seed data in V3, and Spring Batch BATCH_* metadata tables are auto-provisioned separately —
--   none of those belong here.
-- Tables are ordered so every foreign-key target exists before its referrer.

-- 1) CUSTOMER  <- app/cpy/CVCUS01Y.cpy (RECLN 500)
CREATE TABLE customer (
    cust_id                  BIGINT       NOT NULL,   -- CUST-ID 9(09)
    cust_first_name          VARCHAR(25),             -- CUST-FIRST-NAME X(25)
    cust_middle_name         VARCHAR(25),             -- CUST-MIDDLE-NAME X(25)
    cust_last_name           VARCHAR(25),             -- CUST-LAST-NAME X(25)
    cust_addr_line_1         VARCHAR(50),             -- CUST-ADDR-LINE-1 X(50)
    cust_addr_line_2         VARCHAR(50),             -- CUST-ADDR-LINE-2 X(50)
    cust_addr_line_3         VARCHAR(50),             -- CUST-ADDR-LINE-3 X(50)
    cust_addr_state_cd       VARCHAR(2),              -- CUST-ADDR-STATE-CD X(02)
    cust_addr_country_cd     VARCHAR(3),              -- CUST-ADDR-COUNTRY-CD X(03)
    cust_addr_zip            VARCHAR(10),             -- CUST-ADDR-ZIP X(10)
    cust_phone_num_1         VARCHAR(15),             -- CUST-PHONE-NUM-1 X(15)
    cust_phone_num_2         VARCHAR(15),             -- CUST-PHONE-NUM-2 X(15)
    cust_ssn                 BIGINT,                  -- CUST-SSN 9(09)
    cust_govt_issued_id      VARCHAR(20),             -- CUST-GOVT-ISSUED-ID X(20)
    cust_dob_yyyy_mm_dd      DATE,                    -- CUST-DOB-YYYY-MM-DD X(10) -> DATE
    cust_eft_account_id      VARCHAR(10),             -- CUST-EFT-ACCOUNT-ID X(10)
    cust_pri_card_holder_ind VARCHAR(1),              -- CUST-PRI-CARD-HOLDER-IND X(01)
    cust_fico_credit_score   INTEGER,                 -- CUST-FICO-CREDIT-SCORE 9(03)
    PRIMARY KEY (cust_id)
);

-- 2) ACCOUNT  <- app/cpy/CVACT01Y.cpy (RECLN 300). The 3 DATE fields sit BETWEEN the money fields.
CREATE TABLE account (
    acct_id                 BIGINT        NOT NULL,   -- ACCT-ID 9(11)
    acct_active_status      VARCHAR(1),               -- ACCT-ACTIVE-STATUS X(01)
    acct_curr_bal           NUMERIC(12,2),            -- ACCT-CURR-BAL S9(10)V99
    acct_credit_limit       NUMERIC(12,2),            -- ACCT-CREDIT-LIMIT S9(10)V99
    acct_cash_credit_limit  NUMERIC(12,2),            -- ACCT-CASH-CREDIT-LIMIT S9(10)V99
    acct_open_date          DATE,                     -- ACCT-OPEN-DATE X(10) -> DATE
    acct_expiration_date    DATE,                     -- ACCT-EXPIRAION-DATE X(10) [COBOL typo normalized] -> DATE
    acct_reissue_date       DATE,                     -- ACCT-REISSUE-DATE X(10) -> DATE
    acct_curr_cyc_credit    NUMERIC(12,2),            -- ACCT-CURR-CYC-CREDIT S9(10)V99
    acct_curr_cyc_debit     NUMERIC(12,2),            -- ACCT-CURR-CYC-DEBIT S9(10)V99
    acct_addr_zip           VARCHAR(10),              -- ACCT-ADDR-ZIP X(10)
    acct_group_id           VARCHAR(10),              -- ACCT-GROUP-ID X(10)
    version                 BIGINT,                   -- JPA @Version optimistic lock (COACTUPC read-then-rewrite); not in 300B record
    PRIMARY KEY (acct_id)
);

-- 3) CARD  <- app/cpy/CVACT02Y.cpy (RECLN 150)
CREATE TABLE card (
    card_num             VARCHAR(16)  NOT NULL,       -- CARD-NUM X(16)
    card_acct_id         BIGINT,                      -- CARD-ACCT-ID 9(11)
    card_cvv_cd          INTEGER,                     -- CARD-CVV-CD 9(03)
    card_embossed_name   VARCHAR(50),                 -- CARD-EMBOSSED-NAME X(50)
    card_expiration_date DATE,                        -- CARD-EXPIRAION-DATE X(10) [COBOL typo normalized] -> DATE
    card_active_status   VARCHAR(1),                  -- CARD-ACTIVE-STATUS X(01)
    version              BIGINT,                      -- JPA @Version optimistic lock (COCRDUPC)
    PRIMARY KEY (card_num),
    CONSTRAINT fk_card_account FOREIGN KEY (card_acct_id) REFERENCES account (acct_id)
);

-- 4) CARD_XREF  <- app/cpy/CVACT03Y.cpy (RECLN 50; fixture carries only the first 36 bytes)
CREATE TABLE card_xref (
    xref_card_num VARCHAR(16) NOT NULL,               -- XREF-CARD-NUM X(16)
    xref_cust_id  BIGINT,                             -- XREF-CUST-ID 9(09)
    xref_acct_id  BIGINT,                             -- XREF-ACCT-ID 9(11)
    PRIMARY KEY (xref_card_num),
    CONSTRAINT fk_xref_account  FOREIGN KEY (xref_acct_id) REFERENCES account (acct_id),
    CONSTRAINT fk_xref_customer FOREIGN KEY (xref_cust_id) REFERENCES customer (cust_id)
);

-- 5) TRANSACTION_CATEGORY_BALANCE  <- app/cpy/CVTRA01Y.cpy (RECLN 50; composite key 17 bytes)
CREATE TABLE transaction_category_balance (
    trancat_acct_id BIGINT      NOT NULL,             -- TRANCAT-ACCT-ID 9(11)
    trancat_type_cd VARCHAR(2)  NOT NULL,             -- TRANCAT-TYPE-CD X(02)
    trancat_cd      INTEGER     NOT NULL,             -- TRANCAT-CD 9(04)
    tran_cat_bal    NUMERIC(11,2),                    -- TRAN-CAT-BAL S9(09)V99
    PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd),
    CONSTRAINT fk_tcb_account FOREIGN KEY (trancat_acct_id) REFERENCES account (acct_id)
);

-- 6) TRANSACTION  <- app/cpy/CVTRA05Y.cpy (RECLN 350). Seeded EMPTY (no ASCII fixture; POSTTRAN populates at runtime).
CREATE TABLE transaction (
    tran_id            VARCHAR(16)  NOT NULL,         -- TRAN-ID X(16)
    tran_type_cd       VARCHAR(2),                    -- TRAN-TYPE-CD X(02)
    tran_cat_cd        INTEGER,                       -- TRAN-CAT-CD 9(04)
    tran_source        VARCHAR(10),                   -- TRAN-SOURCE X(10)
    tran_desc          VARCHAR(100),                  -- TRAN-DESC X(100)
    tran_amt           NUMERIC(11,2),                 -- TRAN-AMT S9(09)V99
    tran_merchant_id   BIGINT,                        -- TRAN-MERCHANT-ID 9(09)
    tran_merchant_name VARCHAR(50),                   -- TRAN-MERCHANT-NAME X(50)
    tran_merchant_city VARCHAR(50),                   -- TRAN-MERCHANT-CITY X(50)
    tran_merchant_zip  VARCHAR(10),                   -- TRAN-MERCHANT-ZIP X(10)
    tran_card_num      VARCHAR(16),                   -- TRAN-CARD-NUM X(16)
    tran_orig_ts       VARCHAR(26),                   -- TRAN-ORIG-TS X(26) (text timestamp, NOT a DATE/TIMESTAMP)
    tran_proc_ts       VARCHAR(26),                   -- TRAN-PROC-TS X(26)
    PRIMARY KEY (tran_id)
);

-- 7) DAILY_TRANSACTION  <- app/cpy/CVTRA06Y.cpy (RECLN 350). POSTTRAN input staging (dalytran_ prefix).
CREATE TABLE daily_transaction (
    dalytran_id            VARCHAR(16)  NOT NULL,     -- DALYTRAN-ID X(16)
    dalytran_type_cd       VARCHAR(2),                -- DALYTRAN-TYPE-CD X(02)
    dalytran_cat_cd        INTEGER,                   -- DALYTRAN-CAT-CD 9(04)
    dalytran_source        VARCHAR(10),               -- DALYTRAN-SOURCE X(10)
    dalytran_desc          VARCHAR(100),              -- DALYTRAN-DESC X(100)
    dalytran_amt           NUMERIC(11,2),             -- DALYTRAN-AMT S9(09)V99
    dalytran_merchant_id   BIGINT,                    -- DALYTRAN-MERCHANT-ID 9(09)
    dalytran_merchant_name VARCHAR(50),               -- DALYTRAN-MERCHANT-NAME X(50)
    dalytran_merchant_city VARCHAR(50),               -- DALYTRAN-MERCHANT-CITY X(50)
    dalytran_merchant_zip  VARCHAR(10),               -- DALYTRAN-MERCHANT-ZIP X(10)
    dalytran_card_num      VARCHAR(16),               -- DALYTRAN-CARD-NUM X(16)
    dalytran_orig_ts       VARCHAR(26),               -- DALYTRAN-ORIG-TS X(26)
    dalytran_proc_ts       VARCHAR(26),               -- DALYTRAN-PROC-TS X(26)
    PRIMARY KEY (dalytran_id)
);

-- 8) TRANSACTION_TYPE  <- app/cpy/CVTRA03Y.cpy (RECLN 60)
CREATE TABLE transaction_type (
    tran_type      VARCHAR(2) NOT NULL,               -- TRAN-TYPE X(02)
    tran_type_desc VARCHAR(50),                       -- TRAN-TYPE-DESC X(50)
    PRIMARY KEY (tran_type)
);

-- 9) TRANSACTION_CATEGORY_TYPE  <- app/cpy/CVTRA04Y.cpy (RECLN 60; composite key 6 bytes)
CREATE TABLE transaction_category_type (
    tran_type_cd       VARCHAR(2)  NOT NULL,          -- TRAN-TYPE-CD X(02)
    tran_cat_cd        INTEGER     NOT NULL,          -- TRAN-CAT-CD 9(04)
    tran_cat_type_desc VARCHAR(50),                   -- TRAN-CAT-TYPE-DESC X(50)
    PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

-- 10) DISCLOSURE_GROUP  <- app/cpy/CVTRA02Y.cpy (RECLN 50; composite key 16 bytes)
CREATE TABLE disclosure_group (
    dis_acct_group_id VARCHAR(10) NOT NULL,           -- DIS-ACCT-GROUP-ID X(10)
    dis_tran_type_cd  VARCHAR(2)  NOT NULL,           -- DIS-TRAN-TYPE-CD X(02)
    dis_tran_cat_cd   INTEGER     NOT NULL,           -- DIS-TRAN-CAT-CD 9(04)
    dis_int_rate      NUMERIC(6,2),                   -- DIS-INT-RATE S9(04)V99 (interest RATE; sign + scale preserved)
    PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

-- 11) USER_SECURITY  <- app/cpy/CSUSR01Y.cpy (RECLN 80). pwd widened to 60 for BCrypt (C-003 / Decision Log D-002).
CREATE TABLE user_security (
    sec_usr_id    VARCHAR(8)  NOT NULL,               -- SEC-USR-ID X(08)
    sec_usr_fname VARCHAR(20),                        -- SEC-USR-FNAME X(20)
    sec_usr_lname VARCHAR(20),                        -- SEC-USR-LNAME X(20)
    sec_usr_pwd   VARCHAR(60),                        -- SEC-USR-PWD X(08) -> widened for BCrypt hash
    sec_usr_type  VARCHAR(1),                         -- SEC-USR-TYPE X(01) ('A'=admin / 'U'=user)
    PRIMARY KEY (sec_usr_id)
);
