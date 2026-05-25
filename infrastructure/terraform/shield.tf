###############################################################################
# infrastructure/terraform/shield.tf
#
# AWS Shield Advanced subscription on the CardDemo Application Load Balancer
# (optional, gated by var.shield_advanced_enabled).
#
# Purpose:
#   Provisions AWS Shield Advanced — the paid L7 DDoS protection and
#   incident-response capability — on top of the always-on AWS Shield
#   Standard service that every ALB receives at no additional charge.
#   Shield Advanced upgrades the perimeter-defense posture in three ways:
#
#     1. Enhanced L3 / L4 DDoS mitigation with finer-grained traffic
#        engineering and elevated SLA commitments versus Shield Standard.
#     2. Visibility into ongoing DDoS events through CloudWatch metrics
#        (DDoSAttackBitsPerSecond, DDoSAttackPacketsPerSecond,
#        DDoSAttackRequestsPerSecond), the AWS Shield console, and the
#        ListAttacks / DescribeAttack APIs.
#     3. Access to the AWS Shield Response Team (SRT), who can be engaged
#        proactively during an attack and post-incident for forensics and
#        rule tuning.
#
#   The Application Layer Automatic Response resource additionally enables
#   Shield to automatically write a WAFv2 rate-based BLOCK rule into the
#   ALB's Web ACL (from waf.tf) when an L7 DDoS attack is detected,
#   short-circuiting the attack at the WAF edge without operator
#   intervention.
#
#   Responsibilities discharged by this file (one resource per
#   responsibility, all conditional on var.shield_advanced_enabled):
#
#     1. aws_shield_protection.alb
#        Registers the ALB ARN with Shield Advanced. Once registered, the
#        ALB benefits from Shield Advanced's elevated mitigation
#        capabilities and SRT engagement option.
#
#     2. aws_shield_protection_group.carddemo
#        Groups Shield-protected resources (currently just the ALB) into a
#        named protection group for unified CloudWatch monitoring and
#        Shield Advanced dashboard aggregation. The MAX aggregation +
#        ARBITRARY pattern combination is used because (a) we want
#        whole-group attack metrics to surface the worst-affected member
#        (MAX) rather than a sum or average that could be diluted by
#        unaffected members, and (b) the ARBITRARY pattern is required
#        when the membership list is an explicit, hand-picked set of
#        resources rather than a resource-type filter.
#
#     3. aws_shield_application_layer_automatic_response.alb
#        Configures Shield Advanced to automatically respond to detected
#        L7 (HTTP / HTTPS) DDoS attacks by writing a rate-based BLOCK
#        rule into the ALB's WAFv2 Web ACL (aws_wafv2_web_acl.alb from
#        waf.tf, associated via aws_wafv2_web_acl_association.alb).
#        Action = BLOCK in production; staging may set this to COUNT for
#        observation-only tuning before enabling enforcement.
#
# Cost model (CRITICAL — operator awareness):
#   * Shield Advanced subscription: ~$3,000 USD per month per account,
#     12-month minimum commitment (per AWS pricing at the time of writing —
#     confirm current pricing before enabling).
#   * Per-protected-resource fees: included in the subscription for the
#     first 100 protected resources; data-transfer-out usage charges for
#     attack traffic are also covered under the subscription.
#   * Shield Standard (always-on, no Terraform action required): FREE.
#   * Default for var.shield_advanced_enabled is `false` — sandbox / dev /
#     staging environments will NOT incur Shield Advanced charges by
#     default. Production deployments opt in by setting
#     `shield_advanced_enabled = true` in their tfvars.
#
# Account-level subscription prerequisite (CRITICAL):
#   Shield Advanced requires an active `aws_shield_subscription` at the
#   AWS account level BEFORE `aws_shield_protection` creations succeed.
#   The Terraform AWS provider exposes `aws_shield_subscription` only as
#   a placeholder resource that cannot reliably create / destroy the
#   subscription idempotently (the subscription has a 12-month minimum
#   commitment that conflicts with `terraform destroy` semantics).
#   Operators MUST therefore subscribe to Shield Advanced out-of-band
#   (via the AWS Console: WAF & Shield -> Getting started -> Subscribe to
#   AWS Shield Advanced) BEFORE applying this Terraform module with
#   `shield_advanced_enabled = true`. See ../README.md ("shield.tf"
#   section) for the operator runbook. If the subscription is not active,
#   `terraform apply` will fail at the `aws_shield_protection.alb`
#   creation step with a `SubscriptionRequiredException`.
#
# Scope of protected resources:
#   * Only `aws_lb.carddemo` (the internet-facing Application Load
#     Balancer fronting the Spring Boot REST API) is protected. Shield
#     Advanced also supports NLB, CloudFront, Route 53 hosted zones,
#     Global Accelerator accelerators, and Elastic IPs, but the CardDemo
#     deployment exposes none of those — RDS, ElastiCache, MSK, and
#     internal services live in private subnets and are reachable only
#     via the ALB.
#   * VPC endpoints (S3 Gateway, Secrets Manager / KMS / ECR / CloudWatch
#     Logs Interface endpoints from main.tf) are NOT internet-facing and
#     are therefore not Shield-protected.
#
# Layered defence (recap):
#   AWS Shield Standard      L3 / L4 (always on, free)              <- baseline
#   AWS Shield Advanced      L3 / L4 + L7 + SRT (this file, opt-in) <- premium
#   AWS WAF (waf.tf)         L7 inspection / managed rules + custom  <- inspection
#   ALB security group       L3 / L4 perimeter SG (alb.tf)           <- network
#   Spring Security          App-layer authn / authz                 <- application
#
# Replaces (legacy mainframe components — frozen, retained as
# behavioural ground truth under app/):
#   * No direct mainframe analogue. The z/OS LPAR's 3270 terminal
#     network was a private SNA / TCP/IP segment with physical-access
#     controls; DDoS protection did not exist as a concept because the
#     network was not internet-facing. Moving CardDemo behind an
#     internet-facing ALB (AAP §0.1.1: "runtime z/OS LPAR -> ECS Fargate
#     behind ALB") introduces the threat class that Shield mitigates.
#
# Coordination with sibling files (read-only dependencies — never
# modified by this file):
#   * infrastructure/terraform/main.tf
#       Provides `local.common_tags` (mandatory Project / Environment /
#       Owner / ManagedBy tag set, also applied via the AWS provider's
#       default_tags block). Each Shield resource below explicitly sets
#       `tags = local.common_tags` for clarity in the Terraform state
#       and to match the pattern used by every other resource in the
#       module.
#   * infrastructure/terraform/variables.tf
#       Provides `var.environment` (one of dev | staging | prod, used in
#       resource naming) and `var.shield_advanced_enabled` (the bool
#       gate on every resource in this file; default `false`).
#   * infrastructure/terraform/alb.tf
#       Provides `aws_lb.carddemo` (the protected resource). Its .arn
#       attribute is the resource_arn for aws_shield_protection.alb,
#       aws_shield_protection_group.carddemo.members[0], and
#       aws_shield_application_layer_automatic_response.alb.
#   * infrastructure/terraform/waf.tf
#       Provides `aws_wafv2_web_acl_association.alb` (the WAFv2 Web ACL
#       attached to the ALB). The Application Layer Automatic Response
#       resource declares this association as an explicit depends_on so
#       that the automatic-response rule has a Web ACL to write into.
#       Without the association, Shield Advanced cannot write the
#       rate-based BLOCK rule and the automatic-response API rejects the
#       configuration with an InvalidParameterException.
#
# References:
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     ("AWS WAF managed rule groups for financial services attach to the
#     ALB; Shield Standard is enabled by default, with Shield Advanced
#     recommended for the ALB to gain L7 DDoS protection and incident
#     response").
#   * AAP §0.7.2 — Special Instructions and Constraints ("AWS WAF +
#     Shield on ALB for all public-facing endpoints").
#   * AAP §0.3.1 — Target Design / Refactored Structure Planning
#     (Shield Advanced subscription if configured).
#   * AWS Shield Developer Guide:
#     https://docs.aws.amazon.com/waf/latest/developerguide/shield-chapter.html
#   * AWS Shield Advanced pricing:
#     https://aws.amazon.com/shield/pricing/
###############################################################################

# =============================================================================
# Section 1 — Shield Advanced protection on the ALB
# =============================================================================
# Registers `aws_lb.carddemo` (the internet-facing Application Load Balancer
# defined in alb.tf) with AWS Shield Advanced. Once this resource exists,
# the ALB is included in Shield Advanced's enhanced mitigation pipeline and
# becomes visible to the AWS Shield Response Team (SRT).
#
# Conditional creation:
#   * `count = var.shield_advanced_enabled ? 1 : 0` — when the operator
#     sets shield_advanced_enabled = false (the module default), this
#     resource is NOT created and no Shield Advanced charges are incurred.
#     When true, exactly one protection resource is created for the ALB.
#   * Downstream references in this file use the `[0]` index (e.g.,
#     `aws_shield_protection.alb[0].arn`) because the resource is a count-
#     indexed list in Terraform state — see the depends_on lines in
#     Section 2 and Section 3 below.
#
# Naming:
#   `carddemo-${var.environment}-alb-shield` matches the
#   `carddemo-<env>-<suffix>` convention used by alb.tf
#   (`${local.resource_name_prefix}-alb`), waf.tf
#   (`carddemo-${var.environment}-alb-waf`), and every other resource in
#   the module. Shield protection resource names are surfaced in the AWS
#   Shield console and in `aws shield list-protections` output.
#
# resource_arn:
#   `aws_lb.carddemo.arn` — the ALB ARN provided by alb.tf. The implicit
#   dependency on aws_lb.carddemo guarantees that the ALB exists before
#   Shield attempts to register it. (Shield Advanced supports CloudFront,
#   ALB, NLB, Global Accelerator, Route 53, and Elastic IP resources; we
#   protect only the ALB because no other internet-facing AWS resource
#   exists in the CardDemo deployment.)
#
# Members exposed for downstream consumers (per the file schema):
#   id, arn, name, resource_arn, tags_all.
# =============================================================================

resource "aws_shield_protection" "alb" {
  # Gated by var.shield_advanced_enabled — defaults to false so that
  # sandbox / dev / staging environments do not incur the $3,000/month
  # Shield Advanced subscription fee. Production sets this to true via
  # a per-environment tfvars override.
  count = var.shield_advanced_enabled ? 1 : 0

  # Human-readable Shield Advanced protection name. Surfaced in the
  # AWS Shield console and the `aws shield list-protections` output.
  # The "-shield" suffix disambiguates from the "-alb" (the ALB itself)
  # and "-alb-waf" (the WAFv2 Web ACL) names already in use under the
  # same `carddemo-<env>` prefix.
  name = "carddemo-${var.environment}-alb-shield"

  # The protected resource ARN. The implicit reference to aws_lb.carddemo
  # creates a Terraform dependency edge — `terraform apply` will create
  # or update the ALB before reaching this resource.
  resource_arn = aws_lb.carddemo.arn

  # Mandatory common tag set per the folder summary / AAP §0.7
  # conventions (Project / Environment / Owner / ManagedBy). The
  # provider's default_tags block in main.tf already applies these
  # implicitly; the explicit `tags = local.common_tags` line is a
  # belt-and-braces pattern matched across every sibling .tf file in
  # this module for state-level clarity.
  tags = local.common_tags
}

# =============================================================================
# Section 2 — Shield Advanced protection group
# =============================================================================
# Groups one or more Shield-protected resources together for unified
# CloudWatch monitoring, dashboard aggregation, and SRT visibility.
#
# Why group a single resource?
#   Even with one member, the protection group establishes a stable named
#   entity in the Shield console for the CardDemo deployment. As the
#   deployment expands (additional ALBs for new regions, NLBs for
#   non-HTTP services, CloudFront distributions for static content), new
#   protected resources can be added to this group's `members` list
#   without renaming or restructuring CloudWatch alarms keyed off the
#   protection_group_id. The group also provides a single CloudWatch
#   namespace dimension (`ProtectionGroupId`) for cross-resource metrics.
#
# Aggregation / pattern combination:
#   * aggregation = "MAX" — Shield CloudWatch metrics for the group are
#     emitted as the maximum value across all members rather than SUM or
#     MEAN. For DDoS detection we care about the worst-affected resource
#     in the group surfacing the alarm signal; SUM would over-count and
#     MEAN would dilute the signal when only one resource is under
#     attack. (Per the AWS Shield API reference: SUM, MEAN, or MAX.)
#   * pattern = "ARBITRARY" — Members are an explicit, hand-picked list.
#     Alternative patterns are ALL (every Shield-protected resource in
#     the account joins automatically — too broad for CardDemo's
#     intentional grouping) and BY_RESOURCE_TYPE (every resource of a
#     given AWS::ElasticLoadBalancingV2::LoadBalancer-style type joins
#     automatically — too coarse). ARBITRARY requires the `members` list
#     and is the safest pattern when membership is curated.
#
# Members:
#   * `aws_lb.carddemo.arn` — the only protected resource today.
#     Add additional ARNs (NLBs, CloudFront distributions, etc.) here
#     in the future when they enter Shield Advanced protection.
#
# depends_on:
#   Explicit dependency on `aws_shield_protection.alb` ensures the ALB
#   is registered as a Shield-protected resource BEFORE Shield attempts
#   to add it to the protection group. Without this ordering, the
#   protection group creation can race the protection registration and
#   fail with `InvalidParameterException: members must be protected
#   resources`. Terraform infers no implicit dependency on
#   aws_shield_protection.alb here (we reference aws_lb.carddemo.arn
#   directly, not aws_shield_protection.alb.resource_arn) so the
#   depends_on block is required.
#
# Members exposed for downstream consumers (per the file schema):
#   protection_group_id, protection_group_arn, aggregation, pattern,
#   members, tags_all.
# =============================================================================

resource "aws_shield_protection_group" "carddemo" {
  # Conditional on Shield Advanced enablement — the protection group is
  # only meaningful when there is at least one Shield-protected resource
  # to group, and Shield-protected resources only exist when the
  # subscription is active.
  count = var.shield_advanced_enabled ? 1 : 0

  # Logical identifier for the protection group. Must be unique within
  # the AWS account. Matches the `carddemo-<env>-<suffix>` naming
  # convention.
  protection_group_id = "carddemo-${var.environment}-protection-group"

  # Statistical aggregation across group members. MAX surfaces the
  # worst-affected member in CloudWatch metrics — the right choice for
  # DDoS detection where a single resource under attack must trigger
  # alarms regardless of how many other unaffected resources are in the
  # group.
  aggregation = "MAX"

  # ARBITRARY membership pattern — `members` is the authoritative list
  # of resource ARNs in this group. Required for hand-curated groups
  # (versus ALL or BY_RESOURCE_TYPE auto-population patterns).
  pattern = "ARBITRARY"

  # Explicit member list. The ALB is currently the only internet-facing
  # AWS resource in the CardDemo deployment, so it is the only member.
  # When additional internet-facing resources are added (e.g., a future
  # CloudFront distribution for static asset delivery, an NLB for an
  # internal-to-external service), append their ARNs here.
  members = [aws_lb.carddemo.arn]

  # Mandatory common tags (Project / Environment / Owner / ManagedBy).
  tags = local.common_tags

  # Ordering safeguard — ensure the protection registration completes
  # before the group tries to claim the resource as a member.
  depends_on = [aws_shield_protection.alb]
}

# =============================================================================
# Section 3 — Shield Application Layer Automatic Response
# =============================================================================
# Configures Shield Advanced to automatically respond to detected L7
# (HTTP / HTTPS) DDoS attacks against the ALB by writing a rate-based
# BLOCK rule into the ALB's WAFv2 Web ACL (the Web ACL is associated to
# the ALB by aws_wafv2_web_acl_association.alb in waf.tf). When Shield
# detects the attack has subsided, the automatic-response rule is removed
# without operator intervention.
#
# This is the L7 piece of Shield Advanced's protection. The L3 / L4
# mitigation runs at the AWS network edge automatically once Shield
# Advanced is enabled (no resource needed), but L7 mitigation requires
# this opt-in resource because it modifies the customer's own Web ACL.
#
# Prerequisites enforced by depends_on:
#   * aws_shield_protection.alb — Shield must consider the ALB a
#     protected resource before automatic-response configuration is
#     accepted. Failure mode without this dependency: AccessDeniedException
#     ("resource not protected by Shield Advanced").
#   * aws_wafv2_web_acl_association.alb — the ALB must already have a
#     WAFv2 Web ACL associated, because Shield writes the rate-based
#     BLOCK rule INTO that associated Web ACL. Failure mode without this
#     dependency: InvalidParameterException ("the resource ARN is not
#     associated with a Web ACL"). The association is created in waf.tf
#     and Terraform infers no implicit dependency on it here (we
#     reference only aws_lb.carddemo.arn) — depends_on is required.
#
# action:
#   * "BLOCK" — production posture. Detected L7 attack traffic is
#     immediately blocked at the WAF edge with HTTP 403, never reaching
#     the ECS Fargate tasks. This satisfies the AAP §0.6.6 / §0.7.2 PCI-DSS
#     control "AWS WAF + Shield on ALB for all public-facing endpoints"
#     and the Phase 4 directive of the agent prompt
#     ("automatic_response.action = \"BLOCK\": production should BLOCK").
#   * "COUNT" — staging / tuning posture. Detected attack traffic is
#     counted (CloudWatch metric DDoSAttackRequestsPerSecond) but NOT
#     blocked. Useful for observation-only validation in non-prod
#     environments before enabling enforcement. Not used by default —
#     operators set this manually in non-prod tfvars via a follow-up
#     configuration if desired.
#
# Single-ALB scope:
#   Each automatic-response configuration protects exactly one
#   resource_arn. Multi-ALB deployments would declare one resource per
#   ALB. The CardDemo deployment has one ALB so this is a single
#   resource.
#
# Members exposed for downstream consumers (per the file schema):
#   resource_arn, action.
# =============================================================================

resource "aws_shield_application_layer_automatic_response" "alb" {
  # Conditional on Shield Advanced — automatic response is a Shield
  # Advanced feature; it cannot exist without an active subscription
  # and an active aws_shield_protection on the resource.
  count = var.shield_advanced_enabled ? 1 : 0

  # The ALB ARN to automatically respond on. Must be the same ARN that
  # aws_shield_protection.alb registered and that
  # aws_wafv2_web_acl_association.alb is associated with — Shield uses
  # the resource_arn to look up both the protection record and the
  # associated Web ACL.
  resource_arn = aws_lb.carddemo.arn

  # BLOCK in all environments per the agent-prompt Phase 4 instruction
  # ("production should BLOCK"). The action attribute accepts BLOCK or
  # COUNT; staging deployments can override this manually after initial
  # provisioning if they want a COUNT-only observation window — note
  # that switching action triggers an in-place update at the next
  # `terraform apply` (no resource replacement needed).
  action = "BLOCK"

  # Explicit dependency ordering. Terraform infers an edge to
  # aws_lb.carddemo through resource_arn, but the Shield Advanced API
  # additionally requires (a) the resource to already be Shield-
  # protected and (b) a Web ACL to already be associated. Both edges
  # are declared explicitly so apply ordering is deterministic.
  depends_on = [
    aws_shield_protection.alb,
    aws_wafv2_web_acl_association.alb
  ]
}

# =============================================================================
# Section 4 — Shield Standard documentation (no Terraform resource)
# =============================================================================
# AWS Shield Standard is enabled by default at NO ADDITIONAL CHARGE on
# every AWS account and is automatically applied to:
#
#   * AWS edge services that terminate connections at the AWS network
#     edge (CloudFront, Route 53, AWS Global Accelerator).
#   * Elastic Load Balancing (Application, Network, and Gateway Load
#     Balancers) — including `aws_lb.carddemo` defined in alb.tf.
#   * Amazon EC2 instances (including the ENIs of Fargate tasks).
#
# Shield Standard protects against the most common, frequently observed
# DDoS attacks:
#
#   Layer 3 (network)   UDP reflection / amplification attacks
#                       (e.g., NTP, DNS, SSDP, memcached amplification),
#                       IP fragmentation attacks.
#   Layer 4 (transport) SYN flood attacks (Shield mitigates with SYN
#                       proxies at the edge), ACK / RST floods.
#   Layer 7 (application) Common HTTP-flood patterns when used in
#                       combination with AWS WAF managed rule groups
#                       (see waf.tf). Shield Standard alone does NOT
#                       protect against sophisticated L7 attacks —
#                       Shield Advanced + the Application Layer
#                       Automatic Response above is required for
#                       managed L7 mitigation.
#
# NO TERRAFORM RESOURCE IS REQUIRED FOR SHIELD STANDARD. The protection
# is implicit when an eligible resource (the ALB) is created. This
# section exists as documentation only.
#
# Shield Standard limitations (motivating Shield Advanced in prod):
#   * No SRT (Shield Response Team) access during attacks.
#   * No DDoS cost protection — if an attack causes auto-scaling or
#     data-transfer surges, the resulting AWS bill is the customer's
#     responsibility.
#   * Limited L7 attack mitigation (relies on WAF rules the customer
#     wrote themselves; no automatic rule injection).
#   * No CloudWatch DDoS metrics or attack history visibility.
#
# Operators evaluating whether to enable Shield Advanced should review
# the trade-off between the ~$3,000/month subscription fee and the cost
# protection / SRT engagement / L7 automatic response capabilities
# unlocked by the subscription. The default
# `var.shield_advanced_enabled = false` posture is appropriate for any
# environment that does not have a documented DDoS risk profile or
# regulatory requirement (e.g., PCI-DSS Service Provider attestation)
# that mandates Shield Advanced.
# =============================================================================
