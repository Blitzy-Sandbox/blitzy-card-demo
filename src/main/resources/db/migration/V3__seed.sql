-- ============================================================================
-- V3__seed.sql — Reference data seed
-- ============================================================================
--
-- Source-of-truth fixtures: app/data/ASCII/{trantype,trancatg,discgrp}.txt
-- Counts: 7 transaction_types + 18 transaction_categories + 51 discount_groups
--
-- Trailing spaces on CHAR(N) columns are significant — DEFAULT and ZEROAPR
-- sentinels are 7 chars + 3 trailing spaces = 10 chars to satisfy CHAR(10)
-- findById parity per AAP §0.5.1 and the DiscountGroupRepositoryIT contract.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. transaction_types (7 rows from app/data/ASCII/trantype.txt)
-- ---------------------------------------------------------------------------
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('01', 'Purchase');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('02', 'Payment');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('03', 'Credit');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('04', 'Authorization');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('05', 'Refund');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('06', 'Reversal');
INSERT INTO transaction_types (tran_type, tran_type_desc) VALUES ('07', 'Adjustment');

-- ---------------------------------------------------------------------------
-- 2. transaction_categories (18 rows from app/data/ASCII/trancatg.txt)
-- ---------------------------------------------------------------------------
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('01', 1, 'Regular Sales Draft');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('01', 2, 'Regular Cash Advance');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('01', 3, 'Convenience Check Debit');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('01', 4, 'ATM Cash Advance');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('01', 5, 'Interest Amount');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('02', 1, 'Cash payment');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('02', 2, 'Electronic payment');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('02', 3, 'Check payment');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('03', 1, 'Credit to Account');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('03', 2, 'Credit to Purchase balance');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('03', 3, 'Credit to Cash balance');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('04', 1, 'Zero dollar authorization');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('04', 2, 'Online purchase authorization');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('04', 3, 'Travel booking authorization');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('05', 1, 'Refund credit');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('06', 1, 'Fraud reversal');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('06', 2, 'Non-fraud reversal');
INSERT INTO transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES ('07', 1, 'Sales draft credit adjustment');

-- ---------------------------------------------------------------------------
-- 3. discount_groups (51 rows from app/data/ASCII/discgrp.txt)
-- A000000000 (17 rows) + 'DEFAULT   ' (17 rows) + 'ZEROAPR   ' (17 rows)
-- Group IDs preserve CHAR(10) trailing-space padding for DEFAULT/ZEROAPR.
-- ---------------------------------------------------------------------------
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '01', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '01', 2, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '01', 3, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '01', 4, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '02', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '02', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '02', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '03', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '03', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '03', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '04', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '04', 2, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '04', 3, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '05', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '06', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '06', 2, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('A000000000', '07', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '01', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '01', 2, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '01', 3, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '01', 4, 25.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '02', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '02', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '02', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '03', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '03', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '03', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '04', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '04', 2, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '04', 3, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '05', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '06', 1, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '06', 2, 15.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('DEFAULT   ', '07', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '01', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '01', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '01', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '01', 4, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '02', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '02', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '02', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '03', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '03', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '03', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '04', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '04', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '04', 3, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '05', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '06', 1, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '06', 2, 0.00);
INSERT INTO discount_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES ('ZEROAPR   ', '07', 1, 0.00);
