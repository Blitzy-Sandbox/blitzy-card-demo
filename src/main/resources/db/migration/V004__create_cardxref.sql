-- =============================================================================
-- Flyway Migration: V004__create_cardxref.sql
-- Purpose:    Create the card_xref table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS plus its alternate index
--             AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX. The card_xref table is the
--             pure cross-reference join table that links cards, customers,
--             and accounts into the 3-way (card, customer, account)
--             relationship that drives every CardDemo business workflow.
--             It is the fourth of the four foundation reference tables
--             (V001 accounts, V002 cards, V003 customers, V004 card_xref);
--             V001/V002/V003 must run BEFORE V004 so that the three FK
--             reference targets (cards(card_num), customers(cust_id),
--             accounts(acct_id)) exist at constraint-creation time. Flyway's
--             strict V<NNN>__ execution order guarantees this ordering.
--
--             *** CRITICALITY NOTE ***
--             The CARDXREF VSAM cluster's alternate index (CXACAIX) on the
--             xref_acct_id column is the SINGLE MOST HEAVILY USED VSAM AIX
--             in the entire COBOL CardDemo system. Multiple online and
--             batch programs depend on the "given an account ID, find all
--             associated card numbers (and the linked customer)" lookup --
--             the AccountViewService (COACTVWC) joins account + customer
--             + card_xref to render the account-inquiry screen, the
--             TransactionAddService (COTRN02C) validates the card -> account
--             -> customer link before posting a new transaction, and the
--             TransactionPostingService (CBTRN01C/CBTRN02C/CBTRN03C) runs
--             a cross-reference lookup as the FIRST stage of its 4-stage
--             validation cascade (XREF / account / credit-limit /
--             card-expiration -- reject codes 100-109 preserved verbatim
--             per AAP §0.1.1). The PostgreSQL secondary index
--             idx_cardxref_acct_id declared at the bottom of this migration
--             is therefore the single most important secondary index in
--             the entire CardDemo schema -- without it, every COACTVWC,
--             COTRN02C, and CBTRN02C invocation would degenerate into a
--             full table scan of card_xref.
--
--             Consumers (Java target services that READ from / WRITE to
--             the card_xref table):
--               - AccountViewService          (COBOL COACTVWC)  -- joins
--                 Account + Customer + CardCrossReference for the
--                 COACTVW.bms account-inquiry screen. Per AAP §0.4.1, the
--                 CardCrossReferenceRepository.findByXrefAcctId(Long acctId)
--                 derived JPA query method uses the idx_cardxref_acct_id
--                 secondary index for efficient by-account lookup.
--               - TransactionAddService       (COBOL COTRN02C)  -- validates
--                 the (card, account, customer) link BEFORE posting a new
--                 transaction; rejects the request if no card_xref row
--                 exists for the supplied card_num + acct_id combination.
--               - TransactionPostingService   (COBOL CBTRN01C/02C/03C) --
--                 Stage 1 of the 4-stage validation cascade is the
--                 cross-reference lookup (reject code 100 if the card is
--                 not associated with the supplied account in card_xref
--                 per AAP §0.4.1 / CBTRN02C; this reject code is preserved
--                 verbatim per the AAP §0.1.1 directive that "error codes
--                 surfaced to downstream consumers must be preserved
--                 verbatim").
--               - XrefFileReaderService       (COBOL CBACT03C)  -- batch
--                 sequential scanner that emits every row in the card_xref
--                 table for audit / reporting / regulatory inquiry
--                 purposes.
--               - CardCrossReferenceRepository (Spring Data JPA) -- exposes
--                 findById(String cardNum) keyed by xref_card_num and the
--                 critical findByXrefAcctId(Long acctId) derived query
--                 method; injected into every service above.
--
-- Source:     app/cpy/CVACT03Y.cpy   (CARD-XREF-RECORD layout, RECLN 50,
--                                     3 business fields + 14-byte trailing
--                                     FILLER; verified L4-L8. This is the
--                                     simplest record in the CardDemo
--                                     schema: 3 columns + padding.)
--             app/jcl/XREFFILE.jcl   (IDCAMS DEFINE CLUSTER L39-L52:
--                                     KEYS(16 0), RECORDSIZE(50 50),
--                                     CYLINDERS(1 5), SHAREOPTIONS(2 3),
--                                     ERASE, INDEXED; plus REPRO step
--                                     L57-L65 loading CARDXREF.PS -> KSDS;
--                                     plus DEFINE ALTERNATEINDEX L69-L82
--                                     KEYS(11,25) NONUNIQUEKEY UPGRADE
--                                     RECORDSIZE(50,50) FREESPACE(10,20);
--                                     plus DEFINE PATH L87-L92 and
--                                     BLDINDEX L96-L102 for AIX-base-
--                                     cluster linkage.)
--             app/catlg/LISTCAT.txt  (VSAM cluster inventory snapshot;
--                                     confirms CARDXREF.VSAM.KSDS
--                                     KEYLEN=16, RKP=0, MAXLRECL=50,
--                                     AVGLRECL=50, INDEXED, SHROPTNS(2,3),
--                                     plus the AIX KEYS(11,25)
--                                     NONUNIQUEKEY UPGRADE attributes.)
--
-- AAP Refs:   §0.4.1 (V004 card_xref; one-to-one mapping of CARDXREF.KSDS
--                     to the card_xref relational table; foreign-key
--                     dependent on V001 accounts, V002 cards, V003
--                     customers. The CARDXREF.VSAM.AIX KEYS(11,25)
--                     NONUNIQUEKEY UPGRADE alternate index translates to
--                     the PostgreSQL secondary index idx_cardxref_acct_id
--                     declared below.),
--             §0.6.2 (VSAM-to-RDS migration strategy -- KEYS(16 0) maps to
--                     VARCHAR(16) PRIMARY KEY on xref_card_num (16-char
--                     card-number string, NOT a numeric type, consistent
--                     with V002 cards.card_num typing); the AIX
--                     KEYS(11,25) NONUNIQUEKEY UPGRADE translates to a
--                     regular (NOT UNIQUE) PostgreSQL B-tree index on
--                     xref_acct_id because one account can have multiple
--                     cards; all three FK constraints use ON DELETE NO
--                     ACTION -- NEVER CASCADE -- to preserve referential
--                     integrity and prevent accidental data loss in this
--                     critical join table.),
--             §0.6.6 (PCI-DSS compliance -- the xref_card_num column
--                     contains CARDHOLDER DATA per PCI-DSS scope (16-byte
--                     PAN-like identifier copied from cards.card_num);
--                     encryption at rest delegated to RDS via the
--                     customer KMS CMK (configured in
--                     infrastructure/terraform/rds.tf); the application
--                     MUST mask xref_card_num in logs (PAN-like regex
--                     enforced by CloudWatch log filters per AAP §0.6.6).),
--             §0.7.1 (refactor discipline -- (a) the table name 'card_xref'
--                     (SINGULAR) is AAP-prescribed and intentional, even
--                     though sibling tables V001 accounts / V002 cards /
--                     V003 customers use PLURAL names; the Java JPA
--                     CardCrossReference entity binds to this singular
--                     table name via an explicit @Table(name = "card_xref")
--                     annotation; (b) all three FKs are NAMED constraints
--                     fk_cardxref_card / fk_cardxref_cust / fk_cardxref_acct
--                     to satisfy the exports schema and to enable
--                     deterministic violation handling in
--                     GlobalExceptionHandler; (c) the secondary index
--                     idx_cardxref_acct_id is explicitly NAMED and
--                     NON-UNIQUE because the VSAM AIX is NONUNIQUEKEY --
--                     one account can have multiple card_xref rows.),
--             §0.7.3 (Minimal Change Clause -- preserve COBOL semantics
--                     exactly; do NOT add CHECK constraints or business-
--                     rule validation that did not exist in the COBOL
--                     source; the FILLER PIC X(14) is OMITTED following
--                     the same pattern used in V001 accounts, V002 cards,
--                     V003 customers).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
--             in app/jcl/XREFFILE.jcl:L39-L52. The IDCAMS DEFINE
--             ALTERNATEINDEX for AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX
--             KEYS(11,25) NONUNIQUEKEY UPGRADE in app/jcl/XREFFILE.jcl:
--             L72-L82 is translated to the PostgreSQL secondary index
--             idx_cardxref_acct_id created at the bottom of this
--             migration. The IDCAMS DEFINE PATH (L90-L92) and BLDINDEX
--             (L100-L102) steps have NO PostgreSQL equivalent --
--             PostgreSQL indexes are automatically synchronized with the
--             base table on every INSERT / UPDATE / DELETE (which mirrors
--             the VSAM UPGRADE attribute semantic). The IDCAMS REPRO step
--             (app/jcl/XREFFILE.jcl:L57-L65) that loaded CARDXREF.PS into
--             the KSDS is NOT replaced by a Flyway seed migration --
--             cross-reference data is loaded for bulk-fact purposes by
--             an AWS Glue Spark job per AAP §0.6.2 (Glue reads ASCII
--             fixtures from S3 and writes to RDS via the Glue PostgreSQL
--             connection); reference rows for local testing are loaded
--             by Testcontainers fixtures and integration-test seed data.
-- =============================================================================

-- =============================================================================
-- COBOL CARD-XREF-RECORD layout (CVACT03Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field              PIC clause       PostgreSQL column         Type
--   ------------------------ ---------------- ------------------------- ------------------
--   XREF-CARD-NUM            PIC X(16)        xref_card_num             VARCHAR(16) (PK,FK)
--   XREF-CUST-ID             PIC 9(09)        xref_cust_id              BIGINT       NN (FK)
--   XREF-ACCT-ID             PIC 9(11)        xref_acct_id              BIGINT       NN (FK)
--   FILLER                   PIC X(14)        OMITTED                   -- (padding)
--
-- COBOL record byte budget (per CVACT03Y.cpy / LISTCAT MAXLRECL=50):
--     16 + 9 + 11 + 14 = 50 bytes (verified; matches XREFFILE.jcl
--                                  RECORDSIZE(50 50) and LISTCAT.txt
--                                  MAXLRECL=50 / AVGLRECL=50).
-- Total PostgreSQL business columns: 3 (3 business fields from copybook
--                                       minus the FILLER). This is the
--                                       SIMPLEST and SMALLEST record in
--                                       the entire CardDemo schema --
--                                       3 columns + 14 bytes of padding.
--
-- VSAM primary-key attributes (per app/jcl/XREFFILE.jcl L43 and
-- app/catlg/LISTCAT.txt):
--   - KEYS(16 0)         : 16-byte key at RKP=0 (byte offset 0). Maps
--                          to the COBOL XREF-CARD-NUM PIC X(16) field.
--                          -> VARCHAR(16) PRIMARY KEY on xref_card_num.
--                          This is the SAME primary-key column type as
--                          cards.card_num in V002 (the FK target). The
--                          16-char card-number string is treated as an
--                          opaque alphanumeric identifier (not a numeric
--                          value); leading-zero preservation is REQUIRED
--                          for byte-identical parallel-run output diff
--                          validation per AAP §0.7.2.
--
-- VSAM alternate-index attributes (per app/jcl/XREFFILE.jcl L72-L82):
--   - KEYS(11,25)        : 11-byte key starting at byte offset 25 of
--                          the base cluster record. Byte offset 25
--                          corresponds to XREF-ACCT-ID PIC 9(11) (after
--                          XREF-CARD-NUM PIC X(16) at offset 0..15 and
--                          XREF-CUST-ID PIC 9(09) at offset 16..24).
--                          -> PostgreSQL index column: xref_acct_id.
--   - NONUNIQUEKEY       : one account can have MULTIPLE cards / card_xref
--                          rows. -> PostgreSQL index is NOT UNIQUE
--                          (CREATE INDEX without the UNIQUE keyword).
--                          The agent-prompt confirms this: "The CICS
--                          XREF AIX was NONUNIQUEKEY UPGRADE: one account
--                          can have multiple cards, so the index is
--                          non-unique."
--   - UPGRADE            : the AIX is automatically synchronized with
--                          the base cluster on every base-cluster
--                          update. -> PostgreSQL native behavior: all
--                          indexes are automatically maintained on
--                          every INSERT / UPDATE / DELETE; no explicit
--                          configuration required.
--   - RECORDSIZE(50,50)  : VSAM AIX record-size attribute, irrelevant
--                          to PostgreSQL B-tree storage.
--   - FREESPACE(10,20)   : VSAM AIX free-space percentages for control
--                          interval / control area, irrelevant to
--                          PostgreSQL (autovacuum and fillfactor handle
--                          equivalent concerns).
--   - DEFINE PATH        : the VSAM PATH that relates the AIX to the
--                          base cluster (XREFFILE.jcl:L90-L92).
--                          -> PostgreSQL indexes are directly associated
--                          with the base table via the CREATE INDEX ...
--                          ON card_xref(...) clause; no separate PATH
--                          entity is required.
--   - BLDINDEX           : the VSAM step that builds the initial AIX
--                          contents from the base cluster
--                          (XREFFILE.jcl:L100-L102). -> PostgreSQL
--                          CREATE INDEX populates the B-tree
--                          automatically when the table already has
--                          data (this migration runs against an empty
--                          table, so the index is created empty and
--                          populated incrementally on INSERT).
--
-- VSAM cluster attributes (storage-tier) from XREFFILE.jcl that have NO
-- PostgreSQL equivalent (PostgreSQL handles storage layout automatically
-- and the RDS Multi-AZ topology supersedes z/OS VSAM characteristics):
--   - CYLINDERS(1 5)         : z/OS physical allocation; not applicable
--   - SHAREOPTIONS(2 3)      : VSAM concurrency; replaced by PostgreSQL
--                              MVCC + JPA @Transactional isolation
--                              (READ_COMMITTED default)
--   - ERASE                  : VSAM data zeroing on DELETE; replaced by
--                              standard SQL DELETE (PostgreSQL's MVCC
--                              tombstone is reclaimed by autovacuum)
--   - INDEXED                : KSDS = indexed; PostgreSQL B-tree on PK
--                              provides the same random-access semantic
--   - VOLUMES(AWSHJ1)        : z/OS storage volume serial; not applicable
--
-- The FILLER PIC X(14) is OMITTED. It is the trailing padding that
-- brings the COBOL record to its fixed 50-byte VSAM record length
-- (16 + 9 + 11 + 14 = 50). PostgreSQL has no concept of fixed-width
-- records, so this padding has no relational equivalent and is dropped
-- per AAP §0.6.2 (consistent with the FILLER-omission pattern used in
-- V001 accounts, V002 cards, V003 customers).
--
-- *** NO version COLUMN ***
-- Unlike V001 accounts and V002 cards, this migration does NOT add an
-- application-side BIGINT version column for JPA @Version optimistic
-- locking. Rationale: the COBOL source does NOT support in-place UPDATE
-- of CARDXREF rows -- the cross-reference is treated as a write-once
-- linkage table that is created at card issuance and deleted at card
-- closure, never updated. The CardCrossReferenceRepository exposes
-- save() (for INSERT) and delete() (for card closure / customer
-- migration scenarios) but does NOT expose an update flow. There is no
-- COBOL EXEC CICS READ UPDATE / REWRITE pattern in any of the source
-- programs (COACTVWC, COTRN02C, CBTRN01C, CBTRN02C, CBTRN03C, CBACT03C)
-- that touches CARDXREF -- those programs READ only. Consequently, the
-- optimistic-locking concern that drove version on accounts and cards
-- does not apply here, and adding it would violate the Minimal Change
-- Clause (AAP §0.7.3).
--
-- *** DATA-TYPE COMPATIBILITY FOR THE FOREIGN KEYS ***
-- xref_cust_id is declared BIGINT (NOT NUMERIC(9)) so that the FOREIGN
-- KEY constraint to customers(cust_id) -- which is BIGINT per
-- V003__create_customer.sql:L179 -- is valid at constraint-creation
-- time. xref_acct_id is declared BIGINT (NOT NUMERIC(11)) so that the
-- FOREIGN KEY constraint to accounts(acct_id) -- which is BIGINT per
-- V001__create_account.sql:L210 -- is valid. PostgreSQL FK constraints
-- require the referencing column type to be binary-coercible to the
-- referenced column type, and NUMERIC / BIGINT are NOT directly
-- compatible (the foreign-key constraint would fail with
-- "foreign key constraint cannot be implemented: incompatible types").
-- BIGINT (signed 8-byte integer, range -2^63..2^63-1) fully contains
-- both the COBOL PIC 9(09) range (0..999,999,999) and PIC 9(11) range
-- (0..99,999,999,999) without truncation. This is the SAME decision
-- documented in V002__create_card.sql:L247-L257 for card_acct_id and
-- in V006__create_tcatbal.sql for trancat_acct_id.
-- =============================================================================



create table card_xref (
    -- XREF-CARD-NUM PIC X(16); the primary VSAM KSDS key (RKP=0,
    -- KEYLEN=16 per app/jcl/XREFFILE.jcl L43 "KEYS(16 0)" and
    -- app/catlg/LISTCAT.txt). 16-character card-number string that
    -- functions as BOTH the PRIMARY KEY of card_xref AND a FOREIGN KEY
    -- to cards.card_num -- one card has exactly one cross-reference row
    -- in card_xref (1:1 cardinality). Spring Data JPA's
    -- CardCrossReferenceRepository uses this as the @Id (entity class:
    -- CardCrossReference, mapping field xrefCardNum : String). Stored
    -- as VARCHAR(16) -- NOT cast to BIGINT -- because:
    --   (a) The FK target cards.card_num is VARCHAR(16) per V002__
    --       create_card.sql:L276; PostgreSQL FK constraints require
    --       the referencing column type to match the referenced
    --       column type, and VARCHAR / BIGINT are NOT compatible;
    --   (b) Card numbers are conventionally treated as opaque
    --       identifier strings (not arithmetic values);
    --   (c) Leading-zero preservation is REQUIRED for byte-identical
    --       parallel-run output diff validation per AAP §0.7.2;
    --   (d) The Java target CardCrossReference.@Id field is declared
    --       as String, matching the COBOL PIC X(16) alphanumeric type.
    -- This column contains CARDHOLDER DATA per PCI-DSS scope (§0.6.6)
    -- and MUST be masked in application logs by the CloudWatch log
    -- filter regex that detects PAN-like sequences.
    xref_card_num              varchar(16)   not null,

    -- XREF-CUST-ID PIC 9(09); 9-digit unsigned numeric customer
    -- identifier that owns the card referenced by xref_card_num. This
    -- is a foreign key into the customers table (V003). Declared BIGINT
    -- (NOT NUMERIC(9)) to match the BIGINT type of customers.cust_id
    -- in V003__create_customer.sql:L179. PostgreSQL FK constraints
    -- require binary-coercible types and NUMERIC / BIGINT are NOT
    -- directly compatible -- attempting to declare this as NUMERIC(9)
    -- would cause the FK constraint to fail at constraint-creation
    -- time with "foreign key constraint cannot be implemented:
    -- incompatible types". BIGINT (signed 8-byte integer, range
    -- -2^63..2^63-1) fully contains the COBOL PIC 9(09) unsigned range
    -- (0..999,999,999). Maps to CardCrossReference.xrefCustId (Long)
    -- in JPA. NOT NULL because every card_xref row links a card to a
    -- specific customer; an unlinked card row would violate the
    -- COBOL invariant that the cross-reference is the authoritative
    -- linkage table.
    xref_cust_id               bigint        not null,

    -- XREF-ACCT-ID PIC 9(11); 11-digit unsigned numeric account
    -- identifier that owns the card referenced by xref_card_num. This
    -- is a foreign key into the accounts table (V001). Declared BIGINT
    -- (NOT NUMERIC(11)) to match the BIGINT type of accounts.acct_id
    -- in V001__create_account.sql:L210; the same NUMERIC vs BIGINT
    -- compatibility constraint as xref_cust_id applies. BIGINT
    -- (signed 8-byte integer) fully contains the COBOL PIC 9(11)
    -- unsigned range (0..99,999,999,999). Maps to CardCrossReference.
    -- xrefAcctId (Long) in JPA. NOT NULL because every card_xref row
    -- links a card to a specific account.
    --
    -- *** SECONDARY INDEX TARGET ***
    -- This column is the target of the secondary index
    -- idx_cardxref_acct_id declared at the bottom of this migration --
    -- the SINGLE MOST CRITICAL secondary index in the entire CardDemo
    -- schema. The index replaces the COBOL VSAM
    -- AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX (KEYS(11,25) NONUNIQUEKEY
    -- UPGRADE, referenced by the CICS name CXACAIX) and supports the
    -- CardCrossReferenceRepository.findByXrefAcctId(Long acctId)
    -- derived JPA query method used by:
    --   - AccountViewService          (COACTVWC)  -- account inquiry
    --   - TransactionAddService       (COTRN02C)  -- card->acct link
    --     validation before posting
    --   - TransactionPostingService   (CBTRN02C)  -- Stage 1 of the
    --     4-stage validation cascade (XREF / account / credit-limit /
    --     card-expiration; reject codes 100-109 preserved verbatim per
    --     AAP §0.1.1)
    -- Without this index, every COACTVWC, COTRN02C, and CBTRN02C
    -- invocation would degenerate into a full table scan of card_xref.
    xref_acct_id               bigint        not null,

    -- The trailing COBOL FILLER PIC X(14) is OMITTED. It is unused
    -- padding that brings the COBOL record to its fixed 50-byte VSAM
    -- record length (16 + 9 + 11 + 14 = 50). PostgreSQL has no concept
    -- of fixed-width records, so this padding has no relational
    -- equivalent and is dropped per AAP §0.6.2 (consistent with the
    -- FILLER-omission pattern used in V001 accounts, V002 cards, V003
    -- customers).

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS
    -- primary key (RKP=0, KEYLEN=16 per app/jcl/XREFFILE.jcl L43
    -- "KEYS(16 0)"). Spring Data JPA's CardCrossReferenceRepository
    -- uses this as the @Id (entity class: CardCrossReference). The
    -- PostgreSQL B-tree on this PK satisfies:
    --   - AccountViewService random read by card_num (resolved via the
    --     account-to-card join, see CardListService usage of
    --     idx_cards_acct_id in V002 + this PK in V004);
    --   - TransactionAddService random read by card_num (COBOL COTRN02C
    --     EXEC CICS READ DATASET('CXREFDAT') RIDFLD(WS-CARD-NUM));
    --   - TransactionPostingService Stage 1 cross-reference lookup
    --     (COBOL CBTRN02C EXEC CICS READ DATASET('CXREFDAT') with
    --     reject code 100 on NOTFND).
    -- The PK name is explicitly chosen as pk_card_xref to mirror the
    -- naming convention used in V001 (pk_accounts), V002 (pk_cards),
    -- V003 (pk_customers).
    constraint pk_card_xref primary key (xref_card_num),

    -- Foreign key constraint -- xref_card_num references cards
    -- (card_num) per AAP §0.6.2. ON DELETE NO ACTION (PostgreSQL
    -- default action, explicitly stated here for clarity) is the
    -- conservative choice mandated by the agent_prompt: "All three FKs
    -- use ON DELETE NO ACTION per AAP §0.6.2 -- never CASCADE -- to
    -- prevent accidental data loss in this critical join table." If
    -- an application attempts to DELETE a cards row that still has a
    -- card_xref row, PostgreSQL raises a foreign-key violation, which
    -- the GlobalExceptionHandler translates to HTTP 409 Conflict. This
    -- forces the application layer to delete the dependent card_xref
    -- row BEFORE deleting the card, preserving the COBOL VSAM cascade-
    -- delete semantic which required explicit programmatic deletion
    -- of each related dataset record. There is NO CASCADE here: silent
    -- cascading deletion of card_xref would lose the audit linkage
    -- between cards / customers / accounts and break the AAP §0.7.2
    -- directive that "audit trail content -- transaction IDs,
    -- timestamps, operator codes, and audit fields -- must continue
    -- to be emitted with the same values and semantics."
    -- The FK name fk_cardxref_card is exposed in the migration's
    -- exports schema (members_exposed) for deterministic constraint-
    -- violation handling in GlobalExceptionHandler.
    constraint fk_cardxref_card foreign key (xref_card_num)
        references cards (card_num) on delete no action,

    -- Foreign key constraint -- xref_cust_id references customers
    -- (cust_id) per AAP §0.6.2. Same ON DELETE NO ACTION rationale as
    -- fk_cardxref_card; deletion of a customer that still has
    -- card_xref rows raises a FK violation, forcing the application
    -- layer to clean up dependent cross-references first. This
    -- preserves the COBOL VSAM semantic where customer deletion was
    -- a multi-dataset operation requiring explicit programmatic
    -- cleanup of CARDXREF (and CARDDAT, ACCTDAT) entries first.
    -- The FK name fk_cardxref_cust is exposed in the migration's
    -- exports schema (members_exposed).
    constraint fk_cardxref_cust foreign key (xref_cust_id)
        references customers (cust_id) on delete no action,

    -- Foreign key constraint -- xref_acct_id references accounts
    -- (acct_id) per AAP §0.6.2. Same ON DELETE NO ACTION rationale as
    -- the other two FKs; deletion of an account that still has
    -- card_xref rows raises a FK violation, forcing the application
    -- layer to clean up dependent cross-references first. The FK name
    -- fk_cardxref_acct is exposed in the migration's exports schema
    -- (members_exposed).
    constraint fk_cardxref_acct foreign key (xref_acct_id)
        references accounts (acct_id) on delete no action
);



-- =============================================================================
-- Secondary index idx_cardxref_acct_id -- replaces VSAM AIX
-- AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX KEYS(11,25) NONUNIQUEKEY UPGRADE
-- (app/jcl/XREFFILE.jcl:L72-L82). Referenced by CICS resource definitions
-- and by COBOL EXEC CICS READ commands under the symbolic name CXACAIX.
--
-- *** THIS IS THE SINGLE MOST CRITICAL SECONDARY INDEX IN THE CARDDEMO
--     SCHEMA -- failure to create it would degenerate every account-view,
--     transaction-add, and transaction-posting workflow into a full table
--     scan of card_xref. The PostgreSQL index name idx_cardxref_acct_id
--     follows the AAP §0.6.2 naming convention; it does NOT need to match
--     the CICS symbolic name "CXACAIX" because PostgreSQL identifies
--     indexes by their own names (the Java JPA layer references the index
--     implicitly via the findByXrefAcctId derived query method, never by
--     index name).
-- -----------------------------------------------------------------------------
-- The COBOL VSAM alternate index has the following semantics that map to
-- this PostgreSQL index:
--   - KEYS(11,25)        : 11-byte key at byte offset 25 of the base
--                          cluster record. Byte offset 25 corresponds to
--                          XREF-ACCT-ID PIC 9(11) (after XREF-CARD-NUM
--                          PIC X(16) at offset 0..15 and XREF-CUST-ID
--                          PIC 9(09) at offset 16..24). -> PostgreSQL
--                          index column: xref_acct_id.
--   - NONUNIQUEKEY       : one account can have MULTIPLE cards / card_xref
--                          rows. -> PostgreSQL index is NOT UNIQUE
--                          (CREATE INDEX without the UNIQUE keyword).
--                          This matches the COBOL real-world semantic:
--                          a single account number commonly maps to 2-4
--                          physical / virtual cards (primary card, joint-
--                          cardholder card, virtual / digital card,
--                          replacement card after reissue).
--   - UPGRADE            : the AIX is automatically synchronized with
--                          the base cluster on every base-cluster
--                          update. -> PostgreSQL native behavior: all
--                          indexes are automatically maintained on
--                          every INSERT / UPDATE / DELETE; no explicit
--                          configuration required.
--   - RECORDSIZE(50,50)  : VSAM AIX record-size attribute, irrelevant
--                          to PostgreSQL B-tree storage.
--   - FREESPACE(10,20)   : VSAM AIX free-space percentages for control
--                          interval (10%) and control area (20%);
--                          irrelevant to PostgreSQL (autovacuum and
--                          fillfactor handle equivalent concerns at the
--                          parameter-group level).
--   - DEFINE PATH        : the VSAM PATH that links the AIX to the base
--                          cluster (app/jcl/XREFFILE.jcl:L90-L92,
--                          AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH).
--                          -> PostgreSQL indexes are directly associated
--                          with the base table via the CREATE INDEX ...
--                          ON card_xref(...) clause; no separate PATH
--                          entity is required.
--   - BLDINDEX           : the VSAM step that builds the initial AIX
--                          contents from the base cluster
--                          (app/jcl/XREFFILE.jcl:L100-L102). -> PostgreSQL
--                          CREATE INDEX populates the B-tree
--                          automatically when the table already has
--                          data (this migration runs against an empty
--                          table, so the index is created empty and
--                          populated incrementally on INSERT as the
--                          AWS Glue Spark load job inserts CARDXREF.PS
--                          rows).
--
-- Consumers of this index (in order of estimated query frequency):
--   - AccountViewService          (COBOL COACTVWC) -- the most frequent
--     online consumer; renders the account-view screen by joining
--     accounts + customers + card_xref on the account ID. The derived
--     JPA query method findByXrefAcctId(Long acctId) drives the join.
--   - TransactionAddService       (COBOL COTRN02C) -- looks up all
--     card_xref rows for an account when authorizing a new transaction;
--     the query is "find all cards associated with this account, then
--     verify the supplied card is one of them" (the OPPOSITE direction
--     of findByXrefCardNum which uses the PK -- this AIX is used when
--     the input is the account, not the card).
--   - TransactionPostingService   (COBOL CBTRN02C) -- Stage 1 of the
--     4-stage validation cascade; rejects a transaction with code 100
--     if no card_xref row exists for the (card_num, acct_id) pair. The
--     by-account lookup is used both for the validation check and for
--     the subsequent "find all cards for this account" enumeration
--     when applying account-level posting rules.
--   - XrefFileReaderService       (COBOL CBACT03C) -- batch sequential
--     scanner; uses the PK B-tree primarily but also benefits from
--     this index when grouped-by-account aggregations are requested.
--
-- The Spring Data JPA derived query name and signature is:
--     List<CardCrossReference> findByXrefAcctId(Long acctId);
-- and is declared on the CardCrossReferenceRepository interface. The
-- index name idx_cardxref_acct_id is referenced indirectly via the
-- query optimizer; PostgreSQL EXPLAIN ANALYZE output will show
-- "Bitmap Index Scan on idx_cardxref_acct_id" for queries on this
-- column.
-- =============================================================================
create index idx_cardxref_acct_id on card_xref (xref_acct_id);



-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ card_xref and via
-- JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling). Inline
-- COMMENT ON statements make the COBOL provenance, the FILLER omission, the
-- BIGINT-over-NUMERIC FK type decision, the AIX-to-index translation, and
-- the absence of a version column discoverable from the database catalog
-- itself. Per AAP §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- The same pattern is used in V001 accounts, V002 cards, V003 customers.
-- =============================================================================

comment on table card_xref is
    'Card cross-reference linkage table -- the 3-way (card, customer, '
    'account) join table powering every CardDemo business transaction. '
    'Java target for COBOL VSAM cluster AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS '
    '(source: app/cpy/CVACT03Y.cpy, app/jcl/XREFFILE.jcl, '
    'app/catlg/LISTCAT.txt). Foreign-key dependent on V001 accounts, V002 '
    'cards, V003 customers. Read by AccountViewService (COACTVWC), '
    'TransactionAddService (COTRN02C), TransactionPostingService '
    '(CBTRN01C/02C/03C), and XrefFileReaderService (CBACT03C); no UPDATE '
    'flow exists in the COBOL source, so no JPA @Version column is added '
    '(consistent with the COBOL invariant that CARDXREF is a write-once / '
    'delete-when-card-closes linkage table). The COBOL FILLER PIC X(14) is '
    'OMITTED -- no relational equivalent for fixed-width VSAM padding. '
    'The CARDXREF.VSAM.AIX KEYS(11,25) NONUNIQUEKEY UPGRADE alternate '
    'index (referenced under the CICS symbolic name CXACAIX) is replaced '
    'by the PostgreSQL secondary index idx_cardxref_acct_id on '
    'xref_acct_id (non-unique, automatically maintained) -- the single '
    'most critical secondary index in the entire CardDemo schema. PCI-DSS '
    'scope: contains cardholder data (xref_card_num); column-level '
    'masking in logs enforced by CloudWatch log filters per AAP §0.6.6; '
    'encryption at rest delegated to RDS KMS CMK. Table name is '
    'SINGULAR (card_xref, not card_xrefs) per AAP §0.6.2 explicit '
    'directive -- the Java JPA CardCrossReference entity binds to this '
    'name via @Table(name = "card_xref").';

comment on column card_xref.xref_card_num is
    'COBOL: XREF-CARD-NUM PIC X(16). VSAM KSDS primary key (RKP=0, '
    'KEYLEN=16 per app/jcl/XREFFILE.jcl L43 "KEYS(16 0)"). 16-character '
    'card-number string. Stored as VARCHAR(16) -- NOT BIGINT -- because '
    '(a) the FK target cards.card_num is VARCHAR(16); (b) card numbers '
    'are opaque identifier strings; (c) leading-zero preservation is '
    'required for byte-identical parallel-run output diff per AAP '
    '§0.7.2; (d) the Java target CardCrossReference.@Id field is String. '
    'This column is BOTH the primary key of card_xref AND a foreign key '
    'to cards (card_num) via fk_cardxref_card -- 1:1 cardinality (one '
    'card has exactly one cross-reference row). PCI-DSS cardholder data '
    'per AAP §0.6.6 -- MUST be masked in application logs by the '
    'CloudWatch log filter regex that detects PAN-like sequences.';

comment on column card_xref.xref_cust_id is
    'COBOL: XREF-CUST-ID PIC 9(09). 9-digit unsigned numeric customer '
    'identifier (range 0..999,999,999). Foreign key into customers '
    '(cust_id) via fk_cardxref_cust. Declared BIGINT (NOT NUMERIC(9)) '
    'to match the BIGINT type of customers.cust_id in V003__create_'
    'customer.sql:L179; PostgreSQL FK constraints require binary-'
    'coercible types and NUMERIC / BIGINT are NOT directly compatible '
    '(the FK constraint would fail at constraint-creation time with '
    '"foreign key constraint cannot be implemented: incompatible '
    'types"). BIGINT (8-byte signed integer) fully contains the COBOL '
    'PIC 9(09) range. Maps to CardCrossReference.xrefCustId (Long) in '
    'JPA. NOT NULL because every card_xref row must link to a customer.';

comment on column card_xref.xref_acct_id is
    'COBOL: XREF-ACCT-ID PIC 9(11). 11-digit unsigned numeric account '
    'identifier (range 0..99,999,999,999). Foreign key into accounts '
    '(acct_id) via fk_cardxref_acct. Declared BIGINT (NOT NUMERIC(11)) '
    'to match the BIGINT type of accounts.acct_id in V001__create_'
    'account.sql:L210; same NUMERIC vs BIGINT FK compatibility '
    'constraint as xref_cust_id. BIGINT (8-byte signed integer) fully '
    'contains the COBOL PIC 9(11) range. Maps to CardCrossReference.'
    'xrefAcctId (Long) in JPA. Indexed by idx_cardxref_acct_id (the '
    'single most critical secondary index in the entire CardDemo '
    'schema) which replaces the COBOL VSAM CARDXREF.VSAM.AIX '
    'KEYS(11,25) NONUNIQUEKEY UPGRADE alternate index, referenced '
    'under the CICS symbolic name CXACAIX in COBOL programs '
    '(COACTVWC, COTRN02C, CBTRN02C). Supports the '
    'CardCrossReferenceRepository.findByXrefAcctId(Long acctId) '
    'derived JPA query method.';

comment on constraint pk_card_xref on card_xref is
    'Primary key on card_xref.xref_card_num. Replaces COBOL VSAM '
    'AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS primary-key index (RKP=0, '
    'KEYLEN=16 per app/jcl/XREFFILE.jcl L43). 1:1 cardinality with '
    'cards -- one card has exactly one cross-reference row.';

comment on constraint fk_cardxref_card on card_xref is
    'Foreign key xref_card_num -> cards (card_num). ON DELETE NO ACTION '
    'per AAP §0.6.2 -- NEVER CASCADE. Deletion of a cards row that '
    'still has a card_xref row raises a FK violation, translated to '
    'HTTP 409 Conflict by GlobalExceptionHandler, forcing the '
    'application layer to delete the dependent card_xref row first. '
    'Preserves the COBOL VSAM multi-dataset cascade-delete semantic '
    'which required explicit programmatic deletion of related dataset '
    'records.';

comment on constraint fk_cardxref_cust on card_xref is
    'Foreign key xref_cust_id -> customers (cust_id). ON DELETE NO '
    'ACTION per AAP §0.6.2 -- NEVER CASCADE. Same deletion-handling '
    'semantic as fk_cardxref_card; deletion of a customer with '
    'outstanding card_xref rows raises a FK violation.';

comment on constraint fk_cardxref_acct on card_xref is
    'Foreign key xref_acct_id -> accounts (acct_id). ON DELETE NO '
    'ACTION per AAP §0.6.2 -- NEVER CASCADE. Same deletion-handling '
    'semantic as fk_cardxref_card; deletion of an account with '
    'outstanding card_xref rows raises a FK violation.';

comment on index idx_cardxref_acct_id is
    'Secondary B-tree index on card_xref.xref_acct_id. Replaces COBOL '
    'VSAM alternate index AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX with '
    'KEYS(11,25) NONUNIQUEKEY UPGRADE (app/jcl/XREFFILE.jcl:L72-L82), '
    'referenced under the CICS symbolic name CXACAIX. NON-UNIQUE '
    'because one account can have multiple card_xref rows (multiple '
    'cards per account). Automatically maintained by PostgreSQL on '
    'every INSERT / UPDATE / DELETE -- mirrors the VSAM UPGRADE '
    'attribute semantic. THE SINGLE MOST CRITICAL SECONDARY INDEX IN '
    'THE ENTIRE CARDDEMO SCHEMA: used by AccountViewService '
    '(COACTVWC), TransactionAddService (COTRN02C), '
    'TransactionPostingService (CBTRN01C/02C/03C Stage 1 validation), '
    'and XrefFileReaderService (CBACT03C). Powers the '
    'CardCrossReferenceRepository.findByXrefAcctId(Long acctId) '
    'derived JPA query method. Without this index, every account-view, '
    'transaction-add, and transaction-posting workflow would degenerate '
    'into a full table scan of card_xref.';

