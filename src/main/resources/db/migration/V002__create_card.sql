-- =============================================================================
-- Flyway Migration: V002__create_card.sql
-- Purpose:    Create the cards table -- the JPA-mapped relational equivalent
--             of the COBOL VSAM cluster AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
--             plus its alternate index AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.
--             The cards table stores every physical / virtual payment card
--             that has ever been issued against an account in the CardDemo
--             system. It is the second of the four foundation reference
--             tables (V001 accounts, V002 cards, V003 customers, V004
--             card_xref) -- the cross-reference cluster (CARDXREF.VSAM.KSDS)
--             links these three independent tables into the 3-way
--             (card, customer, account) relationship that drives every
--             CardDemo business transaction.
--
--             Consumers (Java target services that READ from / WRITE to
--             the cards table):
--               - CardListService             (COBOL COCRDLIC)  -- paginated
--                 browse by card_acct_id; the FIND-BY-CARD-ACCT-ID derived
--                 query method on CardRepository powers the COCRDLI.bms
--                 7-row-per-page card-list screen. Uses the secondary
--                 index idx_cards_acct_id declared below (which replaces
--                 the COBOL VSAM CARDDATA.VSAM.AIX KEYS(11 16)
--                 NONUNIQUEKEY UPGRADE) for efficient by-account lookup.
--               - CardDetailService           (COBOL COCRDSLC)  -- random
--                 read by card_num (the 16-character primary key); the
--                 entity is loaded into the COCRDSL.bms card-detail
--                 screen.
--               - CardUpdateService           (COBOL COCRDUPC)  -- random
--                 read + update under a @Transactional(rollbackFor =
--                 Exception.class) boundary with JPA @Version optimistic
--                 locking; replaces the COBOL before/after image
--                 comparison pattern on EXEC CICS READ UPDATE / REWRITE
--                 per AAP §0.6.2 / §0.7.1.
--               - TransactionPostingService   (COBOL CBTRN01C/02C/03C) --
--                 reads card_expiration_date and card_active_status
--                 during the 4-stage validation cascade (XREF / account /
--                 credit-limit / card-expiration -- reject codes 100-109
--                 preserved verbatim per AAP §0.1.1).
--               - TransactionAddService       (COBOL COTRN02C)  -- looks
--                 up the card row when authorizing a new transaction;
--                 also joins via card_xref to resolve the account.
--               - CardFileReaderService       (COBOL CBACT02C)  -- batch
--                 sequential scanner that emits every row in the cards
--                 table for audit / reporting purposes.
--               - StatementGenerationService  (COBOL CBSTM03A/03B) --
--                 prints card_embossed_name / card_num (masked) onto
--                 monthly statements.
--               - CardRepository (Spring Data JPA) -- exposes
--                 findById(String cardNum) keyed by card_num and the
--                 paged findByCardAcctId(Long acctId, Pageable pageable)
--                 derived query; injected into every service above.
--
-- Source:     app/cpy/CVACT02Y.cpy   (CARD-RECORD layout, RECLN 150,
--                                     6 business fields + 59-byte trailing
--                                     FILLER; verified L4-L11. Note: the
--                                     source copybook contains a COBOL
--                                     spelling typo on L9
--                                     "CARD-EXPIRAION-DATE" instead of
--                                     "EXPIRATION" -- the PostgreSQL
--                                     column adopts the corrected
--                                     spelling per AAP §0.6.2 / consistent
--                                     with the V001 acct_expiration_date
--                                     correction.)
--             app/jcl/CARDFILE.jcl   (IDCAMS DEFINE CLUSTER L50-L63:
--                                     KEYS(16 0), RECORDSIZE(150 150),
--                                     CYLINDERS(1 5), SHAREOPTIONS(2 3),
--                                     ERASE, INDEXED; plus REPRO step
--                                     L68-L76 loading CARDDATA.PS -> KSDS;
--                                     plus DEFINE ALTERNATEINDEX L80-L92
--                                     KEYS(11 16) NONUNIQUEKEY UPGRADE
--                                     RECORDSIZE(150,150); plus DEFINE
--                                     PATH L97-L102 and BLDINDEX L107-L112
--                                     for AIX-base-cluster linkage)
--             app/catlg/LISTCAT.txt  (verifies CARDDATA.VSAM.KSDS cluster
--                                     L164-L222: KEYLEN=16, RKP=0,
--                                     MAXLRECL=150, AVGLRECL=150,
--                                     INDEXED, SHROPTNS(2,3),
--                                     CISIZE=18432, BUFSPACE=37376,
--                                     REC-TOTAL=50; plus AIX PATH
--                                     L150-L163 associating
--                                     CARDDATA.VSAM.AIX with the base
--                                     cluster)
--
-- AAP Refs:   §0.4.1 (V002 card; one-to-one mapping of CARDDATA.KSDS to
--                     the cards relational table; foreign-key dependent
--                     on V001 accounts. The CARDDATA.VSAM.AIX KEYS(11 16)
--                     NONUNIQUEKEY UPGRADE alternate index translates to
--                     the PostgreSQL secondary index idx_cards_acct_id
--                     declared below.),
--             §0.6.2 (VSAM-to-RDS migration strategy -- KEYS(16 0) maps to
--                     VARCHAR(16) PRIMARY KEY on card_num (16-character
--                     card-number string, NOT a numeric type because
--                     card numbers are conventionally treated as opaque
--                     identifier strings and the leading-zero / Luhn-
--                     check semantics require string handling); the AIX
--                     KEYS(11 16) NONUNIQUEKEY translates to a regular
--                     (NOT UNIQUE) PostgreSQL B-tree index on
--                     card_acct_id because one account can have multiple
--                     cards; the cleaned-up column name
--                     card_expiration_date corrects the COBOL "EXPIRAION"
--                     typo),
--             §0.6.6 (PCI-DSS compliance -- the card_num and card_cvv_cd
--                     columns are CARDHOLDER DATA per PCI-DSS scope;
--                     encryption at rest is delegated to RDS via the
--                     customer KMS CMK (configured in infrastructure/
--                     terraform/rds.tf); encryption in transit via
--                     'rds.force_ssl=1' parameter; per AAP §0.6.6 the
--                     Java application layer MUST mask card_num in logs
--                     (PAN-like regex enforced by CloudWatch log filters)
--                     and MUST NOT log card_cvv_cd under any
--                     circumstance. The CVV is preserved as a NUMERIC(3)
--                     column to mirror the COBOL source verbatim per the
--                     Minimal Change Clause -- in a production-hardened
--                     deployment column-level pgcrypto or removal of the
--                     column would be additional defense-in-depth
--                     beyond this migration's scope.),
--             §0.7.1 (refactor discipline -- (a) optimistic locking via
--                     JPA @Version replaces COBOL before/after image
--                     comparison in COCRDUPC.cbl; (b) the FK to accounts
--                     uses ON DELETE NO ACTION (NEVER CASCADE) to
--                     preserve referential integrity without surprising
--                     cascades; (c) the secondary index idx_cards_acct_id
--                     is explicitly named and explicitly NON-UNIQUE
--                     because multiple cards may share the same account
--                     -- consistent with the VSAM NONUNIQUEKEY attribute
--                     in CARDFILE.jcl L86),
--             §0.7.3 (Minimal Change Clause -- preserve COBOL semantics
--                     exactly; do NOT add CHECK constraints or business-
--                     rule validation that did not exist in the COBOL
--                     source; CHECK on card_active_status restricted to
--                     ('Y','N') is the same defense-in-depth pattern
--                     used in V001 accounts for acct_active_status and
--                     in V010 user_security for sec_usr_type).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
--             in app/jcl/CARDFILE.jcl:L50-L63. The IDCAMS DEFINE
--             ALTERNATEINDEX for AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX
--             KEYS(11 16) NONUNIQUEKEY UPGRADE in app/jcl/CARDFILE.jcl:
--             L83-L92 is translated to the PostgreSQL secondary index
--             idx_cards_acct_id created at the bottom of this migration.
--             The IDCAMS DEFINE PATH (L100-L102) and BLDINDEX (L107-L112)
--             steps have NO PostgreSQL equivalent -- PostgreSQL indexes
--             are automatically synchronized with the base table on
--             every INSERT / UPDATE / DELETE (which mirrors the VSAM
--             UPGRADE attribute semantic). The IDCAMS REPRO step
--             (app/jcl/CARDFILE.jcl:L68-L76) that loaded CARDDATA.PS
--             into the KSDS is NOT replaced by a Flyway seed migration
--             -- card master data is loaded for bulk-fact purposes by
--             an AWS Glue Spark job per AAP §0.6.2.
-- =============================================================================

-- =============================================================================
-- COBOL CARD-RECORD layout (CVACT02Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field              PIC clause       PostgreSQL column         Type
--   ------------------------ ---------------- ------------------------- ------------------
--   CARD-NUM                 PIC X(16)        card_num                  VARCHAR(16) (PK)
--   CARD-ACCT-ID             PIC 9(11)        card_acct_id              BIGINT       NN (FK)
--   CARD-CVV-CD              PIC 9(03)        card_cvv_cd               NUMERIC(3)   NN
--   CARD-EMBOSSED-NAME       PIC X(50)        card_embossed_name        VARCHAR(50)  NN
--   CARD-EXPIRAION-DATE      PIC X(10) [typo] card_expiration_date      DATE         NN
--   CARD-ACTIVE-STATUS       PIC X(01)        card_active_status        CHAR(1)      NN
--   FILLER                   PIC X(59)        OMITTED                   -- (padding)
--   (no COBOL equivalent)    --               version                   BIGINT       NN
--
-- Total COBOL record length: 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes (per
--                          LISTCAT MAXLRECL=150 / AVGLRECL=150 at
--                          app/catlg/LISTCAT.txt L202-L203).
--
-- Total PostgreSQL columns: 7 (6 business fields from copybook minus the
--                           FILLER, plus the application-added version
--                           column for JPA @Version).
--
-- VSAM primary key position (RKP=0) and key length (KEYLEN=16 per
-- LISTCAT L202) map to a VARCHAR(16) PRIMARY KEY on card_num. The
-- 16-character card-number string is stored as-is (NOT cast to BIGINT)
-- because:
--   (a) Real-world card numbers are conventionally treated as opaque
--       identifier strings (not arithmetic values);
--   (b) Leading zeros must be preserved (BIN-range prefixes start at
--       4 but the test fixtures include 16-digit values whose leading
--       digit pattern must round-trip byte-identical for parallel-run
--       diff validation per AAP §0.7.2);
--   (c) Luhn-check and BIN-range parsing operate on the string form;
--   (d) The Java target Card.@Id field is declared as String, matching
--       the COBOL PIC X(16) alphanumeric type.
--
-- VSAM alternate-index key position (RKP=16, KEYLEN=11 per
-- app/jcl/CARDFILE.jcl L85 -- KEYS(11 16) means 11-byte key starting at
-- byte offset 16) maps to the secondary index idx_cards_acct_id on the
-- card_acct_id column. The VSAM AIX is explicitly NONUNIQUEKEY (L86)
-- because one account can have multiple cards -- the PostgreSQL index
-- is therefore a regular (NOT UNIQUE) B-tree. The VSAM UPGRADE attribute
-- (L87) -- which forces the AIX to be automatically updated when the
-- base cluster is updated -- is native PostgreSQL behavior; indexes
-- are always kept in sync with the base table without explicit
-- configuration.
--
-- Storage-tier attributes from CARDFILE.jcl that have NO PostgreSQL
-- equivalent (PostgreSQL handles storage layout automatically and the
-- RDS Multi-AZ topology supersedes z/OS VSAM characteristics):
--   - CYLINDERS(1 5)         : z/OS physical allocation; not applicable
--   - SHAREOPTIONS(2 3)      : VSAM concurrency; replaced by PostgreSQL
--                              MVCC + JPA @Transactional isolation
--                              (READ_COMMITTED default; stronger as
--                              required by CardUpdateService and
--                              TransactionPostingService boundaries)
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
--   - DEFINE PATH            : VSAM PATH that relates the AIX to the
--                              base cluster -- PostgreSQL indexes are
--                              directly associated with the base table
--                              and require no separate PATH entity
--   - BLDINDEX               : VSAM AIX build step -- PostgreSQL
--                              CREATE INDEX populates the B-tree
--                              automatically at creation time and
--                              maintains it on every INSERT / UPDATE /
--                              DELETE
--
-- The FILLER PIC X(59) is OMITTED. It is the trailing padding that
-- brings the COBOL record to its fixed 150-byte VSAM record length
-- (16 + 11 + 3 + 50 + 10 + 1 + 59 = 150). PostgreSQL has no concept of
-- fixed-width records, so this padding has no relational equivalent
-- and is dropped per AAP §0.6.2 (consistent with the FILLER-omission
-- pattern used in V001 accounts and the other migrations).
--
-- The version BIGINT NOT NULL DEFAULT 0 column is added to support the
-- JPA @Version optimistic-locking pattern that replaces the COBOL
-- before/after image comparison in COCRDUPC.cbl. Per AAP §0.6.2 / §0.7.1
-- this is the SAME PATTERN used in V001 accounts to replace the in-place
-- EXEC CICS READ UPDATE / REWRITE flow: every card update reads the
-- current version, increments it on save, and the JPA layer throws
-- OptimisticLockException (translated to ConcurrentModificationException
-- per the GlobalExceptionHandler in src/main/java/com/awsm2/carddemo/
-- exception/) when two concurrent writers race.
--
-- ***   DATA-TYPE COMPATIBILITY NOTE FOR THE FOREIGN KEY:   ***
-- card_acct_id is declared BIGINT (NOT NUMERIC(11)) so that the
-- FOREIGN KEY constraint to accounts(acct_id) -- which is BIGINT per
-- V001__create_account.sql:L210 -- is valid. PostgreSQL FK constraints
-- require the referencing column type to be binary-coercible to the
-- referenced column type, and NUMERIC / BIGINT are NOT directly
-- compatible (the foreign-key constraint would fail with
-- "foreign key constraint cannot be implemented: incompatible types").
-- BIGINT (signed 8-byte integer, range -2^63..2^63-1) fully contains
-- the COBOL PIC 9(11) unsigned range (0..99,999,999,999), so the COBOL
-- value space is preserved without truncation. This is the SAME
-- decision documented in V006__create_tcatbal.sql for trancat_acct_id.
-- =============================================================================


create table cards (
    -- CARD-NUM PIC X(16); the primary VSAM KSDS key (RKP=0, KEYLEN=16
    -- per app/catlg/LISTCAT.txt L202). 16-character card-number string
    -- (typically PAN-format: 4 BIN digits + 6 issuer digits + 6
    -- account-identifier digits, but the COBOL fixture format treats
    -- the value as an opaque alphanumeric identifier). Spring Data JPA's
    -- CardRepository uses this as the @Id (entity class: Card, mapping
    -- field cardNum : String). Stored as VARCHAR(16) -- NOT cast to
    -- BIGINT -- because card numbers are conventionally treated as
    -- opaque identifier strings; leading-zero preservation is REQUIRED
    -- for byte-identical parallel-run output diffing per AAP §0.7.2;
    -- Luhn-check / BIN-range parsing operates on the string form. This
    -- column contains CARDHOLDER DATA per PCI-DSS scope (§0.6.6) and
    -- MUST be masked in application logs by the CloudWatch log filter
    -- regex that detects PAN-like sequences.
    card_num                   varchar(16)   not null,

    -- CARD-ACCT-ID PIC 9(11); 11-digit unsigned numeric account
    -- identifier that owns this card. This is a foreign key into the
    -- accounts table (V001). Declared BIGINT (NOT NUMERIC(11)) to
    -- match the BIGINT type of accounts.acct_id in V001__create_account
    -- .sql; PostgreSQL FK constraints require binary-coercible types
    -- and NUMERIC / BIGINT are NOT directly compatible. BIGINT (signed
    -- 8-byte integer, range -2^63..2^63-1) fully contains the COBOL
    -- PIC 9(11) unsigned range (0..99,999,999,999). Maps to
    -- Card.cardAcctId (Long) in JPA. This column is the target of the
    -- secondary index idx_cards_acct_id declared at the bottom of this
    -- migration -- the index replaces the COBOL VSAM CARDDATA.VSAM.AIX
    -- KEYS(11 16) NONUNIQUEKEY UPGRADE alternate index and supports
    -- the findByCardAcctId(Long acctId, Pageable pageable) derived
    -- query method on CardRepository that powers the COCRDLIC card-list
    -- service.
    card_acct_id               bigint        not null,

    -- CARD-CVV-CD PIC 9(03); 3-digit numeric Card Verification Value
    -- (also known as CVV2 / CVC2 / CID depending on the network). The
    -- COBOL source stores this as a numeric type (PIC 9(03), NOT
    -- PIC X(03)), so the PostgreSQL column type is NUMERIC(3) -- never
    -- VARCHAR. Leading zeros are SIGNIFICANT and must be preserved
    -- (CVV "007" must not collapse to "7"); PostgreSQL NUMERIC handles
    -- this natively because numeric values are stored without any
    -- string-representation padding -- the precision attribute
    -- enforces the 3-digit maximum.
    --
    -- *** PCI-DSS CRITICAL NOTE (AAP §0.6.6) ***
    -- The CVV is "sensitive authentication data" per PCI-DSS v4.0
    -- Requirement 3.2 and MUST NOT be persisted post-authorization in
    -- a production payment-card environment. This column is preserved
    -- here ONLY because:
    --   (a) The COBOL source persists it (CVACT02Y.cpy L7) and the
    --       Minimal Change Clause (AAP §0.7.3) forbids removing fields
    --       that exist in the COBOL source;
    --   (b) CardDemo is a non-production reference / training
    --       application, not a real payment processor;
    --   (c) Encryption at rest is delegated to RDS via the customer
    --       KMS CMK (configured in infrastructure/terraform/rds.tf
    --       per AAP §0.6.6); the application MUST NOT log this
    --       column under any circumstance and MUST NOT return it in
    --       any API response outside of card-update flows that
    --       explicitly require it.
    -- Production hardening (out of scope for this migration) would
    -- replace this column with column-level pgcrypto encryption, an
    -- HSM-managed key derivation, or removal of the column entirely.
    card_cvv_cd                numeric(3)    not null,

    -- CARD-EMBOSSED-NAME PIC X(50); 50-character cardholder name as
    -- embossed / printed on the physical card. Fixed 50-character
    -- COBOL width (typically space-padded right); stored as VARCHAR(50)
    -- to allow trimming on read without loss of information. Read by
    -- StatementGenerationService when printing monthly statements and
    -- by CardDetailService when populating the COCRDSL.bms card-detail
    -- screen. NOT NULL because every card has an embossed name (in
    -- the COBOL source the field is always populated, even if with
    -- spaces).
    card_embossed_name         varchar(50)   not null,

    -- CARD-EXPIRAION-DATE PIC X(10) -- note the COBOL spelling typo
    -- "EXPIRAION" on app/cpy/CVACT02Y.cpy:L9. The PostgreSQL column
    -- adopts the corrected spelling 'card_expiration_date' per AAP
    -- §0.6.2 -- consistent with the V001 acct_expiration_date typo
    -- correction. The COBOL source field name is preserved in this
    -- inline comment for traceability and in the COMMENT ON COLUMN
    -- catalog metadata below.
    --
    -- Card expiration date in ISO-8601 'YYYY-MM-DD' format inside the
    -- COBOL record. Parsed from the COBOL X(10) string to a native
    -- PostgreSQL DATE via DateValidationService (porting CSUTLDPY.cpy /
    -- CSUTLDTC.cbl) using java.time.LocalDate.parse / DateTimeFormatter
    -- per AAP §0.6.1. Native DATE enables JPA / Hibernate to map the
    -- field to java.time.LocalDate without manual substring parsing.
    -- Read by TransactionPostingService at validation stage 4 (card-
    -- expiration check; reject code 103 preserved verbatim per AAP
    -- §0.1.1 / CBTRN02C). NOT NULL because every card has a known
    -- expiration date.
    card_expiration_date       date          not null,

    -- CARD-ACTIVE-STATUS PIC X(01); 1-character card status flag.
    -- COBOL business rule: 'Y' = active (transactions may be posted),
    -- 'N' = inactive / blocked / lost / stolen (transactions are
    -- rejected). 1-character fixed-width in COBOL; stored as CHAR(1)
    -- to preserve the exact semantics. A CHECK constraint is added
    -- below for defense-in-depth following the same pattern used in
    -- V001 accounts for acct_active_status and V010 user_security for
    -- sec_usr_type (per AAP §0.7.1 / §0.7.3 -- prevents corrupted
    -- values from silently breaking transaction-posting decisions).
    card_active_status         char(1)       not null,

    -- The trailing COBOL FILLER PIC X(59) is OMITTED. It is unused
    -- padding that brings the COBOL record to its fixed 150-byte VSAM
    -- record length (16 + 11 + 3 + 50 + 10 + 1 + 59 = 150). PostgreSQL
    -- has no concept of fixed-width records, so this padding has no
    -- relational equivalent and is dropped per AAP §0.6.2 (consistent
    -- with the FILLER-omission pattern used in V001 accounts).

    -- version -- application-added BIGINT for JPA @Version optimistic
    -- locking. Has NO COBOL equivalent: the COBOL source's
    -- before/after image comparison pattern in COCRDUPC.cbl is replaced
    -- by Spring Data JPA's optimistic-lock check that compares the
    -- WHERE clause version against the loaded version on every UPDATE.
    -- Per AAP §0.6.2 / §0.7.1 this is the SAME PATTERN used to replace
    -- the EXEC CICS READ UPDATE / REWRITE flow. Hibernate increments
    -- the value on every save; a stale write throws
    -- org.hibernate.StaleObjectStateException ->
    -- jakarta.persistence.OptimisticLockException, translated to
    -- ConcurrentModificationException (HTTP 409 Conflict) by
    -- GlobalExceptionHandler per AAP §0.4.1. NOT NULL DEFAULT 0 so
    -- existing legacy rows loaded via the Glue Spark migration start
    -- at version=0.
    version                    bigint        not null default 0,

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS
    -- primary key (RKP=0, KEYLEN=16 per app/catlg/LISTCAT.txt L202).
    -- Spring Data JPA's CardRepository uses this as the @Id (entity
    -- class: Card). The PostgreSQL B-tree on this PK satisfies:
    --   - CardDetailService random read by card_num (COBOL COCRDSLC
    --     EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-NUM));
    --   - CardUpdateService random read + update (COBOL COCRDUPC
    --     EXEC CICS READ UPDATE DATASET('CARDDAT') -> REWRITE);
    --   - CardFileReaderService sequential scan (COBOL CBACT02C
    --     EXEC CICS STARTBR / READNEXT in primary-key order).
    constraint pk_cards primary key (card_num),

    -- Foreign key constraint -- card_acct_id references accounts
    -- (acct_id) per AAP §0.6.2. ON DELETE NO ACTION (PostgreSQL
    -- default action, explicitly stated here for clarity) is the
    -- conservative choice: if an application attempts to DELETE an
    -- account that still has cards rows, PostgreSQL raises a foreign-
    -- key violation, which the GlobalExceptionHandler translates to
    -- HTTP 409 Conflict. This forces the application layer to delete
    -- the dependent cards rows (and other dependent rows: card_xref,
    -- transactions, tran_cat_bal) BEFORE deleting the account,
    -- preserving the COBOL VSAM cascade-delete semantic which required
    -- explicit programmatic deletion of each related dataset record.
    -- There is NO CASCADE here: silent cascading deletion of cards
    -- would lose audit history and break the AAP §0.7.2 directive
    -- that "audit trail content -- transaction IDs, timestamps,
    -- operator codes, and audit fields -- must continue to be emitted
    -- with the same values and semantics."
    constraint fk_cards_acct foreign key (card_acct_id)
        references accounts (acct_id) on delete no action,

    -- CHECK constraint enforcing valid card-status discriminator
    -- values. Defense-in-depth improvement following the same pattern
    -- used in V001 accounts for acct_active_status. The COBOL source
    -- did not validate this value at the data layer, so this
    -- constraint blocks INSERT/UPDATE attempts with invalid values
    -- WITHOUT changing the COBOL application's behavior for valid
    -- 'Y' or 'N' values -- a minimal addition consistent with the AAP
    -- §0.7.3 Minimal Change Clause (defense-in-depth is permitted;
    -- behavior-changing constraints are not).
    constraint chk_cards_active_status check (card_active_status in ('Y', 'N'))
);

-- =============================================================================
-- Secondary index idx_cards_acct_id -- replaces VSAM AIX
-- AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY UPGRADE
-- (app/jcl/CARDFILE.jcl:L83-L92).
-- -----------------------------------------------------------------------------
-- The COBOL VSAM alternate index has the following semantics that map to
-- this PostgreSQL index:
--   - KEYS(11 16)        : 11-byte key at byte offset 16 of the base
--                          cluster record. Byte offset 16 corresponds to
--                          CARD-ACCT-ID PIC 9(11) (after CARD-NUM
--                          PIC X(16) at offset 0..15). -> PostgreSQL
--                          index column: card_acct_id.
--   - NONUNIQUEKEY       : one account can have multiple cards. ->
--                          PostgreSQL index is NOT UNIQUE (CREATE INDEX
--                          without the UNIQUE keyword).
--   - UPGRADE            : the AIX is automatically synchronized with
--                          the base cluster on every base-cluster
--                          update. -> PostgreSQL native behavior: all
--                          indexes are automatically maintained on
--                          every INSERT / UPDATE / DELETE; no explicit
--                          configuration required.
--   - RECORDSIZE(150,150): VSAM AIX record-size attribute, irrelevant
--                          to PostgreSQL B-tree storage.
--   - DEFINE PATH        : the VSAM PATH that links the AIX to the
--                          base cluster. -> PostgreSQL indexes are
--                          directly associated with the base table via
--                          the CREATE INDEX ... ON cards(...) clause;
--                          no separate PATH entity is required.
--   - BLDINDEX           : the VSAM step that builds the initial AIX
--                          contents from the base cluster. -> PostgreSQL
--                          CREATE INDEX populates the B-tree
--                          automatically when the table already has
--                          data (this migration runs against an empty
--                          table, so the index is created empty and
--                          populated incrementally on INSERT).
--
-- Consumers of this index:
--   - CardListService (COBOL COCRDLIC) -- the paged
--     findByCardAcctId(Long acctId, Pageable pageable) derived JPA
--     query method uses this index for efficient by-account lookup
--     (avoiding a full table scan over potentially millions of card
--     rows). Pageable size = 7 matches the COCRDLI.bms 7-row-per-page
--     display.
--   - TransactionPostingService (COBOL CBTRN01C/CBTRN02C) -- looks up
--     all cards associated with an account when applying account-level
--     posting rules.
--   - TransactionAddService (COBOL COTRN02C) -- looks up the cards
--     belonging to an account when authorizing a new transaction.
--   - CardCrossReferenceRepository -- joined with card_xref to resolve
--     the (card, customer, account) triplet for the AccountViewService
--     (COBOL COACTVWC) screen.
-- =============================================================================
create index idx_cards_acct_id on cards (card_acct_id);


-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ cards and via JDBC
-- DatabaseMetaData (used by Backstage / Glue / auditor tooling). Inline
-- COMMENT ON statements make the COBOL provenance, the FILLER omission, the
-- COBOL "EXPIRAION" typo correction, the PCI-DSS CVV warning, the AIX-to-index
-- translation, and the JPA @Version design decision discoverable from the
-- database catalog itself. Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- =============================================================================

comment on table cards is
    'Card master records. Java target for COBOL VSAM cluster '
    'AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS (source: app/cpy/CVACT02Y.cpy, '
    'app/jcl/CARDFILE.jcl, app/catlg/LISTCAT.txt). Foreign-key dependent on '
    'V001 accounts; referenced by V004 card_xref, V005 transactions. Read by '
    'CardListService, CardDetailService, CardFileReaderService, '
    'TransactionPostingService, TransactionAddService, StatementGeneration'
    'Service; updated by CardUpdateService. JPA @Version optimistic locking '
    'via the version column replaces the COBOL before/after image comparison '
    'pattern in COCRDUPC.cbl per AAP §0.6.2 / §0.7.1. The COBOL FILLER '
    'PIC X(59) is OMITTED -- no relational equivalent for fixed-width VSAM '
    'padding. The CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY UPGRADE '
    'alternate index is replaced by the PostgreSQL secondary index '
    'idx_cards_acct_id on card_acct_id (non-unique, automatically '
    'maintained). PCI-DSS scope: contains cardholder data (card_num) and '
    'sensitive authentication data (card_cvv_cd) -- column-level masking '
    'in logs enforced by CloudWatch log filters per AAP §0.6.6; encryption '
    'at rest delegated to RDS KMS CMK.';

comment on column cards.card_num is
    'COBOL: CARD-NUM PIC X(16). VSAM KSDS primary key (RKP=0, KEYLEN=16 '
    'per app/catlg/LISTCAT.txt L202). 16-character card-number string. '
    'Stored as VARCHAR(16) (NOT BIGINT) because card numbers are opaque '
    'identifier strings; leading-zero preservation is required for '
    'parallel-run byte-identical output diff per AAP §0.7.2; Luhn-check / '
    'BIN-range parsing operates on the string form. Maps to Card.@Id '
    '(String) in JPA. PCI-DSS cardholder data per AAP §0.6.6 -- MUST be '
    'masked in application logs by the CloudWatch log filter regex that '
    'detects PAN-like sequences.';

comment on column cards.card_acct_id is
    'COBOL: CARD-ACCT-ID PIC 9(11). 11-digit unsigned numeric account '
    'identifier (range 0..99,999,999,999). Foreign key into accounts '
    '(acct_id). Declared BIGINT (NOT NUMERIC(11)) to match the BIGINT type '
    'of accounts.acct_id in V001; PostgreSQL FK constraints require '
    'binary-coercible types and NUMERIC / BIGINT are NOT directly '
    'compatible. BIGINT (8-byte signed integer) fully contains the COBOL '
    'PIC 9(11) range. Maps to Card.cardAcctId (Long) in JPA. Indexed by '
    'idx_cards_acct_id which replaces the COBOL VSAM CARDDATA.VSAM.AIX '
    'KEYS(11 16) NONUNIQUEKEY UPGRADE alternate index.';

comment on column cards.card_cvv_cd is
    'COBOL: CARD-CVV-CD PIC 9(03). 3-digit Card Verification Value (CVV2 / '
    'CVC2 / CID). Stored as NUMERIC(3) NOT NULL; leading-zero preservation '
    'is native to PostgreSQL NUMERIC. *** PCI-DSS CRITICAL (AAP §0.6.6) '
    '*** This is sensitive authentication data per PCI-DSS v4.0 '
    'Requirement 3.2 and MUST NOT be persisted post-authorization in a '
    'production payment-card environment. CardDemo persists it ONLY '
    'because the COBOL source persists it (CVACT02Y.cpy:L7) and the '
    'Minimal Change Clause forbids removing fields that exist in the COBOL '
    'source. The application MUST NOT log this column under any '
    'circumstance and MUST NOT return it in any API response outside of '
    'card-update flows that explicitly require it. Encryption at rest is '
    'delegated to RDS KMS CMK.';

comment on column cards.card_embossed_name is
    'COBOL: CARD-EMBOSSED-NAME PIC X(50). 50-character cardholder name as '
    'embossed / printed on the physical card. Stored as VARCHAR(50) NOT '
    'NULL. Read by StatementGenerationService (monthly statements) and '
    'CardDetailService (COCRDSL.bms card-detail screen).';

comment on column cards.card_expiration_date is
    'COBOL: CARD-EXPIRAION-DATE PIC X(10) -- note the COBOL spelling typo '
    '"EXPIRAION" on app/cpy/CVACT02Y.cpy:L9. The PostgreSQL column adopts '
    'the corrected spelling per AAP §0.6.2 (consistent with the V001 '
    'acct_expiration_date typo correction). Card expiration date, parsed '
    'from COBOL ISO-8601 string to native DATE via DateValidationService. '
    'Read by TransactionPostingService at validation stage 4 -- reject '
    'code 103 (card expired) preserved verbatim per AAP §0.1.1 / CBTRN02C.';

comment on column cards.card_active_status is
    'COBOL: CARD-ACTIVE-STATUS PIC X(01). 1-character card status flag: '
    '''Y'' = active (transactions may be posted), ''N'' = inactive / '
    'blocked / lost / stolen (transactions rejected). Enforced by '
    'chk_cards_active_status CHECK constraint as a defense-in-depth '
    'improvement over the COBOL implicit assumption (per AAP §0.7.3 -- '
    'minimal additions, no behavior change for valid values). Same '
    'pattern as V001 accounts.acct_active_status.';

comment on column cards.version is
    'Application-added BIGINT for JPA @Version optimistic locking. No '
    'COBOL equivalent. Replaces the before/after image comparison in '
    'COCRDUPC.cbl per AAP §0.6.2 / §0.7.1. Hibernate increments the value '
    'on every save; stale-write detection throws OptimisticLockException, '
    'translated to ConcurrentModificationException (HTTP 409 Conflict) by '
    'GlobalExceptionHandler. NOT NULL DEFAULT 0 so existing legacy rows '
    'loaded via the Glue Spark migration start at version=0.';

comment on index idx_cards_acct_id is
    'Secondary B-tree index on cards.card_acct_id. Replaces COBOL VSAM '
    'alternate index AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX with KEYS(11 16) '
    'NONUNIQUEKEY UPGRADE (app/jcl/CARDFILE.jcl:L83-L92). NON-UNIQUE '
    'because one account can have multiple cards (consistent with the VSAM '
    'NONUNIQUEKEY attribute). Automatically maintained by PostgreSQL on '
    'every INSERT / UPDATE / DELETE -- mirrors the VSAM UPGRADE attribute '
    'semantic. Used by CardRepository.findByCardAcctId(Long acctId, '
    'Pageable pageable) which powers the COCRDLIC card-list service '
    '(COCRDLI.bms 7-row-per-page display).';

