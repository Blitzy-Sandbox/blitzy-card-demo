###############################################################################
# infrastructure/terraform/waf.tf
#
# AWS WAF v2 Web ACL protecting the CardDemo Application Load Balancer.
#
# Purpose:
#   Provisions the perimeter-defense layer that AWS WAF (Web Application
#   Firewall) v2 places in front of the CardDemo Application Load Balancer
#   (aws_lb.carddemo, defined in alb.tf). Every inbound HTTP / HTTPS request
#   addressed to the CardDemo REST API is inspected by the rules in this
#   Web ACL BEFORE the ALB forwards the request to ECS Fargate. Requests
#   matching any rule's BLOCK action are short-circuited at the WAF layer
#   with an HTTP 403 response, never reaching the Spring Boot application —
#   satisfying the PCI-DSS perimeter-defense requirement (AAP §0.6.6 and
#   §0.7.2: "AWS WAF + Shield on ALB for all public-facing endpoints").
#
#   Responsibilities discharged by this file (one resource per responsibility):
#
#     1. aws_wafv2_web_acl.alb
#        REGIONAL-scoped Web ACL containing:
#          a. AWSManagedRulesCommonRuleSet         (OWASP-style core rules)
#          b. AWSManagedRulesKnownBadInputsRuleSet (known-bad payloads)
#          c. AWSManagedRulesSQLiRuleSet           (SQL-injection patterns)
#          d. AWSManagedRulesAmazonIpReputationList(IPs Amazon has identified
#                                                   as malicious)
#          e. RateLimitPerIp                       (per-source-IP rate limit
#                                                   of 2 000 requests per
#                                                   five-minute window)
#        Default action is `allow {}` (only requests that match a rule's
#        BLOCK action are denied). CloudWatch metrics and request sampling
#        are enabled per rule and at the Web ACL level for incident
#        investigation and tuning.
#
#     2. aws_wafv2_web_acl_association.alb
#        One-to-one attachment of the Web ACL to the ALB ARN. Each ALB can
#        be associated with exactly one Web ACL; this association makes the
#        Web ACL inspect every request the ALB receives.
#
#     3. aws_cloudwatch_log_group.waf
#        KMS-CMK-encrypted CloudWatch Log Group that receives WAF
#        inspection logs. The log group name starts with the literal prefix
#        `aws-waf-logs-` — this is a HARD AWS requirement for WAFv2
#        CloudWatch logging destinations (see the AWS WAF Developer Guide,
#        "Logging web ACL traffic" section). Encryption at rest uses
#        aws_kms_key.carddemo (defined in kms.tf) per AAP §0.6.6 / §0.7.1.
#
#     4. aws_wafv2_web_acl_logging_configuration.alb
#        Connects the Web ACL to the CloudWatch Log Group above and
#        configures field redaction so the `authorization` and `cookie`
#        headers are NEVER written to the log destination (the
#        authorization header carries JWT bearer tokens issued by
#        AuthController.signin per AAP §0.3.4, and the cookie header
#        carries ALB sticky-session cookies — both qualify as
#        authentication credentials and MUST be redacted per PCI-DSS).
#
# Scope:
#   REGIONAL. The ALB is a regional resource; ALB association requires the
#   Web ACL to be REGIONAL-scoped. CloudFront distributions would require
#   GLOBAL scope (provisioned in us-east-1) — the CardDemo architecture
#   does not use CloudFront, so GLOBAL scope is not used here.
#
# Layered defence:
#   AWS WAF is the first L7 inspection layer. It is COMPLEMENTED, not
#   replaced, by:
#     * AWS Shield Standard (always on for AWS-edge services such as ALB
#       and CloudFront) and optionally Shield Advanced (shield.tf — extra
#       L3/L4 DDoS resilience plus L7 automatic response when paired with
#       a Web ACL).
#     * The ALB security group (alb.tf) — L3/L4 ingress / egress.
#     * The Spring Security configuration (src/main/java/.../SecurityConfig)
#       — application-layer authentication / authorisation.
#     * Spring Validation (Jakarta Bean Validation) on every controller —
#       per-field semantic validation.
#
# Replaces (legacy mainframe components — frozen, retained as behavioural
# ground truth under app/):
#   * No direct mainframe analogue. AWS WAF implements perimeter-defense
#     controls that did not exist on the z/OS LPAR (the 3270 terminal
#     network was a private SNA / TCP/IP segment with physical-access
#     controls). PCI-DSS Requirement 1 ("Install and maintain a firewall
#     configuration to protect cardholder data") motivates this layer in
#     the AWS target architecture.
#
# Coordination with sibling files (none modified — read-only dependencies):
#   * infrastructure/terraform/main.tf
#       Provides `local.common_tags` — the mandatory Project / Environment
#       / Owner / ManagedBy tag set merged onto every WAF resource. Also
#       provides `local.partition`, `local.account_id`, and the AWS
#       provider's `default_tags` block (which already applies common_tags
#       to every resource; the explicit `tags = local.common_tags` lines
#       below are belt-and-braces for clarity in the WAF state).
#   * infrastructure/terraform/variables.tf
#       Provides `var.environment` (interpolated into resource names) and
#       `var.cloudwatch_log_retention_days` (default 365 days — the
#       PCI-DSS audit-log-retention floor of one year).
#   * infrastructure/terraform/alb.tf
#       Provides `aws_lb.carddemo` (the protected resource). The WAF Web
#       ACL is associated with `aws_lb.carddemo.arn`.
#   * infrastructure/terraform/kms.tf
#       Provides `aws_kms_key.carddemo` (the primary customer-managed CMK).
#       The KMS key policy already grants the CloudWatch Logs service
#       principal (logs.<region>.amazonaws.com) Encrypt/Decrypt
#       permissions scoped to log groups in this account — see the
#       `AllowCloudWatchLogs` statement in kms.tf — so the WAF log group
#       below is encrypted-at-rest with the carddemo CMK with no further
#       policy changes required.
#
# References:
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     ("AWS WAF managed rule groups for financial services attach to the
#     ALB"; "Shield Standard is enabled by default, with Shield Advanced
#     recommended for the ALB to gain L7 DDoS protection").
#   * AAP §0.7.1 — Refactoring-Specific Rules ("Encrypt all RDS data at
#     rest using AWS KMS customer-managed keys (CMKs)"; "All secrets and
#     credentials managed via AWS Secrets Manager" — implied: every log
#     group containing request metadata is CMK-encrypted).
#   * AAP §0.7.2 — Special Instructions and Constraints ("AWS WAF + Shield
#     on ALB for all public-facing endpoints"; "All data at rest encrypted
#     via AWS KMS (RDS, S3, ElastiCache, CloudWatch Logs)").
#   * AAP §0.3.4 — User Interface Design (JWT bearer tokens carried in the
#     `authorization` header — redacted in WAF logs below).
###############################################################################

# =============================================================================
# Section 1 — AWS WAF v2 Web ACL (REGIONAL)
# =============================================================================
# Single Web ACL containing five rules in priority order:
#
#   priority   10  — AWS-AWSManagedRulesCommonRuleSet         (OWASP-style)
#   priority   20  — AWS-AWSManagedRulesKnownBadInputsRuleSet (curated)
#   priority   30  — AWS-AWSManagedRulesSQLiRuleSet           (SQL injection)
#   priority   40  — AWS-AWSManagedRulesAmazonIpReputationList(IP rep.)
#   priority  100  — RateLimitPerIp                           (anti-bot)
#
# Rules are evaluated in ascending priority order. The first rule whose
# statement matches AND whose effective action is BLOCK terminates request
# processing with HTTP 403. Lower-numbered priorities run first; the gap
# between priority 40 and priority 100 is intentional, leaving room for
# additional managed rule groups (e.g., AnonymousIpList, LinuxRuleSet,
# ATPRuleSet) at priorities 50–90 without renumbering existing rules.
#
# Managed rule groups (AWSManagedRules*) use `override_action.none {}`
# which preserves each sub-rule's vendor-defined default action (typically
# BLOCK). Use `override_action.count {}` to put an entire group in
# count-only mode during tuning. Per-sub-rule overrides use
# `rule_action_override` inside `managed_rule_group_statement`.
#
# Sub-rule exceptions documented inline below — only made with explicit
# justification, never as a blanket "disable noisy rules" workaround.
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, name, capacity, description, scope, lock_token, tags_all.
# =============================================================================

resource "aws_wafv2_web_acl" "alb" {
  name        = "carddemo-${var.environment}-alb-waf"
  description = "WAF Web ACL protecting the CardDemo ALB; AWS Managed Rules for financial services per AAP §0.6.6 / §0.7.2"
  scope       = "REGIONAL"

  # ---------------------------------------------------------------------------
  # Default action — apply to any request that no rule matches.
  # `allow {}` means: if no BLOCK rule matches, the request proceeds to the
  # ALB target group. The Web ACL is therefore a "block-list" model — only
  # explicitly-bad traffic is rejected. The alternative `block {}` default
  # ("allow-list" model) is not used because the CardDemo API surface is
  # public-facing (PCI-DSS allows authenticated public APIs, and the
  # signin endpoint is by definition unauthenticated).
  # ---------------------------------------------------------------------------
  default_action {
    allow {}
  }

  # ---------------------------------------------------------------------------
  # Rule 1 — AWSManagedRulesCommonRuleSet (priority 10).
  #
  # The Core Rule Set is AWS's curated, vendor-maintained equivalent of the
  # OWASP ModSecurity CRS. It detects a broad set of HTTP-borne attacks
  # including XSS, command injection, payload smuggling, and abnormal
  # header / URI patterns. This is the BASELINE rule group every WAFv2 web
  # ACL should include.
  #
  # Sub-rule exception: `SizeRestrictions_BODY`
  #   Default action: BLOCK requests whose body exceeds 8 KiB.
  #   Override:       COUNT (log the match but do not block).
  #   Justification:  REST endpoints in the CardDemo API (notably
  #                   POST /api/transactions, POST /api/admin/users, and
  #                   POST /api/reports/submit) may legitimately carry
  #                   JSON payloads larger than 8 KiB when batch-creating
  #                   transactions or submitting wide report parameters.
  #                   Blocking at 8 KiB would generate false-positive
  #                   403s for valid traffic; COUNT keeps visibility into
  #                   abnormally large payloads without disrupting valid
  #                   API calls. (The ALB itself enforces a 1 MiB header
  #                   limit and a 10 MiB body limit at L7.)
  # ---------------------------------------------------------------------------
  rule {
    name     = "AWS-AWSManagedRulesCommonRuleSet"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        # Per-sub-rule override: change SizeRestrictions_BODY action from
        # BLOCK to COUNT for REST API compatibility (see justification above).
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 2 — AWSManagedRulesKnownBadInputsRuleSet (priority 20).
  #
  # Detects known-bad request patterns including exploit attempts targeting
  # Apache Struts, Log4Shell (CVE-2021-44228), Spring Cloud Function RCE,
  # and other public CVEs. Maintained by AWS — receives signature updates
  # automatically. No sub-rule overrides; all sub-rules use vendor-default
  # actions.
  # ---------------------------------------------------------------------------
  rule {
    name     = "AWS-AWSManagedRulesKnownBadInputsRuleSet"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "KnownBadInputs"
      sampled_requests_enabled   = true
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 3 — AWSManagedRulesSQLiRuleSet (priority 30).
  #
  # Dedicated SQL-injection detection. Inspects URI, query string, body,
  # cookies, and headers for SQL-injection signatures. CardDemo writes to
  # RDS PostgreSQL via Spring Data JPA (which uses parameterised queries
  # exclusively), so the application layer is not actually vulnerable to
  # SQLi — but defence-in-depth requires blocking at the perimeter
  # regardless. The synthetic-attack validation step in the agent prompt
  # (POST body `'; DROP TABLE users; --` returns HTTP 403) verifies this
  # rule is active.
  # ---------------------------------------------------------------------------
  rule {
    name     = "AWS-AWSManagedRulesSQLiRuleSet"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesSQLiRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "SQLiRuleSet"
      sampled_requests_enabled   = true
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 4 — AWSManagedRulesAmazonIpReputationList (priority 40).
  #
  # Amazon's continuously-updated list of IP addresses associated with bots,
  # scanners, reconnaissance scripts, and known threat actors. Sourced from
  # Amazon's threat intelligence (including GuardDuty findings,
  # CloudFront edge data, and Shield observations). Block-by-default is
  # appropriate — these IPs have NO legitimate business reason to talk to
  # a financial-services API.
  # ---------------------------------------------------------------------------
  rule {
    name     = "AWS-AWSManagedRulesAmazonIpReputationList"
    priority = 40

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AmazonIpReputationList"
      sampled_requests_enabled   = true
    }
  }

  # ---------------------------------------------------------------------------
  # Rule 5 — RateLimitPerIp (priority 100).
  #
  # Custom (non-managed) rate-based rule: any source IP that exceeds 2 000
  # requests in a rolling 5-minute window is BLOCKED for the remainder of
  # the window (and a new 5-minute counter starts on the next request).
  # Defends against credential-stuffing attacks against /api/auth/signin,
  # enumeration attacks against /api/accounts/{id}, and brute-force
  # attacks generally.
  #
  # Limit selection — 2 000 / 5 min = 400 / minute = ~6.7 / second per IP.
  # The vast majority of legitimate users (a single human operator or a
  # back-office batch process) will never approach this rate. NAT'd
  # corporate networks routing multiple users through one egress IP MAY
  # approach this rate during peak hours; if production telemetry shows
  # legitimate 429s, raise the limit by a multiple of 10 (next sensible
  # value is 20 000) rather than disabling the rule entirely.
  #
  # `aggregate_key_type = "IP"` keys by source IP as seen by the ALB
  # (X-Forwarded-For-aware). The alternative `FORWARDED_IP` would use
  # the X-Forwarded-For header — useful only when CloudFront sits in
  # front of the ALB (not the case here).
  # ---------------------------------------------------------------------------
  rule {
    name     = "RateLimitPerIp"
    priority = 100

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "RateLimitPerIp"
      sampled_requests_enabled   = true
    }
  }

  # ---------------------------------------------------------------------------
  # Top-level visibility configuration — emits CloudWatch metrics for the
  # Web ACL as a whole (allowed / blocked request counts). Required by the
  # AWS provider even when per-rule visibility_config is also set.
  #
  # `sampled_requests_enabled = true` enables the AWS WAF console "Sampled
  # requests" view, which captures a 5-minute rolling sample of the most
  # recent requests passed and blocked — invaluable for incident triage
  # without enabling full logging.
  # ---------------------------------------------------------------------------
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "carddemo-${var.environment}-waf"
    sampled_requests_enabled   = true
  }

  # ---------------------------------------------------------------------------
  # Tags — mandatory per the folder summary (Project / Environment /
  # Owner / ManagedBy via local.common_tags from main.tf). The AWS provider
  # `default_tags` block in main.tf already applies common_tags to every
  # resource in the module, but we include the explicit tag block here as
  # belt-and-braces so the Web ACL state always shows the tag set without
  # requiring readers to know about default_tags.
  # ---------------------------------------------------------------------------
  tags = local.common_tags
}

# =============================================================================
# Section 2 — Web ACL <-> ALB association
# =============================================================================
# Attaches the Web ACL to the CardDemo Application Load Balancer. After this
# association is applied, every request the ALB receives is forwarded to AWS
# WAF for inspection BEFORE the ALB routes it to the ECS Fargate target
# group.
#
# AWS allows exactly one Web ACL per ALB. Replacing the Web ACL requires
# destroying the association first (Terraform handles this automatically
# via dependency ordering on the resource references below).
#
# Members exposed for downstream consumers (per the file schema):
#   id, resource_arn, web_acl_arn.
# =============================================================================

resource "aws_wafv2_web_acl_association" "alb" {
  # The protected resource — aws_lb.carddemo is defined in alb.tf and is
  # the internet-facing ALB fronting the CardDemo Spring Boot REST API.
  resource_arn = aws_lb.carddemo.arn

  # The Web ACL applied to that resource.
  web_acl_arn = aws_wafv2_web_acl.alb.arn
}

# =============================================================================
# Section 3 — CloudWatch Log Group for WAF inspection logs
# =============================================================================
# Destination log group for the WAF logging configuration in Section 4.
#
# Naming constraint (CRITICAL — hard AWS requirement):
#   The log group name MUST start with the literal prefix `aws-waf-logs-`
#   for the WAFv2 logging configuration to accept it as a destination.
#   See the AWS WAF Developer Guide, "Logging web ACL traffic" section:
#   "For Amazon CloudWatch Logs log groups: The name of the log group
#    must start with the prefix aws-waf-logs-."
#   The agent-prompt example name `/aws/wafv2/...` does NOT satisfy this
#   constraint and would cause `aws_wafv2_web_acl_logging_configuration`
#   creation to fail at apply time with a WAFInvalidParameterException.
#
# Encryption at rest:
#   `kms_key_id = aws_kms_key.carddemo.arn` encrypts the log group with
#   the CardDemo primary CMK from kms.tf. The KMS key policy's
#   `AllowCloudWatchLogs` statement (kms.tf section 3, statement 2) grants
#   the CloudWatch Logs service principal (logs.<region>.amazonaws.com)
#   the Encrypt / Decrypt / ReEncrypt / GenerateDataKey permissions scoped
#   to log groups in this account via the
#   `kms:EncryptionContext:aws:logs:arn` condition — so this log group
#   inherits CMK encryption without any policy changes.
#
# Retention:
#   `retention_in_days = var.cloudwatch_log_retention_days` (default 365
#   days from variables.tf, the PCI-DSS audit-log-retention floor of one
#   year). Per-environment override is possible via terraform.tfvars.
#
# Log group class:
#   `log_group_class = "STANDARD"` — the default for high-write,
#   subsecond-query log groups. Required for WAF logs which are
#   high-volume (one record per inspected request). The alternative
#   `INFREQUENT_ACCESS` class is cheaper but does not support real-time
#   querying or metric filters and is not appropriate for live security
#   telemetry.
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, name, retention_in_days, kms_key_id, log_group_class, tags_all.
# =============================================================================

resource "aws_cloudwatch_log_group" "waf" {
  # `aws-waf-logs-` prefix is REQUIRED by the WAF logging service — see the
  # naming-constraint block above.
  name              = "aws-waf-logs-carddemo-${var.environment}"
  retention_in_days = var.cloudwatch_log_retention_days

  # KMS-CMK encryption at rest per AAP §0.6.6 / §0.7.1.
  # F-CP6-TF-KMS-01: WAF log group uses the dedicated CloudWatch CMK
  # (aws_kms_key.cloudwatch_kms) rather than the legacy shared key.
  kms_key_id = aws_kms_key.cloudwatch_kms.arn

  # STANDARD class supports real-time queries, metric filters, and
  # subscription filters — all needed for WAF security telemetry.
  log_group_class = "STANDARD"

  tags = merge(local.common_tags, {
    Name    = "aws-waf-logs-carddemo-${var.environment}"
    Purpose = "AWS WAF v2 inspection logs for the CardDemo ALB Web ACL (AAP §0.6.6)"
  })
}

# =============================================================================
# Section 4 — WAF Web ACL logging configuration
# =============================================================================
# Wires the Web ACL to the CloudWatch Log Group above and configures field
# redaction so that authentication credentials are NEVER written to the log
# destination.
#
# Redacted fields (PCI-DSS Requirement 3.2 / 8.2.1 — protect authentication
# credentials at rest and in motion):
#   * `authorization` — Bearer JWT tokens issued by AuthController.signin
#                       (per AAP §0.3.4) and Basic-auth credentials for
#                       any legacy clients. Logging these would let a
#                       reader of the log group impersonate any user.
#   * `cookie`        — ALB sticky-session cookies (AWSALB / AWSALBCORS)
#                       and any application session cookies. Logging
#                       these enables session-hijacking from the log
#                       archive.
#
# Note: the request URI and body are NOT redacted; per the SQLi rule's
# operation, blocked requests have their full URI / body recorded for
# forensic analysis. If a future field is identified as containing PII /
# PAN (e.g., a query parameter accidentally carrying a card number),
# additional `redacted_fields` blocks should be added here and the
# offending controller fixed to stop emitting the value.
#
# Members exposed for downstream consumers (per the file schema):
#   id, resource_arn, log_destination_configs.
# =============================================================================

resource "aws_wafv2_web_acl_logging_configuration" "alb" {
  # Destination — the CloudWatch Log Group from Section 3. The provider
  # accepts a single-element list; for fan-out to multiple destinations
  # (e.g., S3 archive + CloudWatch real-time), additional ARNs would be
  # listed here.
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  # The Web ACL whose inspection events feed this logging configuration.
  resource_arn = aws_wafv2_web_acl.alb.arn

  # Redact the `authorization` header (JWT bearer tokens — never log).
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  # Redact the `cookie` header (ALB sticky-session cookies + any
  # application cookies — never log).
  redacted_fields {
    single_header {
      name = "cookie"
    }
  }
}
