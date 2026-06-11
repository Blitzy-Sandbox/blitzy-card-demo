-- =====================================================================================
-- Flyway Migration V1 -- CardDemo Relational Schema (PostgreSQL 16)
-- =====================================================================================
-- Purpose : First versioned migration. Creates the COMPLETE relational schema (all 11
--           tables) that replaces the legacy z/OS VSAM KSDS and sequential-PS datasets of
--           the AWS CardDemo application during the COBOL -> Java 25 / Spring Boot 3.x
--           migration. This script defines STRUCTURE ONLY: secondary / alternate indexes
--           are created in V2__create_indexes.sql and reference / seed data in
--           V3__seed_data.sql.
--
-- Flyway  : The file name encodes version "1" and description "create_schema"
--           (V<version>__<description>.sql -- single underscore after the version, double
--           underscore before the description). It is auto-discovered through
--           spring.flyway.locations=classpath:db/migration and executed exactly once, in
--           strict version order, on application startup BEFORE any @Service or Spring
--           Batch job runs. Once shipped this file is IMMUTABLE -- Flyway records its
--           checksum and a later edit would break validation on already-migrated databases.
--
-- Schema authority : Hibernate runs with spring.jpa.hibernate.ddl-auto=validate, so
--           Hibernate creates NOTHING -- it VALIDATES the JPA entities in
--           com.cardemo.model.entity.* (and the @EmbeddedId composite-key classes in
--           com.cardemo.model.key.*) against THIS schema at startup. Any divergence in a
--           table name, column name, SQL type, length, precision, scale, or primary key
--           fails application startup. This DDL is therefore the single source of truth
--           for the physical schema; the entities MUST mirror the identifiers and types
--           defined here.
--
-- Decimal fidelity (DECISION_LOG D-001 / AAP 0.7.3): every COBOL COMP-3 / COMP /
--           PIC S9(i)V(d) monetary or decimal field maps to NUMERIC(i+d, d) with the
--           EXACT precision and scale (e.g. S9(10)V99 -> NUMERIC(12,2), S9(09)V99 ->
--           NUMERIC(11,2), S9(04)V99 -> NUMERIC(6,2)). float / double precision / real /
--           money / serial are PROHIBITED for any value originating from a COBOL PIC clause.
--
-- BCrypt (DECISION_LOG D-002 / AAP 0.7.2 constraint C-003): user_security.password is
--           sized for a BCrypt hash (VARCHAR(72)) and stores a hash, never plaintext. The
--           legacy COBOL field is PIC X(08) plaintext; this upgrade is the SINGLE permitted
--           behavioral change in the migration.
--
-- Type rules : X(n) -> VARCHAR(n); unsigned 9(n) id/code -> INTEGER (n<=4) or BIGINT
--           (5<=n<=18); X(10) 'YYYY-MM-DD' -> DATE; X(26) timestamp -> TIMESTAMP (no time
--           zone). COBOL FILLER is record padding only and is intentionally NOT a column.
--
-- VSAM -> relational : each VSAM KSDS cluster becomes one table; the KSDS primary key
--           becomes a PRIMARY KEY; a composite VSAM key becomes a composite PRIMARY KEY;
--           an AIX / PATH alternate index becomes a secondary index (created in V2).
--
-- Base package : com.cardemo (DECISION_LOG D-006).
-- Traceability : Source = AWS CardDemo COBOL baseline, commit 27d6c6f. No COBOL / JCL
--           source text is copied here; only the derived relational structure is expressed.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- transaction_type  (reference data)
--   VSAM source : AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS  (KSDS, KEYS(2,0), RECORDSIZE 60)
--   Copybook    : CVTRA03Y (TRAN-TYPE-RECORD)
--   The 2-byte VSAM key (TRAN-TYPE @ offset 0) becomes PRIMARY KEY (type_code).
--   Trailing FILLER X(08) is record padding only and is omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE transaction_type (
    type_code        VARCHAR(2)  NOT NULL,                 -- TRAN-TYPE       PIC X(02)
    type_description VARCHAR(50),                           -- TRAN-TYPE-DESC  PIC X(50)
    CONSTRAINT pk_transaction_type PRIMARY KEY (type_code)
);


-- -------------------------------------------------------------------------------------
-- transaction_category  (reference data, composite key)
--   VSAM source : AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS  (KSDS, KEYS(6,0), RECORDSIZE 60)
--   Copybook    : CVTRA04Y (TRAN-CAT-RECORD)
--   The 6-byte composite VSAM key (TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) @ offset 0)
--   becomes a composite PRIMARY KEY (type_code, category_code), mirrored by the
--   @EmbeddedId key class in com.cardemo.model.key.*; column ORDER matches the copybook.
--   FILLER X(04) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE transaction_category (
    type_code            VARCHAR(2) NOT NULL,              -- TRAN-TYPE-CD       PIC X(02)
    category_code        INTEGER    NOT NULL,              -- TRAN-CAT-CD        PIC 9(04)
    category_description VARCHAR(50),                       -- TRAN-CAT-TYPE-DESC PIC X(50)
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_code, category_code)
);


-- -------------------------------------------------------------------------------------
-- disclosure_group  (reference data, composite key)
--   VSAM source : AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS  (KSDS, KEYS(16,0), RECORDSIZE 50)
--   Copybook    : CVTRA02Y (DIS-GROUP-RECORD)
--   The 16-byte composite VSAM key (DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02)
--   + DIS-TRAN-CAT-CD 9(04) @ offset 0) becomes the composite PRIMARY KEY.
--   interest_rate: PIC S9(04)V99 -> NUMERIC(6,2) (D-001); it feeds the interest formula
--   (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 in the InterestCalculation batch job.
--   FILLER X(28) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE disclosure_group (
    account_group_id          VARCHAR(10) NOT NULL,        -- DIS-ACCT-GROUP-ID PIC X(10)
    transaction_type_code     VARCHAR(2)  NOT NULL,        -- DIS-TRAN-TYPE-CD  PIC X(02)
    transaction_category_code INTEGER     NOT NULL,        -- DIS-TRAN-CAT-CD   PIC 9(04)
    interest_rate             NUMERIC(6,2),                 -- DIS-INT-RATE      PIC S9(04)V99
    CONSTRAINT pk_disclosure_group
        PRIMARY KEY (account_group_id, transaction_type_code, transaction_category_code)
);


-- -------------------------------------------------------------------------------------
-- user_security
--   VSAM source : AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS  (KSDS, KEYS(8,0), RECORDSIZE 80)
--   Copybook    : CSUSR01Y (SEC-USER-DATA)
--   The 8-byte VSAM key (SEC-USR-ID @ offset 0) becomes PRIMARY KEY (user_id).
--   password: COBOL SEC-USR-PWD is PIC X(08) PLAINTEXT. Per D-002 / constraint C-003 it is
--   upgraded to a BCrypt hash, so the column is VARCHAR(72) (BCrypt emits 60 chars; 72
--   leaves headroom) -- deliberately NOT VARCHAR(8). This is the single permitted
--   behavioral change. user_type: 'A' = admin, 'U' = regular user.
--   FILLER (SEC-USR-FILLER X(23)) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE user_security (
    user_id    VARCHAR(8) NOT NULL,                        -- SEC-USR-ID    PIC X(08)
    first_name VARCHAR(20),                                 -- SEC-USR-FNAME PIC X(20)
    last_name  VARCHAR(20),                                 -- SEC-USR-LNAME PIC X(20)
    password   VARCHAR(72),                                 -- SEC-USR-PWD   PIC X(08) -> BCrypt hash (D-002)
    user_type  VARCHAR(1),                                  -- SEC-USR-TYPE  PIC X(01)
    CONSTRAINT pk_user_security PRIMARY KEY (user_id)
);


-- -------------------------------------------------------------------------------------
-- customer
--   VSAM source : AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS  (KSDS, KEYS(9,0), RECORDSIZE 500)
--   Copybook    : CVCUS01Y / CUSTREC (CUSTOMER-RECORD)
--   The 9-byte VSAM key (CUST-ID 9(09) @ offset 0) becomes PRIMARY KEY (customer_id).
--   customer_id and ssn: PIC 9(09) numerics -> BIGINT (leading-zero display, if any, is
--   restored by application-layer formatting, not the column type).
--   date_of_birth: CUST-DOB-YYYY-MM-DD PIC X(10) 'YYYY-MM-DD' -> DATE. FILLER X(168) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE customer (
    customer_id                   BIGINT NOT NULL,         -- CUST-ID                  PIC 9(09)
    first_name                    VARCHAR(25),              -- CUST-FIRST-NAME          PIC X(25)
    middle_name                   VARCHAR(25),              -- CUST-MIDDLE-NAME         PIC X(25)
    last_name                     VARCHAR(25),              -- CUST-LAST-NAME           PIC X(25)
    address_line_1                VARCHAR(50),              -- CUST-ADDR-LINE-1         PIC X(50)
    address_line_2                VARCHAR(50),              -- CUST-ADDR-LINE-2         PIC X(50)
    address_line_3                VARCHAR(50),              -- CUST-ADDR-LINE-3         PIC X(50)
    state_code                    VARCHAR(2),               -- CUST-ADDR-STATE-CD       PIC X(02)
    country_code                  VARCHAR(3),               -- CUST-ADDR-COUNTRY-CD     PIC X(03)
    zip_code                      VARCHAR(10),              -- CUST-ADDR-ZIP            PIC X(10)
    phone_number_1                VARCHAR(15),              -- CUST-PHONE-NUM-1         PIC X(15)
    phone_number_2                VARCHAR(15),              -- CUST-PHONE-NUM-2         PIC X(15)
    ssn                           BIGINT,                   -- CUST-SSN                 PIC 9(09)
    government_issued_id          VARCHAR(20),              -- CUST-GOVT-ISSUED-ID      PIC X(20)
    date_of_birth                 DATE,                     -- CUST-DOB-YYYY-MM-DD      PIC X(10)
    eft_account_id                VARCHAR(10),              -- CUST-EFT-ACCOUNT-ID      PIC X(10)
    primary_card_holder_indicator VARCHAR(1),               -- CUST-PRI-CARD-HOLDER-IND PIC X(01)
    fico_credit_score             INTEGER,                  -- CUST-FICO-CREDIT-SCORE   PIC 9(03)
    CONSTRAINT pk_customer PRIMARY KEY (customer_id)
);


-- -------------------------------------------------------------------------------------
-- account
--   VSAM source : AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS  (KSDS, KEYS(11,0), RECORDSIZE 300)
--   Copybook    : CVACT01Y (ACCOUNT-RECORD)
--   The 11-byte VSAM key (ACCT-ID 9(11) @ offset 0) becomes PRIMARY KEY (account_id).
--   Monetary fields PIC S9(10)V99 -> NUMERIC(12,2) (D-001). Date fields PIC X(10) -> DATE.
--   NOTE: expiration_date derives from COBOL ACCT-EXPIRAION-DATE -- the source field name
--   contains the spelling "EXPIRAION"; the clean column name expiration_date is used here
--   and the entity maps @Column(name = "expiration_date") accordingly.
--   version: NEW optimistic-lock column with no COBOL source. It is required because
--   COACTUPC performs a read-update before/after snapshot comparison; it maps to JPA
--   @Version (Long -> BIGINT). FILLER X(178) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE account (
    account_id           BIGINT NOT NULL,                  -- ACCT-ID                PIC 9(11)
    active_status        VARCHAR(1),                        -- ACCT-ACTIVE-STATUS     PIC X(01)
    current_balance      NUMERIC(12,2),                     -- ACCT-CURR-BAL          PIC S9(10)V99
    credit_limit         NUMERIC(12,2),                     -- ACCT-CREDIT-LIMIT      PIC S9(10)V99
    cash_credit_limit    NUMERIC(12,2),                     -- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
    open_date            DATE,                              -- ACCT-OPEN-DATE         PIC X(10)
    expiration_date      DATE,                              -- ACCT-EXPIRAION-DATE    PIC X(10) (source spelling preserved)
    reissue_date         DATE,                              -- ACCT-REISSUE-DATE      PIC X(10)
    current_cycle_credit NUMERIC(12,2),                     -- ACCT-CURR-CYC-CREDIT   PIC S9(10)V99
    current_cycle_debit  NUMERIC(12,2),                     -- ACCT-CURR-CYC-DEBIT    PIC S9(10)V99
    address_zip          VARCHAR(10),                       -- ACCT-ADDR-ZIP          PIC X(10)
    group_id             VARCHAR(10),                       -- ACCT-GROUP-ID          PIC X(10)
    version              BIGINT NOT NULL DEFAULT 0,         -- JPA @Version optimistic lock (no COBOL source)
    CONSTRAINT pk_account PRIMARY KEY (account_id)
);


-- -------------------------------------------------------------------------------------
-- card
--   VSAM source : AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS  (KSDS, KEYS(16,0), RECORDSIZE 150)
--   Copybook    : CVACT02Y (CARD-RECORD)
--   The 16-byte VSAM key (CARD-NUM X(16) @ offset 0) becomes PRIMARY KEY (card_number);
--   card_number stays VARCHAR(16) to preserve the exact 16-character external format.
--   account_id (CARD-ACCT-ID 9(11)) -> BIGINT, FK -> account(account_id). This column is
--   the CARDDATA alternate-index target (AIX KEYS(11,16)); that index is created in V2.
--   expiration_date derives from CARD-EXPIRAION-DATE (same source spelling note as account).
--   version: NEW JPA @Version optimistic-lock column (COCRDUPC read-update). FILLER X(59)
--   omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE card (
    card_number     VARCHAR(16) NOT NULL,                 -- CARD-NUM            PIC X(16)
    account_id      BIGINT      NOT NULL,                 -- CARD-ACCT-ID        PIC 9(11)
    cvv_code        INTEGER,                               -- CARD-CVV-CD         PIC 9(03)
    embossed_name   VARCHAR(50),                           -- CARD-EMBOSSED-NAME  PIC X(50)
    expiration_date DATE,                                  -- CARD-EXPIRAION-DATE PIC X(10) (source spelling preserved)
    active_status   VARCHAR(1),                            -- CARD-ACTIVE-STATUS  PIC X(01)
    version         BIGINT      NOT NULL DEFAULT 0,        -- JPA @Version optimistic lock (no COBOL source)
    CONSTRAINT pk_card PRIMARY KEY (card_number),
    CONSTRAINT fk_card_account FOREIGN KEY (account_id) REFERENCES account (account_id)
);


-- -------------------------------------------------------------------------------------
-- card_xref  (card-to-account/customer cross reference)
--   VSAM source : AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS  (KSDS, KEYS(16,0), RECORDSIZE 50)
--   Copybook    : CVACT03Y (CARD-XREF-RECORD)
--   The 16-byte VSAM key (XREF-CARD-NUM X(16) @ offset 0) becomes PRIMARY KEY.
--   account_id (XREF-ACCT-ID 9(11) @ offset 25) is the CXACAIX alternate-index target
--   (AIX KEYS(11,25)); that index is created in V2. Both ids -> BIGINT with FKs.
--   FILLER X(14) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE card_xref (
    card_number VARCHAR(16) NOT NULL,                     -- XREF-CARD-NUM PIC X(16)
    customer_id BIGINT      NOT NULL,                     -- XREF-CUST-ID  PIC 9(09)
    account_id  BIGINT      NOT NULL,                     -- XREF-ACCT-ID  PIC 9(11)
    CONSTRAINT pk_card_xref PRIMARY KEY (card_number),
    CONSTRAINT fk_card_xref_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_card_xref_account  FOREIGN KEY (account_id)  REFERENCES account (account_id)
);


-- -------------------------------------------------------------------------------------
-- transaction_category_balance  (composite key)
--   VSAM source : AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS  (KSDS, KEYS(17,0), RECORDSIZE 50)
--   Copybook    : CVTRA01Y (TRAN-CAT-BAL-RECORD)
--   The 17-byte composite VSAM key (TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02)
--   + TRANCAT-CD 9(04) @ offset 0) becomes the composite PRIMARY KEY (column ORDER per the
--   copybook / @EmbeddedId). balance: PIC S9(09)V99 -> NUMERIC(11,2) (D-001).
--   account_id FK -> account(account_id). FILLER X(22) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE transaction_category_balance (
    account_id    BIGINT     NOT NULL,                    -- TRANCAT-ACCT-ID PIC 9(11)
    type_code     VARCHAR(2) NOT NULL,                    -- TRANCAT-TYPE-CD PIC X(02)
    category_code INTEGER    NOT NULL,                    -- TRANCAT-CD      PIC 9(04)
    balance       NUMERIC(11,2),                           -- TRAN-CAT-BAL    PIC S9(09)V99
    CONSTRAINT pk_transaction_category_balance
        PRIMARY KEY (account_id, type_code, category_code),
    CONSTRAINT fk_tcatbal_account FOREIGN KEY (account_id) REFERENCES account (account_id)
);


-- -------------------------------------------------------------------------------------
-- transaction
--   VSAM source : AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS  (KSDS, KEYS(16,0), RECORDSIZE 350)
--   Copybook    : CVTRA05Y (TRAN-RECORD)
--   The 16-byte VSAM key (TRAN-ID X(16) @ offset 0) becomes PRIMARY KEY (transaction_id).
--   "transaction" is a NON-RESERVED keyword in PostgreSQL and is valid unquoted as a table
--   identifier; the entity maps @Table(name = "transaction").
--   amount: PIC S9(09)V99 -> NUMERIC(11,2) (D-001). processed_timestamp (TRAN-PROC-TS X(26)
--   @ offset 304) is the TRANSACT alternate-index target (AIX KEYS(26,304)); that index is
--   created in V2. Kept FK-light to mirror the COBOL access pattern. Not seeded by V3 (no
--   fixture exists); populated at runtime by the batch posting job. FILLER X(20) omitted.
-- -------------------------------------------------------------------------------------
CREATE TABLE transaction (
    transaction_id      VARCHAR(16) NOT NULL,             -- TRAN-ID            PIC X(16)
    type_code           VARCHAR(2),                        -- TRAN-TYPE-CD       PIC X(02)
    category_code       INTEGER,                           -- TRAN-CAT-CD        PIC 9(04)
    source              VARCHAR(10),                       -- TRAN-SOURCE        PIC X(10)
    description         VARCHAR(100),                      -- TRAN-DESC          PIC X(100)
    amount              NUMERIC(11,2),                     -- TRAN-AMT           PIC S9(09)V99
    merchant_id         BIGINT,                            -- TRAN-MERCHANT-ID   PIC 9(09)
    merchant_name       VARCHAR(50),                       -- TRAN-MERCHANT-NAME PIC X(50)
    merchant_city       VARCHAR(50),                       -- TRAN-MERCHANT-CITY PIC X(50)
    merchant_zip        VARCHAR(10),                       -- TRAN-MERCHANT-ZIP  PIC X(10)
    card_number         VARCHAR(16),                       -- TRAN-CARD-NUM      PIC X(16)
    original_timestamp  TIMESTAMP,                         -- TRAN-ORIG-TS       PIC X(26)
    processed_timestamp TIMESTAMP,                         -- TRAN-PROC-TS       PIC X(26)
    CONSTRAINT pk_transaction PRIMARY KEY (transaction_id)
);


-- -------------------------------------------------------------------------------------
-- daily_transactions  (batch staging)
--   Source      : AWS.M2.CARDDEMO.DALYTRAN.PS  (sequential PS staging file, RECLN 350)
--   Copybook    : CVTRA06Y (DALYTRAN-RECORD) -- byte-for-byte parallel of CVTRA05Y.
--   Staging table for unposted daily input (POSTTRAN.jcl -> CBTRN02C). Held FK-light on
--   purpose: it carries raw, possibly-not-yet-valid input (e.g. a card or account that has
--   not been posted yet), so strict FK constraints would reject legitimate staging rows.
--   The PS file has no VSAM key; transaction_id (DALYTRAN-ID X(16)) is the natural record
--   identifier and is used as the PRIMARY KEY. amount: PIC S9(09)V99 -> NUMERIC(11,2)
--   (D-001). FILLER X(20) omitted.
--   Entity-authoritative naming: the table name (daily_transactions) and primary-key column
--   (transaction_id) mirror the @Table / @Id @Column mapping of
--   com.cardemo.model.entity.DailyTransaction, which is the authoritative source for this
--   table's DDL per its agent contract. The mapping deliberately parallels the transaction
--   table (DailyTransaction is a 350-byte clone of Transaction): the staging row is read by
--   DailyTransactionPostingJob, validated (CBTRN02C cascade) and posted into transactions.
--   The V3 seed loads this table from app/data/ASCII/dailytran.txt (the golden parity fixture).
-- -------------------------------------------------------------------------------------
CREATE TABLE daily_transactions (
    transaction_id       VARCHAR(16) NOT NULL,            -- DALYTRAN-ID            PIC X(16)
    type_code            VARCHAR(2),                       -- DALYTRAN-TYPE-CD       PIC X(02)
    category_code        INTEGER,                          -- DALYTRAN-CAT-CD        PIC 9(04)
    source               VARCHAR(10),                       -- DALYTRAN-SOURCE        PIC X(10)
    description          VARCHAR(100),                      -- DALYTRAN-DESC          PIC X(100)
    amount               NUMERIC(11,2),                     -- DALYTRAN-AMT           PIC S9(09)V99
    merchant_id          BIGINT,                            -- DALYTRAN-MERCHANT-ID   PIC 9(09)
    merchant_name        VARCHAR(50),                       -- DALYTRAN-MERCHANT-NAME PIC X(50)
    merchant_city        VARCHAR(50),                       -- DALYTRAN-MERCHANT-CITY PIC X(50)
    merchant_zip         VARCHAR(10),                       -- DALYTRAN-MERCHANT-ZIP  PIC X(10)
    card_number          VARCHAR(16),                       -- DALYTRAN-CARD-NUM      PIC X(16)
    original_timestamp   TIMESTAMP,                         -- DALYTRAN-ORIG-TS       PIC X(26)
    processed_timestamp  TIMESTAMP,                         -- DALYTRAN-PROC-TS       PIC X(26)
    CONSTRAINT pk_daily_transactions PRIMARY KEY (transaction_id)
);

