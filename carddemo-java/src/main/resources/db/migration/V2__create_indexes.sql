-- =====================================================================================
-- Flyway Migration V2 -- CardDemo Secondary / Alternate Indexes (PostgreSQL 16)
-- =====================================================================================
-- Purpose : Second versioned migration. Recreates the legacy z/OS VSAM ALTERNATE-INDEX
--           (AIX) access patterns of the AWS CardDemo application as PostgreSQL secondary
--           B-tree indexes, and adds the supporting foreign-key / query indexes that back
--           the Spring Data JPA repository methods. V1__create_schema.sql defines
--           STRUCTURE ONLY (tables, primary keys, foreign keys); THIS file (V2) adds the
--           non-primary indexes; V3__seed_data.sql then loads reference / fixture data.
--
-- Flyway  : The file name encodes version "2" and description "create_indexes"
--           (V<version>__<description>.sql -- single underscore after the version, double
--           underscore before the description). It is auto-discovered through
--           spring.flyway.locations=classpath:db/migration and executed exactly once, in
--           strict version order -- AFTER V1 (the referenced tables and columns must
--           already exist) and BEFORE V3 -- on application startup, BEFORE any @Service or
--           Spring Batch job runs. Once shipped this file is IMMUTABLE: Flyway records its
--           checksum and a later edit would break validation on already-migrated
--           databases, so every object below is given a STABLE, schema-unique name.
--
-- VSAM AIX -> relational index : on z/OS the base VSAM KSDS clusters carried ALTERNATE
--           INDEXes (AIX) defined by IDCAMS (DEFINE ALTERNATEINDEX + DEFINE PATH +
--           BLDINDEX) that let records be reached by a NON-PRIMARY key. In the relational
--           target each such access path becomes an ordinary secondary B-tree index, and
--           the COBOL "READ via PATH" becomes a Spring Data derived query (AAP 0.1.2:
--           "VSAM AIX/PATH -> secondary query methods -> @Query / derived queries";
--           tech-spec L684).
--
-- NON-UNIQUE : every AIX migrated here was defined NONUNIQUEKEY UPGRADE on the mainframe
--           (multiple base records may share one alternate-key value). The PostgreSQL
--           indexes are therefore NON-UNIQUE -- plain CREATE INDEX, never
--           CREATE UNIQUE INDEX. This is mandatory, not stylistic: e.g. many cards map to
--           one account, so card_xref legitimately holds duplicate account_id values that
--           the V3 seed data must be allowed to insert.
--
-- Identifier authority : all table and column names below are taken VERBATIM from
--           V1__create_schema.sql (the single source of truth for the physical schema).
--           In particular the transaction base table is the UNQUOTED identifier
--           `transaction` (a NON-reserved keyword in PostgreSQL, declared unquoted in V1);
--           it is referenced the same way here for consistency.
--
-- Mapping legend (VSAM alternate index  ->  PostgreSQL index) :
--           CXACAIX      (AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX  KEYS(11,25))
--                          -> idx_card_xref_account_id            ON card_xref (account_id)
--           TRANSACT AIX (AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX  KEYS(26,304))
--                          -> idx_transaction_processed_timestamp ON transaction (processed_timestamp)
--
-- Traceability : Source = AWS CardDemo COBOL baseline, commit 27d6c6f. No COBOL / JCL
--           source text is copied here; only the derived relational index structure is
--           expressed. Source artifacts consulted: app/jcl/XREFFILE.jcl,
--           app/jcl/TRANFILE.jcl, app/jcl/CARDFILE.jcl, app/cpy/CVACT03Y.cpy,
--           app/cpy/CVTRA05Y.cpy; catalog cross-check: app/catlg/LISTCAT.txt.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- (1) CXACAIX  --  card_xref alternate index on the owning account id   [MANDATED AIX]
--   VSAM source : AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX
--                 app/jcl/XREFFILE.jcl STEP20 -> DEFINE ALTERNATEINDEX
--                 KEYS(11,25) NONUNIQUEKEY UPGRADE  +  STEP25 DEFINE PATH  +  STEP30 BLDINDEX
--   Copybook    : CVACT03Y (CARD-XREF-RECORD). The alternate key is the 11-byte field at
--                 offset 25 = XREF-ACCT-ID PIC 9(11)
--                 (XREF-CARD-NUM X(16) @0, XREF-CUST-ID 9(09) @16, XREF-ACCT-ID @25).
--                 Cross-checked in LISTCAT: CARDXREF.VSAM.AIX KEYLEN 11, relative key
--                 position (AXRKP) 25.
--   Meaning     : a NON-UNIQUE alternate key on the owning account id; on the mainframe it
--                 served the "find every cross-reference row for an account" (account ->
--                 cards) read path.
--   Target      : NON-UNIQUE B-tree index on card_xref(account_id). Backs
--                 CardCrossReferenceRepository.findByXrefAcctId(Long) -- the account-id
--                 lookup used by COACTVWC, COCRDLIC and COTRN02C (online) and by CBACT03C,
--                 CBTRN02C and CBSTM03A (batch).
-- -------------------------------------------------------------------------------------
CREATE INDEX idx_card_xref_account_id
    ON card_xref (account_id);


-- -------------------------------------------------------------------------------------
-- (2) TRANSACT AIX  --  transaction alternate index on the processed timestamp  [MANDATED AIX]
--   VSAM source : AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX
--                 app/jcl/TRANFILE.jcl STEP20 -> DEFINE ALTERNATEINDEX
--                 KEYS(26,304) NONUNIQUEKEY UPGRADE  +  STEP25 DEFINE PATH  +  STEP30 BLDINDEX
--   Copybook    : CVTRA05Y (TRAN-RECORD). The alternate key is the 26-byte field at
--                 offset 304 = TRAN-PROC-TS PIC X(26). Cross-checked in LISTCAT:
--                 TRANSACT.VSAM.AIX KEYLEN 26, relative key position (AXRKP) 304.
--   Meaning     : a NON-UNIQUE alternate key on the processing timestamp; it gave
--                 chronological / processed-date access to transactions (many transactions
--                 may share one processed timestamp).
--   Target      : NON-UNIQUE B-tree index on transaction(processed_timestamp). The base
--                 table `transaction` is referenced UNQUOTED, exactly as declared in V1.
-- -------------------------------------------------------------------------------------
CREATE INDEX idx_transaction_processed_timestamp
    ON transaction (processed_timestamp);


-- -------------------------------------------------------------------------------------
-- (3) Supporting FK index  --  card by owning account id              [SUPPORTING INDEX]
--   Rationale   : card.account_id is a logical foreign key to account(account_id), and
--                 PostgreSQL does NOT auto-create an index on a foreign-key column. The
--                 CardRepository exposes the derived query findByCardAcctId(Long) that
--                 powers COCRDLIC's account-filtered card browse ("list cards for an
--                 account"); without this index that browse degrades to a sequential scan.
--   Lineage     : this index also preserves the legacy CARDDATA.VSAM.AIX KEYS(11,16)
--                 access path (app/jcl/CARDFILE.jcl STEP40; LISTCAT CARDDATA.VSAM.AIX
--                 KEYLEN 11, AXRKP 16 = CARD-ACCT-ID). AAP 0.4.1 scopes the V2 ALTERNATE-
--                 INDEX deliverables to CXACAIX + TRANSACT AIX only; this card->account
--                 index is added in its capacity as a SUPPORTING foreign-key index that a
--                 documented repository method genuinely requires (per the agent contract's
--                 conditional rule), NOT as a third "alternate index". The V1 card-table
--                 comment likewise anticipates this index being created in V2.
--   Target      : NON-UNIQUE B-tree index on card(account_id) (a card maps to exactly one
--                 account, but one account owns many cards -> non-unique).
-- -------------------------------------------------------------------------------------
CREATE INDEX idx_card_account_id
    ON card (account_id);


-- -------------------------------------------------------------------------------------
-- (4) Supporting query index  --  transaction by card number         [SUPPORTING INDEX]
--   Rationale   : transaction.card_number is a logical foreign key to card(card_number)
--                 and the principal non-primary filter for the transaction list/browse
--                 (COTRN00C TransactionListService) and the detail / add flows (COTRN01C,
--                 COTRN02C). TransactionRepository selects transactions by card number;
--                 this index keeps that access path index-served instead of a table scan.
--   Note        : the transaction primary key (pk_transaction on transaction_id) already
--                 serves the max-transaction-id lookup used for auto-ID generation in
--                 COTRN02C (a reverse / MAX scan of the primary-key B-tree), so no extra
--                 index is added for that path.
--   Target      : NON-UNIQUE B-tree index on transaction(card_number).
-- -------------------------------------------------------------------------------------
CREATE INDEX idx_transaction_card_number
    ON transaction (card_number);


-- =====================================================================================
-- Intentionally NOT created -- documented per the Minimal Change Clause (AAP 0.7.1:
-- "only add indexes a documented repository access path requires; do not over-index"):
--
--   * card_xref(customer_id) -- no repository query selects cross-reference rows by
--     customer id; the only documented xref access paths are by card_number (the primary
--     key) and by account_id (CXACAIX, index #1 above). A foreign key does not itself
--     require an index in PostgreSQL, so this FK column is intentionally left unindexed.
--
--   * transaction(original_timestamp) -- no documented access path selects by the
--     origination timestamp; the processed-timestamp AIX (index #2) plus the primary key
--     cover the documented chronological and keyed access paths.
--
--   * transaction_category_balance(account_id) -- already index-served by the leading
--     column of its composite primary key (account_id, type_code, category_code); a
--     separate single-column index would be redundant under the left-prefix rule.
--
--   * A standalone "CARDDATA alternate index" object -- its card->account access path is
--     provided by supporting index #3 above (idx_card_account_id); it is therefore not
--     migrated as a separate alternate-index deliverable (AAP 0.4.1 scope).
-- =====================================================================================
