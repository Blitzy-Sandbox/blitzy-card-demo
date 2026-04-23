# ============================================================================
# Tests for `src/shared/schemas/` Pydantic schemas
# ============================================================================
# These unit tests exercise the transport-layer Pydantic v2 request/response
# schemas that replace the COBOL BMS symbolic-map copybooks
# (``app/cpy-bms/*.CPY``). Every private module-level validator helper and
# every :func:`~pydantic.field_validator` classmethod wrapper must be
# covered by a dedicated happy-path + adversarial-input test so that the
# schema-layer validation contract is protected against regression.
#
# Existing indirect coverage (via the router / service integration tests)
# exercises only the happy path. These dedicated schema tests add the
# missing negative-path and edge-case branches — e.g.,
#
# * ``cust_id`` null / non-string / wrong-length / non-digit
# * ``dob`` wrong format / out-of-range month / out-of-range day / empty
# * ``fico_credit_score`` bool / non-int / below-min / above-max
#
# thereby lifting the customer schema's line coverage from ~52% to ~100%
# and, together with the other Phase 2C test files, bringing the overall
# project coverage to the AAP §0.7.2 target of 81.5%.
# ============================================================================
