-- =============================================================================
-- AWS CardDemo — Flyway V016: optimistic locking for user_security
-- =============================================================================
--
-- QA CR-14:
--   Concurrent PUT /api/admin/users/{id} calls previously used last-write-wins
--   semantics. Every client received HTTP 200 even when another transaction
--   overwrote its changes. The Java target now maps UserSecurity.version with
--   JPA @Version so Hibernate adds `AND version = ?` to UPDATE predicates and
--   raises OptimisticLockingFailureException for stale writes. The global
--   exception handler translates that conflict to HTTP 409.
--
-- COBOL provenance:
--   COUSR02C performed the user-admin READ UPDATE + REWRITE inside one CICS
--   task against the USRSEC VSAM KSDS. REST clients can issue truly concurrent
--   writes, so the relational target needs an explicit version column to
--   preserve correctness under concurrent access.
-- =============================================================================

ALTER TABLE user_security
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;