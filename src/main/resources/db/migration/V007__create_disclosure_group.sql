-- =============================================================================
-- Flyway Migration: V007__create_disclosure_group.sql
-- Purpose:    Create the disclosure_group table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS. This lookup table maps each
--             (account-group, transaction-type, transaction-category) tuple
--             to an annual interest-rate percentage (APR). It is the heart
--             of the end-of-day interest-calculation logic ported from
--             COBOL CBACT04C to Java InterestCalculationService; without
--             correct seed data, the entire interest job produces zero or
--             wrong results.
--
--             Consumers (Java target services that JOIN against this table):
--               - InterestCalculationService (COBOL CBACT04C) -- composite-key
--                 lookup per (account.acct_group_id, tran.type, tran.cat)
--                 with DEFAULT-group fallback when no account-specific row
--                 is found, and ZEROAPR-group short-circuit when the
--                 account is assigned the promotional zero-APR group
--                 (see AAP §0.6.1 "DEFAULT fallback" pattern).
--               - StatementGenerationService (COBOL CBSTM03A/CBSTM03B) --
--                 joins to embed the applied APR percentage onto generated
--                 statements alongside per-category balance totals.
--               - DisclosureGroupRepository (Spring Data JPA) -- exposes
--                 findById(DisclosureGroupId) and a derived query for the
--                 'DEFAULT' fallback lookup; the @EmbeddedId on the entity
--                 mirrors the 3-column composite PK declared here.
--
-- Source:     app/cpy/CVTRA02Y.cpy   (DIS-GROUP-RECORD layout, RECLN 50,
--                                     composite key 16 bytes:
--                                     DIS-ACCT-GROUP-ID(10)
--                                     + DIS-TRAN-TYPE-CD(2)
--                                     + DIS-TRAN-CAT-CD(4),
--                                     plus DIS-INT-RATE PIC S9(04)V99
--                                     and 28-byte trailing FILLER)
--             app/jcl/DISCGRP.jcl    (IDCAMS DEFINE CLUSTER L36-L49:
--                                     KEYS(16 0), RECORDSIZE(50 50),
--                                     SHAREOPTIONS(2 3), ERASE, INDEXED,
--                                     CYLINDERS(1 5); plus REPRO step
--                                     L54-L62 loading DISCGRP.PS -> KSDS)
--             app/catlg/LISTCAT.txt  (verifies DISCGRP.VSAM.KSDS cluster:
--                                     KEYLEN=16, RKP=0, MAXLRECL=50,
--                                     AVGLRECL=50, INDEXED, SHROPTNS(2,3),
--                                     CISIZE=18432, REC-TOTAL=51)
--
-- AAP Refs:   §0.4.1 (V007 disclosure_group; one-to-one mapping of
--                     DISCGRP.KSDS to the disclosure_group relational table),
--             §0.6.1 (BigDecimal/NUMERIC precision -- rates use scale 2 to
--                     match COBOL PIC S9(04)V99 banker's-rounded arithmetic;
--                     the DEFAULT-fallback rule is documented here but
--                     implemented in InterestCalculationService Java, not
--                     in the SQL DDL),
--             §0.6.2 (VSAM-to-RDS migration strategy; the 16-byte KSDS
--                     composite key maps to a 3-column composite PK; the
--                     PostgreSQL B-tree index on the composite PK provides
--                     leading-column lookups for the most common query
--                     pattern -- WHERE dis_acct_group_id = ?),
--             §0.7.1 (refactor discipline; preserve fixture data verbatim
--                     in V012; codes and rates are public reference data,
--                     not PII or PAN -- no encryption or masking required).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
--             in app/jcl/DISCGRP.jcl:L36-L49. The IDCAMS REPRO step
--             (app/jcl/DISCGRP.jcl:L54-L62) that loaded DISCGRP.PS into
--             the KSDS is replaced by the companion seed migration
--             V012__seed_disclosure_group.sql (51 rows from
--             app/data/ASCII/discgrp.txt).
-- =============================================================================

-- =============================================================================
-- COBOL DIS-GROUP-RECORD layout (CVTRA02Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field            PIC clause      PostgreSQL column   Type
--   ---------------------- --------------- ------------------- ----------------
--   DIS-ACCT-GROUP-ID      PIC X(10)       dis_acct_group_id   VARCHAR(10)  (PK1)
--   DIS-TRAN-TYPE-CD       PIC X(02)       dis_tran_type_cd    CHAR(2)      (PK2)
--   DIS-TRAN-CAT-CD        PIC 9(04)       dis_tran_cat_cd     INTEGER      (PK3)
--   DIS-INT-RATE           PIC S9(04)V99   dis_int_rate        NUMERIC(6,2)
--   FILLER                 PIC X(28)       OMITTED             --
--
-- Total COBOL record length: 10 + 2 + 4 + 6 + 28 = 50 bytes (per LISTCAT
-- MAXLRECL=50). Total PostgreSQL business columns: 4 (FILLER omitted).
--
-- The 16-byte VSAM composite key (KEYS(16 0) per IDCAMS DEFINE CLUSTER in
-- DISCGRP.jcl, KEYLEN=16 per LISTCAT) decomposes into three logical key
-- columns whose individual widths sum exactly to 16 bytes:
--     DIS-ACCT-GROUP-ID (10) + DIS-TRAN-TYPE-CD (2) + DIS-TRAN-CAT-CD (4) = 16
-- These three columns form the PostgreSQL composite PRIMARY KEY in the same
-- left-to-right order as the COBOL key, which preserves the lexicographic
-- ordering semantics of the VSAM KSDS for any application code that relied
-- on it (none of the COBOL programs in the CardDemo source rely on KSDS
-- traversal order for disclosure_group; lookups are always exact-match by
-- composite key, so this ordering is documentary rather than functional).
-- The Java JPA target declares an @Embeddable DisclosureGroupId with the
-- same field order, used as @EmbeddedId on the DisclosureGroup @Entity.
--
-- VSAM-to-PostgreSQL design notes (storage-tier attributes from DISCGRP.jcl
-- and LISTCAT.txt that have NO PostgreSQL equivalent; PostgreSQL handles
-- storage automatically and the RDS Multi-AZ topology supersedes z/OS VSAM
-- characteristics):
--   - KEYS(16 0)             : 16-byte composite key at byte offset 0; maps
--                              to the (dis_acct_group_id, dis_tran_type_cd,
--                              dis_tran_cat_cd) composite PRIMARY KEY whose
--                              column widths sum to 16 (10 + 2 + 4).
--   - RECORDSIZE(50 50)      : fixed-width 50-byte record (avg = max = 50);
--                              PostgreSQL has no concept of fixed-width
--                              rows -- columns are stored at natural widths
--                              and the trailing 28-byte FILLER is omitted.
--   - SHAREOPTIONS(2 3)      : "one writer + multiple readers within a
--                              z/OS image, cross-system sharing allowed"
--                              in VSAM. PostgreSQL handles concurrent
--                              access natively via MVCC (multi-version
--                              concurrency control) with READ_COMMITTED
--                              default isolation; no SQL equivalent is
--                              required. RDS Multi-AZ provides synchronous
--                              standby for HA.
--   - ERASE                  : VSAM cluster-level secure delete (writes
--                              zeros over deleted control intervals).
--                              PostgreSQL relies on WAL + tablespace
--                              encryption (KMS CMK in production per AAP
--                              §0.7.1) for at-rest protection; the
--                              tablespace itself is encrypted, so deleted
--                              row bytes are encrypted at rest.
--   - INDEXED                : KSDS = indexed; the PostgreSQL B-tree index
--                              on the composite primary key is created
--                              implicitly by the PRIMARY KEY constraint
--                              and provides O(log n) point lookups and
--                              prefix-range scans.
--   - CYLINDERS(1 5),
--     CA-RECLAIM, CISIZE,
--     FREESPACE, etc.        : z/OS physical-allocation attributes; not
--                              applicable to managed RDS PostgreSQL.
--   - REC-TOTAL=51           : 51 reference rows seeded by V012 from
--                              app/data/ASCII/discgrp.txt (17 rows for
--                              account group 'A000000000', 17 rows for
--                              'DEFAULT' fallback, 17 rows for the
--                              promotional 'ZEROAPR' group).
--
-- This table has NO foreign-key constraints. It is a leaf lookup that is
-- NEVER updated at transaction time -- it is read-mostly reference data
-- seeded once by V012 and refreshed only by ops-managed schema migrations.
-- The application layer (InterestCalculationService) enforces the implicit
-- referential integrity between (dis_tran_type_cd, dis_tran_cat_cd) and
-- (tran_type.tran_type, tran_category.tran_cat_cd) at write time. No FK
-- is declared here for three reasons:
--   1. The composite PK already prevents duplicate keys, which is the only
--      data-integrity constraint the COBOL source enforced.
--   2. Reference rows are bulk-seeded by Flyway in a single migration; FK
--      validation at INSERT time is unnecessary because every (type, cat)
--      tuple in V012 is already covered by V013 (tran_type) and V014
--      (tran_category).
--   3. The InterestCalculationService validation cascade matches the
--      COBOL CBACT04C source behavior precisely (which used VSAM READs
--      with FILE STATUS '23' NOTFND handling, not RDBMS foreign-key
--      constraints); replicating COBOL semantics is required by the
--      Minimal Change Clause (AAP §0.7.3).
--
-- DEFAULT-fallback strategy notes (per AAP §0.6.1) -- documented here for
-- reviewers but implemented in JAVA, not SQL:
--   - When the JPA repository's findById(group, type, cat) returns empty,
--     InterestCalculationService re-issues the lookup with group_id set
--     to the literal string 'DEFAULT'. The 'DEFAULT' rows seeded by V012
--     cover the same 17 (type, cat) tuples as 'A000000000', guaranteeing
--     coverage for any account whose acct_group_id is unrecognized.
--   - When account.acct_group_id is 'ZEROAPR', the lookup hits the 17
--     'ZEROAPR' rows (all 0.00% rates) and InterestCalculationService
--     short-circuits to a zero interest charge for that account.
--   - These two patterns are NOT enforced by the schema (no CHECK
--     constraints, no triggers) -- they are runtime conventions of the
--     Java service layer.
-- =============================================================================

create table disclosure_group (
    -- DIS-ACCT-GROUP-ID PIC X(10); first column of the COBOL composite key
    -- (bytes 1-10 of the 16-byte VSAM key per KEYS(16 0) in DISCGRP.jcl).
    -- 10-character alphanumeric account-group identifier. Three distinct
    -- values are seeded by V012 from app/data/ASCII/discgrp.txt:
    --     'A000000000' -- the default operational account group (a literal
    --                     fully-numeric-looking string with leading 'A';
    --                     stored exactly as 10 chars).
    --     'DEFAULT'    -- the fallback group used by InterestCalculation-
    --                     Service when an account's specific group lookup
    --                     fails (stored TRIMMED of trailing spaces -- the
    --                     COBOL fixture pads it with 3 trailing spaces to
    --                     fill the 10-char field, but V012 stores the
    --                     trimmed 7-char value for query ergonomics).
    --     'ZEROAPR'    -- the zero-APR promotional group (stored TRIMMED).
    -- VARCHAR(10) is chosen over CHAR(10) so that the trimmed 'DEFAULT'
    -- and 'ZEROAPR' values do not pad-compare against the COBOL fixture's
    -- 'DEFAULT   ' / 'ZEROAPR   '; runtime JPA queries always use the
    -- exact (trimmed) literal. Java @Embeddable field: disAcctGroupId
    -- (@Column(name = "dis_acct_group_id", length = 10)).
    dis_acct_group_id    varchar(10)    not null,

    -- DIS-TRAN-TYPE-CD PIC X(02); second column of the COBOL composite key
    -- (bytes 11-12 of the 16-byte VSAM key). 2-character alphanumeric
    -- transaction-type code that is the same value space as tran_type
    -- (V008) -- e.g., '01' Purchase, '02' Payment, '03' Credit, '04'
    -- Authorization, '05' Refund, '06' Reversal, '07' Adjustment.
    -- Leading zeros are SIGNIFICANT and MUST be preserved -- the code is
    -- always exactly 2 characters, always quoted as a string (NEVER stored
    -- as an integer 1 that would lose the leading zero on read). CHAR(2)
    -- is used here (matching V008.tran_type.tran_type) so that the value
    -- space and storage representation are identical across the two
    -- tables. Java @Embeddable field: disTranTypeCd
    -- (@Column(name = "dis_tran_type_cd", length = 2, columnDefinition = "char(2)")).
    dis_tran_type_cd     char(2)        not null,

    -- DIS-TRAN-CAT-CD PIC 9(04); third column of the COBOL composite key
    -- (bytes 13-16 of the 16-byte VSAM key). 4-digit numeric transaction-
    -- category code matching tran_category.tran_cat_cd (V009). Stored as
    -- an INTEGER (4-byte signed integer, range -2^31..2^31-1) which fully
    -- contains the COBOL PIC 9(04) range (0..9999); COBOL's PIC 9(04)
    -- "0001" becomes the integer 1, mirroring the convention used by
    -- V012 (seed rows reference cat 1, 2, 3, 4) and V014
    -- (tran_category.tran_cat_cd). INTEGER is chosen over NUMERIC(4)
    -- to match the Java Integer mapping in DisclosureGroup.disTranCatCd;
    -- Hibernate's schema-validation requires the JDBC type to match the
    -- Java type (Integer -> INTEGER, not NUMERIC). Java @Embeddable
    -- field: disTranCatCd
    -- (@Column(name = "dis_tran_cat_cd", precision = 4, scale = 0)).
    dis_tran_cat_cd      integer        not null,

    -- DIS-INT-RATE PIC S9(04)V99; signed 4-digit integer with 2 implied
    -- decimal places = APR percentage (e.g., 15.00 = 15.00% APR, 25.00 =
    -- 25.00% APR, 0.00 = no interest). The COBOL PIC clause permits any
    -- value in [-9999.99, +9999.99]; PostgreSQL NUMERIC(6,2) faithfully
    -- preserves this range with precision = 4 integer digits + 2 fraction
    -- digits = 6 total digits. The DEFAULT 0 is a defensive default for
    -- any future INSERT that omits the rate (none of the V012 seed rows
    -- use it, and the InterestCalculationService never INSERTs into this
    -- table; the default exists solely to match the COBOL VALUE ZEROS
    -- convention typical for monetary fields).
    --
    -- BigDecimal contract per AAP §0.6.1:
    --   - Java type: java.math.BigDecimal with scale = 2 enforced.
    --   - Rounding mode: RoundingMode.HALF_EVEN (banker's rounding).
    --   - Arithmetic in InterestCalculationService follows the COBOL
    --     CBACT04C pattern literally:
    --         interest = balance.multiply(rate)
    --                           .divide(BigDecimal.valueOf(1200),
    --                                   2, RoundingMode.HALF_EVEN);
    --     This computes monthly interest from an annual percentage rate
    --     (rate / 100 = decimal rate, then / 12 = monthly fraction); the
    --     literal divisor 1200 is preserved without algebraic
    --     simplification to match the COBOL source byte-for-byte.
    --   - NEVER use double or float for this column -- PostgreSQL NUMERIC
    --     is arbitrary-precision and exactly matches BigDecimal semantics.
    --
    -- Java @Embeddable parent's field: disIntRate
    -- (@Column(name = "dis_int_rate", precision = 6, scale = 2)).
    dis_int_rate         numeric(6, 2)  not null default 0,

    -- The COBOL FILLER PIC X(28) trailing the rate in the 50-byte VSAM
    -- record is OMITTED here. It is unused 28-byte padding that brings
    -- the COBOL record to its declared 50-byte RECORDSIZE (10 + 2 + 4 +
    -- 6 + 28 = 50). The source fixture (app/data/ASCII/discgrp.txt)
    -- contains the literal "0000000000000000000000000000" (28 zeros) in
    -- positions 23-50 for every row, confirming this is padding rather
    -- than data. PostgreSQL has no concept of fixed-width records, so
    -- this padding has no relational equivalent.

    -- Composite primary key constraint -- one-to-one with the COBOL VSAM
    -- KSDS composite key (KEYS(16 0), KEYLEN=16, RKP=0 per LISTCAT). The
    -- column order matches the COBOL byte order (group_id leftmost,
    -- type_cd middle, cat_cd rightmost), preserving the implicit
    -- lexicographic ordering of the KSDS for any future queries that
    -- want to range-scan within a single group_id. The PostgreSQL
    -- B-tree index on the composite PK is created implicitly by this
    -- constraint and provides:
    --   - O(log n) exact-match lookups by the full 3-column key
    --     (the InterestCalculationService primary query pattern);
    --   - O(log n) prefix scans by dis_acct_group_id alone (used by
    --     the DEFAULT-fallback lookup pattern when the service iterates
    --     all (type, cat) rows for a given group);
    --   - O(log n) prefix scans by (dis_acct_group_id, dis_tran_type_cd)
    --     pairs (used by StatementGenerationService when emitting
    --     per-type interest breakdowns).
    -- No additional secondary indexes are required because none of the
    -- target Java services query by dis_tran_type_cd or dis_tran_cat_cd
    -- without also specifying dis_acct_group_id (there is no equivalent
    -- of a VSAM AIX on this KSDS in the source -- LISTCAT shows neither
    -- DISCGRP.VSAM.AIX nor DISCGRP.VSAM.PATH entries).
    constraint pk_disclosure_group primary key
        (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ disclosure_group
-- and via JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance discoverable from
-- the database itself. Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- The same discipline applies at the schema level so that DBAs, auditors,
-- and regulators inspecting the live RDS instance can trace every column
-- back to its COBOL field of origin without consulting the source repo.
-- =============================================================================

comment on table disclosure_group is
    'Disclosure-group interest-rate lookup table. Java target for COBOL VSAM '
    'cluster AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS (source: app/cpy/CVTRA02Y.cpy, '
    'app/jcl/DISCGRP.jcl). 51 reference rows seeded by V012 from '
    'app/data/ASCII/discgrp.txt -- 17 rows each for groups ''A000000000'', '
    '''DEFAULT'' (fallback), and ''ZEROAPR'' (promotional zero APR). '
    'Read-mostly reference data consumed by InterestCalculationService '
    '(COBOL CBACT04C) for monthly interest computation and by '
    'StatementGenerationService for APR disclosure on statements. The 16-byte '
    'COBOL composite VSAM key (group_id 10 + type_cd 2 + cat_cd 4) maps to '
    'the 3-column composite PRIMARY KEY. DEFAULT-fallback and ZEROAPR '
    'short-circuit semantics are implemented in the Java service layer '
    '(InterestCalculationService), NOT in the SQL DDL.';

comment on column disclosure_group.dis_acct_group_id is
    'COBOL: DIS-ACCT-GROUP-ID PIC X(10). First 10 bytes of the VSAM KSDS '
    'composite key (KEYS(16 0), KEYLEN=16, RKP=0). 10-character account-group '
    'identifier. Seeded values: ''A000000000'' (standard ops), ''DEFAULT'' '
    '(fallback group queried when account-specific lookup misses), ''ZEROAPR'' '
    '(promotional zero-APR group). Stored TRIMMED of trailing padding spaces '
    'for query ergonomics (the COBOL fixture pads ''DEFAULT'' / ''ZEROAPR'' '
    'with trailing spaces to fit the 10-char field; the PostgreSQL VARCHAR(10) '
    'column stores the trimmed value to match the Java service-layer literals). '
    'Maps to DisclosureGroupId.disAcctGroupId in JPA (@Embeddable).';

comment on column disclosure_group.dis_tran_type_cd is
    'COBOL: DIS-TRAN-TYPE-CD PIC X(02). Bytes 11-12 of the VSAM KSDS composite '
    'key. 2-character transaction-type code; same value space as '
    'tran_type.tran_type (V008): ''01''=Purchase, ''02''=Payment, ''03''=Credit, '
    '''04''=Authorization, ''05''=Refund, ''06''=Reversal, ''07''=Adjustment. '
    'Leading zeros are significant -- always exactly 2 characters, never '
    'normalized to integer. Maps to DisclosureGroupId.disTranTypeCd in JPA.';

comment on column disclosure_group.dis_tran_cat_cd is
    'COBOL: DIS-TRAN-CAT-CD PIC 9(04). Bytes 13-16 of the VSAM KSDS composite '
    'key (the final 4 bytes of the 16-byte key). 4-digit numeric '
    'transaction-category code; same value space as tran_category.tran_cat_cd '
    '(V009). Stored as integer NUMERIC(4); COBOL ''0001'' becomes integer 1. '
    'Maps to DisclosureGroupId.disTranCatCd in JPA.';

comment on column disclosure_group.dis_int_rate is
    'COBOL: DIS-INT-RATE PIC S9(04)V99. Signed 4-digit integer with 2 implied '
    'decimal places = annual percentage rate (APR). Stored as NUMERIC(6,2) '
    'to faithfully preserve COBOL fixed-point semantics with BigDecimal at '
    'runtime (RoundingMode.HALF_EVEN per AAP §0.6.1). Seed values are 0.00, '
    '15.00, and 25.00 percent. Monthly interest is computed by '
    'InterestCalculationService (COBOL CBACT04C) as '
    'balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, HALF_EVEN). '
    'NEVER store as float or double -- PostgreSQL NUMERIC is the only safe '
    'mapping for COBOL decimal arithmetic.';
