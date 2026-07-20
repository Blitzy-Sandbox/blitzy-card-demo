-- =====================================================================
-- V2__seed_tracer.sql
-- Seeds EXACTLY ONE account and its ONE card for the tracer slice.
-- Insert ACCOUNT (parent) first, then CARD (child FK -> ACCOUNT.ACCT_ID).
-- Surrogate _SK columns omitted (identity, DB-assigned).
-- Values from app/data/ASCII/acctdata.txt + carddata.txt
-- (linkage confirmed via cardxref.txt). [SRC: COCRDSLC | CARDDAT]
-- =====================================================================

INSERT INTO ACCOUNT (
    ACCT_ID,
    ACCT_ACTIVE_STATUS,
    ACCT_CURR_BAL,
    ACCT_CREDIT_LIMIT,
    ACCT_CASH_CREDIT_LIMIT,
    ACCT_OPEN_DATE,
    ACCT_EXPIRAION_DATE,
    ACCT_REISSUE_DATE,
    ACCT_CURR_CYC_CREDIT,
    ACCT_CURR_CYC_DEBIT,
    ACCT_ADDR_ZIP,
    ACCT_GROUP_ID
) VALUES (
    50,
    'Y',
    492.00,
    6169.00,
    4587.00,
    '2011-04-22',
    '2023-03-09',
    '2023-03-09',
    0.00,
    0.00,
    'A000000000',
    NULL
);

-- CARD_CVV_CD is intentionally NEITHER a column NOR a seed value: the CVV is
-- sensitive card data excluded from the skeleton (finding P7-M16). The tracer
-- read path exposes only the five non-sensitive Card Detail fields.
INSERT INTO CARD (
    CARD_NUM,
    CARD_ACCT_ID,
    CARD_EMBOSSED_NAME,
    CARD_EXPIRAION_DATE,
    CARD_ACTIVE_STATUS
) VALUES (
    '0500024453765740',
    50,
    'Aniya Von',
    '2023-03-09',
    'Y'
);
