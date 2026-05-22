-- =============================================================================
-- Flyway Migration: V003__create_customer.sql
-- Purpose:    Create the customers table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS. This table stores the
--             demographic, address, contact, identity, and credit-rating
--             attributes for every cardholder in the CardDemo system. It
--             is the foundational reference table that all card and
--             cross-reference data depends on -- without a populated
--             customers table, the entire signon-to-statement business
--             flow has no subject to act upon.
--
--             Consumers (Java target services that JOIN against this
--             table or that READ / WRITE customer rows):
--               - AccountViewService (COBOL COACTVWC) -- joins customers
--                 onto accounts via the card_xref table to render the
--                 account-view screen; the 18 customer attributes
--                 (name, address, phone, SSN, govt-id, DOB, FICO, EFT
--                 routing, primary-cardholder indicator) are surfaced
--                 directly into AccountViewDto.
--               - AccountUpdateService (COBOL COACTUPC) -- updates
--                 customer columns (name, address, phone, govt-id, DOB,
--                 EFT account, FICO) inside the same @Transactional
--                 boundary that updates the account row; the cust_id is
--                 fetched via the card_xref join.
--               - CustomerFileReaderService (COBOL CBCUS01C) -- batch
--                 sequential scanner that emits every row in the
--                 customers table for audit / reporting purposes.
--               - StatementGenerationService (COBOL CBSTM03A/CBSTM03B)
--                 -- prints the customer name and mailing address as
--                 the statement header.
--               - CustomerRepository (Spring Data JPA) -- exposes
--                 findById(Long custId) keyed by cust_id; injected into
--                 every service above.
--
-- Source:     app/cpy/CVCUS01Y.cpy   (CUSTOMER-RECORD layout, RECLN 500,
--                                     18 business fields + 168-byte
--                                     trailing FILLER; CUST-DOB-YYYY-MM-DD
--                                     naming variant used as the
--                                     authoritative copybook per AAP
--                                     §0.6.2 / agent_prompt)
--             app/cpy/CUSTREC.cpy    (Identical 500-byte layout; only
--                                     differs from CVCUS01Y in that the
--                                     DOB field is named
--                                     CUST-DOB-YYYYMMDD (no hyphens).
--                                     The PostgreSQL column adopts the
--                                     cleaner CVCUS01Y form
--                                     cust_dob_yyyy_mm_dd.)
--             app/jcl/CUSTFILE.jcl   (IDCAMS DEFINE CLUSTER L43-L59:
--                                     KEYS(9 0), RECORDSIZE(500 500),
--                                     SHAREOPTIONS(2 3), ERASE,
--                                     INDEXED, CYLINDERS(1 5); plus
--                                     REPRO step L64-L72 loading
--                                     CUSTDATA.PS -> KSDS)
--             app/catlg/LISTCAT.txt  (verifies CUSTDATA.VSAM.KSDS
--                                     cluster: KEYLEN=9, RKP=0,
--                                     MAXLRECL=500, AVGLRECL=500,
--                                     INDEXED, SHROPTNS(2,3),
--                                     CISIZE=18432, BUFSPACE=37376)
--
-- AAP Refs:   §0.4.1 (V003 customer; one-to-one mapping of CUSTDATA.KSDS
--                     to the customers relational table),
--             §0.6.2 (VSAM-to-RDS migration strategy; the 9-byte KSDS
--                     primary key maps to NUMERIC(9) PRIMARY KEY on
--                     cust_id; PostgreSQL B-tree index on the PK
--                     provides equality lookups for AccountViewService
--                     and AccountUpdateService),
--             §0.6.6 (PCI-DSS / PII compliance -- cust_ssn is sensitive
--                     PII; encryption at rest delegated to RDS KMS CMK;
--                     Macie scans output S3 buckets continuously for
--                     SSN leakage; application-layer masks SSN in
--                     application logs via logback-spring.xml patterns;
--                     no column-level pgcrypto encryption applied at
--                     the schema level per AAP §0.6.6 architectural
--                     decision),
--             §0.7.1 (refactor discipline -- COBOL semantic preservation
--                     mandates NUMERIC(9) without CHECK constraints on
--                     cust_fico_credit_score and cust_addr_state_cd;
--                     these business rules live in the application layer
--                     ValidationLookupService that ports CSLKPCDY.cpy).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
--             in app/jcl/CUSTFILE.jcl:L46-L59. The IDCAMS REPRO step
--             (app/jcl/CUSTFILE.jcl:L64-L72) that loaded CUSTDATA.PS
--             into the KSDS is NOT replaced by a Flyway seed migration
--             (customer master data is loaded for bulk-fact purposes by
--             an AWS Glue Spark job per AAP §0.6.2; reference rows for
--             local testing are loaded by Testcontainers fixtures).
-- =============================================================================

-- =============================================================================
-- COBOL CUSTOMER-RECORD layout (CVCUS01Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field                PIC clause     PostgreSQL column         Type
--   -------------------------- -------------- ------------------------- ------------
--   CUST-ID                    PIC 9(09)      cust_id                   NUMERIC(9)  (PK)
--   CUST-FIRST-NAME            PIC X(25)      cust_first_name           VARCHAR(25) NN
--   CUST-MIDDLE-NAME           PIC X(25)      cust_middle_name          VARCHAR(25)
--   CUST-LAST-NAME             PIC X(25)      cust_last_name            VARCHAR(25) NN
--   CUST-ADDR-LINE-1           PIC X(50)      cust_addr_line_1          VARCHAR(50) NN
--   CUST-ADDR-LINE-2           PIC X(50)      cust_addr_line_2          VARCHAR(50)
--   CUST-ADDR-LINE-3           PIC X(50)      cust_addr_line_3          VARCHAR(50)
--   CUST-ADDR-STATE-CD         PIC X(02)      cust_addr_state_cd        CHAR(2)     NN
--   CUST-ADDR-COUNTRY-CD       PIC X(03)      cust_addr_country_cd      CHAR(3)     NN
--   CUST-ADDR-ZIP              PIC X(10)      cust_addr_zip             VARCHAR(10) NN
--   CUST-PHONE-NUM-1           PIC X(15)      cust_phone_num_1          VARCHAR(15)
--   CUST-PHONE-NUM-2           PIC X(15)      cust_phone_num_2          VARCHAR(15)
--   CUST-SSN                   PIC 9(09)      cust_ssn                  NUMERIC(9)  NN (PII)
--   CUST-GOVT-ISSUED-ID        PIC X(20)      cust_govt_issued_id       VARCHAR(20)
--   CUST-DOB-YYYY-MM-DD        PIC X(10)      cust_dob_yyyy_mm_dd       DATE        NN
--   CUST-EFT-ACCOUNT-ID        PIC X(10)      cust_eft_account_id       VARCHAR(10)
--   CUST-PRI-CARD-HOLDER-IND   PIC X(01)      cust_pri_card_holder_ind  CHAR(1)     NN
--   CUST-FICO-CREDIT-SCORE     PIC 9(03)      cust_fico_credit_score    NUMERIC(3)  NN
--   FILLER                     PIC X(168)     OMITTED                   --
--
-- COBOL record byte budget (per CVCUS01Y.cpy / LISTCAT MAXLRECL=500):
--     9 + (25*3) + (50*3) + 2 + 3 + 10 + (15*2) + 9 + 20 + 10 + 10 + 1 + 3 + 168
--   = 9 + 75 + 150 + 2 + 3 + 10 + 30 + 9 + 20 + 10 + 10 + 1 + 3 + 168
--   = 500 bytes  (CVCUS01Y verified; matches CUSTFILE.jcl RECORDSIZE(500 500)
--                 and LISTCAT.txt MAXLRECL=500 / AVGLRECL=500).
-- Total PostgreSQL business columns: 18 (FILLER omitted; "NN" annotation
-- above marks the NOT NULL columns).
--
-- The 9-byte VSAM primary key (KEYS(9 0) per IDCAMS DEFINE CLUSTER,
-- KEYLEN=9 / RKP=0 per LISTCAT) maps to a single-column NUMERIC(9)
-- PRIMARY KEY on cust_id. The Java JPA target declares Customer.@Id
-- as Long (validated to 9-digit range at the application layer).
--
-- VSAM-to-PostgreSQL design notes (storage-tier attributes from
-- CUSTFILE.jcl and LISTCAT.txt that have NO PostgreSQL equivalent;
-- PostgreSQL handles storage automatically and the RDS Multi-AZ
-- topology supersedes z/OS VSAM characteristics):
--   - KEYS(9 0) / KEYLEN=9    : single-column NUMERIC(9) primary key
--   - RECORDSIZE(500 500)     : fixed-width 500-byte VSAM record;
--                               PostgreSQL stores rows variably and
--                               omits the FILLER padding entirely
--   - SHAREOPTIONS(2 3)       : VSAM share options replaced by
--                               PostgreSQL MVCC + READ_COMMITTED
--                               isolation (per AAP §0.6.2)
--   - ERASE                   : VSAM secure-erase on DELETE CLUSTER;
--                               PostgreSQL DELETE / TRUNCATE semantics
--                               apply; RDS KMS encryption-at-rest
--                               provides equivalent crypto-shredding
--                               via key rotation
--   - INDEXED                 : KSDS = indexed; PostgreSQL B-tree
--                               provided automatically by PRIMARY KEY
--   - CYLINDERS(1 5)          : z/OS physical extent allocation; not
--                               applicable
--   - CISIZE=18432            : VSAM control-interval size; PostgreSQL
--                               uses 8 KB pages by default (block_size)
--   - BUFSPACE=37376          : VSAM buffer pool sizing; PostgreSQL
--                               uses shared_buffers / effective_cache_size
--                               at the cluster level
--
-- All NOT NULL decisions follow the AAP §0.4.1 conservative-NOT-NULL
-- strategy: every field that has a value in 100% of the golden
-- fixtures (app/data/ASCII/custdata.txt) and that is referenced by
-- the application's business logic is marked NOT NULL. Fields where
-- the COBOL fixture contains all-spaces values in some rows
-- (middle-name, address lines 2/3, secondary phone, govt-issued ID,
-- EFT account ID) are nullable. Because PostgreSQL has no concept of
-- COBOL fixed-width padding, the application layer trims trailing
-- spaces on read; an all-spaces COBOL field that the application
-- treats as "empty" is stored as NULL in PostgreSQL.
-- =============================================================================

create table customers (
    -- CUST-ID PIC 9(09); 9-digit unsigned numeric customer identifier.
    -- VSAM KSDS primary key (RKP=0, KEYLEN=9 per
    -- app/catlg/LISTCAT.txt / app/jcl/CUSTFILE.jcl:L50 "KEYS(9 0)").
    -- Stored as NUMERIC(9) to preserve the COBOL unsigned 9-digit
    -- range (0..999,999,999). Maps to Customer.@Id (Long) in JPA.
    -- Referenced as a foreign key by card_xref.xref_cust_id (FK
    -- added in V004__create_cardxref.sql).
    cust_id                     numeric(9)   not null,

    -- CUST-FIRST-NAME PIC X(25); customer first name. 25-character
    -- fixed-width in COBOL, stored TRIMMED of trailing padding spaces
    -- in PostgreSQL (idiomatic relational storage). NOT NULL because
    -- every customer in the source fixture has a populated first name
    -- and the field is required for statement-header rendering.
    cust_first_name             varchar(25)  not null,

    -- CUST-MIDDLE-NAME PIC X(25); customer middle name. Nullable
    -- because some customers in the source fixture have all-spaces
    -- middle names (no middle name) -- the application layer treats
    -- an all-spaces COBOL field as the absence of a value, which maps
    -- to PostgreSQL NULL.
    cust_middle_name            varchar(25),

    -- CUST-LAST-NAME PIC X(25); customer last name. NOT NULL for the
    -- same reasons as cust_first_name -- required by every consumer
    -- and populated in every fixture row.
    cust_last_name              varchar(25)  not null,

    -- CUST-ADDR-LINE-1 PIC X(50); first line of customer mailing
    -- address (typically street number + street name). NOT NULL --
    -- required for statement mailing and populated in every fixture.
    cust_addr_line_1            varchar(50)  not null,

    -- CUST-ADDR-LINE-2 PIC X(50); second line of mailing address
    -- (typically apt/suite/unit number or PO Box). Nullable -- not
    -- present for every customer (single-family residence without
    -- apartment number leaves this field empty).
    cust_addr_line_2            varchar(50),

    -- CUST-ADDR-LINE-3 PIC X(50); third line of mailing address
    -- (typically city + state + ZIP combined, or international
    -- locality details). Nullable -- present primarily for foreign
    -- addresses or addresses requiring additional locality lines.
    cust_addr_line_3            varchar(50),

    -- CUST-ADDR-STATE-CD PIC X(02); US state or territory abbreviation
    -- (e.g., 'TX', 'CA', 'NY', 'PR', 'VI'). 2-character fixed-width
    -- in COBOL; stored as CHAR(2) to preserve the exact 2-character
    -- semantics. Validation against the authoritative US state /
    -- territory list lives in the application layer
    -- (ValidationLookupService porting CSLKPCDY.cpy per AAP §0.7.1) --
    -- a SQL CHECK constraint here would require maintaining the
    -- state list in two places and is therefore omitted per the
    -- Minimal Change Clause (AAP §0.7.3).
    cust_addr_state_cd          char(2)      not null,

    -- CUST-ADDR-COUNTRY-CD PIC X(03); 3-letter country code (e.g.,
    -- 'USA', 'CAN', 'MEX'). 3-character fixed-width in COBOL; stored
    -- as CHAR(3). Per the COBOL fixture, all rows are 'USA'; the
    -- field exists to support future international customers without
    -- a schema change.
    cust_addr_country_cd        char(3)      not null,

    -- CUST-ADDR-ZIP PIC X(10); customer ZIP / ZIP+4 (US) or postal
    -- code (international). Stored as VARCHAR(10) to accommodate
    -- 5-digit, 5+4 hyphenated US ZIP ('78487-7965'), and shorter
    -- international postal codes without trailing-padding storage.
    -- Combined with cust_addr_state_cd, validated by the
    -- ValidationLookupService NANPA / ZIP-prefix lookup table
    -- (CSLKPCDY.cpy) at the application layer.
    cust_addr_zip               varchar(10)  not null,

    -- CUST-PHONE-NUM-1 PIC X(15); primary phone number, 15-character
    -- fixed-width in COBOL. Stored as VARCHAR(15) to preserve any
    -- formatting characters (parentheses, hyphens, spaces) from the
    -- source fixture without trailing padding. Nullable because the
    -- AAP NOT NULL policy lists this as a nullable field (some
    -- customers in the fixture have no primary phone on file).
    cust_phone_num_1            varchar(15),

    -- CUST-PHONE-NUM-2 PIC X(15); secondary / alternate phone number.
    -- Nullable -- many customers have only one phone on file.
    cust_phone_num_2            varchar(15),

    -- CUST-SSN PIC 9(09); 9-digit US Social Security Number, the
    -- single most sensitive PII column in the entire CardDemo schema.
    -- Stored as NUMERIC(9) (range 000-000-0000 .. 999-99-9999).
    -- NOT NULL because SSN is a regulatory identity-verification
    -- requirement and is populated in every COBOL fixture row.
    --
    -- *** PCI-DSS / PII NOTE ***
    --   1. Encryption at rest is provided by RDS via the customer
    --      KMS CMK (configured in infrastructure/terraform/rds.tf and
    --      attached to the RDS instance / cluster). The DDL itself
    --      does NOT encrypt this column -- encryption is delegated to
    --      the RDS storage layer (transparent data encryption).
    --   2. Encryption in transit is enforced by the
    --      'rds.force_ssl=1' parameter on the RDS parameter group
    --      (per AAP §0.6.6); the JDBC connection string in
    --      application-prod.yml requires sslmode=require.
    --   3. Macie continuously scans S3 buckets for accidental SSN
    --      exposure in export files / Glue ETL outputs (per AAP
    --      §0.6.6); any SSN leak triggers a Macie finding +
    --      CloudWatch alarm.
    --   4. The Java application layer masks SSN in application logs
    --      via Logback patterns in src/main/resources/logback-spring.xml
    --      (per AAP §0.6.6); never log the raw cust_ssn value.
    --   5. Column-level pgcrypto encryption is NOT applied here per
    --      AAP §0.6.6 architectural decision -- encryption is a
    --      storage-layer concern, not a schema-layer concern.
    --   6. Per AAP §0.7.3 refactor discipline, the column is stored
    --      as NUMERIC(9) to preserve COBOL semantics; reformatting
    --      (e.g., to '###-##-####') is an application-layer concern.
    -- See the COMMENT ON COLUMN customers.cust_ssn statement below
    -- for the catalog-discoverable PII annotation.
    cust_ssn                    numeric(9)   not null,

    -- CUST-GOVT-ISSUED-ID PIC X(20); government-issued identifier
    -- (driver's-license number, passport number, state-ID number,
    -- etc.). 20-character fixed-width in COBOL; stored as VARCHAR(20).
    -- Nullable -- not every customer in the fixture has a govt-issued
    -- ID on file. Also sensitive PII (subject to the same masking /
    -- Macie-scanning rules as cust_ssn at the application layer).
    cust_govt_issued_id         varchar(20),

    -- CUST-DOB-YYYY-MM-DD PIC X(10); customer date of birth in ISO
    -- 8601 'YYYY-MM-DD' format inside the COBOL record. NOT NULL --
    -- required for KYC / regulatory identity verification.
    --
    -- The COBOL X(10) representation is parsed to a native PostgreSQL
    -- DATE type via DateValidationService (porting CSUTLDPY.cpy /
    -- CSUTLDTC.cbl) -- this enables native date arithmetic in JPA
    -- (e.g., age calculation, FICO-vintage analysis) without manual
    -- substring parsing.
    --
    -- The CUSTREC.cpy variant of this layout names the field
    -- CUST-DOB-YYYYMMDD (no hyphens); both names refer to the same
    -- 10-byte position in the record. The PostgreSQL column adopts
    -- the cleaner CVCUS01Y form 'cust_dob_yyyy_mm_dd' (per AAP §0.6.2
    -- and the agent_prompt).
    cust_dob_yyyy_mm_dd         date         not null,

    -- CUST-EFT-ACCOUNT-ID PIC X(10); electronic funds transfer (EFT)
    -- account routing identifier used when posting bill payments
    -- (BillPaymentService / COBIL00C). 10-character fixed-width in
    -- COBOL; stored as VARCHAR(10). Nullable -- not every customer
    -- has set up EFT (some customers pay by other means).
    cust_eft_account_id         varchar(10),

    -- CUST-PRI-CARD-HOLDER-IND PIC X(01); primary cardholder
    -- indicator. 'Y' = this customer is the primary cardholder on
    -- their associated account(s); 'N' = secondary / authorized user.
    -- 1-character fixed-width in COBOL; stored as CHAR(1). NOT NULL
    -- (every customer in the fixture has a populated indicator).
    -- Per the Minimal Change Clause (AAP §0.7.3), a SQL CHECK
    -- constraint restricting the value to ('Y','N') is intentionally
    -- omitted -- the COBOL source did not validate this value at the
    -- data layer, so the Java target also does not. Application-layer
    -- validation lives in the Customer JPA entity / DTO.
    cust_pri_card_holder_ind    char(1)      not null,

    -- CUST-FICO-CREDIT-SCORE PIC 9(03); 3-digit FICO credit score.
    -- Stored as NUMERIC(3). NOT NULL -- required for account credit-
    -- limit decisions and disclosure-group lookup.
    --
    -- The CHECK constraint `cust_fico_credit_score BETWEEN 300 AND 850`
    -- restricts the value to the canonical FICO credit-score range as
    -- mandated by the checkpoint contract and AAP §0.7.1 data-integrity
    -- discipline. Although the underlying COBOL PIC 9(03) accepts the
    -- full 000-999 range, the Java target enforces the real-world FICO
    -- range at the schema layer for defense-in-depth: any out-of-range
    -- score would corrupt downstream credit-limit calculations and
    -- disclosure-group lookups (the COBOL source relied on operational
    -- discipline to keep loaded values in range; the relational layer
    -- now enforces this invariant deterministically). The same Java
    -- application-layer validation in the Customer JPA entity / DTO
    -- still applies as a first line of defense before INSERT/UPDATE.
    cust_fico_credit_score      numeric(3)   not null,
    constraint ck_customers_cust_fico_credit_score
        check (cust_fico_credit_score between 300 and 850),

    -- The trailing FILLER PIC X(168) in the COBOL record is OMITTED
    -- here. It is unused padding that brings the COBOL record to its
    -- fixed 500-byte VSAM record length
    -- (9 + 75 + 150 + 2 + 3 + 10 + 30 + 9 + 20 + 10 + 10 + 1 + 3
    --  + 168 = 500). PostgreSQL has no concept of fixed-width records,
    -- so this padding has no relational equivalent and is dropped.

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS
    -- primary key (RKP=0, KEYLEN=9 per LISTCAT.txt). Spring Data
    -- JPA's CustomerRepository uses this as the @Id (entity class:
    -- Customer). No additional secondary indexes are defined here:
    --   - The CUSTDATA.VSAM.KSDS in the source has NO alternate index
    --     (AIX) / PATH per app/catlg/LISTCAT.txt. All COBOL access
    --     to this file is by primary key (random READ on CUST-ID)
    --     or sequential scan (CBCUS01C). The PostgreSQL B-tree on
    --     the PK satisfies both patterns.
    --   - The card_xref join (xref_cust_id -> cust_id) is satisfied
    --     by the PK B-tree on this side and an explicit FK + index
    --     declared in V004__create_cardxref.sql on the other side.
    constraint pk_customers primary key (cust_id)
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ customers and
-- via JDBC DatabaseMetaData (used by Backstage / Glue / auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance and the PII
-- handling rules discoverable from the database catalog itself. Per AAP
-- §0.7.3 refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- The PCI-DSS / PII annotation on cust_ssn is mandated by the agent_prompt
-- and AAP §0.6.6.
-- =============================================================================

comment on table customers is
    'Customer demographic / address / identity / credit-rating records. '
    'Java target for COBOL VSAM cluster AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS '
    '(source: app/cpy/CVCUS01Y.cpy, app/cpy/CUSTREC.cpy, '
    'app/jcl/CUSTFILE.jcl). Read by AccountViewService, '
    'CustomerFileReaderService, StatementGenerationService; updated by '
    'AccountUpdateService. PCI-DSS / PII: contains cust_ssn -- encrypted '
    'at rest via RDS KMS CMK; scanned by Macie; masked in application '
    'logs by logback-spring.xml.';

comment on column customers.cust_id is
    'COBOL: CUST-ID PIC 9(09). VSAM KSDS primary key (RKP=0, KEYLEN=9 '
    'per app/catlg/LISTCAT.txt). 9-digit unsigned numeric customer '
    'identifier (range 0..999,999,999). Maps to Customer.@Id (Long) in '
    'JPA. Referenced by card_xref.xref_cust_id (FK in V004).';

comment on column customers.cust_first_name is
    'COBOL: CUST-FIRST-NAME PIC X(25). Customer first name. Trimmed of '
    'trailing COBOL padding spaces on read. Required for statement '
    'header rendering.';

comment on column customers.cust_middle_name is
    'COBOL: CUST-MIDDLE-NAME PIC X(25). Customer middle name. Nullable '
    '-- all-spaces in the COBOL source maps to NULL in PostgreSQL.';

comment on column customers.cust_last_name is
    'COBOL: CUST-LAST-NAME PIC X(25). Customer last name. Trimmed of '
    'trailing COBOL padding spaces on read. Required for statement '
    'header rendering.';

comment on column customers.cust_addr_line_1 is
    'COBOL: CUST-ADDR-LINE-1 PIC X(50). First line of mailing address '
    '(street number + street name). Required for statement mailing.';

comment on column customers.cust_addr_line_2 is
    'COBOL: CUST-ADDR-LINE-2 PIC X(50). Second line of mailing address '
    '(apt / suite / PO Box). Nullable for single-family residences.';

comment on column customers.cust_addr_line_3 is
    'COBOL: CUST-ADDR-LINE-3 PIC X(50). Third line of mailing address '
    '(additional locality details, foreign address lines). Nullable.';

comment on column customers.cust_addr_state_cd is
    'COBOL: CUST-ADDR-STATE-CD PIC X(02). US state / territory '
    'abbreviation (e.g., ''TX'', ''CA'', ''NY'', ''PR'', ''VI''). '
    'Validated against the CSLKPCDY.cpy lookup table by the application '
    'layer ValidationLookupService -- not enforced as a SQL CHECK '
    'constraint per AAP §0.7.3 Minimal Change Clause.';

comment on column customers.cust_addr_country_cd is
    'COBOL: CUST-ADDR-COUNTRY-CD PIC X(03). 3-letter country code '
    '(e.g., ''USA''). Forward-compatible for future international '
    'customers without a schema change.';

comment on column customers.cust_addr_zip is
    'COBOL: CUST-ADDR-ZIP PIC X(10). Customer ZIP / ZIP+4 (US) or '
    'postal code (international). Combined with cust_addr_state_cd for '
    'NANPA / ZIP-prefix validation by ValidationLookupService.';

comment on column customers.cust_phone_num_1 is
    'COBOL: CUST-PHONE-NUM-1 PIC X(15). Primary phone number. Nullable.';

comment on column customers.cust_phone_num_2 is
    'COBOL: CUST-PHONE-NUM-2 PIC X(15). Secondary / alternate phone '
    'number. Nullable.';

comment on column customers.cust_ssn is
    'PII - Sensitive. Encrypted at rest via RDS KMS CMK. Access '
    'restricted by application-layer authorization. Masked in logs. '
    'COBOL: CUST-SSN PIC 9(09); 9-digit US Social Security Number. '
    'See AAP §0.6.6 for the full PCI-DSS handling matrix (RDS KMS, '
    'Macie scanning, logback masking, no column-level pgcrypto).';

comment on column customers.cust_govt_issued_id is
    'COBOL: CUST-GOVT-ISSUED-ID PIC X(20). Government-issued identifier '
    '(driver''s license, passport, state ID). Nullable. Sensitive PII -- '
    'subject to the same masking / Macie-scanning rules as cust_ssn at '
    'the application layer (per AAP §0.6.6).';

comment on column customers.cust_dob_yyyy_mm_dd is
    'COBOL: CUST-DOB-YYYY-MM-DD PIC X(10) (or CUST-DOB-YYYYMMDD in '
    'CUSTREC.cpy -- same 10-byte position). Customer date of birth, '
    'parsed from the COBOL ISO-8601 string to native DATE via '
    'DateValidationService (CSUTLDPY.cpy / CSUTLDTC.cbl). Required for '
    'KYC / regulatory identity verification.';

comment on column customers.cust_eft_account_id is
    'COBOL: CUST-EFT-ACCOUNT-ID PIC X(10). Electronic funds transfer '
    'routing identifier for bill payments (BillPaymentService / '
    'COBIL00C). Nullable.';

comment on column customers.cust_pri_card_holder_ind is
    'COBOL: CUST-PRI-CARD-HOLDER-IND PIC X(01). Primary cardholder flag '
    '(''Y'' = primary, ''N'' = secondary / authorized user). Application-'
    'layer validation only; no SQL CHECK constraint per AAP §0.7.3 '
    'Minimal Change Clause.';

comment on column customers.cust_fico_credit_score is
    'COBOL: CUST-FICO-CREDIT-SCORE PIC 9(03). 3-digit FICO credit score. '
    'CHECK constraint (ck_customers_cust_fico_credit_score) restricts '
    'the value to the canonical real-world FICO range 300..850 per the '
    'checkpoint data-integrity contract -- although the underlying COBOL '
    'PIC 9(03) accepts the broader 000-999 range, the Java target '
    'enforces the operational invariant deterministically at the schema '
    'layer to protect downstream credit-limit calculations and '
    'disclosure-group lookups. Used by AccountUpdateService and '
    'InterestCalculationService.';
