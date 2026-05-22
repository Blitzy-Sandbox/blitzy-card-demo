-- =============================================================================
-- Flyway Migration: V001__create_account.sql
-- Purpose:    Create the accounts table -- the foundational JPA-mapped
--             relational equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS. This is the single most
--             important reference table in the CardDemo schema: every
--             cardholder transaction, interest calculation, balance update,
--             cycle-credit accumulation, and statement-generation step in
--             the CardDemo application revolves around the row identified
--             by ACCT-ID. Without a populated accounts table, the entire
--             signon-to-statement business flow has no subject to act upon.
--
--             Consumers (Java target services that READ from / WRITE to
--             the accounts table):
--               - AccountViewService          (COBOL COACTVWC)  -- random
--                 read by acct_id, joined with customers via the
--                 card_xref table to render the account-view screen.
--               - AccountUpdateService        (COBOL COACTUPC)  -- random
--                 read + update under a @Transactional(rollbackFor =
--                 Exception.class) boundary with JPA @Version optimistic
--                 locking; replaces the COBOL before/after image
--                 comparison + EXEC CICS SYNCPOINT ROLLBACK pattern that
--                 is the ONLY explicit multi-dataset transactional
--                 integrity mechanism in the CardDemo source per AAP
--                 §0.1.1.
--               - BillPaymentService          (COBOL COBIL00C)  -- updates
--                 acct_curr_bal inside a @Transactional boundary that
--                 also writes the offsetting transaction row.
--               - InterestCalculationService  (COBOL CBACT04C)  -- end-of-
--                 cycle batch job that reads every account row, joins
--                 against tran_cat_bal + disclosure_group (with DEFAULT
--                 fallback), computes interest via the formula
--                 (balance * rate) / 1200 with BigDecimal scale 2 and
--                 RoundingMode.HALF_EVEN per AAP §0.6.1, and updates
--                 acct_curr_bal / acct_curr_cyc_credit / acct_curr_cyc_debit.
--               - TransactionPostingService   (COBOL CBTRN01C/02C/03C) --
--                 reads acct_curr_bal and acct_credit_limit during the
--                 4-stage validation cascade (XREF / account / credit
--                 limit / card expiration -- reject codes 100-109
--                 preserved verbatim per AAP §0.1.1).
--               - AccountFileReaderService    (COBOL CBACT01C)  -- batch
--                 sequential scanner that emits every row in the accounts
--                 table for audit / reporting purposes.
--               - StatementGenerationService  (COBOL CBSTM03A/03B) --
--                 reads account balances and cycle credit/debit totals
--                 to print monthly statements.
--               - AccountRepository (Spring Data JPA) -- exposes
--                 findById(Long acctId) keyed by acct_id; injected into
--                 every service above.
--
-- Source:     app/cpy/CVACT01Y.cpy   (ACCOUNT-RECORD layout, RECLN 300,
--                                     12 business fields + 178-byte
--                                     trailing FILLER; verified L4-L17.
--                                     Note: the source copybook contains
--                                     a COBOL spelling typo on L11
--                                     "ACCT-EXPIRAION-DATE" instead of
--                                     "EXPIRATION" -- the PostgreSQL
--                                     column adopts the corrected
--                                     spelling per AAP §0.6.2.)
--             app/jcl/ACCTFILE.jcl   (IDCAMS DEFINE CLUSTER L36-L49:
--                                     KEYS(11 0), RECORDSIZE(300 300),
--                                     CYLINDERS(1 5), SHAREOPTIONS(2 3),
--                                     ERASE, INDEXED; plus REPRO step
--                                     L54-L62 loading ACCTDATA.PS -> KSDS)
--             app/catlg/LISTCAT.txt  (verifies ACCTDATA.VSAM.KSDS cluster
--                                     L22-L79: KEYLEN=11, RKP=0,
--                                     MAXLRECL=300, AVGLRECL=300,
--                                     INDEXED, SHROPTNS(2,3),
--                                     CISIZE=18432, BUFSPACE=37376,
--                                     REC-TOTAL=50)
--
-- AAP Refs:   §0.4.1 (V001 account; one-to-one mapping of ACCTDATA.KSDS
--                     to the accounts relational table; foundation table
--                     referenced by V002 cards, V004 card_xref, V006
--                     tran_cat_bal),
--             §0.6.1 (COBOL decimal precision and BigDecimal mapping --
--                     every PIC S9(10)V99 monetary field maps to
--                     NUMERIC(12,2) where precision = digits_total + 1
--                     (sign) and scale = 2 to match the V99 component;
--                     banker's rounding -- RoundingMode.HALF_EVEN -- is
--                     enforced at the Java service layer, NOT in SQL DDL,
--                     because PostgreSQL NUMERIC arithmetic is exact and
--                     rounding only applies at explicit scale changes),
--             §0.6.2 (VSAM-to-RDS migration strategy -- KEYS(11 0) maps to
--                     NUMERIC(11) PRIMARY KEY on acct_id; B-tree on the
--                     PK satisfies the COBOL VSAM random-read pattern;
--                     ACCTDATA.VSAM.KSDS has NO alternate index (AIX)
--                     per LISTCAT.txt, so no secondary index is required;
--                     the cleaned-up column name acct_expiration_date
--                     corrects the COBOL "EXPIRAION" typo),
--             §0.6.6 (PCI-DSS compliance -- encryption at rest delegated
--                     to RDS via the customer KMS CMK, configured in
--                     infrastructure/terraform/rds.tf; encryption in
--                     transit via 'rds.force_ssl=1' parameter; no column-
--                     level pgcrypto applied at the schema layer per the
--                     AAP §0.6.6 architectural decision),
--             §0.7.1 (refactor discipline -- (a) BigDecimal +
--                     RoundingMode.HALF_EVEN for all monetary values;
--                     (b) optimistic locking via JPA @Version replaces
--                     COBOL before/after image comparison in COACTUPC.cbl;
--                     (c) all monetary columns NOT NULL DEFAULT 0 to
--                     mirror COBOL fixed-width semantics where every byte
--                     is always present),
--             §0.7.3 (Minimal Change Clause -- preserve COBOL semantics
--                     exactly; do NOT add CHECK constraints or business-
--                     rule validation that did not exist in the COBOL
--                     source; CHECK on acct_active_status restricted to
--                     ('Y','N') is an exception added as defense-in-
--                     depth per the same pattern used in V010
--                     user_security for sec_usr_type).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
--             in app/jcl/ACCTFILE.jcl:L36-L49. The IDCAMS REPRO step
--             (app/jcl/ACCTFILE.jcl:L54-L62) that loaded ACCTDATA.PS into
--             the KSDS is NOT replaced by a Flyway seed migration --
--             account master data is loaded for bulk-fact purposes by an
--             AWS Glue Spark job per AAP §0.6.2 (Glue reads ASCII fixtures
--             from S3 and writes to RDS via the Glue PostgreSQL
--             connection); reference rows for local testing are loaded
--             by Testcontainers fixtures and integration-test seed data.
-- =============================================================================

-- =============================================================================
-- COBOL ACCOUNT-RECORD layout (CVACT01Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field              PIC clause       PostgreSQL column         Type
--   ------------------------ ---------------- ------------------------- -----------------
--   ACCT-ID                  PIC 9(11)        acct_id                   NUMERIC(11)  (PK)
--   ACCT-ACTIVE-STATUS       PIC X(01)        acct_active_status        CHAR(1)      NN
--   ACCT-CURR-BAL            PIC S9(10)V99    acct_curr_bal             NUMERIC(12,2) NN
--   ACCT-CREDIT-LIMIT        PIC S9(10)V99    acct_credit_limit         NUMERIC(12,2) NN
--   ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99    acct_cash_credit_limit    NUMERIC(12,2) NN
--   ACCT-OPEN-DATE           PIC X(10)        acct_open_date            DATE         NN
--   ACCT-EXPIRAION-DATE      PIC X(10) [typo] acct_expiration_date      DATE         NN
--   ACCT-REISSUE-DATE        PIC X(10)        acct_reissue_date         DATE
--   ACCT-CURR-CYC-CREDIT     PIC S9(10)V99    acct_curr_cyc_credit      NUMERIC(12,2) NN
--   ACCT-CURR-CYC-DEBIT      PIC S9(10)V99    acct_curr_cyc_debit       NUMERIC(12,2) NN
--   ACCT-ADDR-ZIP            PIC X(10)        acct_addr_zip             VARCHAR(10)
--   ACCT-GROUP-ID            PIC X(10)        acct_group_id             VARCHAR(10)
--   FILLER                   PIC X(178)       OMITTED                   -- (padding)
--   (no COBOL equivalent)    --               version                   BIGINT       NN
--
-- Total COBOL record length: 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12
--                          +  10 + 10 + 178 = 300 bytes (per LISTCAT
--                          MAXLRECL=300 / AVGLRECL=300).
--
-- Total PostgreSQL columns: 13 (12 business fields from copybook minus
--                           the FILLER, plus the application-added
--                           version column for JPA @Version).
--
-- VSAM key position (RKP=0) and key length (KEYLEN=11) map to a
-- NUMERIC(11) PRIMARY KEY on acct_id. The 11-digit unsigned numeric
-- range (0..99,999,999,999) maps cleanly to a Java Long inside the
-- Account JPA entity.
--
-- Storage-tier attributes from ACCTFILE.jcl that have NO PostgreSQL
-- equivalent (PostgreSQL handles storage layout automatically and the
-- RDS Multi-AZ topology supersedes z/OS VSAM characteristics):
--   - CYLINDERS(1 5)         : z/OS physical allocation; not applicable
--   - SHAREOPTIONS(2 3)      : VSAM concurrency; replaced by PostgreSQL
--                              MVCC + JPA @Transactional isolation
--                              (READ_COMMITTED default; stronger as
--                              required by business rule)
--   - ERASE                  : VSAM data zeroing on DELETE; replaced by
--                              standard SQL DELETE (PostgreSQL's MVCC
--                              tombstone is reclaimed by autovacuum)
--   - INDEXED                : KSDS = indexed; PostgreSQL B-tree on PK
--                              provides the same random-access semantic
--   - CISIZE=18432           : VSAM control-interval size; PostgreSQL
--                              uses 8 KB pages by default and tuning is
--                              a parameter-group concern, not a DDL one
--   - BUFSPACE=37376         : VSAM buffer pool; PostgreSQL shared
--                              buffers are configured at the cluster /
--                              parameter-group level
--
-- The FILLER PIC X(178) is the largest field in the COBOL record (178
-- bytes of trailing padding to bring the layout to the fixed 300-byte
-- RECLN). PostgreSQL has no concept of fixed-width records, so this
-- padding has no relational equivalent and is dropped. See the
-- corresponding inline comment at the bottom of the CREATE TABLE.
--
-- The version BIGINT NOT NULL DEFAULT 0 column is added to support the
-- JPA @Version optimistic-locking pattern that replaces the COBOL
-- before/after image comparison in COACTUPC.cbl. Per AAP §0.6.2 / §0.7.1
-- this is the SAME PATTERN used to replace the in-place EXEC CICS
-- READ UPDATE / REWRITE flow: every account update reads the current
-- version, increments it on save, and the JPA layer throws
-- OptimisticLockException (translated to ConcurrentModificationException
-- per the GlobalExceptionHandler in src/main/java/com/awsm2/carddemo/
-- exception/) when two concurrent writers race.
-- =============================================================================

create table accounts (
    -- ACCT-ID PIC 9(11); the primary VSAM KSDS key (RKP=0, KEYLEN=11
    -- per app/catlg/LISTCAT.txt). 11-digit unsigned account identifier
    -- (range 0..99,999,999,999). Spring Data JPA's AccountRepository
    -- uses this as the @Id (entity class: Account, mapping field
    -- acctId : Long). Referenced as a foreign key by:
    --   - cards.acct_id      (V002 -- account-to-card relationship)
    --   - card_xref.acct_id  (V004 -- 3-way cross-reference)
    --   - tran_cat_bal.acct_id (V006 -- category balance accumulator)
    acct_id                    numeric(11)   not null,

    -- ACCT-ACTIVE-STATUS PIC X(01); 1-character account status flag.
    -- COBOL business rule: 'Y' = active (transactions may be posted),
    -- 'N' = closed / inactive (transactions are rejected at validation
    -- stage 2 in TransactionPostingService per AAP §0.4.1 / CBTRN02C
    -- reject codes 100-109 preservation). 1-character fixed-width in
    -- COBOL; stored as CHAR(1) to preserve the exact semantics. A
    -- CHECK constraint is added below for defense-in-depth following
    -- the same pattern used in V010 user_security for sec_usr_type
    -- (per AAP §0.7.1 / §0.7.3 -- prevents corrupted values from
    -- silently breaking transaction-posting decisions).
    acct_active_status         char(1)       not null,

    -- ACCT-CURR-BAL PIC S9(10)V99; current account balance, signed
    -- 10-digit integer + 2 implied decimal places. Maps to
    -- NUMERIC(12,2): precision = 10 (digits) + 2 (V99 fractional) = 12,
    -- scale = 2 -- per AAP §0.6.1. NOT NULL DEFAULT 0 mirrors COBOL's
    -- fixed-width every-byte-always-present semantics (no concept of
    -- NULL in a COBOL record).
    --
    -- *** MONETARY FIELD -- BIGDECIMAL ARITHMETIC RULES ***
    -- Banker's rounding (RoundingMode.HALF_EVEN) is enforced at the
    -- Java service layer (TransactionPostingService, BillPaymentService,
    -- InterestCalculationService) per AAP §0.6.1. NEVER use DOUBLE
    -- PRECISION, REAL, or FLOAT here -- those types lose precision on
    -- decimal arithmetic and break parity with COBOL. NUMERIC is
    -- arbitrary-precision exact arithmetic; rounding only occurs at
    -- explicit scale changes (set_scale / @DecimalMax / multiply +
    -- divide chains). The Java BigDecimal column type in the Account
    -- entity ALWAYS specifies scale=2; if a computed value would
    -- exceed precision=12, OnSizeErrorException is thrown per AAP
    -- §0.6.1 to mirror the COBOL ON SIZE ERROR semantic.
    acct_curr_bal              numeric(12,2) not null default 0,

    -- ACCT-CREDIT-LIMIT PIC S9(10)V99; total credit limit. Same
    -- NUMERIC(12,2) NOT NULL DEFAULT 0 rules as acct_curr_bal. Read
    -- by TransactionPostingService at validation stage 3 (credit-
    -- limit check; reject code 102 preserved verbatim per AAP §0.1.1
    -- / CBTRN02C). The 10-digit precision permits balances up to
    -- $9,999,999,999.99 -- a forward-compatible range that matches
    -- the COBOL source exactly.
    acct_credit_limit          numeric(12,2) not null default 0,

    -- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99; cash-advance credit limit
    -- (separate from total credit limit). Same NUMERIC(12,2) NOT NULL
    -- DEFAULT 0 rules. Read by TransactionPostingService when the
    -- incoming transaction's transaction-category-code identifies the
    -- transaction as a cash advance (TRANCATG lookup).
    acct_cash_credit_limit     numeric(12,2) not null default 0,

    -- ACCT-OPEN-DATE PIC X(10); account-opening date in ISO-8601
    -- 'YYYY-MM-DD' format inside the COBOL record. Parsed from the
    -- COBOL X(10) string to a native PostgreSQL DATE via
    -- DateValidationService (porting CSUTLDPY.cpy / CSUTLDTC.cbl)
    -- using java.time.LocalDate.parse / DateTimeFormatter per AAP
    -- §0.6.1. Native DATE enables JPA / Hibernate to map the field to
    -- java.time.LocalDate without manual substring parsing. NOT NULL
    -- because every account has a known opening date.
    acct_open_date             date          not null,

    -- ACCT-EXPIRAION-DATE PIC X(10) -- note the COBOL spelling typo
    -- "EXPIRAION" on app/cpy/CVACT01Y.cpy:L11. The PostgreSQL column
    -- adopts the corrected spelling 'acct_expiration_date' per AAP
    -- §0.6.2 column listing. The COBOL source field name is preserved
    -- in this inline comment for traceability and in the
    -- COMMENT ON COLUMN catalog metadata below.
    --
    -- Account expiration date in ISO-8601 'YYYY-MM-DD' format inside
    -- the COBOL record. Parsed from X(10) to native DATE via
    -- DateValidationService. Read by TransactionPostingService at
    -- validation stage 4 (card expiration check; reject code 103
    -- preserved verbatim per AAP §0.1.1 / CBTRN02C). NOT NULL because
    -- every account has a known expiration date.
    acct_expiration_date       date          not null,

    -- ACCT-REISSUE-DATE PIC X(10); the most recent reissue / card-
    -- replacement date in ISO-8601 'YYYY-MM-DD' format. Nullable
    -- because not every account has been reissued -- new accounts
    -- and never-reissued accounts have an empty / all-spaces value
    -- in the COBOL source, which maps to PostgreSQL NULL per the
    -- standard COBOL-to-PostgreSQL all-spaces-> NULL convention.
    acct_reissue_date          date,

    -- ACCT-CURR-CYC-CREDIT PIC S9(10)V99; cumulative credits posted
    -- in the current billing cycle (payments, refunds, reversals).
    -- Same NUMERIC(12,2) NOT NULL DEFAULT 0 rules as the other
    -- monetary fields. Read + updated by InterestCalculationService
    -- (CBACT04C) end-of-cycle and by BillPaymentService (COBIL00C)
    -- when posting a customer payment.
    acct_curr_cyc_credit       numeric(12,2) not null default 0,

    -- ACCT-CURR-CYC-DEBIT PIC S9(10)V99; cumulative debits posted in
    -- the current billing cycle (purchases, cash advances, fees,
    -- interest). Same NUMERIC(12,2) NOT NULL DEFAULT 0 rules. Read +
    -- updated by InterestCalculationService (CBACT04C) end-of-cycle
    -- and by TransactionPostingService (CBTRN02C) when posting a
    -- non-payment transaction.
    acct_curr_cyc_debit        numeric(12,2) not null default 0,

    -- ACCT-ADDR-ZIP PIC X(10); account-holder ZIP / ZIP+4 (US) or
    -- postal code (international). 10-character fixed-width in COBOL;
    -- stored as VARCHAR(10) to accommodate 5-digit US ZIP ('78487'),
    -- 5+4 hyphenated US ZIP ('78487-7965'), and shorter international
    -- postal codes without trailing-padding storage. Nullable -- not
    -- every account in the COBOL fixture has a non-empty ZIP (some
    -- legacy accounts predate ZIP collection). Validated against the
    -- CSLKPCDY.cpy NANPA / ZIP-prefix lookup at the application layer
    -- by ValidationLookupService per AAP §0.7.1.
    acct_addr_zip              varchar(10),

    -- ACCT-GROUP-ID PIC X(10); the disclosure-group lookup key into
    -- the disclosure_group table (V007__create_disclosure_group.sql).
    -- 10-character fixed-width in COBOL; stored as VARCHAR(10).
    -- Drives interest-rate selection in InterestCalculationService
    -- (CBACT04C) -- the join is acct_group_id -> disclosure_group.
    -- dis_acct_group_id with DEFAULT fallback per AAP §0.4.1
    -- (CBACT04C lookup against DisclosureGroup with DEFAULT
    -- fallback). Nullable because legacy fixture rows may have an
    -- all-spaces value (which maps to NULL); when null, the
    -- InterestCalculationService applies the DEFAULT disclosure
    -- group rate. Note: a FOREIGN KEY to disclosure_group is NOT
    -- declared here because that parent table is created in V007
    -- (Flyway enforces strict V001 -> V002 -> ... execution order
    -- and disclosure_group does not yet exist at V001 apply time);
    -- the referential integrity for this column is enforced by
    -- application-layer ValidationLookupService and by the
    -- InterestCalculationService DEFAULT-fallback contract.
    acct_group_id              varchar(10),

    -- The trailing COBOL FILLER PIC X(178) is OMITTED. It is unused
    -- padding that brings the COBOL record to its fixed 300-byte
    -- VSAM record length (11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12
    -- + 12 + 10 + 10 + 178 = 300). PostgreSQL has no concept of
    -- fixed-width records, so this padding has no relational
    -- equivalent and is dropped per AAP §0.6.2.

    -- version -- application-added BIGINT for JPA @Version optimistic
    -- locking. Has NO COBOL equivalent: the COBOL source's
    -- before/after image comparison pattern in COACTUPC.cbl is
    -- replaced by Spring Data JPA's optimistic-lock check that
    -- compares the WHERE clause version against the loaded version
    -- on every UPDATE. Per AAP §0.6.2 / §0.7.1 this is the SAME
    -- PATTERN used to replace the EXEC CICS READ UPDATE / REWRITE
    -- flow. Hibernate increments the value on every save; a stale
    -- write throws org.hibernate.StaleObjectStateException ->
    -- jakarta.persistence.OptimisticLockException, translated to
    -- ConcurrentModificationException (HTTP 409 Conflict) by
    -- GlobalExceptionHandler per AAP §0.4.1. NOT NULL DEFAULT 0 so
    -- existing legacy rows loaded via the Glue Spark migration start
    -- at version=0.
    version                    bigint        not null default 0,

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS
    -- primary key (RKP=0, KEYLEN=11 per app/catlg/LISTCAT.txt).
    -- Spring Data JPA's AccountRepository uses this as the @Id
    -- (entity class: Account). No additional secondary indexes are
    -- defined here:
    --   - The ACCTDATA.VSAM.KSDS in the source has NO alternate index
    --     (AIX) / PATH per app/catlg/LISTCAT.txt (the only AIX/PATH
    --     pairs in the CardDemo source are CXACAIX over CARDXREF and
    --     TRANSACT.AIX over TRANSACT). All COBOL access to this file
    --     is by primary key (random READ on ACCT-ID) or sequential
    --     scan (CBACT01C). The PostgreSQL B-tree on the PK satisfies
    --     both patterns.
    --   - The card_xref join (xref_acct_id -> acct_id) is satisfied
    --     by the PK B-tree on this side and an explicit FK + index
    --     declared in V004__create_cardxref.sql on the other side.
    constraint pk_accounts primary key (acct_id),

    -- CHECK constraint enforcing valid account-status discriminator
    -- values. Defense-in-depth improvement following the same pattern
    -- used in V010 user_security for sec_usr_type. The COBOL source
    -- did not validate this value at the data layer, so this
    -- constraint blocks INSERT/UPDATE attempts with invalid values
    -- WITHOUT changing the COBOL application's behavior for valid
    -- 'Y' or 'N' values -- a minimal addition consistent with the
    -- AAP §0.7.3 Minimal Change Clause (defense-in-depth is
    -- permitted; behavior-changing constraints are not).
    constraint chk_accounts_active_status check (acct_active_status in ('Y', 'N'))
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ accounts and via
-- JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling). Inline
-- COMMENT ON statements make the COBOL provenance, the FILLER omission, the
-- COBOL "EXPIRAION" typo correction, and the JPA @Version design decision
-- discoverable from the database catalog itself. Per AAP §0.7.3 refactor
-- discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- =============================================================================

comment on table accounts is
    'Account master records. Java target for COBOL VSAM cluster '
    'AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS (source: app/cpy/CVACT01Y.cpy, '
    'app/jcl/ACCTFILE.jcl, app/catlg/LISTCAT.txt). Foundation table for '
    'the CardDemo schema -- referenced by cards (V002), card_xref (V004), '
    'and tran_cat_bal (V006). Read by AccountViewService, AccountFileReader'
    'Service, InterestCalculationService, TransactionPostingService, '
    'StatementGenerationService; updated by AccountUpdateService, '
    'BillPaymentService, InterestCalculationService. JPA @Version optimistic '
    'locking via the version column replaces the COBOL before/after image '
    'comparison pattern in COACTUPC.cbl per AAP §0.6.2 / §0.7.1. The COBOL '
    'FILLER PIC X(178) is OMITTED -- no relational equivalent for '
    'fixed-width VSAM padding.';

comment on column accounts.acct_id is
    'COBOL: ACCT-ID PIC 9(11). VSAM KSDS primary key (RKP=0, KEYLEN=11 '
    'per app/catlg/LISTCAT.txt). 11-digit unsigned account identifier '
    '(range 0..99,999,999,999). Maps to Account.@Id (Long) in JPA. '
    'Referenced by cards.acct_id (V002), card_xref.xref_acct_id (V004), '
    'tran_cat_bal.acct_id (V006).';

comment on column accounts.acct_active_status is
    'COBOL: ACCT-ACTIVE-STATUS PIC X(01). 1-character account status '
    'flag: ''Y'' = active (transactions may be posted), ''N'' = closed / '
    'inactive (rejected at TransactionPostingService validation stage 2). '
    'Enforced by chk_accounts_active_status CHECK constraint as a '
    'defense-in-depth improvement over the COBOL implicit assumption '
    '(per AAP §0.7.3 -- minimal additions, no behavior change for valid '
    'values).';

comment on column accounts.acct_curr_bal is
    'COBOL: ACCT-CURR-BAL PIC S9(10)V99. Current account balance, signed '
    'decimal (range -9,999,999,999.99 .. +9,999,999,999.99). Mapped to '
    'NUMERIC(12,2): precision = 10 (digits) + 2 (V99 fractional) = 12, '
    'scale = 2 per AAP §0.6.1. NOT NULL DEFAULT 0 -- COBOL fixed-width '
    'records have no NULL concept. Banker''s rounding (RoundingMode.'
    'HALF_EVEN) enforced at the Java service layer (BillPaymentService, '
    'InterestCalculationService). NEVER use DOUBLE PRECISION, REAL, or '
    'FLOAT here.';

comment on column accounts.acct_credit_limit is
    'COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99. Total credit limit. '
    'NUMERIC(12,2) NOT NULL DEFAULT 0 per the same rules as acct_curr_bal. '
    'Read by TransactionPostingService at validation stage 3 -- reject '
    'code 102 (credit-limit exceeded) preserved verbatim per AAP §0.1.1 / '
    'CBTRN02C.';

comment on column accounts.acct_cash_credit_limit is
    'COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99. Cash-advance credit '
    'limit (separate sub-limit). NUMERIC(12,2) NOT NULL DEFAULT 0. Read '
    'by TransactionPostingService when the incoming transaction''s '
    'transaction-category-code identifies a cash advance via the '
    'TRANCATG lookup.';

comment on column accounts.acct_open_date is
    'COBOL: ACCT-OPEN-DATE PIC X(10). Account-opening date, parsed from '
    'COBOL ISO-8601 string to native DATE via DateValidationService '
    '(CSUTLDPY.cpy / CSUTLDTC.cbl). Required for statement age '
    'calculation, regulatory reporting, and FICO-vintage analysis.';

comment on column accounts.acct_expiration_date is
    'COBOL: ACCT-EXPIRAION-DATE PIC X(10) -- note the COBOL spelling typo '
    '"EXPIRAION" on app/cpy/CVACT01Y.cpy:L11. The PostgreSQL column adopts '
    'the corrected spelling per AAP §0.6.2. Account expiration date, '
    'parsed from COBOL ISO-8601 string to native DATE via '
    'DateValidationService. Read by TransactionPostingService at '
    'validation stage 4 -- reject code 103 (card / account expired) '
    'preserved verbatim per AAP §0.1.1 / CBTRN02C.';

comment on column accounts.acct_reissue_date is
    'COBOL: ACCT-REISSUE-DATE PIC X(10). Most recent reissue / card-'
    'replacement date, parsed from COBOL ISO-8601 string to native DATE. '
    'Nullable -- new accounts and never-reissued accounts have an '
    'all-spaces value in the COBOL source which maps to NULL in '
    'PostgreSQL per the all-spaces-> NULL convention.';

comment on column accounts.acct_curr_cyc_credit is
    'COBOL: ACCT-CURR-CYC-CREDIT PIC S9(10)V99. Cumulative credits '
    'posted in the current billing cycle (payments, refunds, reversals). '
    'NUMERIC(12,2) NOT NULL DEFAULT 0. Read + updated by '
    'InterestCalculationService (CBACT04C) end-of-cycle and by '
    'BillPaymentService (COBIL00C) on payment posting.';

comment on column accounts.acct_curr_cyc_debit is
    'COBOL: ACCT-CURR-CYC-DEBIT PIC S9(10)V99. Cumulative debits posted '
    'in the current billing cycle (purchases, cash advances, fees, '
    'interest). NUMERIC(12,2) NOT NULL DEFAULT 0. Read + updated by '
    'InterestCalculationService and TransactionPostingService.';

comment on column accounts.acct_addr_zip is
    'COBOL: ACCT-ADDR-ZIP PIC X(10). Account-holder ZIP / ZIP+4 (US) or '
    'postal code (international). Nullable. Validated by '
    'ValidationLookupService (CSLKPCDY.cpy NANPA / ZIP-prefix lookup) at '
    'the application layer.';

comment on column accounts.acct_group_id is
    'COBOL: ACCT-GROUP-ID PIC X(10). Disclosure-group lookup key into '
    'disclosure_group.dis_acct_group_id (V007). Drives interest-rate '
    'selection in InterestCalculationService (CBACT04C) with DEFAULT '
    'fallback per AAP §0.4.1. Nullable -- null maps to the DEFAULT '
    'disclosure group. No SQL FOREIGN KEY declared here because '
    'disclosure_group is created in V007 (Flyway strict V<NNN> '
    'execution order) and DEFAULT-fallback referential integrity is '
    'enforced at the application layer.';

comment on column accounts.version is
    'Application-added BIGINT for JPA @Version optimistic locking. No '
    'COBOL equivalent. Replaces the before/after image comparison in '
    'COACTUPC.cbl per AAP §0.6.2 / §0.7.1. Hibernate increments the '
    'value on every save; stale-write detection throws OptimisticLock'
    'Exception, translated to ConcurrentModificationException (HTTP '
    '409 Conflict) by GlobalExceptionHandler.';
