-- V2__create_indexes.sql
-- Secondary indexes mapped from VSAM Alternate Index (AIX) definitions
-- Plus additional performance indexes for frequently queried columns
-- Source: aws-samples/carddemo commit 27d6c6f
--
-- VSAM Alternate Indexes (AIX) provide non-primary-key access paths to
-- records in a KSDS dataset. In PostgreSQL, these translate directly to
-- B-tree secondary indexes on the corresponding columns.
--
-- This migration creates:
--   Section 1: VSAM AIX-Mapped Indexes (3 indexes from JCL DEFINE ALTERNATEINDEX)
--     1. card_cross_references.account_id  (XREFFILE.jcl CXACAIX)
--     2. transactions.proc_ts              (TRANFILE.jcl TRANSACT AIX)
--     3. cards.card_acct_id                (CARDFILE.jcl CARDDATA AIX)
--   Section 2: Performance Indexes for COBOL program query patterns
--     4. transactions.card_num             (COTRN00C.cbl browse by card)
--     5. transactions(card_num, orig_ts)   (COTRN00C.cbl ordered browse)
--     6. transaction_category_balances.acct_id (CBACT04C.cbl interest calc)
--     7. disclosure_groups.group_id        (CBACT04C.cbl rate lookup)
--     8. daily_transactions.card_num       (CBTRN02C.cbl batch validation)
--
-- Indexes NOT created (already covered by primary keys):
--   - user_security.usr_id:  PK (usr_id) — COSGN00C.cbl auth lookup is PK scan
--   - accounts.acct_id:      PK (acct_id) — direct keyed reads use PK
--   - customers.cust_id:     PK (cust_id) — direct keyed reads use PK
--   - cards.card_num:        PK (card_num) — direct keyed reads use PK
--   - transactions.tran_id:  PK (tran_id) — direct keyed reads use PK

-- ============================================================================
-- Section 1: VSAM AIX-Mapped Indexes
-- Direct translations of IDCAMS DEFINE ALTERNATEINDEX from JCL provisioning jobs
-- These are MANDATORY — they replicate the exact alternate access paths defined
-- in the original VSAM dataset configuration.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Index 1: card_cross_references.account_id
-- Source: XREFFILE.jcl STEP20 — DEFINE ALTERNATEINDEX
--   NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX)
--   RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
--   KEYS(11,25)  -> 11-byte key at offset 25 = XREF-ACCT-ID (PIC 9(11))
--   NONUNIQUEKEY -> Multiple cards can reference the same account
-- PATH: AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH (CXACAIX)
-- Used by: COACTVWC.cbl (Account View) — finds all cards for an account
--          CardCrossReferenceRepository.findByAccountId()
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_card_xref_account_id
    ON card_cross_references (account_id);

-- ---------------------------------------------------------------------------
-- Index 2: transactions.proc_ts
-- Source: TRANFILE.jcl STEP20 — DEFINE ALTERNATEINDEX
--   NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
--   RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
--   KEYS(26,304) -> 26-byte key at offset 304 = TRAN-PROC-TS (PIC X(26))
--   NONUNIQUEKEY -> Multiple transactions can share a processed timestamp
-- PATH: AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH (CXACAIX)
-- Used by: TRANREPT.jcl + CBTRN03C.cbl — date-range filtered transaction reporting
--          TransactionRepository date-range queries for batch reporting
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transactions_proc_ts
    ON transactions (proc_ts);

-- ---------------------------------------------------------------------------
-- Index 3: cards.card_acct_id
-- Source: CARDFILE.jcl STEP40 — DEFINE ALTERNATEINDEX
--   NAME(AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX)
--   RELATE(AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS)
--   KEYS(11,16) -> 11-byte key at offset 16 = CARD-ACCT-ID (PIC 9(11))
--   NONUNIQUEKEY -> Multiple cards can belong to the same account
-- PATH: AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH (CARDAIX)
-- Used by: COCRDLIC.cbl (Card List) — account-based card lookup
--          CardRepository.findByCardAcctId()
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_cards_acct_id
    ON cards (card_acct_id);

-- ============================================================================
-- Section 2: Performance Indexes
-- Support query patterns observed in COBOL programs that do not have explicit
-- VSAM AIX definitions but benefit from indexed access in PostgreSQL.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Index 4: transactions.card_num
-- Supports paginated transaction browsing by card number.
-- Source pattern: COTRN00C.cbl — Transaction List browse
--   The online program browses TRANSACT by card number using CICS STARTBR
--   and READNEXT to display transactions 10 per page for a given card.
-- Used by: TransactionRepository.findByCardNum() with pagination
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transactions_card_num
    ON transactions (card_num);

-- ---------------------------------------------------------------------------
-- Index 5: transactions (card_num, orig_ts) — composite
-- Supports ordered transaction browsing by card + origination timestamp.
-- Source pattern: COTRN00C.cbl — Transaction List with chronological ordering
--   CICS READNEXT iterates transactions for a card in key order; this
--   composite index enables efficient card-scoped, time-ordered pagination.
-- Used by: TransactionRepository paginated queries with card filter + date sort
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transactions_card_orig_ts
    ON transactions (card_num, orig_ts);

-- ---------------------------------------------------------------------------
-- Index 6: transaction_category_balances.acct_id
-- Supports interest calculation lookups by account.
-- Source pattern: CBACT04C.cbl — Interest Calculation batch
--   The batch program reads all category balances for a given account to
--   compute interest per type/category combination using the formula:
--   (balance * int_rate) / 1200
-- Note: acct_id is the leading column of composite PK (acct_id, type_cd, cat_cd),
--   so the PK index CAN serve queries filtering only by acct_id. This dedicated
--   single-column index provides a smaller, more efficient lookup path for
--   account-only queries during batch interest calculation.
-- Used by: TransactionCategoryBalanceRepository.findByAcctId()
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_tcat_bal_acct_id
    ON transaction_category_balances (acct_id);

-- ---------------------------------------------------------------------------
-- Index 7: disclosure_groups.group_id
-- Supports disclosure group lookup with DEFAULT fallback.
-- Source pattern: CBACT04C.cbl — Interest Calculation batch
--   The batch program looks up interest rates first by the account's group_id,
--   then falls back to 'DEFAULT' group when no account-specific rate is found.
-- Note: group_id is the leading column of composite PK (group_id, type_cd, cat_cd),
--   so the PK index CAN serve queries filtering only by group_id. This dedicated
--   single-column index provides a smaller, more efficient lookup path for
--   group-only queries during the DEFAULT fallback lookup.
-- Used by: DisclosureGroupRepository.findByGroupId()
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_disc_group_group_id
    ON disclosure_groups (group_id);

-- ---------------------------------------------------------------------------
-- Index 8: daily_transactions.card_num
-- Supports batch daily transaction validation by card number.
-- Source pattern: CBTRN02C.cbl — Daily Transaction Posting batch
--   The batch program validates each daily transaction by looking up the
--   associated card via card number (validation stage 1: card existence check).
--   Card number is also used to resolve the account for balance updates.
-- Used by: DailyTransactionRepository queries during batch processing
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_daily_tran_card_num
    ON daily_transactions (card_num);

-- ============================================================================
-- Index Summary
-- ============================================================================
-- Total indexes created: 8
--   VSAM AIX-mapped:  3 (card_cross_references, transactions, cards)
--   Performance:      5 (transactions x2, tcat_bal, disc_group, daily_transactions)
--
-- All index names follow the idx_ prefix convention.
-- All column names match the snake_case naming in V1__create_schema.sql.
-- IF NOT EXISTS ensures idempotent re-execution safety.
-- ============================================================================
