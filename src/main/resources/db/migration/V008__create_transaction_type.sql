-- =============================================================================
-- Flyway Migration: V008__create_transaction_type.sql
-- Purpose:    Create the tran_type lookup table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS. This small reference table
--             maps a 2-character transaction-type code to a human-readable
--             description. The seven canonical type codes (Purchase, Payment,
--             Credit, Authorization, Refund, Reversal, Adjustment) are loaded
--             by the companion seed migration V013__seed_transaction_type.sql
--             from app/data/ASCII/trantype.txt.
--
--             Consumers (Java target services that JOIN against this table):
--               - TransactionPostingService (COBOL CBTRN02C) -- validates the
--                 incoming TRAN-TYPE-CD against this lookup during the
--                 4-stage validation cascade.
--               - TransactionReportService  (COBOL CBTRN03C) -- joins to
--                 produce human-readable transaction-type labels on the
--                 generated report.
--               - TransactionAddService     (COBOL COTRN02C) -- validates
--                 user-supplied TRAN-TYPE-CD before INSERT into transactions.
--               - InterestCalculationService (COBOL CBACT04C) -- composite-key
--                 lookups against disclosure_group include TRAN-TYPE-CD.
--               - tran_category (V009) -- composite primary key
--                 (tran_type_cd, tran_cat_cd) where tran_type_cd is logically
--                 the foreign reference to this table.
--               - tran_cat_bal  (V006) -- per-(account, type, category)
--                 balance buckets keyed by tran_type_cd.
--
-- Source:     app/cpy/CVTRA03Y.cpy   (TRAN-TYPE-RECORD layout, RECLN 60,
--                                     3 fields: 2-byte key + 50-byte desc +
--                                     8-byte trailing FILLER)
--             app/jcl/TRANTYPE.jcl   (IDCAMS DEFINE CLUSTER L36-L49:
--                                     KEYS(2,0), RECORDSIZE(60,60),
--                                     SHAREOPTIONS(1,4), ERASE, INDEXED,
--                                     CYLINDERS(1,5); plus REPRO step L54-L62
--                                     loading TRANTYPE.PS -> KSDS)
--             app/catlg/LISTCAT.txt  (verifies TRANTYPE.VSAM.KSDS cluster:
--                                     KEYLEN=2, RKP=0, MAXLRECL=60,
--                                     AVGLRECL=60, INDEXED, SHROPTNS(1,4),
--                                     CISIZE=18432, REC-TOTAL=7)
--
-- AAP Refs:   §0.4.1 (V008 tran_type; one-to-one mapping of TRANTYPE.KSDS
--                     to the tran_type relational table),
--             §0.6.2 (VSAM-to-RDS migration strategy; each KSDS becomes a JPA
--                     @Entity with the VSAM record key as the @Id; AIX/PATH
--                     not applicable -- TRANTYPE.KSDS has no alternate index),
--             §0.7.1 (refactor discipline; preserve fixture data verbatim;
--                     codes and descriptions are public reference data,
--                     not PII or PAN -- no encryption or masking required).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
--             in app/jcl/TRANTYPE.jcl:L36-L49. The IDCAMS REPRO step
--             (app/jcl/TRANTYPE.jcl:L54-L62) that loaded TRANTYPE.PS into
--             the KSDS is replaced by the companion seed migration
--             V013__seed_transaction_type.sql.
-- =============================================================================

-- =============================================================================
-- COBOL TRAN-TYPE-RECORD layout (CVTRA03Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field        PIC clause   PostgreSQL column   Type
--   ------------------ ------------ ------------------- ----------------------
--   TRAN-TYPE          PIC X(02)    tran_type           CHAR(2)   (PRIMARY KEY)
--   TRAN-TYPE-DESC     PIC X(50)    tran_type_desc      VARCHAR(50) NOT NULL
--   FILLER             PIC X(08)    OMITTED             --
--
-- Total COBOL record length: 2 + 50 + 8 = 60 bytes (per LISTCAT MAXLRECL=60).
-- Total PostgreSQL business columns: 2 (FILLER omitted).
--
-- The PRIMARY KEY column has the same name (tran_type) as the table -- this
-- matches the COBOL field name TRAN-TYPE exactly per AAP §0.6.2 ("Field-level
-- mapping preserves byte offsets in the entity comments and JPA @Column
-- definitions"). The corresponding Java @Entity will declare a field named
-- tranType with @Id and @Column(name = "tran_type").
--
-- VSAM-to-PostgreSQL design notes:
--   - KEYS(2,0)             : 2-byte primary key at offset 0; maps to
--                             tran_type CHAR(2) PRIMARY KEY.
--   - RECORDSIZE(60,60)     : fixed-width 60-byte record; PostgreSQL has no
--                             concept of fixed-width rows -- columns are
--                             stored at natural widths and the trailing
--                             8-byte FILLER is omitted.
--   - SHAREOPTIONS(1,4)     : "single writer + multiple readers across
--                             address spaces" in VSAM. PostgreSQL handles
--                             concurrent access natively via MVCC (multi-
--                             version concurrency control) with READ_COMMITTED
--                             default isolation; no SQL equivalent is required.
--   - ERASE / INDEXED       : VSAM physical-storage attributes; PostgreSQL
--                             B-tree index is created implicitly by the
--                             PRIMARY KEY constraint.
--   - CYLINDERS(1,5),
--     CA-RECLAIM, CISIZE,
--     etc.                  : z/OS physical-allocation attributes; not
--                             applicable to managed RDS PostgreSQL.
--   - REC-TOTAL=7           : 7 reference rows seeded by V013 from
--                             app/data/ASCII/trantype.txt (no plaintext
--                             secrets; safe to commit as inline SQL).
--
-- This table has NO foreign-key constraints because it is a leaf lookup -- it
-- has no parent. Logically, transactions.tran_type_cd, tran_category.tran_type_cd,
-- and tran_cat_bal.tran_type_cd reference this table's tran_type column, but
-- those FK constraints are NOT added in the current migration scope because
-- Flyway lexicographic ordering places V005 (transactions), V006 (tran_cat_bal),
-- and V009 (tran_category) -- the FK consumers -- around or before V008. The
-- current scope is 15 migrations exactly per AAP; no V016 reverse-FK migration
-- is planned. Application-layer validation in TransactionPostingService /
-- TransactionAddService enforces tran_type_cd existence (preserves COBOL
-- CBTRN02C / COTRN02C validation cascade behavior).
-- =============================================================================

create table tran_type (
    -- TRAN-TYPE PIC X(02); the primary VSAM key (RKP=0, KEYLEN=2 per
    -- LISTCAT). 2-character alphanumeric transaction-type code such as
    -- '01' (Purchase), '02' (Payment), '03' (Credit), '04' (Authorization),
    -- '05' (Refund), '06' (Reversal), '07' (Adjustment). Leading zeros are
    -- SIGNIFICANT and MUST be preserved -- the code is always exactly 2
    -- characters, always quoted as a string (NEVER stored as an integer 1
    -- that would lose the leading zero on read). CHAR(2) is space-padded on
    -- read by PostgreSQL, but since every value is exactly 2 characters
    -- there is no padding to trim. Java @Entity field: tranType
    -- (@Column(name = "tran_type")).
    tran_type        char(2)      not null,

    -- TRAN-TYPE-DESC PIC X(50); human-readable description of the
    -- transaction type. The COBOL source pads each description to 50
    -- characters with trailing spaces; PostgreSQL VARCHAR(50) stores the
    -- trimmed (idiomatic relational) value. The exact text of the description
    -- is preserved verbatim from app/data/ASCII/trantype.txt by the
    -- companion seed migration V013 -- DO NOT normalize case or trim
    -- meaningful internal whitespace, because regulatory output formats
    -- (TransactionReportService output, statement generation) depend on
    -- the exact text and capitalization. NOT NULL because the COBOL
    -- fixed-width record always has 50 bytes of (potentially space-padded)
    -- content -- never NULL.
    tran_type_desc   varchar(50)  not null,

    -- The COBOL FILLER PIC X(08) trailing the description in the 60-byte
    -- VSAM record is OMITTED here. It is unused 8-byte padding that brings
    -- the COBOL record to its declared 60-byte RECORDSIZE (2 + 50 + 8 = 60).
    -- The source fixture (app/data/ASCII/trantype.txt) contains literal
    -- "00000000" in positions 53-60 for every row, confirming this is
    -- padding rather than data. PostgreSQL has no concept of fixed-width
    -- records, so this padding has no relational equivalent.

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS key
    -- (RKP=0, KEYLEN=2). The PostgreSQL B-tree index on tran_type is
    -- created implicitly by this constraint and provides O(log n) lookup
    -- for all consumer services (TransactionPostingService validation,
    -- TransactionReportService joins, etc.).
    constraint pk_tran_type primary key (tran_type)
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ tran_type and
-- via JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance discoverable from
-- the database itself. Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- The same discipline applies at the schema level so that DBAs, auditors,
-- and regulators inspecting the live RDS instance can trace every column
-- back to its COBOL field of origin without consulting the source repo.
-- =============================================================================

comment on table tran_type is
    'Transaction-type lookup table. Java target for COBOL VSAM cluster '
    'AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS (source: app/cpy/CVTRA03Y.cpy, '
    'app/jcl/TRANTYPE.jcl). Seven canonical type codes (01..07) seeded by '
    'V013 from app/data/ASCII/trantype.txt. Read-mostly reference data; '
    'consumed by TransactionPostingService, TransactionReportService, '
    'TransactionAddService, and InterestCalculationService. Composite-key '
    'parent for tran_category (V009) and tran_cat_bal (V006) at the '
    'application layer.';

comment on column tran_type.tran_type is
    'COBOL: TRAN-TYPE PIC X(02). VSAM KSDS primary key (RKP=0, KEYLEN=2 '
    'per app/catlg/LISTCAT.txt). 2-character transaction-type code: '
    '''01''=Purchase, ''02''=Payment, ''03''=Credit, ''04''=Authorization, '
    '''05''=Refund, ''06''=Reversal, ''07''=Adjustment. Leading zeros are '
    'significant -- always exactly 2 characters, never normalized to '
    'integer. Maps to TransactionType.@Id in JPA.';

comment on column tran_type.tran_type_desc is
    'COBOL: TRAN-TYPE-DESC PIC X(50). Human-readable description of the '
    'transaction type (e.g., ''Purchase'', ''Payment''). Text preserved '
    'verbatim from app/data/ASCII/trantype.txt; capitalization and exact '
    'wording must not be normalized -- regulatory output formats and '
    'downstream report consumers depend on the exact text. COBOL padded '
    'to 50 chars with trailing spaces; PostgreSQL stores the trimmed value.';
