-- =============================================================================
-- Flyway V2 secondary indexes -- CardDemo (legacy VSAM AIX/PATH -> PostgreSQL 16)
-- 3 non-unique indexes derived from the app/jcl DEFINE ALTERNATEINDEX jobs at
-- source commit 27d6c6f. Each legacy AIX is NONUNIQUEKEY, so each index here is
-- non-unique. Runs in strict order immediately after V1__create_schema.sql.
-- =============================================================================

-- AIX AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX (TRANIDX.jcl / TRANFILE.jcl KEYS(26 304) NONUNIQUEKEY)
CREATE INDEX idx_transactions_proc_ts ON transactions (proc_ts);

-- AIX AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX (XREFFILE.jcl KEYS(11 25) NONUNIQUEKEY)
CREATE INDEX idx_card_xref_acct_id ON card_xref (xref_acct_id);

-- AIX AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX (CARDFILE.jcl KEYS(11 16) NONUNIQUEKEY)
CREATE INDEX idx_cards_acct_id ON cards (card_acct_id);
