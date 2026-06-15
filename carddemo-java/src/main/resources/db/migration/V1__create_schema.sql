-- =============================================================================
-- V1__create_schema.sql  --  CardDemo (Java) initial schema  (Flyway V1)
-- =============================================================================
-- Provisions the complete relational schema for the 11 CardDemo domain tables
-- on PostgreSQL 16, PLUS the Spring Batch metadata tables. Flyway applies this
-- migration on startup BEFORE Hibernate initializes
-- (spring.jpa.hibernate.ddl-auto: validate) and before any online or batch
-- flow runs, and ahead of V2 (indexes) and V3 (seed data).
--
-- The Spring Batch metadata schema is included here because
-- spring.batch.jdbc.initialize-schema=never (Spring Boot will not auto-create
-- the BATCH_* tables, yet they must exist for any job to run).
--
-- Conventions:
--   * Lowercase, unquoted identifiers throughout.
--   * Monetary / rate columns are NUMERIC with scale 2 (fixed-point, not binary
--     floating-point) to preserve exact decimal precision.
--   * Column names, SQL types, and precision/scale match the JPA entities
--     exactly so Hibernate schema validation passes on boot.
--   * No FOREIGN KEY constraints between domain tables (entities use plain
--     columns, not associations); this also avoids seed-ordering fragility.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. account  (account master record)
-- -----------------------------------------------------------------------------
CREATE TABLE account (
    acct_id                 BIGINT          NOT NULL,
    acct_active_status      VARCHAR(1)      NOT NULL,
    acct_curr_bal           NUMERIC(12, 2)  NOT NULL,
    acct_credit_limit       NUMERIC(12, 2)  NOT NULL,
    acct_cash_credit_limit  NUMERIC(12, 2)  NOT NULL,
    acct_open_date          VARCHAR(10)     NOT NULL,
    acct_expiraion_date     VARCHAR(10)     NOT NULL,
    acct_reissue_date       VARCHAR(10)     NOT NULL,
    acct_curr_cyc_credit    NUMERIC(12, 2)  NOT NULL,
    acct_curr_cyc_debit     NUMERIC(12, 2)  NOT NULL,
    acct_addr_zip           VARCHAR(10)     NOT NULL,
    acct_group_id           VARCHAR(10)     NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_account PRIMARY KEY (acct_id)
);

-- -----------------------------------------------------------------------------
-- 2. card  (card master record)
-- -----------------------------------------------------------------------------
CREATE TABLE card (
    card_num             VARCHAR(16)  NOT NULL,
    card_acct_id         BIGINT       NOT NULL,
    card_cvv_cd          INTEGER      NOT NULL,
    card_embossed_name   VARCHAR(50)  NOT NULL,
    card_expiraion_date  VARCHAR(10)  NOT NULL,
    card_active_status   VARCHAR(1)   NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_card PRIMARY KEY (card_num)
);

-- -----------------------------------------------------------------------------
-- 3. customer  (customer master record)
-- -----------------------------------------------------------------------------
CREATE TABLE customer (
    cust_id                   BIGINT       NOT NULL,
    cust_first_name           VARCHAR(25)  NOT NULL,
    cust_middle_name          VARCHAR(25)  NOT NULL,
    cust_last_name            VARCHAR(25)  NOT NULL,
    cust_addr_line_1          VARCHAR(50)  NOT NULL,
    cust_addr_line_2          VARCHAR(50)  NOT NULL,
    cust_addr_line_3          VARCHAR(50)  NOT NULL,
    cust_addr_state_cd        VARCHAR(2)   NOT NULL,
    cust_addr_country_cd      VARCHAR(3)   NOT NULL,
    cust_addr_zip             VARCHAR(10)  NOT NULL,
    cust_phone_num_1          VARCHAR(15)  NOT NULL,
    cust_phone_num_2          VARCHAR(15)  NOT NULL,
    cust_ssn                  BIGINT       NOT NULL,
    cust_govt_issued_id       VARCHAR(20)  NOT NULL,
    cust_dob_yyyy_mm_dd       VARCHAR(10)  NOT NULL,
    cust_eft_account_id       VARCHAR(10)  NOT NULL,
    cust_pri_card_holder_ind  VARCHAR(1)   NOT NULL,
    cust_fico_credit_score    INTEGER      NOT NULL,
    CONSTRAINT pk_customer PRIMARY KEY (cust_id)
);

-- -----------------------------------------------------------------------------
-- 4. card_xref  (card-to-account-to-customer cross reference)
-- -----------------------------------------------------------------------------
CREATE TABLE card_xref (
    xref_card_num  VARCHAR(16)  NOT NULL,
    xref_cust_id   BIGINT       NOT NULL,
    xref_acct_id   BIGINT       NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num)
);

-- -----------------------------------------------------------------------------
-- 5. transaction  (posted transactions)
--    "transaction" is a PostgreSQL non-reserved keyword, valid unquoted and
--    matching Hibernate's unquoted @Table(name = "transaction") reference.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction (
    tran_id             VARCHAR(16)     NOT NULL,
    tran_type_cd        VARCHAR(2)      NOT NULL,
    tran_cat_cd         INTEGER         NOT NULL,
    tran_source         VARCHAR(10)     NOT NULL,
    tran_desc           VARCHAR(100)    NOT NULL,
    tran_amt            NUMERIC(11, 2)  NOT NULL,
    tran_merchant_id    BIGINT          NOT NULL,
    tran_merchant_name  VARCHAR(50)     NOT NULL,
    tran_merchant_city  VARCHAR(50)     NOT NULL,
    tran_merchant_zip   VARCHAR(10)     NOT NULL,
    tran_card_num       VARCHAR(16)     NOT NULL,
    tran_orig_ts        VARCHAR(26)     NOT NULL,
    tran_proc_ts        VARCHAR(26)     NOT NULL,
    CONSTRAINT pk_transaction PRIMARY KEY (tran_id)
);

-- -----------------------------------------------------------------------------
-- 6. user_security  (sign-on credentials).
--    sec_usr_pwd is widened to 60 chars to hold a BCrypt hash (credential
--    hardening C-003); the legacy plaintext field was 8 chars.
-- -----------------------------------------------------------------------------
CREATE TABLE user_security (
    sec_usr_id     VARCHAR(8)   NOT NULL,
    sec_usr_fname  VARCHAR(20)  NOT NULL,
    sec_usr_lname  VARCHAR(20)  NOT NULL,
    sec_usr_pwd    VARCHAR(60)  NOT NULL,
    sec_usr_type   VARCHAR(1)   NOT NULL,
    CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id)
);

-- -----------------------------------------------------------------------------
-- 7. transaction_category_balance  (per-account category balances).
--    Composite primary key (account id + type code + category code).
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_category_balance (
    trancat_acct_id  BIGINT          NOT NULL,
    trancat_type_cd  VARCHAR(2)      NOT NULL,
    trancat_cd       INTEGER         NOT NULL,
    tran_cat_bal     NUMERIC(11, 2)  NOT NULL,
    CONSTRAINT pk_transaction_category_balance
        PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);

-- -----------------------------------------------------------------------------
-- 8. disclosure_group  (interest-rate disclosure groups).
--    Composite primary key; dis_acct_group_id also holds the DEFAULT/ZEROAPR
--    fallback literals in addition to A0000000xx-style group ids.
-- -----------------------------------------------------------------------------
CREATE TABLE disclosure_group (
    dis_acct_group_id  VARCHAR(10)    NOT NULL,
    dis_tran_type_cd   VARCHAR(2)     NOT NULL,
    dis_tran_cat_cd    INTEGER        NOT NULL,
    dis_int_rate       NUMERIC(6, 2)  NOT NULL,
    CONSTRAINT pk_disclosure_group
        PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

-- -----------------------------------------------------------------------------
-- 9. transaction_type  (transaction-type reference)
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_type (
    tran_type       VARCHAR(2)   NOT NULL,
    tran_type_desc  VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_transaction_type PRIMARY KEY (tran_type)
);

-- -----------------------------------------------------------------------------
-- 10. transaction_category  (transaction-category reference).
--     Composite primary key (type code + category code).
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_category (
    tran_type_cd        VARCHAR(2)   NOT NULL,
    tran_cat_cd         INTEGER      NOT NULL,
    tran_cat_type_desc  VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_transaction_category PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

-- -----------------------------------------------------------------------------
-- 11. daily_transaction  (daily-transaction staging input)
-- -----------------------------------------------------------------------------
CREATE TABLE daily_transaction (
    dalytran_id             VARCHAR(16)     NOT NULL,
    dalytran_type_cd        VARCHAR(2)      NOT NULL,
    dalytran_cat_cd         INTEGER         NOT NULL,
    dalytran_source         VARCHAR(10)     NOT NULL,
    dalytran_desc           VARCHAR(100)    NOT NULL,
    dalytran_amt            NUMERIC(11, 2)  NOT NULL,
    dalytran_merchant_id    BIGINT          NOT NULL,
    dalytran_merchant_name  VARCHAR(50)     NOT NULL,
    dalytran_merchant_city  VARCHAR(50)     NOT NULL,
    dalytran_merchant_zip   VARCHAR(10)     NOT NULL,
    dalytran_card_num       VARCHAR(16)     NOT NULL,
    dalytran_orig_ts        VARCHAR(26)     NOT NULL,
    dalytran_proc_ts        VARCHAR(26)     NOT NULL,
    CONSTRAINT pk_daily_transaction PRIMARY KEY (dalytran_id)
);

-- =============================================================================
-- Spring Batch 5.x metadata schema (PostgreSQL)
-- -----------------------------------------------------------------------------
-- Reproduced verbatim from org/springframework/batch/core/schema-postgresql.sql
-- (spring-batch-core 5.2.6, resolved by the Spring Boot 3.5.15 BOM). These
-- tables and sequences are required because spring.batch.jdbc.initialize-schema
-- is "never"; Spring Batch's JDBC DAOs depend on these exact object/column
-- names, so do not rename or alter them.
-- =============================================================================

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

