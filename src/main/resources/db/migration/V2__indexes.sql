-- CardDemo V2 indexes — 3 AIX-derived secondary indexes + 1 composite performance index.
-- Source (read-only, commit 27d6c6f / 7756d895ffeb65f7ea72aaa609e356d9899afcec):
--   app/jcl/CARDFILE.jcl, app/jcl/XREFFILE.jcl, app/jcl/TRANFILE.jcl, app/jcl/TRANIDX.jcl
--   IDCAMS DEFINE ALTERNATEINDEX clauses; cross-checked vs app/catlg/LISTCAT.txt
--   (catalog reports exactly 3 AIX + 3 PATH: CARDDATA, CARDXREF, TRANSACT).
-- Flyway applies this SECOND (after V1__schema.sql). Primary keys are declared inline in V1,
--   so this migration adds ONLY the non-primary (secondary) indexes. Seed data lives in V3.
-- PostgreSQL 16 dialect: plain CREATE INDEX (default B-tree). NO "IF NOT EXISTS" and NO
--   "CONCURRENTLY" — Flyway wraps each migration in a single transaction, and CREATE INDEX
--   CONCURRENTLY cannot run inside a transaction block.
-- Indexes 1-3 are one-per-legacy-AIX. Index 4 (idx_transaction_card_num) is a composite
--   performance index backing the EXISTING by-card transaction-list query path
--   (TransactionRepository.findByTranCardNum → COTRN00C / CT00). It adds NO new feature and is
--   therefore within Gate 7 (which bounds the F-001..F-022 feature set, not the physical indexing
--   strategy); it converts a full sequential scan of the fastest-growing table into an index scan.
-- COBOL AIX KEYS(length offset) are documented per index for bidirectional traceability; the SQL
--   indexes by logical column, not by byte offset.

-- AIX #1: CARDFILE.jcl  DEFINE ALTERNATEINDEX AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX  KEYS(11 16)
--          -> CARD-ACCT-ID (11 bytes) at byte offset 16 of the 150-byte CARD record
--             (offset 16 = after the 16-byte CARD-NUM primary key at offset 0).
--          Replaces the "list all cards for an account" access path (COCRDLIC card list,
--          7-rows/page pagination) and backs the fk_card_account foreign key on card.card_acct_id.
CREATE INDEX idx_card_acct_id ON card (card_acct_id);

-- AIX #2: XREFFILE.jcl  DEFINE ALTERNATEINDEX AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX  KEYS(11,25)
--          -> XREF-ACCT-ID (11 bytes) at byte offset 25 of the card cross-reference record
--             (offset 25 = 16-byte XREF-CARD-NUM primary key + 9-byte XREF-CUST-ID).
--          Replaces account-based cross-reference lookups and backs the fk_xref_account
--          foreign key on card_xref.xref_acct_id.
CREATE INDEX idx_cardxref_acct_id ON card_xref (xref_acct_id);

-- AIX #3: TRANFILE.jcl / TRANIDX.jcl  DEFINE ALTERNATEINDEX AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX  KEYS(26 304)
--          -> TRAN-PROC-TS (26 bytes) at byte offset 304 of the 350-byte TRANSACTION record
--             (offset 304 = 16+2+4+10+100+11+9+50+50+10+16+26, i.e. all fields preceding TRAN-PROC-TS).
--          Replaces processing-timestamp ordered access to posted transactions.
--          (TRANIDX.jcl redefines the same TRANSACT AIX with an identical key; one index suffices.)
CREATE INDEX idx_transaction_proc_ts ON transaction (tran_proc_ts);

-- Composite performance index: TRAN-CARD-NUM (filter) + TRAN-ID (order/tie-break).
--          Backs the EXISTING by-card transaction-list access path
--          (TransactionRepository.findByTranCardNum(String, Pageable) → COTRN00C / CT00), whose
--          Spring Data Page count + data queries otherwise force a full Seq Scan of the
--          transaction table on every page load. The leading tran_card_num column serves the
--          WHERE tran_card_num = ? equality filter and the trailing tran_id column serves the
--          ORDER BY tran_id pagination/tie-break, so the count becomes an index scan and the data
--          query a pure index range scan (no separate sort). This is a query-tuning index for an
--          already-delivered feature, not a scope addition (see the Gate 7 note in the header).
CREATE INDEX idx_transaction_card_num ON transaction (tran_card_num, tran_id);
