-- =============================================================================
-- V2__create_indexes.sql  --  CardDemo (Java) alternate indexes  (Flyway V2)
-- =============================================================================
-- Creates the secondary (alternate) indexes that mirror the source VSAM
-- ALTERNATE INDEX (AIX) definitions and back the non-primary-key query methods
-- declared by the Spring Data repositories. Applied AFTER V1 (which declares
-- every primary key / composite key) and BEFORE V3 (seed data).
--
-- The primary and composite primary keys created in V1 already serve all keyed
-- findById reads and the MAX(tran_id) lookup, so those access paths are NOT
-- re-indexed here. Only the three account-id / processing-timestamp alternate
-- keys that have both a source AIX and a repository query method are created.
--
-- Conventions: lowercase, unquoted identifiers; one btree index per mirrored
-- alternate key. "transaction" is a PostgreSQL non-reserved keyword and is
-- valid unquoted, matching the table created in V1.
-- =============================================================================

-- Mirrors the card-data alternate index over the account id.
-- Backs CardRepository.findByCardAcctId(Long) and its paginated variant
-- (the card-list browse).
CREATE INDEX idx_card_acct_id ON card (card_acct_id);

-- Mirrors the card cross-reference (non-unique) alternate index over the
-- account id. Backs CardCrossReferenceRepository.findByXrefAcctId(Long).
CREATE INDEX idx_xref_acct_id ON card_xref (xref_acct_id);

-- Mirrors the transaction (non-unique) alternate index over the processing
-- timestamp. Backs TransactionRepository.findByProcessingDateRange(...) and the
-- paginated transaction-list browse.
CREATE INDEX idx_tran_proc_ts ON transaction (tran_proc_ts);
