-- =============================================================================
-- Flyway Migration: V006__create_tcatbal.sql
-- Purpose:    Create the tran_cat_bal table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS. This table is the central
--             data structure for end-of-day interest calculation: it holds
--             per-account-per-transaction-type-per-transaction-category
--             running balances that the InterestCalculationService
--             (CBACT04C) iterates over to compute monthly interest, and
--             that the TransactionPostingService (CBTRN02C) updates as
--             each daily transaction is posted. The composite key
--             (trancat_acct_id, trancat_type_cd, trancat_cd) ensures that
--             every (account, type, category) tuple has exactly one row.
--
--             Consumers (Java target services that READ from / WRITE to
--             the tran_cat_bal table):
--               - InterestCalculationService (COBOL CBACT04C) -- end-of-
--                 cycle batch job that scans every row in tran_cat_bal,
--                 joins to disclosure_group (V007) via the composite
--                 (acct_group_id from accounts, trancat_type_cd,
--                 trancat_cd) lookup with DEFAULT fallback, computes
--                 interest using the COBOL formula
--                 (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with BigDecimal
--                 scale 2 and RoundingMode.HALF_EVEN per AAP §0.6.1,
--                 then writes the interest amount into a new TRANSACTION
--                 row (V005) AND increments the running balance here.
--                 The divisor 1200 is preserved as BigDecimal.valueOf(1200)
--                 -- NOT algebraically simplified -- to preserve exact
--                 parity with the COBOL COMPUTE statement.
--               - TransactionPostingService  (COBOL CBTRN02C) -- the daily
--                 transaction-posting batch job that, after the 4-stage
--                 validation cascade succeeds (XREF / account / credit
--                 limit / card expiration -- reject codes 100-109
--                 preserved verbatim per AAP §0.1.1), accumulates the
--                 posted TRAN-AMT into the matching (account, type,
--                 category) row in this table; if no row exists for the
--                 tuple the service INSERTs a new row with the initial
--                 amount, otherwise it UPDATEs the running balance.
--               - TransactionAddService      (COBOL COTRN02C) -- online
--                 transaction creation; on each new transaction the
--                 service updates the running balance under the same
--                 (account, type, category) tuple within a
--                 @Transactional boundary alongside the transactions
--                 INSERT and accounts UPDATE.
--               - TransactionReportService   (COBOL CBTRN03C) -- joins to
--                 produce a per-category breakdown on monthly transaction
--                 reports (running balances by category appear under the
--                 category description from tran_category V009).
--               - StatementGenerationService (COBOL CBSTM03A/03B) -- joins
--                 to break down the cycle balance by category on the
--                 generated monthly statement (Purchase / Payment /
--                 Credit / etc. sub-totals).
--               - TransactionCategoryBalanceRepository (Spring Data JPA) --
--                 exposes findByCompositeKey(acctId, typeCd, catCd) via
--                 the @EmbeddedId on entity TransactionCategoryBalance;
--                 injected into every service above.
--
-- Source:     app/cpy/CVTRA01Y.cpy   (TRAN-CAT-BAL-RECORD layout, RECLN 50,
--                                     1 composite-key group + 1 balance
--                                     field + 22-byte trailing FILLER;
--                                     verified L4-L10. The composite
--                                     COBOL group TRAN-CAT-KEY at L5
--                                     contains three nested elementary
--                                     items: TRANCAT-ACCT-ID PIC 9(11)
--                                     at L6, TRANCAT-TYPE-CD PIC X(02)
--                                     at L7, TRANCAT-CD PIC 9(04) at L8.
--                                     The TRAN-CAT-BAL elementary item
--                                     at L9 is PIC S9(09)V99 -- 9 digits
--                                     + 2 implied decimal + sign = 11
--                                     digits of precision. The trailing
--                                     FILLER PIC X(22) at L10 brings the
--                                     record to its fixed 50-byte length:
--                                     11 + 2 + 4 + 11 + 22 = 50.)
--             app/jcl/TCATBALF.jcl   (IDCAMS DEFINE CLUSTER L36-L49:
--                                     KEYS(17 0), RECORDSIZE(50 50),
--                                     CYLINDERS(1 5), SHAREOPTIONS(2 3),
--                                     ERASE, INDEXED; plus REPRO step
--                                     L54-L62 loading TCATBALF.PS -> KSDS.
--                                     KEYS(17 0) declares a 17-byte
--                                     primary key at RKP=0 -- the
--                                     composite 11+2+4 = 17.)
--             app/catlg/LISTCAT.txt  (verifies TCATBALF.VSAM.KSDS cluster:
--                                     KEYLEN=17, RKP=0, MAXLRECL=50,
--                                     AVGLRECL=50, INDEXED, SHROPTNS(2,3),
--                                     CISIZE=18432, BUFSPACE=37376,
--                                     REC-TOTAL=100, REC-INSERTED=49,
--                                     REC-UPDATED=212 -- the per-row
--                                     update rate reflects the heavy
--                                     read-modify-write pattern of daily
--                                     transaction posting and end-of-day
--                                     interest calculation.)
--
-- AAP Refs:   §0.4.1 (V006 tran_cat_bal; one-to-one mapping of
--                     TCATBALF.KSDS to the tran_cat_bal relational table;
--                     no FK constraint to tran_category here because
--                     V009 is created after V006 and Flyway enforces
--                     strict V<NNN> execution order),
--             §0.6.1 (COBOL decimal precision and BigDecimal mapping --
--                     TRAN-CAT-BAL PIC S9(09)V99 maps to NUMERIC(11,2)
--                     where precision = 9 digits + 2 V99 fractional = 11
--                     and scale = 2; banker's rounding -- RoundingMode.
--                     HALF_EVEN -- is enforced at the Java service layer,
--                     NOT in SQL DDL, because PostgreSQL NUMERIC is exact
--                     arithmetic and rounding only applies at explicit
--                     scale changes; ON SIZE ERROR semantics are
--                     replicated by OnSizeErrorException thrown when a
--                     computed value would exceed the NUMERIC(11,2)
--                     range),
--             §0.6.2 (VSAM-to-RDS migration strategy -- KEYS(17 0) maps
--                     to a 3-column composite PRIMARY KEY on
--                     (trancat_acct_id, trancat_type_cd, trancat_cd);
--                     the composite ordering preserves the COBOL
--                     hierarchical-key semantic of TRAN-CAT-KEY group
--                     at CVTRA01Y.cpy:L5 -- account first, then type,
--                     then category. JPA mapping uses @EmbeddedId on
--                     the TransactionCategoryBalance entity in
--                     src/main/java/com/awsm2/carddemo/domain/.
--                     TCATBALF.VSAM.KSDS has NO alternate index (AIX) /
--                     PATH per app/catlg/LISTCAT.txt, so no secondary
--                     index is required -- the composite PK B-tree
--                     satisfies both the primary lookup and the
--                     trancat_acct_id-leading-prefix scan used by
--                     InterestCalculationService end-of-cycle iteration),
--             §0.6.6 (PCI-DSS compliance -- encryption at rest delegated
--                     to RDS via the customer KMS CMK, configured in
--                     infrastructure/terraform/rds.tf; no column-level
--                     pgcrypto applied at the schema layer per the AAP
--                     §0.6.6 architectural decision; tran_cat_bal does
--                     not contain PAN or other PCI-DSS-defined sensitive
--                     authentication data -- it holds aggregated
--                     account balance buckets indexed by an internal
--                     account identifier),
--             §0.7.1 (refactor discipline -- (a) BigDecimal +
--                     RoundingMode.HALF_EVEN for the monetary tran_cat_bal
--                     column; (b) NOT NULL DEFAULT 0 on tran_cat_bal
--                     mirrors COBOL's fixed-width every-byte-always-
--                     present semantics, so newly-inserted rows start
--                     at a zero running balance; (c) no JPA @Version
--                     optimistic-locking column on this table -- the AAP
--                     §0.6.2 specifies optimistic locking only for
--                     accounts (V001) and cards (V002); concurrent
--                     updates to tran_cat_bal are serialized by the
--                     @Transactional service boundary in
--                     InterestCalculationService and
--                     TransactionPostingService, which is sufficient
--                     because the running-balance accumulation is
--                     monotonic and the conflict window is short),
--             §0.7.3 (Minimal Change Clause -- preserve COBOL semantics
--                     exactly; the COBOL FILLER PIC X(22) is OMITTED
--                     because PostgreSQL has no concept of fixed-width
--                     records and the padding has no relational
--                     equivalent; no CHECK constraints are added on
--                     trancat_type_cd or trancat_cd because the COBOL
--                     source did not validate these values at the data
--                     layer -- the application layer enforces the
--                     tran_type / tran_category lookup at write time).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
--             in app/jcl/TCATBALF.jcl:L36-L49. The IDCAMS REPRO step
--             (app/jcl/TCATBALF.jcl:L54-L62) that loaded TCATBALF.PS into
--             the KSDS is NOT replaced by a Flyway seed migration --
--             tran_cat_bal is operational data, not reference data; rows
--             are inserted at runtime by TransactionPostingService and
--             TransactionAddService as each new (account, type, category)
--             tuple appears, NOT preloaded from a fixture. For local
--             development and integration testing, sample rows are
--             provided by Testcontainers fixtures and golden-output
--             diff harness data (src/test/resources/golden/).
-- =============================================================================

-- =============================================================================
-- COBOL TRAN-CAT-BAL-RECORD layout (CVTRA01Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field            PIC clause       PostgreSQL column      Type
--   ---------------------- ---------------- ---------------------- ---------------
--   TRAN-CAT-KEY (group)   17-byte group    -- (decomposed into 3 columns below)
--    + TRANCAT-ACCT-ID     PIC 9(11)        trancat_acct_id        BIGINT       NN
--    + TRANCAT-TYPE-CD     PIC X(02)        trancat_type_cd        CHAR(2)      NN
--    + TRANCAT-CD          PIC 9(04)        trancat_cd             NUMERIC(4)   NN
--   TRAN-CAT-BAL           PIC S9(09)V99    tran_cat_bal           NUMERIC(11,2) NN
--   FILLER                 PIC X(22)        OMITTED                -- (padding)
--
-- Total COBOL record length: 11 + 2 + 4 + 11 + 22 = 50 bytes (per LISTCAT
-- MAXLRECL=50 / AVGLRECL=50 / TCATBALF.jcl RECORDSIZE(50 50)).
--
-- Total PostgreSQL columns: 4 (the 3 composite-key columns from the
-- TRAN-CAT-KEY group, plus the TRAN-CAT-BAL balance column).
--
-- VSAM composite key (RKP=0, KEYLEN=17 per LISTCAT) maps to a 3-column
-- composite PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd).
-- The COBOL KEYS(17 0) declares the 17-byte key starting at offset 0:
--   bytes  0-10  -> TRANCAT-ACCT-ID  (11 bytes, PIC 9(11))
--   bytes 11-12  -> TRANCAT-TYPE-CD  ( 2 bytes, PIC X(02))
--   bytes 13-16  -> TRANCAT-CD       ( 4 bytes, PIC 9(04))
-- The PostgreSQL composite PK preserves the COBOL byte-order: leftmost
-- column is the most-significant (account), rightmost is least-significant
-- (category). This ordering matters because:
--   (a) COBOL CICS STARTBR/READNEXT browse operations scan by key prefix
--       (typically account-first), and the matching Java JPA query
--       findByTrancatAcctId(Long acctId) is an index-prefix scan on the
--       PK B-tree -- which works ONLY when trancat_acct_id is the leading
--       column of the composite PK.
--   (b) The COBOL TRAN-CAT-KEY group at CVTRA01Y.cpy:L5 declares the
--       fields in this order; preserving the order in the PostgreSQL PK
--       matches the COBOL @Embeddable / @EmbeddedId mapping on the
--       TransactionCategoryBalance entity exactly, with no field
--       reordering required.
--
-- ***   DATA-TYPE COMPATIBILITY NOTE FOR THE FOREIGN KEY:   ***
-- trancat_acct_id is declared BIGINT (NOT NUMERIC(11)) so that the
-- FOREIGN KEY constraint to accounts(acct_id) -- which is BIGINT per
-- V001__create_account.sql:L210 -- is valid. PostgreSQL FK constraints
-- require the referencing column type to be binary-coercible to the
-- referenced column type, and NUMERIC / BIGINT are NOT directly
-- compatible (the foreign-key constraint would fail with
-- "foreign key constraint cannot be implemented: incompatible types").
-- BIGINT (signed 8-byte integer, range -2^63..2^63-1) fully contains
-- the COBOL PIC 9(11) unsigned range (0..99,999,999,999), so the COBOL
-- value space is preserved without truncation.
--
-- The FILLER PIC X(22) is OMITTED. It is the trailing padding that
-- brings the COBOL record to its fixed 50-byte VSAM record length
-- (11 + 2 + 4 + 11 + 22 = 50). PostgreSQL has no concept of fixed-width
-- records, so this padding has no relational equivalent and is dropped
-- per AAP §0.6.2 (consistent with the FILLER-omission pattern used in
-- V001 accounts, V002 cards, V003 customers, V005 transactions, etc.).
--
-- Storage-tier attributes from TCATBALF.jcl that have NO PostgreSQL
-- equivalent (PostgreSQL handles storage layout automatically and the
-- RDS Multi-AZ topology supersedes z/OS VSAM characteristics):
--   - CYLINDERS(1 5)         : z/OS physical allocation; not applicable
--   - SHAREOPTIONS(2 3)      : VSAM concurrency; replaced by PostgreSQL
--                              MVCC + JPA @Transactional isolation
--                              (READ_COMMITTED default; stronger as
--                              required by the InterestCalculationService
--                              and TransactionPostingService service
--                              boundaries)
--   - ERASE                  : VSAM data zeroing on DELETE; replaced by
--                              standard SQL DELETE (PostgreSQL's MVCC
--                              tombstone is reclaimed by autovacuum)
--   - INDEXED                : KSDS = indexed; PostgreSQL B-tree on the
--                              composite PK provides the same random-
--                              access semantic for primary-key reads,
--                              and a leading-prefix scan satisfies the
--                              COBOL CICS STARTBR/READNEXT browse
--                              pattern used by end-of-cycle interest
--                              calculation
--   - CISIZE=18432           : VSAM control-interval size; PostgreSQL
--                              uses 8 KB pages by default and tuning is
--                              a parameter-group concern, not a DDL one
--   - BUFSPACE=37376         : VSAM buffer pool; PostgreSQL shared
--                              buffers are configured at the cluster /
--                              parameter-group level
-- =============================================================================

create table tran_cat_bal (
    -- TRANCAT-ACCT-ID PIC 9(11); first 11 bytes of the 17-byte VSAM
    -- composite key (RKP=0, KEYLEN=17 per app/catlg/LISTCAT.txt). The
    -- account identifier that owns this category balance row -- a
    -- foreign key into the accounts table (V001). Declared BIGINT (NOT
    -- NUMERIC(11)) to match the BIGINT type of accounts.acct_id in
    -- V001__create_account.sql; PostgreSQL FK constraints require
    -- binary-coercible types and NUMERIC / BIGINT are NOT directly
    -- compatible. BIGINT (signed 8-byte integer, range -2^63..2^63-1)
    -- fully contains the COBOL PIC 9(11) unsigned range
    -- (0..99,999,999,999). Maps to TransactionCategoryBalance.@EmbeddedId
    -- composite-key field trancatAcctId (Long) in JPA.
    --
    -- This is the LEADING column of the composite primary key, which
    -- means the underlying B-tree index supports:
    --   (a) Random lookup by full key (account, type, category) for
    --       per-row read-modify-write during transaction posting;
    --   (b) Leading-prefix scan by (account) alone for the
    --       InterestCalculationService end-of-cycle pattern that
    --       processes every (type, category) bucket of a single
    --       account in one logical unit of work;
    --   (c) Leading-prefix scan by (account, type) for any future
    --       per-account-per-type aggregation use case.
    trancat_acct_id            bigint        not null,

    -- TRANCAT-TYPE-CD PIC X(02); bytes 11-12 of the 17-byte VSAM
    -- composite key. The 2-character alphanumeric transaction-type code
    -- that classifies the category bucket (e.g., '01' = Purchase,
    -- '02' = Payment, '03' = Credit, '04' = Authorization, '05' = Refund,
    -- '06' = Reversal, '07' = Adjustment). Leading zeros are SIGNIFICANT
    -- and MUST be preserved -- the code is always exactly 2 characters,
    -- always quoted as a string. CHAR(2) is space-padded on read by
    -- PostgreSQL, but since every value is exactly 2 characters there is
    -- no padding to trim. CHAR(2) matches the type used by V008
    -- tran_type.tran_type and V009 tran_category.tran_type_cd, so a
    -- future application-layer JOIN on (trancat_type_cd, trancat_cd) ->
    -- tran_category will use the existing PostgreSQL B-tree index on
    -- tran_category's composite PK without an implicit type cast.
    --
    -- No SQL FOREIGN KEY to tran_type is declared here. Per AAP §0.4.1
    -- the FK is intentionally omitted because tran_type is created in
    -- V008 and Flyway enforces strict V<NNN> execution order (V006 must
    -- run before V008, so the parent table does not yet exist at V006
    -- apply time). Referential integrity for the (type, category) pair
    -- is enforced by TransactionPostingService and TransactionAddService
    -- at write time via the tran_category lookup (CBTRN02C 4-stage
    -- validation cascade per AAP §0.1.1).
    trancat_type_cd            char(2)       not null,

    -- TRANCAT-CD PIC 9(04); bytes 13-16 of the 17-byte VSAM composite
    -- key. The 4-digit unsigned transaction-category code (range
    -- 0001..9999) that further classifies the bucket within the given
    -- transaction-type. The canonical 18 categories defined in
    -- app/data/ASCII/trancatg.txt (loaded by V014) include 5 Purchase
    -- categories, 3 Payment categories, 3 Credit categories, 3
    -- Authorization categories, 1 Refund category, 2 Reversal
    -- categories, and 1 Adjustment category.
    --
    -- NUMERIC(4) precisely captures the COBOL PIC 9(04) semantic --
    -- 4 digits, no decimal, no sign -- matching the type used by V009
    -- tran_category.tran_cat_cd. As with trancat_type_cd, no FK is
    -- declared here because V009 tran_category is created after V006;
    -- the application enforces the lookup at write time.
    --
    -- This is the TRAILING column of the composite primary key; it
    -- provides the finest-grained partitioning of an account's balance
    -- across the (type, category) Cartesian product.
    trancat_cd                 numeric(4)    not null,

    -- TRAN-CAT-BAL PIC S9(09)V99; the per-(account, type, category)
    -- running balance in account currency. Signed 9-digit integer +
    -- 2 implied decimal places. Maps to NUMERIC(11,2): precision =
    -- 9 (digits) + 2 (V99 fractional) = 11, scale = 2 -- per AAP
    -- §0.6.1 (COBOL decimal precision + BigDecimal mapping). NOT NULL
    -- DEFAULT 0 mirrors COBOL's fixed-width every-byte-always-present
    -- semantics: when a TransactionPostingService INSERT creates a new
    -- (account, type, category) tuple for the first time, the row
    -- starts at a zero balance and is immediately incremented by the
    -- posted transaction amount within the same @Transactional
    -- boundary.
    --
    -- *** MONETARY FIELD -- BIGDECIMAL ARITHMETIC RULES ***
    -- Banker's rounding (RoundingMode.HALF_EVEN) is enforced at the
    -- Java service layer (InterestCalculationService,
    -- TransactionPostingService, TransactionAddService) per AAP §0.6.1.
    -- NEVER use DOUBLE PRECISION, REAL, or FLOAT here -- those types
    -- lose precision on decimal arithmetic and break parity with COBOL.
    -- NUMERIC is arbitrary-precision exact arithmetic; rounding only
    -- occurs at explicit scale changes (set_scale / @DecimalMax /
    -- multiply + divide chains).
    --
    -- The InterestCalculationService computes interest on this balance
    -- via the COBOL formula
    --     interest = (tran_cat_bal * dis_int_rate) / 1200
    -- preserved verbatim per AAP §0.6.1 -- the divisor 1200 is kept
    -- as BigDecimal.valueOf(1200) and is NOT algebraically simplified
    -- (e.g., NOT pre-computed as rate/1200 in a constant). The Java
    -- implementation:
    --     balance.multiply(rate)
    --            .divide(BigDecimal.valueOf(1200),
    --                    2, RoundingMode.HALF_EVEN)
    -- The result is the monthly interest charge in account currency,
    -- which is written as a new TRANSACTION row (V005) and also
    -- accumulated back into the running balance for the next cycle.
    --
    -- The TRAN-AMT field in the transactions table (V005) is also
    -- PIC S9(09)V99 / NUMERIC(11,2), so balances accumulated here and
    -- amounts inserted into transactions share the same precision and
    -- can be added without precision loss or implicit scale promotion.
    --
    -- ON SIZE ERROR semantics: if a computed running balance would
    -- exceed the NUMERIC(11,2) range (|value| > 999,999,999.99) the
    -- Java InterestCalculationService / TransactionPostingService
    -- throws OnSizeErrorException per AAP §0.6.1 (translated to HTTP
    -- 422 Unprocessable Entity by GlobalExceptionHandler for online
    -- flows, or recorded as a reject for batch flows).
    tran_cat_bal               numeric(11,2) not null default 0,

    -- The trailing COBOL FILLER PIC X(22) is OMITTED. It is unused
    -- padding that brings the COBOL record to its fixed 50-byte
    -- VSAM record length (11 + 2 + 4 + 11 + 22 = 50). PostgreSQL
    -- has no concept of fixed-width records, so this padding has no
    -- relational equivalent and is dropped per AAP §0.6.2 / §0.7.3
    -- (consistent with the FILLER-omission pattern used in V001
    -- accounts, V002 cards, V003 customers, V005 transactions, etc.).

    -- Composite primary key constraint -- one-to-one with the COBOL
    -- VSAM KSDS primary key (RKP=0, KEYLEN=17 per app/catlg/LISTCAT.txt:
    -- 11-byte account + 2-byte type + 4-byte category = 17 bytes). The
    -- ordering of columns matters:
    --   (a) (trancat_acct_id, trancat_type_cd, trancat_cd) preserves
    --       the byte-order of the COBOL TRAN-CAT-KEY group at
    --       CVTRA01Y.cpy:L5 -- account first, then type, then category.
    --   (b) The leading column (trancat_acct_id) makes per-account
    --       prefix scans efficient -- this is the key access pattern
    --       for InterestCalculationService (CBACT04C) end-of-cycle
    --       iteration, which processes every (type, category) bucket
    --       of a single account in one logical unit of work.
    --   (c) JPA mapping uses @EmbeddedId on the
    --       TransactionCategoryBalance entity with an @Embeddable
    --       TransactionCategoryBalanceKey holding the three fields in
    --       the same order. The B-tree index on this composite PK
    --       satisfies BOTH the random full-key lookup AND the
    --       leading-prefix scan without a separate secondary index.
    --
    -- No additional secondary indexes are declared on this table:
    --   - TCATBALF.VSAM.KSDS in the source has NO alternate index (AIX)
    --     / PATH per app/catlg/LISTCAT.txt (the only AIX/PATH pairs in
    --     the CardDemo source are CXACAIX over CARDXREF and
    --     TRANSACT.AIX over TRANSACT). All COBOL access to this file is
    --     by primary key (random READ on the 17-byte composite) or
    --     leading-prefix browse (STARTBR with a generic key, READNEXT
    --     until the prefix changes). The PostgreSQL composite PK B-tree
    --     satisfies both patterns.
    --   - A separate index on trancat_acct_id alone is NOT needed
    --     because the PK's leading column already provides equivalent
    --     indexability for the per-account prefix-scan pattern.
    constraint pk_tran_cat_bal primary key (trancat_acct_id, trancat_type_cd, trancat_cd),

    -- Foreign key constraint -- trancat_acct_id references accounts
    -- (acct_id) per AAP §0.6.2. ON DELETE NO ACTION (PostgreSQL
    -- default action) is the explicit, conservative choice: if an
    -- application attempts to DELETE an account that still has
    -- tran_cat_bal rows, PostgreSQL raises a foreign-key violation,
    -- which the GlobalExceptionHandler translates to HTTP 409 Conflict.
    -- This forces the application layer to delete the dependent
    -- tran_cat_bal rows (and other dependent rows: cards, card_xref,
    -- transactions) BEFORE deleting the account, preserving the
    -- COBOL VSAM cascade-delete semantic which required explicit
    -- programmatic deletion of each related dataset record. There is
    -- NO CASCADE here: silent cascading deletion of balance buckets
    -- would lose audit history and break the AAP §0.7.2 directive that
    -- "audit trail content -- transaction IDs, timestamps, operator
    -- codes, and audit fields -- must continue to be emitted with the
    -- same values and semantics."
    --
    -- Note: tran_cat_bal does NOT declare a FOREIGN KEY to tran_type
    -- (V008) or to tran_category (V009) because those parent tables
    -- are created AFTER V006 in the Flyway sequence. The application
    -- layer enforces the (trancat_type_cd, trancat_cd) lookup at write
    -- time via TransactionPostingService and TransactionAddService.
    constraint fk_tran_cat_bal_acct_id foreign key (trancat_acct_id)
        references accounts (acct_id) on delete no action
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ tran_cat_bal and
-- via JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance, the composite-key
-- mapping, the FILLER omission, the BIGINT FK-compatibility decision, and the
-- BigDecimal arithmetic rules discoverable from the database catalog itself.
-- Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- =============================================================================

comment on table tran_cat_bal is
    'Per-(account, transaction-type, transaction-category) running balance '
    'buckets. Java target for COBOL VSAM cluster '
    'AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS (source: app/cpy/CVTRA01Y.cpy, '
    'app/jcl/TCATBALF.jcl, app/catlg/LISTCAT.txt). Central data structure '
    'for end-of-day interest calculation (CBACT04C) and daily transaction '
    'posting (CBTRN02C). Read + updated by InterestCalculationService, '
    'TransactionPostingService, TransactionAddService; read by '
    'TransactionReportService and StatementGenerationService. Composite '
    'primary key (trancat_acct_id, trancat_type_cd, trancat_cd) maps to '
    'the 17-byte COBOL VSAM KEYS(17 0) composite (account 11 + type 2 + '
    'category 4 = 17 bytes) and to the @EmbeddedId on the '
    'TransactionCategoryBalance JPA entity. The COBOL FILLER PIC X(22) is '
    'OMITTED -- no relational equivalent for fixed-width VSAM padding. '
    'No JPA @Version on this table -- concurrent updates are serialized '
    'by the @Transactional service boundary per AAP §0.6.2 / §0.7.1.';

comment on column tran_cat_bal.trancat_acct_id is
    'COBOL: TRANCAT-ACCT-ID PIC 9(11). First 11 bytes of the 17-byte VSAM '
    'composite key (RKP=0, KEYLEN=17 per app/catlg/LISTCAT.txt). The '
    'account identifier that owns this category-balance row -- foreign key '
    'into accounts(acct_id) (V001) with ON DELETE NO ACTION. Stored as '
    'BIGINT (signed 8-byte integer) to match accounts.acct_id; NUMERIC(11) '
    'cannot be used here because PostgreSQL FK constraints require type '
    'compatibility and NUMERIC / BIGINT are not binary-coercible. BIGINT '
    'fully contains the COBOL PIC 9(11) range (0..99,999,999,999). Maps '
    'to TransactionCategoryBalanceKey.trancatAcctId (Long) in JPA. Leading '
    'column of the composite PK -- enables per-account prefix scans used '
    'by InterestCalculationService (CBACT04C) end-of-cycle iteration.';

comment on column tran_cat_bal.trancat_type_cd is
    'COBOL: TRANCAT-TYPE-CD PIC X(02). Bytes 11-12 of the 17-byte VSAM '
    'composite key. 2-character alphanumeric transaction-type code '
    '(''01''=Purchase, ''02''=Payment, ''03''=Credit, ''04''=Authorization, '
    '''05''=Refund, ''06''=Reversal, ''07''=Adjustment). Leading zeros are '
    'SIGNIFICANT -- the code is always exactly 2 characters. CHAR(2) '
    'matches the type used by tran_type.tran_type (V008) and '
    'tran_category.tran_type_cd (V009). No SQL FOREIGN KEY declared '
    'because tran_type (V008) is created after V006 in the Flyway '
    'sequence -- application-layer ValidationLookupService and '
    'TransactionPostingService enforce the lookup at write time.';

comment on column tran_cat_bal.trancat_cd is
    'COBOL: TRANCAT-CD PIC 9(04). Bytes 13-16 of the 17-byte VSAM '
    'composite key. 4-digit unsigned transaction-category code (range '
    '0001..9999) that classifies the balance bucket within the given '
    'transaction-type. The 18 canonical categories are loaded into '
    'tran_category (V009) from app/data/ASCII/trancatg.txt by V014. '
    'NUMERIC(4) matches the type used by tran_category.tran_cat_cd '
    '(V009). No SQL FOREIGN KEY declared because tran_category (V009) '
    'is created after V006 -- the (type, category) lookup is enforced '
    'by the application layer via TransactionPostingService.';

comment on column tran_cat_bal.tran_cat_bal is
    'COBOL: TRAN-CAT-BAL PIC S9(09)V99. Per-(account, type, category) '
    'running balance in account currency, signed decimal (range '
    '-999,999,999.99 .. +999,999,999.99). Mapped to NUMERIC(11,2): '
    'precision = 9 (digits) + 2 (V99 fractional) = 11, scale = 2 per '
    'AAP §0.6.1. NOT NULL DEFAULT 0 -- new rows start at zero balance '
    'and are immediately incremented by the posted transaction amount '
    'within the same @Transactional boundary. Banker''s rounding '
    '(RoundingMode.HALF_EVEN) enforced at the Java service layer; the '
    'COBOL interest formula (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 is '
    'preserved verbatim -- the divisor 1200 is NOT algebraically '
    'simplified. NEVER use DOUBLE PRECISION, REAL, or FLOAT here.';
