-- =============================================================================
-- Flyway Migration: V011__create_daily_transaction.sql
-- Purpose:    Create the daily_transactions table -- a STAGING table that
--             holds incoming daily transaction records BEFORE they are
--             validated and posted to the main transactions journal (V005).
--             This is the Java target for the COBOL DALYTRAN flow:
--               PS file AWS.M2.CARDDEMO.DALYTRAN.PS
--                 -> consumed by CBTRN01C (read/dump)
--                 -> validated and posted by CBTRN02C
--                 -> rejects written to AWS.M2.CARDDEMO.DALYREJS(+1)
--                 -> accepted txns inserted into TRANSACT.VSAM.KSDS
--             In the AWS-native target architecture, the staging table is
--             populated either by:
--               (a) Spring Batch ItemReader (S3 -> RDS) for the parallel-run
--                   period using app/data/ASCII/dailytran.txt (300 records)
--                   as the golden fixture, OR
--               (b) AWS Glue Spark jobs ingesting upstream feeds from S3
--                   (per AAP §0.4.1, infrastructure/terraform/glue.tf).
--             The DailyTransactionPostingJob (Spring Batch) then reads from
--             this table, runs the 4-stage validation cascade (XREF lookup,
--             Account lookup, Credit-limit check, Card-expiration check), and
--             on success appends to transactions + updates accounts +
--             updates tran_cat_bal + publishes transaction.posted to MSK;
--             on failure writes a rejection record to S3 via S3OutputService
--             preserving COBOL reject codes 100-109 (AAP §0.4.1).
--
-- Source:     app/cpy/CVTRA06Y.cpy   (DALYTRAN-RECORD layout, RECLN 350;
--                                     byte-identical to CVTRA05Y.cpy with
--                                     DALYTRAN- prefix replacing TRAN-)
--             app/jcl/POSTTRAN.jcl   (DD allocation: DALYTRAN DD
--                                     DSN=AWS.M2.CARDDEMO.DALYTRAN.PS;
--                                     consumed by STEP15 EXEC PGM=CBTRN02C)
--             app/data/ASCII/dailytran.txt
--                                    (300 fixed-width 350-byte golden fixture
--                                     records used for parallel-run testing)
--             app/cbl/CBTRN01C.cbl   (sequential reader -- dumps DALYTRAN file)
--             app/cbl/CBTRN02C.cbl   (transaction-posting cascade)
-- AAP Refs:   §0.4.1 (V011 daily_transactions staging table;
--                     DailyTransactionPostingJob migration plan),
--             §0.6.1 (BigDecimal/NUMERIC precision for DALYTRAN-AMT;
--                     PIC S9(09)V99 -> NUMERIC(11,2) with HALF_EVEN rounding
--                     applied at the Java arithmetic boundary),
--             §0.6.2 (VSAM/PS to RDS migration strategy; preservation of
--                     COBOL byte-layout semantics in relational columns).
--
-- Replaces:   The COBOL PS dataset AWS.M2.CARDDEMO.DALYTRAN.PS (sequential
--             physical-sequential file). In the source system this was a
--             fixed-width flat file allocated via JCL DD card and consumed
--             by CBTRN02C via standard COBOL SELECT/FD READ semantics. In
--             the target, this becomes a relational staging table that is
--             populated by Spring Batch / AWS Glue and consumed by the
--             DailyTransactionPostingJob. No IDCAMS DEFINE CLUSTER existed
--             for DALYTRAN (it was a PS dataset, not VSAM), so no LISTCAT
--             entry corresponds to this table.
--
-- =============================================================================
-- STAGING-TABLE RATIONALE -- FOREIGN KEYS INTENTIONALLY OMITTED
-- =============================================================================
-- This table holds UNPROCESSED incoming transactions. Although
-- dalytran_card_num could logically reference cards(card_num) and
-- dalytran_type_cd could reference tran_type(tran_type), foreign-key
-- constraints are DELIBERATELY NOT ENFORCED at the database level because:
--
--   1. Loading order: a single batch may contain transactions for cards
--      that have not yet been ingested into the cards table (e.g., a new
--      card record arriving in the same end-of-day cycle). Enforcing a FK
--      would force a strict load ordering and complicate the Spring Batch
--      ItemReader / Glue job ingestion.
--
--   2. Validation policy: the COBOL CBTRN02C.cbl program performs explicit
--      FILE-STATUS '23' (NOTFND) checks against XREFFILE, CARDFILE, and
--      ACCTFILE during its validation cascade and assigns specific reject
--      codes (100..109) for each failure mode. The Java equivalent is the
--      4-stage validation cascade in TransactionPostingService that throws
--      RecordNotFoundException (mapped to reject code by category) and
--      writes the rejection to S3 via S3OutputService. Per AAP §0.4.1:
--        "4-stage validation cascade (XREF / Account / Credit limit /
--         Card expiration); rejects written to S3 via S3OutputService;
--         reject codes 100-109 preserved".
--      Validation happens in the Java service layer, NOT enforced at the
--      database level on the staging table.
--
--   3. Replay semantics: a malformed or orphan record arriving in
--      dailytran.txt must be CAPTURED in this staging table so it can be
--      inspected, root-caused, and either replayed or rejected -- never
--      silently dropped at INSERT time by a constraint violation.
--
--   4. Throughput: bulk-load operations (S3 -> RDS via Glue or COPY)
--      benefit from deferred or absent FK validation; the staging table
--      is sized for high-throughput inserts.
--
-- The MAIN transactions table (V005) DOES enforce these constraints because
-- by the time a record reaches transactions, the Java validation cascade
-- has already proven the parent records exist.
-- =============================================================================

-- =============================================================================
-- COBOL DALYTRAN-RECORD layout (CVTRA06Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field           PIC clause       PostgreSQL column         Type
--   --------------------- ---------------- ------------------------- -----------
--   DALYTRAN-ID           PIC X(16)        dalytran_id               VARCHAR(16)
--   DALYTRAN-TYPE-CD      PIC X(02)        dalytran_type_cd          CHAR(2)
--   DALYTRAN-CAT-CD       PIC 9(04)        dalytran_cat_cd           NUMERIC(4)
--   DALYTRAN-SOURCE       PIC X(10)        dalytran_source           VARCHAR(10)
--   DALYTRAN-DESC         PIC X(100)       dalytran_desc             VARCHAR(100)
--   DALYTRAN-AMT          PIC S9(09)V99    dalytran_amt              NUMERIC(11,2)
--   DALYTRAN-MERCHANT-ID  PIC 9(09)        dalytran_merchant_id      NUMERIC(9)
--   DALYTRAN-MERCHANT-NM  PIC X(50)        dalytran_merchant_name    VARCHAR(50)
--   DALYTRAN-MERCHANT-CY  PIC X(50)        dalytran_merchant_city    VARCHAR(50)
--   DALYTRAN-MERCHANT-ZP  PIC X(10)        dalytran_merchant_zip     VARCHAR(10)
--   DALYTRAN-CARD-NUM     PIC X(16)        dalytran_card_num         VARCHAR(16)
--   DALYTRAN-ORIG-TS      PIC X(26)        dalytran_orig_ts          TIMESTAMP(6)
--   DALYTRAN-PROC-TS      PIC X(26)        dalytran_proc_ts          TIMESTAMP(6)
--   FILLER                PIC X(20)        OMITTED                   --
--
-- Notes:
--   - PIC S9(09)V99 (Java BigDecimal scale=2) maps to NUMERIC(11,2) where
--     precision = 9 digits + 2 fractional + 1 reserve for sign = 11 total
--     (PostgreSQL NUMERIC precision is the TOTAL count of significant
--     digits, not including the decimal point). Per AAP §0.6.1, all
--     monetary arithmetic in Java uses BigDecimal with RoundingMode.HALF_EVEN
--     and ON SIZE ERROR is replicated via OnSizeErrorException.
--   - Timestamps stored as TIMESTAMP(6) (microsecond precision) because the
--     source data uses 26-character ISO-like format 'YYYY-MM-DD HH24:MI:SS.ffffff'
--     (verified against app/data/ASCII/dailytran.txt -- e.g.,
--      '2022-06-10 19:27:53.000000'). Parsing/formatting is handled by Java
--     DateValidationService via java.time.LocalDateTime.parse.
--   - The trailing 20-byte FILLER in the COBOL record is OMITTED -- it is
--     unused padding for the fixed-width VSAM/PS layout and has no
--     relational equivalent.
--   - dalytran_type_cd is CHAR(2) (e.g., '01') -- always exactly 2
--     characters with significant leading zeros. The same value space as
--     tran_type (V008/V013).
--   - dalytran_cat_cd is NUMERIC(4) -- the integer category code (e.g.,
--     1, 5, 7) joined with dalytran_type_cd as the tran_category composite
--     key in V009/V014.
--   - dalytran_merchant_id is NUMERIC(9) (PIC 9(09) is UNSIGNED in COBOL;
--     no sign reserve needed).
--   - String columns (VARCHAR) store trimmed values without trailing
--     padding spaces (idiomatic relational storage; the Java entity uses
--     String and Spring Data JPA will TRIM during read where needed for
--     parity with COBOL behavior).
-- =============================================================================

create table daily_transactions (
    -- Primary key -- DALYTRAN-ID PIC X(16); same value space as
    -- transactions.tran_id (V005). 16-byte alphanumeric identifier.
    dalytran_id              varchar(16)     not null,

    -- DALYTRAN-TYPE-CD PIC X(02); transaction-type code (e.g., '01' purchase,
    -- '02' return, '03' payment). Always exactly 2 characters; leading zeros
    -- significant. Logically maps to tran_type.tran_type (V008/V013) but
    -- FK omitted per staging-table policy above.
    dalytran_type_cd         char(2)         not null,

    -- DALYTRAN-CAT-CD PIC 9(04); transaction-category code (1..9999).
    -- Joined with dalytran_type_cd as the composite key referenced by
    -- tran_category (V009/V014). FK omitted per staging-table policy.
    dalytran_cat_cd          numeric(4)      not null,

    -- DALYTRAN-SOURCE PIC X(10); origination source (e.g., 'POS TERM',
    -- 'OPERATOR', 'ONLINE'). Free-form 10-char alphanumeric per fixture.
    dalytran_source          varchar(10),

    -- DALYTRAN-DESC PIC X(100); human-readable transaction description.
    -- May include merchant name and free-text supplied by upstream feed.
    dalytran_desc            varchar(100),

    -- DALYTRAN-AMT PIC S9(09)V99; signed monetary amount in fixed-point
    -- decimal -- 9 integer digits, 2 fractional digits, signed.
    -- Stored as NUMERIC(11,2) so Java BigDecimal arithmetic with scale=2
    -- and RoundingMode.HALF_EVEN matches COBOL semantics exactly.
    -- ON SIZE ERROR overflow is detected in Java via OnSizeErrorException
    -- (any arithmetic result that would exceed NUMERIC(11,2) precision
    -- triggers the exception). Per AAP §0.6.1, NEVER use DOUBLE PRECISION.
    dalytran_amt             numeric(11, 2)  not null,

    -- DALYTRAN-MERCHANT-ID PIC 9(09); unsigned 9-digit merchant identifier.
    -- Logically links to a merchant master, but no merchant table is in
    -- scope for this refactor -- the merchant attributes are denormalized
    -- onto each transaction row (mirrors the COBOL flat-file structure).
    dalytran_merchant_id     numeric(9),

    -- DALYTRAN-MERCHANT-NAME PIC X(50); merchant display name.
    dalytran_merchant_name   varchar(50),

    -- DALYTRAN-MERCHANT-CITY PIC X(50); merchant city.
    dalytran_merchant_city   varchar(50),

    -- DALYTRAN-MERCHANT-ZIP PIC X(10); merchant ZIP/postal code.
    -- VARCHAR(10) accommodates 5-digit, 5+4 hyphenated US ZIP, and
    -- international postal formats per the fixture (e.g., '78487-7965').
    dalytran_merchant_zip    varchar(10),

    -- DALYTRAN-CARD-NUM PIC X(16); 16-digit card PAN (Primary Account
    -- Number) stored as VARCHAR(16) to preserve leading zeros and any
    -- non-numeric formatting variations. Indexed below for batch
    -- validation join performance. Logically references cards.card_num
    -- (V002) but FK omitted per staging-table policy above. Note:
    -- PCI-DSS handling rules apply -- per AAP §0.6.6, no plaintext PAN
    -- is permitted in application logs; database storage is encrypted
    -- at rest via RDS KMS CMK and in transit via TLS 1.2+.
    dalytran_card_num        varchar(16)     not null,

    -- DALYTRAN-ORIG-TS PIC X(26); origination timestamp recorded by the
    -- upstream system in 'YYYY-MM-DD HH24:MI:SS.ffffff' format. Stored
    -- as TIMESTAMP(6) for microsecond precision. Conversion from the
    -- COBOL X(26) text to a SQL TIMESTAMP is performed by Java
    -- DateValidationService / Spring Batch ItemProcessor.
    dalytran_orig_ts         timestamp(6)    not null,

    -- DALYTRAN-PROC-TS PIC X(26); processing timestamp recorded when the
    -- transaction enters the posting pipeline. Stored as TIMESTAMP(6).
    -- The (card_num, proc_ts) tuple is the natural ordering key for
    -- per-card transaction streams (mirrors the alternate index pattern
    -- of TRANSACT.VSAM.AIX referenced in app/catlg/LISTCAT.txt).
    dalytran_proc_ts         timestamp(6)    not null,

    -- The trailing FILLER PIC X(20) in the COBOL record is OMITTED here.
    -- It is unused padding for the 350-byte fixed-width record and has
    -- no relational equivalent.

    constraint pk_daily_transactions primary key (dalytran_id)
);

-- =============================================================================
-- Index: idx_daily_transactions_card_num
-- -----------------------------------------------------------------------------
-- Supports the 4-stage validation cascade in DailyTransactionPostingJob:
-- the second stage joins daily_transactions.dalytran_card_num against
-- cards.card_num (via CardCrossReference for account-level checks).
-- Without this index, the batch validator would full-scan
-- daily_transactions on every join probe, degrading EOD batch SLA
-- compliance. The pattern mirrors the application-level lookup index on
-- the main transactions table (V005) by card_num. Per AAP §0.6.2,
-- secondary indexes replace the VSAM AIX/PATH chains of the source.
-- =============================================================================
create index idx_daily_transactions_card_num
    on daily_transactions (dalytran_card_num);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ daily_transactions
-- -----------------------------------------------------------------------------
-- Inline COMMENT ON statements make the COBOL provenance discoverable from
-- the database itself (regulator/auditor view via JDBC tooling). Per AAP
-- §0.7.3 refactor discipline ("inline traceability comments referencing the
-- original COBOL paragraph/section name").
-- =============================================================================
comment on table daily_transactions is
    'Staging table for unposted daily transactions (COBOL DALYTRAN-RECORD '
    'in CVTRA06Y.cpy; PS dataset AWS.M2.CARDDEMO.DALYTRAN.PS). Java target '
    'for DailyTransactionPostingJob (replaces COBOL CBTRN01C/CBTRN02C). '
    'Foreign keys intentionally omitted -- validation enforced in Java '
    'service layer (TransactionPostingService) preserving COBOL reject '
    'codes 100-109.';

comment on column daily_transactions.dalytran_id is
    'DALYTRAN-ID PIC X(16); primary key; same value space as transactions.tran_id.';
comment on column daily_transactions.dalytran_type_cd is
    'DALYTRAN-TYPE-CD PIC X(02); 2-char transaction-type code (see tran_type).';
comment on column daily_transactions.dalytran_cat_cd is
    'DALYTRAN-CAT-CD PIC 9(04); transaction-category code (see tran_category).';
comment on column daily_transactions.dalytran_source is
    'DALYTRAN-SOURCE PIC X(10); origination source (e.g., POS TERM, OPERATOR).';
comment on column daily_transactions.dalytran_desc is
    'DALYTRAN-DESC PIC X(100); human-readable transaction description.';
comment on column daily_transactions.dalytran_amt is
    'DALYTRAN-AMT PIC S9(09)V99; signed monetary amount; Java BigDecimal HALF_EVEN.';
comment on column daily_transactions.dalytran_merchant_id is
    'DALYTRAN-MERCHANT-ID PIC 9(09); unsigned 9-digit merchant identifier.';
comment on column daily_transactions.dalytran_merchant_name is
    'DALYTRAN-MERCHANT-NAME PIC X(50); merchant display name.';
comment on column daily_transactions.dalytran_merchant_city is
    'DALYTRAN-MERCHANT-CITY PIC X(50); merchant city.';
comment on column daily_transactions.dalytran_merchant_zip is
    'DALYTRAN-MERCHANT-ZIP PIC X(10); merchant ZIP/postal code.';
comment on column daily_transactions.dalytran_card_num is
    'DALYTRAN-CARD-NUM PIC X(16); 16-char card PAN; PCI-DSS protected (KMS at rest, TLS 1.2+ in transit).';
comment on column daily_transactions.dalytran_orig_ts is
    'DALYTRAN-ORIG-TS PIC X(26); origination timestamp; microsecond precision.';
comment on column daily_transactions.dalytran_proc_ts is
    'DALYTRAN-PROC-TS PIC X(26); processing timestamp; microsecond precision.';
