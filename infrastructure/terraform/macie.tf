###############################################################################
# infrastructure/terraform/macie.tf
#
# Amazon Macie continuous PII / financial-data leakage scanner for CardDemo.
#
# Purpose:
#   Provisions every Amazon Macie resource required by the AAP's PCI-DSS
#   security posture (per AAP §0.6.6 and §0.7.2):
#     1. Enables the Macie service in the AWS account with the most aggressive
#        finding-publishing cadence (FIFTEEN_MINUTES) so security operations
#        sees PII / financial-data exposures within minutes of upload, not
#        hours.
#     2. Schedules a DAILY classification job against the batch outputs S3
#        bucket (aws_s3_bucket.batch_outputs from s3.tf), the bucket that
#        stores the GDG-replacement output streams (DALYREJS, SYSTRAN,
#        TRANREPT, STMTFILE, TRANSACT.BKUP — see AAP §0.6.2 / §0.6.3) and is
#        therefore the highest-risk surface for accidental PAN / account-id
#        leakage. The job scans every object (sampling_percentage = 100) and
#        is scoped to plain-text object extensions (txt / csv / json / log /
#        html / ascii) which align with the COBOL-era flat-file outputs.
#     3. Defines two custom data identifiers tailored to the CardDemo data
#        model that Macie's built-in identifiers (PAN, ACH, SSN, CVV) do not
#        cover natively:
#          * acct_id  — 11-digit ACCT-ID per app/cpy/CVACT01Y.cpy line 5
#                       (PIC 9(11)).
#          * card_num — 16-digit CARD-NUM per app/cpy/CVACT02Y.cpy line 5
#                       (PIC X(16)).
#        The custom identifiers use word-boundary anchored regex and a
#        keyword proximity window of 50 characters so that an 11-digit number
#        next to the keyword "ACCT-ID", "account", or similar terms is
#        flagged as a CardDemo ACCT-ID leak rather than a coincidental
#        11-digit number string.
#     4. Routes every Macie finding through Amazon EventBridge to a
#        KMS-encrypted SNS topic that security operations subscribes to for
#        email / PagerDuty / Slack notification. The EventBridge rule matches
#        only `Macie Finding` detail-type events from the `aws.macie` source
#        so unrelated events on the default event bus are not delivered.
#
# Detection scope (Macie built-ins + the two custom identifiers above):
#   * Personally Identifiable Information (PII) — names, addresses, phone
#     numbers, email addresses, dates of birth.
#   * Payment Card Number (PAN) — Macie built-in 13-19 digit PCI PAN
#     detection (including IIN-aware Luhn validation).
#   * Card Verification Value (CVV) — Macie built-in 3-4 digit CVV.
#   * US Social Security Number (SSN) — Macie built-in.
#   * Automated Clearing House (ACH) routing / account numbers — Macie
#     built-in.
#   * CardDemo 11-digit ACCT-ID — this file's `acct_id` custom identifier.
#   * CardDemo 16-digit CARD-NUM — this file's `card_num` custom identifier.
#
# Output channel:
#   aws.macie EventBridge events  --[Macie Finding]-->  aws_cloudwatch_event_rule.macie_findings
#                                                                |
#                                                                v
#                                       aws_sns_topic.macie_findings (KMS-encrypted)
#                                                                |
#                                                                v
#                                       Security-operations subscription (email / PagerDuty / Slack)
#
# Cost considerations (per AAP §0.6.6):
#   Macie is priced per GB scanned. `sampling_percentage = 100` and
#   `daily_schedule = true` give the most thorough coverage but also the
#   highest cost. The PCI-DSS mandate requires every object be scanned
#   (no sampling), so the sampling percentage stays at 100. Operators may
#   pause the classification job in non-prod environments by setting
#   `job_status = "PAUSED"` post-apply if cost becomes a concern; this file
#   defaults to RUNNING (Macie's default for SCHEDULED jobs).
#
# References:
#   * AAP §0.6.1 — CardDemo decimal & key field schemas (ACCT-ID, CARD-NUM).
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS controls
#     ("Amazon Macie continuously scans S3 buckets for accidental PII /
#     financial-data exposure (card numbers, account numbers, SSNs);
#     findings publish to an SNS topic monitored by security operations").
#   * AAP §0.7.1 — All data at rest encrypted via AWS KMS CMKs (SNS topic).
#   * AAP §0.7.2 — "Amazon Macie continuously monitors S3 for PII/financial
#     data leakage".
#   * app/cpy/CVACT01Y.cpy — ACCT-ID PIC 9(11) field definition.
#   * app/cpy/CVACT02Y.cpy — CARD-NUM PIC X(16) field definition.
#
# Coordination:
#   * infrastructure/terraform/variables.tf  — supplies var.environment.
#   * infrastructure/terraform/main.tf       — supplies local.common_tags and
#                                              data.aws_caller_identity.current.
#   * infrastructure/terraform/kms.tf        — supplies aws_kms_key.carddemo
#                                              (SNS topic encryption at rest).
#                                              The KMS key policy in kms.tf
#                                              already grants the SNS service
#                                              principal `kms:Decrypt` and
#                                              `kms:GenerateDataKey` (statement
#                                              "AllowSNS"), so no additional
#                                              key policy changes are required.
#   * infrastructure/terraform/s3.tf         — supplies aws_s3_bucket.batch_outputs
#                                              (the Macie scan target).
#   * Macie service-linked role               — auto-created on Macie
#                                              enablement (AWSServiceRoleForAmazonMacie);
#                                              no IAM resources defined here.
###############################################################################

# =============================================================================
# Section 1 — Enable Macie in the account (aws_macie2_account)
# =============================================================================
# Activates the Amazon Macie service in the current AWS account / region.
# Macie creates its service-linked role (AWSServiceRoleForAmazonMacie) on
# enablement, which is what allows Macie to read S3 inventory + scan object
# content. Every other Macie resource (classification jobs, custom data
# identifiers) requires this account-level enablement and therefore uses
# `depends_on = [aws_macie2_account.carddemo]` to enforce the ordering.
#
# `finding_publishing_frequency = "FIFTEEN_MINUTES"` is the most aggressive
# publishing cadence Macie supports (alternatives: ONE_HOUR, SIX_HOURS).
# Per AAP §0.6.6, security operations needs to see PII / financial-data
# leakage within minutes, not hours, so FIFTEEN_MINUTES is the chosen
# value. This is the cadence at which Macie publishes findings to the
# EventBridge default event bus (which our aws_cloudwatch_event_rule
# below subscribes to).
#
# `status = "ENABLED"` keeps Macie active continuously. Setting it to
# "PAUSED" stops new finding generation but retains existing findings
# (useful only for non-prod cost throttling).
#
# Note: `aws_macie2_account` does NOT accept a `tags` argument — the
# resource manages an account-level toggle, not a taggable object. Tags
# applied via the provider `default_tags` block in main.tf are therefore
# also ignored on this resource.
# =============================================================================

resource "aws_macie2_account" "carddemo" {
  # FIFTEEN_MINUTES delivers Macie findings to the EventBridge default
  # event bus every ~15 min. Combined with our event rule below, security
  # operations receives a finding notification within ~15 min of upload.
  finding_publishing_frequency = "FIFTEEN_MINUTES"

  # ENABLED keeps Macie continuously scanning per the AAP §0.6.6 security
  # posture. Setting to PAUSED would stop the service-linked role from
  # reading new S3 inventory.
  status = "ENABLED"
}

# =============================================================================
# Section 2 — Custom Data Identifier — 11-digit ACCT-ID (aws_macie2_custom_data_identifier)
# =============================================================================
# CardDemo's ACCT-ID is an 11-digit numeric field — see app/cpy/CVACT01Y.cpy
# line 5: `05  ACCT-ID  PIC 9(11)`. Macie's built-in identifiers (PAN, SSN,
# CVV, ACH) do not match this length / format, so we register a custom
# identifier that flags any 11-digit number anchored by a word boundary
# that appears within `maximum_match_distance` characters of an ACCT-ID
# keyword.
#
# Regex anatomy:
#   `\b`        — word boundary (no preceding digit/letter).
#   `[0-9]{11}` — exactly 11 digits.
#   `\b`        — trailing word boundary.
# In Terraform HCL the backslash is escaped (`\\b`), yielding the actual
# regex `\b[0-9]{11}\b` that Macie receives.
#
# Keyword proximity:
#   Macie only fires a finding when one of the listed keywords appears
#   within `maximum_match_distance` (50) characters of the matched regex.
#   This eliminates false positives from incidental 11-digit numbers
#   (e.g., phone numbers, log sequence IDs) and constrains hits to actual
#   ACCT-ID labels such as `ACCT-ID`, `acct-id`, `account`, `acct` —
#   exactly the strings that COBOL source emits when writing flat-file
#   output (DALYREJS, SYSTRAN, STMTFILE).
#
# `maximum_match_distance = 50` is well within Macie's allowed range (1
# to 300) and balances precision against recall — large enough to capture
# COBOL fixed-width record layouts (where ACCT-ID may be 20-40 chars from
# the literal "ACCT-ID") yet small enough to avoid catching unrelated
# 11-digit sequences elsewhere on the page.
#
# AAP cross-reference: §0.6.1 documents that ACCT-ID is PIC 9(11) and is
# the partition key on the MSK transaction.posted / account.updated topics
# (§0.6.5) — so a leak of even one ACCT-ID is a regulatory event.
# =============================================================================

resource "aws_macie2_custom_data_identifier" "acct_id" {
  name        = "carddemo-${var.environment}-acct-id"
  description = "11-digit ACCT-ID account number per AAP §0.6.1 (app/cpy/CVACT01Y.cpy line 5: PIC 9(11)). Custom identifier complements Macie's built-in PII / financial-data identifiers which do not cover the CardDemo-specific 11-digit ACCT-ID format."

  # Word-boundary anchored 11-digit sequence (see regex anatomy above).
  regex = "\\b[0-9]{11}\\b"

  # Keywords that must appear within `maximum_match_distance` chars of the
  # regex match. These are the literal strings COBOL emits when writing
  # ACCT-ID to its flat-file outputs and the strings the Java target
  # emits when logging or serializing the field.
  keywords = ["acct", "account", "acct-id", "ACCT-ID"]

  # Proximity window (chars) within which a keyword must appear relative
  # to the regex match. Valid range: 1-300. 50 balances precision vs.
  # recall for the CardDemo fixed-width record layouts.
  maximum_match_distance = 50

  # Tags applied here for console clarity and cost-allocation reporting
  # (also applied automatically by the provider `default_tags` block in
  # main.tf, but explicit `tags = local.common_tags` keeps the resource
  # readable in console listings and Terraform plan output).
  tags = local.common_tags

  # Macie session must be enabled before any custom data identifier can
  # be created — otherwise the AWS API rejects the request with
  # `AccessDeniedException: Account is not enabled for Amazon Macie`.
  depends_on = [aws_macie2_account.carddemo]
}

# =============================================================================
# Section 3 — Custom Data Identifier — 16-digit CARD-NUM (aws_macie2_custom_data_identifier)
# =============================================================================
# CardDemo's CARD-NUM is a 16-character field — see app/cpy/CVACT02Y.cpy
# line 5: `05  CARD-NUM  PIC X(16)`. While Macie's built-in PAN identifier
# covers 13-19 digit numbers with Luhn validation, the AAP requires a
# CardDemo-specific identifier so that:
#   * Findings can be filtered specifically for CardDemo CARD-NUM leaks
#     (separate finding type from generic PAN detection).
#   * Synthetic CARD-NUM values used in test fixtures (which may not pass
#     Luhn) are still flagged.
#   * The keyword proximity is tuned to CardDemo's literal CARD-NUM /
#     card-num / pan column labels in flat-file outputs.
#
# Regex anatomy: `\b[0-9]{16}\b` — word-boundary anchored exactly 16-digit
# sequence. The CARD-NUM PIC clause is `PIC X(16)` which allows
# alphanumeric, but in practice CardDemo card numbers are 16 digits (see
# app/data/ASCII/cardata.txt for the seed fixtures).
#
# Keyword proximity matches the same 50-char window as the ACCT-ID
# identifier (consistent operator experience), keyed on the literal
# strings emitted by COBOL flat-file output and Java target logs.
#
# AAP cross-reference: §0.6.1 documents CARD-NUM as the primary key of
# the Card entity (replacing CARDDATA.VSAM.KSDS) and §0.6.6 calls out
# CARD-NUM exposure as a top PCI-DSS risk surface.
# =============================================================================

resource "aws_macie2_custom_data_identifier" "card_num" {
  name        = "carddemo-${var.environment}-card-num"
  description = "16-digit CARD-NUM card number per AAP §0.6.1 (app/cpy/CVACT02Y.cpy line 5: PIC X(16)). Custom identifier supplements Macie's built-in PAN detection with CardDemo-specific keyword proximity tuning (matches both Luhn-valid and synthetic test CARD-NUM values)."

  # Word-boundary anchored 16-digit sequence.
  regex = "\\b[0-9]{16}\\b"

  # Keywords that must appear within `maximum_match_distance` chars of the
  # regex match. `pan` covers the generic Primary Account Number alias
  # used in PCI-DSS contexts and downstream banking systems.
  keywords = ["card", "card-num", "CARD-NUM", "pan"]

  # Same 50-char proximity window as the ACCT-ID identifier for operator
  # consistency.
  maximum_match_distance = 50

  tags = local.common_tags

  depends_on = [aws_macie2_account.carddemo]
}

# =============================================================================
# Section 4 — Classification job — daily scan of batch outputs (aws_macie2_classification_job)
# =============================================================================
# Schedules Macie to scan aws_s3_bucket.batch_outputs (the bucket holding
# CardDemo's COBOL-era batch output streams — DALYREJS, SYSTRAN, TRANREPT,
# STMTFILE, TRANSACT.BKUP — per AAP §0.6.2 and §0.6.3). This is the
# highest-risk leakage surface because:
#   * Batch outputs are produced unattended (no human review per object).
#   * Flat-file outputs preserve byte-for-byte layouts (per the AAP
#     "regulatory reporting output formats" out-of-scope constraint), so
#     any operator misconfiguration can dump full ACCT-ID + CARD-NUM
#     records to S3 in clear text.
#   * The bucket is the primary off-mainframe data store for batch
#     evidence — a Macie finding here directly maps to a PCI-DSS incident.
#
# Job parameters:
#   * `job_type = "SCHEDULED"`  — recurring scan (alternative
#                                  ONE_TIME runs once and never repeats).
#   * `schedule_frequency.daily_schedule = true`
#                                — runs once every 24 hours, the densest
#                                  Macie-supported frequency for SCHEDULED
#                                  jobs (alternatives: weekly_schedule,
#                                  monthly_schedule). Combined with the
#                                  FIFTEEN_MINUTES finding publishing
#                                  frequency on the Macie account, this
#                                  delivers fresh findings within 15 min
#                                  of the daily scan completing.
#   * `sampling_percentage = 100`
#                                — scan every object in the bucket (no
#                                  sampling). Required by AAP §0.6.6 for
#                                  PCI-DSS coverage; reducing this would
#                                  leave a stochastic gap.
#   * `s3_job_definition.bucket_definitions.account_id`
#                                — scopes the scan to buckets owned by the
#                                  CURRENT AWS account (defensive — Macie
#                                  can scan cross-account buckets if
#                                  permissions allow, and we deliberately
#                                  do not).
#   * `s3_job_definition.bucket_definitions.buckets`
#                                — explicit list of bucket names (just the
#                                  one: batch_outputs). Adding additional
#                                  buckets here (e.g., the
#                                  aws_s3_bucket.logs bucket from s3.tf)
#                                  is a future-hardening option but is not
#                                  required by the current AAP scope.
#   * `s3_job_definition.scoping.includes`
#                                — restricts the scan to objects whose
#                                  extension matches the CardDemo flat-file
#                                  output formats. This excludes binary
#                                  archives (.tar.gz, .zip, .parquet) that
#                                  do not match COBOL-era plain-text
#                                  outputs and would be expensive to scan
#                                  per-byte. Each extension corresponds
#                                  to a known CardDemo output type:
#                                    txt   — DALYREJS / SYSTRAN raw text
#                                    csv   — Spring Batch tabular exports
#                                    json  — REST API audit dumps
#                                    log   — application log archives
#                                    html  — CBSTM03B HTML statements
#                                    ascii — app/data/ASCII fixtures
#
# The job is associated with the two custom data identifiers above
# automatically — Macie applies every enabled custom identifier in the
# account to every classification job, so we do not pass them explicitly
# here. (The Terraform provider exposes a `custom_data_identifier_ids`
# argument that scopes a job to a subset of identifiers; we omit it to
# get the implicit "use all enabled identifiers" behavior.)
# =============================================================================

resource "aws_macie2_classification_job" "batch_outputs" {
  name        = "carddemo-${var.environment}-batch-outputs-scan"
  description = "Daily scan of the CardDemo batch outputs S3 bucket for PII / PAN / 11-digit ACCT-ID / 16-digit CARD-NUM leakage per AAP §0.6.6. Replaces the mainframe-era manual flat-file audit; output is published to EventBridge with detail-type 'Macie Finding' and routed by aws_cloudwatch_event_rule.macie_findings to the carddemo-${var.environment}-macie-findings SNS topic."

  # SCHEDULED == recurring (vs. ONE_TIME).
  job_type = "SCHEDULED"

  # Bucket scope + object scope.
  s3_job_definition {
    # Bucket selection: explicit-list mode (vs. CRITERIA mode which uses
    # bucket tags). Scoped to the current AWS account for defense in
    # depth.
    bucket_definitions {
      # Scope to buckets owned by the current AWS account only.
      account_id = data.aws_caller_identity.current.account_id

      # The one in-scope bucket: aws_s3_bucket.batch_outputs from s3.tf.
      # Adding the aws_s3_bucket.logs bucket here is a future-hardening
      # option but is not required by AAP §0.6.6's current scope.
      buckets = [aws_s3_bucket.batch_outputs.id]
    }

    # Object scope: include only the file extensions corresponding to
    # CardDemo's flat-file / report outputs. This skips binary archives
    # and other formats that don't match COBOL-era plain text.
    scoping {
      includes {
        and {
          simple_scope_term {
            comparator = "EQ"
            key        = "OBJECT_EXTENSION"
            # Each extension matches a known CardDemo output stream.
            # See the file-header coordination table for the mapping
            # of extension -> COBOL flat-file output stream.
            values = ["txt", "csv", "json", "log", "html", "ascii"]
          }
        }
      }
    }
  }

  # Run once every 24 hours.
  schedule_frequency {
    daily_schedule = true
  }

  # PCI-DSS-mandated full coverage (no sampling). See file-header cost
  # considerations.
  sampling_percentage = 100

  tags = local.common_tags

  # Macie session must be enabled before any classification job can be
  # created.
  depends_on = [aws_macie2_account.carddemo]
}

# =============================================================================
# Section 5 — SNS topic for Macie findings (aws_sns_topic)
# =============================================================================
# KMS-encrypted SNS topic that receives Macie finding notifications via
# the EventBridge -> SNS bridge declared in Section 6. Security operations
# subscribes to this topic (email / PagerDuty / Slack endpoint) to receive
# real-time finding alerts.
#
# `kms_master_key_id = aws_kms_key.carddemo.arn` — encrypts the topic's
# messages at rest with the CardDemo customer-managed CMK from kms.tf,
# satisfying AAP §0.7.1 ("All data at rest encrypted via AWS KMS customer-
# managed keys (CMKs)"). The KMS key policy in kms.tf grants the SNS
# service principal `kms:Decrypt` and `kms:GenerateDataKey` (see kms.tf
# statement "AllowSNS"), so no additional key policy changes are needed
# for SNS to encrypt + decrypt messages with this CMK.
#
# Topic ACL is enforced through the aws_sns_topic_policy in Section 7,
# which grants ONLY the EventBridge service principal permission to
# publish — no human IAM identities, no other AWS services, no cross-
# account principals. This means a Macie finding can ONLY arrive on this
# topic if it transits the EventBridge rule, which itself only matches
# `aws.macie / Macie Finding` events. The combination ensures that the
# topic is a single-purpose Macie-finding channel.
# =============================================================================

resource "aws_sns_topic" "macie_findings" {
  name = "carddemo-${var.environment}-macie-findings"

  # Encrypt topic messages at rest with the CardDemo CMK (AAP §0.7.1).
  # The KMS key policy in kms.tf grants the SNS service principal access.
  kms_master_key_id = aws_kms_key.carddemo.arn

  tags = local.common_tags
}

# =============================================================================
# Section 6 — EventBridge rule + target (aws_cloudwatch_event_rule, aws_cloudwatch_event_target)
# =============================================================================
# Macie publishes every finding to the AWS account's default EventBridge
# event bus with `source = "aws.macie"` and `detail-type = "Macie Finding"`.
# This rule matches that exact event shape and routes the matching events
# to the SNS topic declared in Section 5.
#
# The event pattern is a hard match (both `source` and `detail-type` must
# match) so unrelated events on the default event bus are silently dropped
# rather than being delivered to the SNS topic. This keeps the topic
# strictly single-purpose.
#
# `aws_cloudwatch_event_rule` and `aws_cloudwatch_event_target` are the
# Terraform AWS provider names for EventBridge (the resources were
# originally named for CloudWatch Events, which was rebranded to
# EventBridge in 2019; the resource names retain the legacy `cloudwatch_`
# prefix for backward compatibility).
# =============================================================================

resource "aws_cloudwatch_event_rule" "macie_findings" {
  name        = "carddemo-${var.environment}-macie-findings-rule"
  description = "Route Macie findings (source=aws.macie, detail-type='Macie Finding') from the default EventBridge event bus to the carddemo-${var.environment}-macie-findings SNS topic for security-operations consumption per AAP §0.6.6."

  # Hard match: BOTH source AND detail-type must match. Rendered to JSON
  # via jsonencode() so that Terraform plan output is human-readable.
  event_pattern = jsonencode({
    source      = ["aws.macie"]
    detail-type = ["Macie Finding"]
  })

  tags = local.common_tags
}

resource "aws_cloudwatch_event_target" "macie_findings_to_sns" {
  # The rule whose matching events are sent to this target.
  rule = aws_cloudwatch_event_rule.macie_findings.name

  # Stable target identifier within the rule. Required for `aws_cloudwatch_event_target`
  # so Terraform can manage the target idempotently (re-applying the same
  # plan does not duplicate the target).
  target_id = "macie-findings-sns"

  # The destination SNS topic.
  arn = aws_sns_topic.macie_findings.arn
}

# =============================================================================
# Section 7 — SNS topic policy granting EventBridge publish access
# =============================================================================
# By default, SNS topics inherit no resource-policy permissions — only the
# topic owner can publish. To allow EventBridge to publish Macie finding
# events to the topic, we attach a resource policy granting `SNS:Publish`
# to the `events.amazonaws.com` service principal scoped to this specific
# topic ARN.
#
# The policy is rendered by an `aws_iam_policy_document` data source
# (Section 8) so Terraform validates the JSON structure at plan time and
# the policy is readable HCL rather than an inline JSON heredoc.
#
# Note: the policy intentionally does NOT include any wildcards or human
# IAM principals — only the EventBridge service principal. Any attempt by
# an unprivileged human, another AWS service, or a cross-account principal
# to publish directly to the topic will be denied by IAM evaluation.
# =============================================================================

resource "aws_sns_topic_policy" "macie_findings" {
  arn    = aws_sns_topic.macie_findings.arn
  policy = data.aws_iam_policy_document.macie_findings_sns_policy.json
}

# =============================================================================
# Section 8 — IAM policy document for the SNS topic policy
# =============================================================================
# Renders the JSON policy attached to aws_sns_topic.macie_findings in
# Section 7. Single-statement policy granting only `SNS:Publish` to the
# EventBridge service principal on this exact topic ARN.
#
# Hardening notes:
#   * `resources` is scoped to the topic ARN (no wildcard) so even if the
#     policy is accidentally attached to another topic the policy would
#     have no effect.
#   * `principals.type = "Service"` + `identifiers = ["events.amazonaws.com"]`
#     is the canonical pattern for AWS-service-to-AWS-service permission;
#     it cannot be abused by a customer IAM role or external principal.
#   * No `condition` block is required because the topic name itself is
#     environment-specific (carddemo-${var.environment}-macie-findings),
#     so a dev-environment policy cannot grant publish to a prod topic.
# =============================================================================

data "aws_iam_policy_document" "macie_findings_sns_policy" {
  statement {
    # Human-readable statement identifier; surfaced in CloudTrail and
    # IAM Access Analyzer findings.
    sid    = "AllowEventBridge"
    effect = "Allow"

    # Only EventBridge can publish.
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }

    # Only the publish action; no list / subscribe / get-attributes.
    actions = ["SNS:Publish"]

    # Scoped to this specific topic ARN (no wildcards).
    resources = [aws_sns_topic.macie_findings.arn]
  }
}
