-- =============================================================================
-- Flyway Migration: V009__create_transaction_category.sql
-- Purpose:    Create the tran_category lookup table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS. This reference table maps
--             a (transaction-type-code, category-code) composite key to a
--             human-readable category description. The 18 canonical rows
--             (5 Purchase categories, 3 Payment categories, 3 Credit
--             categories, 3 Authorization categories, 1 Refund category,
--             2 Reversal categories, 1 Adjustment category) are loaded by
--             the companion seed migration V014__seed_transaction_category.sql
--             from app/data/ASCII/trancatg.txt.
--
--             Consumers (Java target services that JOIN against this table):
--               - TransactionPostingService (COBOL CBTRN02C) -- validates the
--                 incoming (TRAN-TYPE-CD, TRAN-CAT-CD) pair against this
--                 lookup during the 4-stage validation cascade.
--               - TransactionReportService  (COBOL CBTRN03C) -- joins to
--                 produce human-readable transaction-category labels on the
--                 generated report.
--               - TransactionAddService     (COBOL COTRN02C) -- validates
--                 user-supplied (TRAN-TYPE-CD, TRAN-CAT-CD) before INSERT
--                 into transactions.
--               - InterestCalculationService (COBOL CBACT04C) -- composite-key
--                 lookups against disclosure_group include TRAN-CAT-CD.
--               - tran_cat_bal  (V006) -- per-(account, type, category)
--                 balance buckets keyed by the same composite (type, cat)
--                 pair this table provides as a reference dimension.
--
-- Source:     app/cpy/CVTRA04Y.cpy   (TRAN-CAT-RECORD layout, RECLN 60,
--                                     composite key TRAN-TYPE-CD(2) +
--                                     TRAN-CAT-CD(4); fields:
--                                     TRAN-TYPE-CD PIC X(02), TRAN-CAT-CD
--                                     PIC 9(04), TRAN-CAT-TYPE-DESC
--                                     PIC X(50), trailing FILLER PIC X(04))
--             app/jcl/TRANCATG.jcl   (IDCAMS DEFINE CLUSTER L36-L48:
--                                     KEYS(6,0), RECORDSIZE(60,60),
--                                     SHAREOPTIONS(2,3), ERASE, INDEXED,
--                                     CYLINDERS(1,5); plus REPRO step L54-L62
--                                     loading TRANCATG.PS -> KSDS)
--             app/catlg/LISTCAT.txt  (verifies TRANCATG.VSAM.KSDS cluster:
--                                     KEYLEN=6, RKP=0, MAXLRECL=60,
--                                     AVGLRECL=60, INDEXED)
--
-- AAP Refs:   §0.4.1 (V009 tran_category; one-to-one mapping of TRANCATG.KSDS
--                     to the tran_category relational table),
--             §0.6.2 (VSAM-to-RDS migration strategy; each KSDS becomes a JPA
--                     @Entity with the VSAM record key as the @Id (composite
--                     key here -- the COBOL TRAN-CAT-KEY group-level item
--                     spans the 2-byte TYPE-CD plus the 4-byte CAT-CD per
--                     CVTRA04Y.cpy lines 5-7); AIX/PATH not applicable --
--                     TRANCATG.KSDS has no alternate index),
--             §0.7.1 (refactor discipline; preserve fixture data verbatim;
--                     codes and descriptions are public reference data,
--                     not PII or PAN -- no encryption or masking required).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
--             in app/jcl/TRANCATG.jcl:L36-L48. The IDCAMS REPRO step
--             (app/jcl/TRANCATG.jcl:L54-L62) that loaded TRANCATG.PS into
--             the KSDS is replaced by the companion seed migration
--             V014__seed_transaction_category.sql.
-- =============================================================================

-- =============================================================================
-- COBOL TRAN-CAT-RECORD layout (CVTRA04Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field         PIC clause    PostgreSQL column     Type
--   ------------------- ------------- --------------------- ----------------------
--   TRAN-TYPE-CD        PIC X(02)     tran_type_cd          CHAR(2)      (PK part 1)
--   TRAN-CAT-CD         PIC 9(04)     tran_cat_cd           NUMERIC(4)   (PK part 2)
--   TRAN-CAT-TYPE-DESC  PIC X(50)     tran_cat_type_desc    VARCHAR(50)  NOT NULL
--   FILLER              PIC X(04)     OMITTED               --
--
-- Total COBOL record length: 2 + 4 + 50 + 4 = 60 bytes (per LISTCAT MAXLRECL=60).
-- Total PostgreSQL business columns: 3 (FILLER omitted).
--
-- The COBOL TRAN-CAT-KEY group-level item (CVTRA04Y.cpy line 5) explicitly
-- spans both the 2-byte TYPE-CD and the 4-byte CAT-CD as a composite key
-- (KEYS(6,0) per IDCAMS DEFINE CLUSTER in TRANCATG.jcl); PostgreSQL models
-- this as a composite PRIMARY KEY (tran_type_cd, tran_cat_cd). The
-- corresponding Java @Entity will declare an @EmbeddedId composite key
-- (TransactionCategoryId) carrying both fields, per AAP §0.4.1.
--
-- VSAM-to-PostgreSQL design notes:
--   - KEYS(6,0)             : 6-byte primary key at offset 0 (the
--                             concatenation of TYPE-CD + CAT-CD); maps to
--                             a composite PRIMARY KEY on (tran_type_cd,
--                             tran_cat_cd) in PostgreSQL.
--   - RECORDSIZE(60,60)     : fixed-width 60-byte record; PostgreSQL has no
--                             concept of fixed-width rows -- columns are
--                             variable-length VARCHAR/NUMERIC. The 4-byte
--                             trailing FILLER is omitted.
--   - SHAREOPTIONS(2,3)     : VSAM concurrency; replaced by PostgreSQL
--                             MVCC + Spring Data JPA repository semantics.
--   - INDEXED               : KSDS index structure; replaced by the
--                             PostgreSQL B-tree implicitly created by the
--                             composite PRIMARY KEY constraint.
--   - ERASE                 : VSAM physical-erase on delete; replaced by
--                             standard PostgreSQL DELETE semantics.
--   - CYLINDERS(1,5)        : z/OS physical allocation; not applicable.
--
-- Foreign-key design note:
--   The logical parent of (tran_type_cd) is tran_type.tran_type (created by
--   V008). Per the same refactor-discipline approach used by V008's reverse
--   reference (V008 line 105-108), a FOREIGN KEY constraint from
--   tran_category.tran_type_cd to tran_type.tran_type is NOT declared at
--   this checkpoint -- the current AAP scope keeps cross-table FKs out of
--   schema-create migrations to permit independent reseeding of reference
--   tables during integration testing. Application-layer validation in
--   TransactionPostingService / TransactionAddService enforces tran_type_cd
--   existence (preserves COBOL CBTRN02C / COTRN02C validation cascade
--   behavior).
-- =============================================================================

create table tran_category (
    -- TRAN-TYPE-CD PIC X(02); the first 2 bytes of the 6-byte composite VSAM
    -- key (RKP=0 per LISTCAT). 2-character alphanumeric transaction-type
    -- code such as '01' (Purchase), '02' (Payment), '03' (Credit), '04'
    -- (Authorization), '05' (Refund), '06' (Reversal), '07' (Adjustment).
    -- Leading zeros are SIGNIFICANT and MUST be preserved -- the code is
    -- always exactly 2 characters, always quoted as a string. CHAR(2) is
    -- space-padded on read by PostgreSQL, but since every value is exactly
    -- 2 characters there is no padding to trim. The same logical value
    -- appears as tran_type.tran_type (PK of V008); see the foreign-key
    -- design note in the header comment.
    tran_type_cd          char(2)      not null,

    -- TRAN-CAT-CD PIC 9(04); the next 4 bytes of the 6-byte composite VSAM
    -- key. 4-digit unsigned numeric category code (range 0001-9999 per
    -- COBOL PIC 9(04) semantics; the source fixture uses 0001..0005 only).
    -- Stored as NUMERIC(4) -- integer values 1..9999, NOT a zero-padded
    -- string. The COBOL PIC 9(04) "0001" becomes the integer 1 in
    -- PostgreSQL (e.g., (tran_type_cd, tran_cat_cd) = ('01', 1) maps to
    -- the COBOL VSAM key bytes '010001'). Application-layer formatting
    -- in TransactionReportService re-pads to 4 digits when emitting
    -- regulatory-format output, preserving byte-for-byte parity with the
    -- COBOL source.
    tran_cat_cd           numeric(4)   not null,

    -- TRAN-CAT-TYPE-DESC PIC X(50); human-readable description of the
    -- transaction category. The COBOL source pads each description to 50
    -- characters with trailing spaces; PostgreSQL VARCHAR(50) stores the
    -- trimmed (idiomatic relational) value. The exact text of the
    -- description is preserved verbatim from app/data/ASCII/trancatg.txt
    -- by the companion seed migration V014 -- DO NOT normalize case or
    -- trim meaningful internal whitespace, because regulatory output
    -- formats (TransactionReportService output, statement generation)
    -- depend on the exact text and capitalization. Per AAP §0.7.1 / §0.7.3
    -- ("Refactor Discipline -- preserve fixture text verbatim"):
    --   * Type 01 entries use Title Case (e.g., "Regular Sales Draft").
    --   * Type 02 entries use lowercase "payment" ("Cash payment", NOT
    --     "Cash Payment") -- intentional and reflects the fixture.
    --   * Type 03-07 entries follow the source fixture's mixed-case
    --     spelling.
    -- NOT NULL because the COBOL fixed-width record always has 50 bytes
    -- of (potentially space-padded) content -- never NULL.
    tran_cat_type_desc    varchar(50)  not null,

    -- The COBOL FILLER PIC X(04) trailing the description in the 60-byte
    -- VSAM record is OMITTED here. It is unused 4-byte padding that brings
    -- the COBOL record to its declared 60-byte RECORDSIZE (2 + 4 + 50 + 4
    -- = 60). The source fixture (app/data/ASCII/trancatg.txt) contains
    -- literal "0000" in positions 57-60 for every row, confirming this is
    -- padding rather than data. PostgreSQL has no concept of fixed-width
    -- records, so this padding has no relational equivalent.

    -- Composite PRIMARY KEY -- one-to-one with the COBOL VSAM KSDS key
    -- (RKP=0, KEYLEN=6 = 2-byte TYPE-CD + 4-byte CAT-CD per LISTCAT).
    -- The PostgreSQL B-tree index on (tran_type_cd, tran_cat_cd) is
    -- created implicitly by this constraint and provides O(log n) lookup
    -- for all consumer services (TransactionPostingService validation,
    -- TransactionReportService joins, InterestCalculationService composite
    -- lookups, etc.).
    constraint pk_tran_category primary key (tran_type_cd, tran_cat_cd)
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ tran_category and
-- via JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance discoverable from
-- the database itself. Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- The same discipline applies at the schema level so that DBAs, auditors,
-- and regulators inspecting the live RDS instance can trace every column
-- back to its COBOL field of origin without consulting the source repo.
-- =============================================================================

comment on table tran_category is
    'Transaction-category lookup table (composite key). Java target for '
    'COBOL VSAM cluster AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS (source: '
    'app/cpy/CVTRA04Y.cpy, app/jcl/TRANCATG.jcl). Eighteen canonical '
    '(tran_type_cd, tran_cat_cd) pairs seeded by V014 from '
    'app/data/ASCII/trancatg.txt. Read-mostly reference data; consumed by '
    'TransactionPostingService, TransactionReportService, '
    'TransactionAddService, and InterestCalculationService.';

comment on column tran_category.tran_type_cd is
    'COBOL: TRAN-TYPE-CD PIC X(02). First 2 bytes of the 6-byte composite '
    'VSAM KSDS primary key (RKP=0, KEYLEN=6 per app/catlg/LISTCAT.txt). '
    '2-character transaction-type code: ''01''=Purchase, ''02''=Payment, '
    '''03''=Credit, ''04''=Authorization, ''05''=Refund, ''06''=Reversal, '
    '''07''=Adjustment. Leading zeros are significant -- always exactly 2 '
    'characters, never normalized to integer. Logical reference to '
    'tran_type.tran_type (V008); FK not declared at schema level (see '
    'V009 header comment) -- application-layer validation enforces '
    'referential integrity. Maps to TransactionCategoryId.tranTypeCd in JPA.';

comment on column tran_category.tran_cat_cd is
    'COBOL: TRAN-CAT-CD PIC 9(04). Last 4 bytes of the 6-byte composite '
    'VSAM KSDS primary key. Unsigned 4-digit numeric category code stored '
    'as NUMERIC(4) integer (e.g., 1, 2, 3) -- NOT a zero-padded string. '
    'Source fixture range is 0001..0005 (with up to 5 categories per type) '
    'though the PIC clause permits 0001..9999. Application-layer formatting '
    're-pads to 4 digits when emitting regulatory-format output. Maps to '
    'TransactionCategoryId.tranCatCd in JPA.';

comment on column tran_category.tran_cat_type_desc is
    'COBOL: TRAN-CAT-TYPE-DESC PIC X(50). Human-readable description of '
    'the transaction category (e.g., ''Regular Sales Draft'', ''Cash '
    'payment''). Text preserved verbatim from app/data/ASCII/trancatg.txt; '
    'capitalization and exact wording must not be normalized -- regulatory '
    'output formats and downstream report consumers depend on the exact '
    'text. COBOL padded to 50 chars with trailing spaces; PostgreSQL '
    'stores the trimmed value.';
