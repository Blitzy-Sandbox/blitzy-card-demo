-- =============================================================================
-- Flyway Migration: V010__create_user_security.sql
-- Purpose:    Create the user_security table -- the JPA-mapped relational
--             equivalent of the COBOL VSAM cluster
--             AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS. This table stores
--             authentication and authorization data for both ADMIN and
--             USER accounts of the CardDemo application. Read by
--             SignonService (COBOL COSGN00C) at signon time and
--             read/written by UserListService / UserAddService /
--             UserUpdateService / UserDeleteService (COBOL COUSR00C..03C)
--             from the admin menu.
--
-- Source:     app/cpy/CSUSR01Y.cpy   (SEC-USER-DATA layout, RECLN 80, 5 fields
--                                     + 23-byte FILLER -- KEYS(8 0) on
--                                     SEC-USR-ID at byte offset 0..7)
--             app/jcl/DUSRSECJ.jcl   (IDCAMS DEFINE CLUSTER L62-L73:
--                                     KEYS(8,0), RECORDSIZE(80,80), REUSE,
--                                     INDEXED, TRACKS(45,15),
--                                     FREESPACE(10,15), CISZ(8192) -- plus
--                                     in-stream load of 10 default users
--                                     via PS staging file + REPRO)
--             app/catlg/LISTCAT.txt  (verifies USRSEC.VSAM.KSDS cluster:
--                                     KEYLEN=8, MAXLRECL=80, AVGLRECL=80,
--                                     RKP=0, SHROPTNS(1,3), CISIZE=8192,
--                                     REC-TOTAL=10)
--
-- AAP Refs:   §0.4.1 (V010 user_security; one-to-one mapping of USRSEC.KSDS
--                     to the user_security relational table),
--             §0.6.2 (VSAM-to-RDS migration strategy; KSDS primary key
--                     becomes JPA @Id; no AIX/PATH exists for USRSEC, so
--                     no secondary index is required),
--             §0.6.6 (PCI-DSS compliance -- credentials hashed at rest;
--                     no plaintext passwords stored in RDS),
--             §0.7.1 (security upgrade: BCrypt hashing replaces plaintext
--                     password storage; explicit user directive).
--
-- Replaces:   IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS in
--             app/jcl/DUSRSECJ.jcl:L62-L73. Seed-data load (REPRO of
--             USRSEC.PS into the KSDS in app/jcl/DUSRSECJ.jcl:L80-L88) is
--             performed by the companion seed migration
--             V015__seed_default_users.sql.
--
-- =============================================================================
-- *** CRITICAL SECURITY UPGRADE -- COBOL X(08) PLAINTEXT -> VARCHAR(60) BCRYPT ***
-- =============================================================================
-- This migration introduces the most significant deliberate-non-parity change
-- in the entire CardDemo schema migration. Per AAP §0.7.1:
--
--   "Plaintext password storage in the source USRSEC file (CSUSR01Y.cpy)
--    must be upgraded to BCrypt hashing in the Java target -- a deliberate
--    security improvement within the scope of PCI-DSS compliance."
--
-- The COBOL source defined SEC-USR-PWD as PIC X(08) -- exactly 8 plaintext
-- characters fixed-width inside the 80-byte SEC-USER-DATA record. The
-- original DUSRSECJ.jcl seeded 'PASSWORDA' (for ADMIN* users) and
-- 'PASSWORDU' (for USER* users) as literal plaintext.
--
-- The PostgreSQL target column sec_usr_pwd is WIDENED to VARCHAR(60) so it
-- can hold a BCrypt hash. BCrypt output is fixed at 60 characters regardless
-- of work factor / strength:
--
--    $2a$<cost>$<22-char-salt><31-char-hash> = 7 + 22 + 31 = 60 characters
--
-- The Spring Security BCryptPasswordEncoder (per
-- src/main/java/com/awsm2/carddemo/security/BCryptPasswordEncoderBean.java)
-- ALWAYS produces a 60-char encoded value; storing fewer characters would
-- silently truncate the hash and break authentication.
--
-- No plaintext password is ever stored in this column. The companion seed
-- migration V015 embeds pre-computed BCrypt(v2b, strength=12) hashes derived
-- offline from the original COBOL plaintext defaults ('PASSWORDA' /
-- 'PASSWORDU'). Default passwords MUST BE ROTATED ON FIRST LOGIN in
-- production (application-layer enforcement; outside the SQL migration's
-- scope).
-- =============================================================================

-- =============================================================================
-- COBOL SEC-USER-DATA layout (CSUSR01Y.cpy) -> PostgreSQL column mapping
-- -----------------------------------------------------------------------------
--   COBOL field           PIC clause      PostgreSQL column   Type
--   --------------------- --------------- ------------------- ----------------
--   SEC-USR-ID            PIC X(08)       sec_usr_id          VARCHAR(8)  (PK)
--   SEC-USR-FNAME         PIC X(20)       sec_usr_fname       VARCHAR(20)
--   SEC-USR-LNAME         PIC X(20)       sec_usr_lname       VARCHAR(20)
--   SEC-USR-PWD           PIC X(08)       sec_usr_pwd         VARCHAR(60) [WIDENED]
--   SEC-USR-TYPE          PIC X(01)       sec_usr_type        CHAR(1) CHECK ('A','U')
--   SEC-USR-FILLER        PIC X(23)       OMITTED             --
--
-- Total COBOL record length: 8 + 20 + 20 + 8 + 1 + 23 = 80 bytes (per LISTCAT).
-- Total PostgreSQL relevant columns: 5 (FILLER omitted).
-- VSAM key position (RKP=0) and key length (KEYLEN=8) map to a
-- VARCHAR(8) PRIMARY KEY on sec_usr_id.
--
-- Storage-tier attributes from DUSRSECJ.jcl that have NO PostgreSQL equivalent
-- (PostgreSQL handles storage layout automatically and the RDS Multi-AZ
-- topology supersedes z/OS VSAM characteristics):
--   - REUSE                  : storage reset on DEFINE; replaced by
--                              Flyway-managed schema lifecycle (clean+migrate
--                              in lower environments)
--   - INDEXED                : KSDS = indexed; PostgreSQL B-tree on PK
--   - TRACKS(45,15)          : z/OS physical allocation; not applicable
--   - FREESPACE(10,15)       : VSAM CI/CA free-space; PostgreSQL uses fillfactor
--   - CISZ(8192)             : VSAM control-interval size; PostgreSQL uses
--                              8 KB pages by default (block_size)
--   - SHROPTNS(1,3)          : VSAM share options; replaced by PostgreSQL
--                              concurrency control + JPA transactions
--                              (READ_COMMITTED default isolation)
--
-- All five business columns are NOT NULL because the COBOL fixed-width record
-- has no concept of NULL: every byte is always present (padded with spaces if
-- the value is empty). Empty-string is permitted only for sec_usr_fname /
-- sec_usr_lname (display-only fields); the application layer trims trailing
-- COBOL padding spaces on read.
-- =============================================================================

create table user_security (
    -- SEC-USR-ID PIC X(08); the primary KSDS key (RKP=0, KEYLEN=8 per
    -- LISTCAT). 8-character alphanumeric user identifier such as
    -- 'ADMIN001' or 'USER0001'. Used as the lookup key at signon
    -- (SignonService / COSGN00C) and as the foreign reference from the
    -- admin-menu user-management flows (COUSR00C..03C).
    sec_usr_id      varchar(8)   not null,

    -- SEC-USR-FNAME PIC X(20); user first name -- 20-character fixed-width
    -- in COBOL, stored TRIMMED of trailing padding spaces in PostgreSQL
    -- (idiomatic relational storage). Display-only field; not used for
    -- authentication or authorization decisions.
    sec_usr_fname   varchar(20)  not null,

    -- SEC-USR-LNAME PIC X(20); user last name -- 20-character fixed-width
    -- in COBOL, stored TRIMMED of trailing padding spaces in PostgreSQL.
    -- Display-only field.
    sec_usr_lname   varchar(20)  not null,

    -- SEC-USR-PWD PIC X(08) -> WIDENED to VARCHAR(60); per AAP §0.7.1
    -- security upgrade. The original COBOL field stored 8-character
    -- plaintext passwords; the PostgreSQL column stores BCrypt(v2b,
    -- strength=12) hashes. BCrypt output is fixed at 60 characters
    -- regardless of work factor:
    --     $2[abxy]$<cost>$<22-char-salt><31-char-hash> = 60 chars total.
    -- Spring Security's BCryptPasswordEncoder (configured in
    -- BCryptPasswordEncoderBean.java) produces this exact length. NEVER
    -- store plaintext here. NEVER use a smaller VARCHAR (truncation would
    -- silently break authentication).
    sec_usr_pwd     varchar(60)  not null,

    -- SEC-USR-TYPE PIC X(01); role discriminator. The COBOL business rule
    -- (enforced implicitly in COSGN00C.cbl signon routing) is that only
    -- two values are valid:
    --     'A' = ADMIN -- routes to the admin menu (COADM01C)
    --     'U' = USER  -- routes to the main user menu (COMEN01C)
    -- A CHECK constraint is added below for defense-in-depth: COBOL
    -- programs never validated SEC-USR-TYPE at the data layer, but
    -- corrupted or unknown values would silently break signon routing.
    -- PostgreSQL rejects INSERT/UPDATE attempts that violate the check,
    -- catching data-quality issues at write time. This is a *minimal*
    -- constraint (defense-in-depth) and does NOT change the COBOL
    -- application's behavior for valid 'A' or 'U' values -- it only
    -- prevents the storage of invalid values.
    sec_usr_type    char(1)      not null,

    -- The COBOL SEC-USR-FILLER PIC X(23) is OMITTED. It is unused trailing
    -- padding that brings the COBOL record to its 80-byte VSAM record
    -- length (8 + 20 + 20 + 8 + 1 + 23 = 80). PostgreSQL has no concept of
    -- fixed-width records, so this padding has no relational equivalent.

    -- Primary key constraint -- one-to-one with the COBOL VSAM KSDS key
    -- (RKP=0, KEYLEN=8). Spring Data JPA's UserSecurityRepository uses
    -- this as the @Id (entity class: UserSecurity).
    constraint pk_user_security primary key (sec_usr_id),

    -- CHECK constraint enforcing valid role discriminator values.
    -- Per AAP §0.7.1 minimal change clause, this is a defense-in-depth
    -- improvement that does NOT alter COBOL application behavior for valid
    -- 'A' or 'U' values. It only blocks invalid writes.
    constraint chk_user_security_type check (sec_usr_type in ('A', 'U'))
);

-- =============================================================================
-- PostgreSQL COMMENT metadata -- discoverable via psql \d+ user_security and
-- via JDBC DatabaseMetaData (used by Backstage/Glue/auditor tooling).
-- Inline COMMENT ON statements make the COBOL provenance and security
-- design decisions discoverable from the database itself. Per AAP §0.7.3
-- refactor discipline:
--     "Inline traceability comments: every translated paragraph carries a
--      // COBOL: <PROGRAM>:<PARAGRAPH> comment."
-- =============================================================================

comment on table user_security is
    'User authentication/authorization records. Java target for COBOL VSAM '
    'cluster AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS (source: app/cpy/CSUSR01Y.cpy, '
    'app/jcl/DUSRSECJ.jcl). Read by SignonService at signon and by '
    'UserListService / UserAddService / UserUpdateService / UserDeleteService '
    'from the admin menu (COBOL COUSR00C..COUSR03C).';

comment on column user_security.sec_usr_id is
    'COBOL: SEC-USR-ID PIC X(08). VSAM KSDS primary key (RKP=0, KEYLEN=8 '
    'per app/catlg/LISTCAT.txt). 8-character alphanumeric user identifier '
    '(e.g., ''ADMIN001'', ''USER0001''). Maps to UserSecurity.@Id in JPA.';

comment on column user_security.sec_usr_fname is
    'COBOL: SEC-USR-FNAME PIC X(20). User first name. Display-only -- not '
    'used for authentication or authorization decisions. Trimmed of '
    'trailing COBOL padding spaces on read.';

comment on column user_security.sec_usr_lname is
    'COBOL: SEC-USR-LNAME PIC X(20). User last name. Display-only -- not '
    'used for authentication or authorization decisions. Trimmed of '
    'trailing COBOL padding spaces on read.';

comment on column user_security.sec_usr_pwd is
    'BCrypt hash (strength 12) per AAP §0.7.1 security upgrade. COBOL '
    'source field was SEC-USR-PWD PIC X(08) -- 8-character plaintext. '
    'PostgreSQL column is widened to VARCHAR(60) to hold BCrypt output '
    '($2[abxy]$<cost>$<22-char-salt><31-char-hash> = 60 chars). NEVER '
    'store plaintext here; NEVER reduce the column width below 60. '
    'Generated by Spring Security BCryptPasswordEncoder.';

comment on column user_security.sec_usr_type is
    'COBOL: SEC-USR-TYPE PIC X(01). Role discriminator: ''A''=ADMIN '
    '(routes to COADM01C admin menu), ''U''=USER (routes to COMEN01C main '
    'menu). Enforced by chk_user_security_type CHECK constraint as a '
    'defense-in-depth improvement over the COBOL implicit assumption. '
    'Drives Spring Security role assignment in SignonService.';
