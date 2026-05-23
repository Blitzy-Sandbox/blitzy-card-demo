-- ============================================================================
-- V2__indexes.sql — Secondary indexes for query performance
-- ============================================================================
--
-- Indexes here back the @Query / derived-query methods on the repository
-- interfaces. Primary keys are already indexed by V1__schema.sql; this script
-- only adds secondary indexes referenced by the IT test suite.
--
-- Naming convention: idx_<table>_<column(s)>
-- ============================================================================

-- card_xref.xref_acct_id — backs CardXrefRepository#findByAccountId (bi-directional lookup)
-- AccountId -> CardXref via index scan (PAN -> CardXref already uses pk_card_xref).
CREATE INDEX idx_card_xref_xref_acct_id ON card_xref (xref_acct_id);

-- cards.card_acct_id — backs CardRepository#findByAccountId(String accountId, Pageable)
CREATE INDEX idx_cards_card_acct_id ON cards (card_acct_id);

-- transactions.card_number — backs TransactionRepository#findByCardNumber(String cardNumber, Pageable)
-- and the JOIN inside TransactionRepository#findByAccountId(@Query).
CREATE INDEX idx_transactions_card_number ON transactions (card_number);

-- transactions.origin_timestamp — backs date-range queries on transactions
CREATE INDEX idx_transactions_origin_timestamp ON transactions (origin_timestamp);

-- transactions.merchant_id — backs aggregate-by-merchant queries used by CBTRN03C report parity
CREATE INDEX idx_transactions_merchant_id ON transactions (merchant_id);

-- security_users.user_type — backs UserSecurityRepository#findByUserType(String, Pageable)
CREATE INDEX idx_security_users_user_type ON security_users (user_type);

-- transaction_category_balances.trancat_acct_id — backs aggregate-by-account queries
CREATE INDEX idx_tcatbal_trancat_acct_id ON transaction_category_balances (trancat_acct_id);
