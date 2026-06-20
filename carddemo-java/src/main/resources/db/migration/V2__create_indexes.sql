-- =====================================================================================
-- Flyway V2 -- CardDemo alternate indexes (VSAM AIX -> PostgreSQL secondary indexes)
-- =====================================================================================
-- Creates the secondary (non-unique) indexes that mirror the source VSAM alternate
-- indexes (AIX) and back the non-primary-key query methods declared by the Spring Data
-- repositories. Primary keys and composite primary keys are already created in V1 and
-- are therefore not duplicated here. Runs strictly after V1__create_schema.sql and
-- before V3__seed_data.sql. Immutable once applied (Flyway checksum).
-- =====================================================================================

-- Account-id alternate index over the card table; backs
-- CardRepository.findByCardAcctId (card-list browse by owning account).
CREATE INDEX idx_card_acct_id ON card (card_acct_id);

-- Account-id alternate index over the card cross-reference table (CXACAIX); backs
-- CardCrossReferenceRepository.findByXrefAcctId (card/account/customer linkage lookup).
CREATE INDEX idx_xref_acct_id ON card_xref (xref_acct_id);

-- Processing-timestamp alternate index over the transaction table; backs
-- TransactionRepository.findByProcessingDateRange (inclusive processing-date-range scan).
CREATE INDEX idx_tran_proc_ts ON transaction (tran_proc_ts);
