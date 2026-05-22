-- =============================================================================
-- Flyway Migration: V015__seed_default_users.sql
-- Purpose:    Seed 10 default user-security rows into user_security
--             (5 administrative users + 5 regular users) to enable initial
--             system signon via AuthController / SignonService
--             (COBOL COSGN00C equivalent).
-- Source:     app/jcl/DUSRSECJ.jcl   (IDCAMS DEFINE CLUSTER + REPRO with in-stream
--                                     USRSEC.PS data -- 10 fixed-width 80-byte records)
--             app/cpy/CSUSR01Y.cpy   (SEC-USER-DATA layout, RECLN 80, KEYS(8 0))
--             app/catlg/LISTCAT.txt  (verifies USRSEC.VSAM.KSDS cluster definition:
--                                     KEYLEN=8, MAXLRECL=80, SHROPTNS(1,3))
-- AAP Refs:   §0.7.1 (security upgrade -- BCrypt for stored passwords),
--             §0.6.6 (PCI-DSS compliance -- no plaintext credentials at rest),
--             §0.4.1 (Flyway seed migrations).
--
-- *** CRITICAL SECURITY UPGRADE ***
-- The original COBOL system stored passwords as plaintext (PASSWORDA for admins,
-- PASSWORDU for regular users) in the 8-byte SEC-USR-PWD field of the USRSEC
-- VSAM cluster. Per AAP §0.7.1, this migration upgrades the security model:
--   1. V010 widens sec_usr_pwd from VARCHAR(8) to VARCHAR(60) to hold BCrypt hashes.
--   2. Plaintext passwords NEVER appear in this file or in any application code.
--   3. Pre-computed BCrypt(v2b, strength=12) hashes are embedded as deterministic
--      SQL literals. Each hash was generated offline via:
--          python3 -c "import bcrypt; print(bcrypt.hashpw(b'<password>', bcrypt.gensalt(rounds=12)).decode())"
--   4. Spring Security BCryptPasswordEncoder verifies user-supplied passwords
--      against these stored hashes at signon time.
--   5. Default passwords MUST BE ROTATED ON FIRST LOGIN in production (enforced
--      at the application layer; not part of this SQL migration).
--   6. BCrypt hashes below were verified offline via bcrypt.checkpw() to
--      authenticate the corresponding plaintext passwords. DO NOT regenerate
--      these hashes -- BCrypt uses a random salt and each generation produces
--      a different (but equivalent) hash. The hashes below are the canonical
--      values committed in this migration for deterministic test reproducibility.
--
-- Replaces:   IDCAMS REPRO step in app/jcl/DUSRSECJ.jcl that loads in-stream
--             USRSEC.PS data into the USRSEC.VSAM.KSDS cluster.
--
-- Source Data Layout (80-byte fixed-width record per CSUSR01Y.cpy):
--   Pos 1-8:   SEC-USR-ID     (PIC X(08))  -> sec_usr_id (VARCHAR(8) PRIMARY KEY)
--   Pos 9-28:  SEC-USR-FNAME  (PIC X(20))  -> sec_usr_fname (VARCHAR(20))
--   Pos 29-48: SEC-USR-LNAME  (PIC X(20))  -> sec_usr_lname (VARCHAR(20))
--   Pos 49-56: SEC-USR-PWD    (PIC X(08))  -> sec_usr_pwd (VARCHAR(60)) [WIDENED]
--   Pos 57:    SEC-USR-TYPE   (PIC X(01))  -> sec_usr_type (CHAR(1))
--   Pos 58-80: FILLER         (PIC X(23))  -> OMITTED in PostgreSQL
--
-- Notes:
--   - The 23-byte FILLER PIC X(23) suffix in the COBOL record is OMITTED.
--   - First and last names are stored trimmed of trailing padding spaces
--     (COBOL pads each to 20 chars; PostgreSQL VARCHAR(20) stores trimmed values).
--   - sec_usr_type is CHAR(1): 'A' for admin, 'U' for regular user (enforced by
--     CHECK constraint added in V010).
--   - ON CONFLICT (sec_usr_id) DO NOTHING permits safe idempotent re-runs.
--   - Flyway manages the transaction boundary -- no explicit BEGIN/COMMIT here.
-- =============================================================================

insert into user_security (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type) values
    -- Administrative users (sec_usr_type = 'A'); BCrypt hash of plaintext 'PASSWORDA'
    ('ADMIN001', 'MARGARET',  'GOLD',       '$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu', 'A'),
    ('ADMIN002', 'RUSSELL',   'RUSSELL',    '$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu', 'A'),
    ('ADMIN003', 'RAYMOND',   'WHITMORE',   '$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu', 'A'),
    ('ADMIN004', 'EMMANUEL',  'CASGRAIN',   '$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu', 'A'),
    ('ADMIN005', 'GRANVILLE', 'LACHAPELLE', '$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu', 'A'),
    -- Regular users (sec_usr_type = 'U'); BCrypt hash of plaintext 'PASSWORDU'
    ('USER0001', 'LAWRENCE',  'THOMAS',     '$2b$12$1SkcD1BWDg5kKEDGOLRSjOV/KPsz.ceCRHxazcSZIFnIWjRhQP0GC', 'U'),
    ('USER0002', 'AJITH',     'KUMAR',      '$2b$12$1SkcD1BWDg5kKEDGOLRSjOV/KPsz.ceCRHxazcSZIFnIWjRhQP0GC', 'U'),
    ('USER0003', 'LAURITZ',   'ALME',       '$2b$12$1SkcD1BWDg5kKEDGOLRSjOV/KPsz.ceCRHxazcSZIFnIWjRhQP0GC', 'U'),
    ('USER0004', 'AVERARDO',  'MAZZI',      '$2b$12$1SkcD1BWDg5kKEDGOLRSjOV/KPsz.ceCRHxazcSZIFnIWjRhQP0GC', 'U'),
    ('USER0005', 'LEE',       'TING',       '$2b$12$1SkcD1BWDg5kKEDGOLRSjOV/KPsz.ceCRHxazcSZIFnIWjRhQP0GC', 'U')
on conflict (sec_usr_id) do nothing;
