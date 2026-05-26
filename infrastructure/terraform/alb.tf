###############################################################################
# infrastructure/terraform/alb.tf
#
# Application Load Balancer (ALB) for the CardDemo Spring Boot REST API.
#
# Purpose:
#   Provisions the internet-facing Application Load Balancer that fronts the
#   ECS Fargate service running the CardDemo Spring Boot application. The
#   ALB is the sole public-facing component of the workload (AAP §0.1.1:
#   "runtime (z/OS mainframe LPAR → ECS Fargate behind ALB)") and is the
#   ingress point for every REST API request enumerated in AAP §0.3.4 —
#   /api/auth/signin, /api/menu/*, /api/accounts/*, /api/cards/*,
#   /api/transactions/*, /api/billing/*, /api/reports/*, /api/admin/users/*.
#
#   Responsibilities discharged by this file (one resource per responsibility):
#
#     1. aws_security_group.alb
#        Network boundary for the ALB ENIs: accepts HTTPS (443) and HTTP (80)
#        from the internet, and egresses to the ECS task ENIs on var.app_port
#        (the Spring Boot listener port, default 8080). The companion
#        ECS task security group (defined in ecs.tf) restricts ingress on
#        var.app_port to this security group only.
#
#     2. aws_lb.carddemo
#        Internet-facing Application Load Balancer in public subnets, with:
#          * deletion protection in prod (var.environment == "prod"),
#          * HTTP/2 + cross-zone load balancing enabled,
#          * drop_invalid_header_fields = true (PCI-DSS Requirement 6.5
#            mitigates HTTP request smuggling),
#          * configurable idle timeout (var.alb_idle_timeout, default 60s),
#          * S3 access logging to aws_s3_bucket.logs under the
#            "alb-access-logs/" prefix (AAP §0.6.6 PCI-DSS Requirement 10
#            "track and monitor all access to network resources and
#            cardholder data").
#
#     3. aws_lb_target_group.carddemo
#        Target group fronting the ECS Fargate tasks:
#          * target_type = "ip" — required for Fargate awsvpc network mode
#            where each task has its own ENI (no EC2 instances to register),
#          * Spring Actuator /actuator/health/readiness probe path
#            (AAP §0.7.2 "Health endpoints via Spring Actuator integrated
#            with ECS health checks and ALB target group health checks"),
#          * sticky sessions (stickiness.type = "lb_cookie") for stateful
#            flows that replicate the CICS COMMAREA pattern from
#            app/cpy/COCOM01Y.cpy — multi-request transaction add
#            confirmation, paginated card list browse continuation,
#            paginated transaction list browse continuation, etc.
#            (AAP §0.3.4: "ALB sticky sessions are used for any flow that
#            depends on prior CICS COMMAREA state across multiple requests
#            (e.g., transaction add confirmation flow)"),
#          * lifecycle { create_before_destroy = true } so the listener
#            forward action can swing to a fresh target group during
#            destructive updates (AWS requires target groups attached to
#            listeners to be replaced via create-before-destroy).
#
#     4. aws_lb_listener.https
#        TLS 1.2+ HTTPS listener bound to the operator-supplied ACM
#        certificate (var.alb_acm_certificate_arn). Uses the AWS-managed
#        ELBSecurityPolicy-TLS13-1-2-2021-06 cipher suite, which permits
#        TLS 1.2 and TLS 1.3 only and disables every cipher / protocol
#        family below TLS 1.2 (per AAP §0.6.6: "TLS 1.2+ is enforced on
#        the ALB (HTTPS listener with ACM certificate)").
#
#     5. aws_lb_listener.http
#        HTTP listener on port 80 whose sole default action is an
#        HTTP 301 (Moved Permanently) redirect to the HTTPS listener.
#        No application traffic ever reaches a Fargate task via HTTP —
#        plain HTTP exists only so that browsers / clients fat-fingering
#        the scheme are upgraded to TLS instead of receiving a connection
#        reset.
#
# Replaces (legacy mainframe components — frozen, retained as behavioural
# ground truth under app/):
#   * The 3270 terminal frontend of the 17 CICS pseudo-conversational
#     transactions (COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC,
#     COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, COBIL00C,
#     CORPT00C, COUSR00C, COUSR01C, COUSR02C, COUSR03C) is replaced by
#     JSON REST endpoints behind this ALB.
#   * The CICS COMMAREA defined in app/cpy/COCOM01Y.cpy
#     (CARDDEMO-COMMAREA: CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM,
#     CDEMO-USER-ID, CDEMO-USER-TYPE, CDEMO-CUST-ID, CDEMO-ACCT-ID,
#     CDEMO-CARD-NUM, CDEMO-LAST-MAP, CDEMO-LAST-MAPSET — i.e., the
#     stateful screen-to-screen context that CICS preserved across
#     pseudo-conversations) is replaced primarily by JWT claims plus —
#     where the prior-screen state cannot be moved into a JWT — by
#     ALB sticky sessions backed by the lb_cookie set on this target
#     group (AAP §0.3.4 stateful flow handling).
#
# Coordination with sibling files:
#   * infrastructure/terraform/main.tf
#       Provides local.common_tags (mandatory Project / Environment / Owner
#       / ManagedBy tag set), data.aws_vpc.carddemo (VPC ID for the
#       security group and target group), and data.aws_subnets.public
#       (public subnet IDs for the internet-facing ALB).
#   * infrastructure/terraform/variables.tf
#       Provides var.environment, var.app_port, var.alb_acm_certificate_arn,
#       var.alb_idle_timeout, var.alb_access_logs_enabled.
#   * infrastructure/terraform/s3.tf
#       Provides aws_s3_bucket.logs — the SSE-KMS-encrypted destination
#       bucket for ALB access logs.
#   * infrastructure/terraform/ecs.tf
#       Registers the ECS service with aws_lb_target_group.carddemo.arn
#       and references aws_security_group.alb.id as the source of the
#       inbound rule on the ECS task security group's var.app_port port.
#   * infrastructure/terraform/waf.tf
#       Attaches aws_wafv2_web_acl.carddemo to aws_lb.carddemo.arn via
#       aws_wafv2_web_acl_association (AAP §0.6.6: "AWS WAF managed rule
#       groups for financial services attach to the ALB").
#   * infrastructure/terraform/shield.tf
#       Optionally protects aws_lb.carddemo.arn via aws_shield_protection
#       for L7 DDoS resilience.
#   * infrastructure/terraform/outputs.tf
#       Exports aws_lb.carddemo.dns_name, aws_lb.carddemo.zone_id, and
#       aws_lb.carddemo.arn for downstream Route53 record creation and
#       cross-stack consumers.
#
# References:
#   * AAP §0.1.1 — Core Refactoring Objective ("runtime z/OS LPAR →
#     ECS Fargate behind ALB").
#   * AAP §0.3.4 — User Interface Design (REST endpoints; JWT bearer
#     tokens; ALB sticky sessions for COMMAREA-equivalent flows).
#   * AAP §0.6.6 — Cross-Cutting: Audit, Observability, and PCI-DSS
#     (TLS 1.2+, WAF, access logging, encryption).
#   * AAP §0.7.2 — Operational requirements (Spring Actuator health
#     endpoints integrated with ALB target group health checks).
#   * app/cpy/COCOM01Y.cpy — the CICS COMMAREA structure whose stateful
#     screen-to-screen semantics motivate the sticky-session
#     configuration on the target group.
###############################################################################

# =============================================================================
# Section 1 — ALB security group
# =============================================================================
# Network boundary for the ALB ENIs. The ALB is internet-facing, so ingress
# from 0.0.0.0/0 on 443 and 80 is required. Egress is restricted to the
# Spring Boot app port (var.app_port, default 8080) so the ALB can ONLY
# reach the ECS tasks on that port — defence-in-depth alongside the ECS
# task security group, which in turn restricts ingress on var.app_port
# to this security group (see ecs.tf).
#
# Notes:
#   * cidr_blocks = ["0.0.0.0/0"] is intentional for the ingress rules.
#     AWS WAF (waf.tf) provides the L7 protection over this open ingress.
#     Without WAF, the ALB would be exposed to every HTTP-borne threat
#     class on the public internet; with WAF managed rule groups for
#     financial services attached (AAP §0.6.6), the ALB is hardened
#     against OWASP Top 10 and AWS-curated financial-services threats.
#   * The egress rule uses 0.0.0.0/0 on var.app_port for portability —
#     the destination Fargate task IPs change continuously as ECS
#     replaces tasks; constraining egress by IP would require coupling
#     to ECS internals and would break with every task replacement.
#     The actual gating is performed by the ECS task security group,
#     which is referenced symbolically (aws_security_group.alb.id) by
#     the task SG's ingress rule.
# =============================================================================

resource "aws_security_group" "alb" {
  name        = "${local.resource_name_prefix}-alb-sg"
  description = "ALB ingress (443 HTTPS, 80 HTTP) from internet; egress to ECS task on app port (var.app_port)"
  vpc_id      = data.aws_vpc.carddemo.id

  # ---------------------------------------------------------------------------
  # Ingress: HTTPS (443) from the public internet.
  # TLS termination occurs on the ALB itself (see aws_lb_listener.https
  # below). Inside the VPC, the ALB-to-ECS hop uses plain HTTP on
  # var.app_port — that hop never crosses any boundary outside the VPC
  # and is protected by the ECS task security group's tight ingress rule.
  # ---------------------------------------------------------------------------
  ingress {
    description = "HTTPS from internet (TLS 1.2+ enforced by ALB security policy)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # ---------------------------------------------------------------------------
  # Ingress: HTTP (80) from the public internet — exists ONLY so that the
  # HTTP listener (aws_lb_listener.http) can issue 301 redirects to HTTPS.
  # No application traffic ever flows over port 80; the listener's default
  # action is a redirect with no target group attached.
  # ---------------------------------------------------------------------------
  ingress {
    description = "HTTP from internet (redirected to HTTPS by listener default action)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # ---------------------------------------------------------------------------
  # Egress: only to the Spring Boot listener port (var.app_port) on the
  # ECS task ENIs. This intentionally restricts the ALB's outbound surface
  # to "the one thing it is supposed to talk to" — every other outbound
  # traffic class (DNS, NTP, AWS APIs) is unnecessary for an ALB.
  # ---------------------------------------------------------------------------
  egress {
    description = "Outbound to ECS task on Spring Boot app port (var.app_port)"
    from_port   = var.app_port
    to_port     = var.app_port
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-alb-sg"
  })

  # ---------------------------------------------------------------------------
  # create_before_destroy ensures that if the SG ever needs to be replaced
  # (e.g., changing the description, which is an immutable attribute), the
  # new SG is created and attached to the ALB before the old SG is
  # destroyed — avoiding a brief window where the ALB is associated with
  # a destroyed SG.
  # ---------------------------------------------------------------------------
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 2 — Application Load Balancer
# =============================================================================
# Internet-facing Application Load Balancer in the operator-supplied public
# subnets (data.aws_subnets.public.ids). Configured per AAP §0.6.6:
#
#   * enable_deletion_protection — gated on var.environment == "prod" so
#     that prod ALBs cannot be removed by `terraform destroy` (or an
#     accidental console click) without first toggling the flag off.
#     Dev / staging ALBs remain destructible for rapid teardown.
#   * enable_http2 — modern multiplexed transport (RFC 7540); reduces
#     connection-setup cost for clients that support it.
#   * enable_cross_zone_load_balancing — required for even task
#     distribution across AZs in the public-subnet pool. Without this,
#     a target in AZ-A can receive disproportionate traffic if the ALB
#     ENI in AZ-B has few clients connected.
#   * idle_timeout = var.alb_idle_timeout — default 60s matches the AWS
#     default and is appropriate for REST request/response cycles. Long-
#     polling clients (e.g., Spring DeferredResult) would need a higher
#     timeout, configurable via the variable.
#   * drop_invalid_header_fields = true — PCI-DSS Requirement 6.5:
#     reject HTTP requests with ambiguous, duplicate, or malformed
#     headers (mitigates HTTP request smuggling, header injection,
#     and downstream parsing inconsistencies).
#   * access_logs — writes one log line per request to aws_s3_bucket.logs
#     under the "alb-access-logs/" prefix. Logs are SSE-KMS-encrypted at
#     rest (by the logs bucket's default encryption configuration set
#     in s3.tf) and retained per the logs bucket's lifecycle policy.
#     PCI-DSS Requirement 10: "Track and monitor all access to network
#     resources and cardholder data."
#
# WAF attachment (waf.tf) and Shield protection (shield.tf) are decoupled
# resources that reference aws_lb.carddemo.arn — they are NOT inlined here
# to keep file responsibilities clear.
# =============================================================================

resource "aws_lb" "carddemo" {
  name               = "${local.resource_name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.public.ids

  # ---------------------------------------------------------------------------
  # Deletion protection — prod only. The conditional evaluates to true
  # when var.environment == "prod" and false otherwise; lifecycle gates
  # are governed by environment to keep prod safe and dev / staging
  # tearable.
  # ---------------------------------------------------------------------------
  enable_deletion_protection = var.environment == "prod"

  # ---------------------------------------------------------------------------
  # Performance and routing toggles. HTTP/2 multiplexing reduces client
  # connection-setup cost; cross-zone balancing equalises task load
  # across all AZs in the public subnet pool.
  # ---------------------------------------------------------------------------
  enable_http2                     = true
  enable_cross_zone_load_balancing = true
  idle_timeout                     = var.alb_idle_timeout

  # ---------------------------------------------------------------------------
  # PCI-DSS hardening: drop_invalid_header_fields rejects requests
  # whose header set contains duplicates / ambiguities (defeats classic
  # HTTP request smuggling), and preserve_host_header is left at its
  # default (false) so the ALB forwards a canonical Host header to
  # the backend rather than whatever the client sent.
  # ---------------------------------------------------------------------------
  drop_invalid_header_fields = true

  # ---------------------------------------------------------------------------
  # Access logging to the centralized logs bucket. The bucket policy on
  # aws_s3_bucket.logs (set in s3.tf) governs the write permission for
  # the ALB log-delivery service principal; the prefix scopes ALB logs
  # under "alb-access-logs/" so that they coexist with S3 server access
  # logs (under "s3-access-logs/") and CloudTrail data events without
  # cross-prefix collision.
  #
  # AAP §0.6.6 PCI-DSS audit requirement: "Track and monitor all access
  # to network resources and cardholder data."
  # ---------------------------------------------------------------------------
  access_logs {
    bucket  = aws_s3_bucket.logs.id
    prefix  = "alb-access-logs"
    enabled = var.alb_access_logs_enabled
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-alb"
  })
}

# =============================================================================
# Section 3 — Target group
# =============================================================================
# Fronts the ECS Fargate tasks running the Spring Boot application. Configured
# per AAP §0.3.4 (sticky sessions for COMMAREA-equivalent flows) and §0.7.2
# (Spring Actuator readiness probe).
#
# Critical attributes:
#
#   * target_type = "ip" — required for Fargate awsvpc network mode.
#     Each Fargate task has its own ENI in a private subnet; targets
#     are registered by IP, not by EC2 instance ID. The ECS service
#     (ecs.tf) registers task IPs automatically via the
#     load_balancer { target_group_arn = aws_lb_target_group.carddemo.arn,
#     container_name = "...", container_port = var.app_port } block.
#
#   * health_check.path = "/actuator/health/readiness" — Spring Boot
#     Actuator's readiness group. The group composition is explicitly
#     declared in `application.yml` under
#     `management.endpoint.health.group.readiness.include` and resolves
#     to:
#       - `readinessState`  Spring Boot's built-in readiness state
#                           transitions (broker post-start to live).
#       - `db`              the auto-configured DataSourceHealthIndicator
#                           probing the configured HikariCP DataSource.
#       - `redis`           the auto-configured RedisHealthIndicator
#                           probing the Lettuce connection factory.
#       - `kafka`           the custom KafkaHealthIndicator (introduced
#                           by QA CP11 M-3 fix — Spring Boot 3.x does
#                           NOT auto-configure a Kafka health indicator
#                           on its own).
#     A 200 response means the task is ready to receive traffic; any
#     other response drains the task from the rotation. Healthy /
#     unhealthy thresholds and the timeout / interval are tuned so that
#     a transient blip (e.g., a single failed DB query) does not
#     de-register the task, but a sustained failure (3 consecutive
#     failures over 90 seconds) does. This pairs with the ECS task
#     health check on the same path (set in ecs.tf) for two-tier health
#     enforcement.
#
#   * stickiness — lb_cookie strategy with 1-hour cookie_duration.
#     The ALB injects an AWSALB cookie on the first response of a new
#     client; subsequent requests with the cookie are routed to the
#     same target. This replicates the CICS pseudo-conversational
#     semantic where a screen-to-screen flow stays "attached" to the
#     same backend across requests (AAP §0.3.4: "ALB sticky sessions
#     are used for any flow that depends on prior CICS COMMAREA state
#     across multiple requests (e.g., transaction add confirmation
#     flow)").
#
#   * deregistration_delay = 30 — when the ECS service drains a task,
#     the ALB waits 30s for in-flight requests to complete before
#     terminating the connection. This is below the default 300s and
#     suits short-lived REST requests; long-running batch endpoints
#     should not be served directly via this target group.
#
#   * lifecycle { create_before_destroy = true } — required by AWS for
#     target groups attached to a listener: the listener's
#     default_action.target_group_arn forward must swing to a new TG
#     before the old TG can be deleted.
# =============================================================================

resource "aws_lb_target_group" "carddemo" {
  name        = "${local.resource_name_prefix}-tg"
  port        = var.app_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.carddemo.id

  # ---------------------------------------------------------------------------
  # Draining window: 30s gives in-flight REST requests time to complete
  # while a task is being replaced by a rolling ECS deploy. Below the AWS
  # default of 300s because REST requests in this workload complete in
  # sub-second time; longer drains needlessly delay rolling deploys.
  # ---------------------------------------------------------------------------
  deregistration_delay = 30

  # ---------------------------------------------------------------------------
  # Spring Actuator readiness probe — AAP §0.7.2: "Health endpoints via
  # Spring Actuator integrated with ECS health checks and ALB target
  # group health checks."
  #
  # path = "/actuator/health/readiness" reaches the Boot Actuator's
  # readiness group endpoint (configured by management.endpoint.health.
  # probes.enabled=true in application.yml, with the group composition
  # explicitly enumerated under management.endpoint.health.group.readiness
  # .include = readinessState,db,redis,kafka). The endpoint returns 200
  # only when every contributing component reports UP; a 503 surfaces
  # when database, Redis, or Kafka connectivity is lost (the Kafka
  # component is contributed by KafkaHealthIndicator — see QA CP11 M-3).
  #
  # Threshold tuning:
  #   * healthy_threshold = 2   — two consecutive 200s register the
  #     target as healthy. Avoids flapping on transient blips.
  #   * unhealthy_threshold = 3 — three consecutive non-200s de-register
  #     the target. Tolerant of a single failed probe; conservative
  #     enough that a sustained failure removes the task promptly.
  #   * interval = 30s          — every 30 seconds (the default).
  #     Frequent enough to catch issues quickly; not so frequent as to
  #     impose health-check load on the application.
  #   * timeout = 10s           — wait up to 10s for the response.
  #     Forgiving for cold-start scenarios where the JVM is still
  #     warming up but the container is already running.
  #   * matcher = "200"         — exact HTTP 200 only; 503 (Service
  #     Unavailable) from the Actuator readiness endpoint correctly
  #     fails the check.
  # ---------------------------------------------------------------------------
  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    port                = "traffic-port"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 10
    matcher             = "200"
  }

  # ---------------------------------------------------------------------------
  # Sticky sessions — lb_cookie strategy.
  #
  # AAP §0.3.4: "ALB sticky sessions are used for any flow that depends on
  # prior CICS COMMAREA state across multiple requests (e.g., transaction
  # add confirmation flow)."
  #
  # The CICS COMMAREA (app/cpy/COCOM01Y.cpy : CARDDEMO-COMMAREA) carried
  # state across pseudo-conversational screen transitions: the previous
  # transaction ID, previous program, user ID and type, customer / account
  # / card identifiers, and the last map / mapset displayed. In the Java
  # target, JWT claims absorb the user-bound fields (USER-ID, USER-TYPE);
  # the remaining screen-flow context (multi-step transaction-add wizard,
  # paginated browse continuation) is stickiness-bound to a single backend
  # instance so the per-request DTO state can be carried over by the same
  # JVM instance.
  #
  # cookie_duration = 3600s (1 hour) — long enough for the longest known
  # stateful flow (paginated transaction browse with operator review),
  # short enough that an abandoned session releases its target affinity
  # well before the next deployment cycle.
  # ---------------------------------------------------------------------------
  stickiness {
    type            = "lb_cookie"
    cookie_duration = 3600
    enabled         = true
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-tg"
  })

  # ---------------------------------------------------------------------------
  # AWS requires create_before_destroy on target groups attached to a
  # listener default_action — otherwise destructive updates (e.g.,
  # changing the target_type) fail with "target group is in use by a
  # listener".
  # ---------------------------------------------------------------------------
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 4 — HTTPS listener (TLS 1.2+ enforced)
# =============================================================================
# Terminates TLS for every inbound REST request and forwards to the
# carddemo target group. Configured per AAP §0.6.6:
#
#   * ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06" — the AWS-
#     managed security policy that permits TLS 1.2 and TLS 1.3 only and
#     disables every cipher / protocol family below TLS 1.2 (no SSLv2,
#     SSLv3, TLS 1.0, or TLS 1.1). This satisfies PCI-DSS Requirement
#     4.1 ("Strong cryptography and security protocols") and AAP §0.6.6
#     ("TLS 1.2+ is enforced on the ALB").
#
#   * certificate_arn = var.alb_acm_certificate_arn — the operator-
#     supplied ACM certificate ARN (validated by variables.tf to be a
#     well-formed certificate ARN). ACM auto-renews the certificate
#     before expiration; the ARN remains stable across renewals.
#
#   * default_action forwards to aws_lb_target_group.carddemo.arn — the
#     application currently serves all paths from one target group;
#     listener rules for path-based routing are not required (would be
#     added in a separate file or this file's Section 6 if introduced).
# =============================================================================

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.carddemo.arn
  port              = 443
  protocol          = "HTTPS"

  # ---------------------------------------------------------------------------
  # ELBSecurityPolicy-TLS13-1-2-2021-06 is the AWS-managed security policy
  # that:
  #   * Permits TLS 1.2 and TLS 1.3.
  #   * Disables every protocol below TLS 1.2 (TLS 1.1, TLS 1.0, SSLv3,
  #     SSLv2).
  #   * Selects modern AEAD cipher suites (ECDHE-RSA / ECDHE-ECDSA with
  #     AES-128-GCM, AES-256-GCM, CHACHA20-POLY1305).
  #   * Disables legacy CBC ciphers, RC4, and 3DES.
  # AAP §0.6.6: "TLS 1.2+ is enforced on the ALB (HTTPS listener with
  # ACM certificate)."
  # ---------------------------------------------------------------------------
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.alb_acm_certificate_arn

  # ---------------------------------------------------------------------------
  # Default action: forward all matching requests to the carddemo target
  # group. Because no listener rules are declared, every request lands
  # on this default action.
  # ---------------------------------------------------------------------------
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.carddemo.arn
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-alb-https-listener"
  })
}

# =============================================================================
# Section 5 — HTTP listener (redirect to HTTPS)
# =============================================================================
# A clear-text HTTP listener on port 80 whose sole default action is an
# HTTP 301 (Moved Permanently) redirect to the HTTPS listener. No
# application traffic ever reaches a backend over HTTP — the redirect is
# emitted by the ALB itself, before any data leaves the load balancer.
#
# Why HTTP 301 (and not 308):
#   * 301 is universally understood by every HTTP client, including older
#     curl / wget / library versions.
#   * 301 + GET / HEAD methods preserves the semantics expected by browsers
#     that hit the bare hostname over HTTP.
#   * For non-idempotent methods (POST / PUT / DELETE), modern clients
#     re-issue the original method on the Location URL; mobile / desktop
#     browsers issue a GET (which is correct for navigations but means
#     POST submissions arriving over HTTP must be retried via the form
#     submission). The application MUST NOT rely on HTTP POST being
#     forwarded — callers must use HTTPS from the start.
#
# Why a separate listener (and not just an HTTPS listener with an HTTP
# redirect rule):
#   * AWS requires a per-port listener; the HTTPS listener listens on 443,
#     so port 80 needs its own listener resource regardless.
#   * The redirect block on the HTTP listener has no target group (and
#     none is needed), keeping the resource minimal.
# =============================================================================

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.carddemo.arn
  port              = 80
  protocol          = "HTTP"

  # ---------------------------------------------------------------------------
  # Default action: 301 redirect to HTTPS on port 443, preserving the
  # incoming host, path, and query string (the redirect block's defaults
  # cover host="#{host}", path="/#{path}", query="#{query}"). The
  # explicit port and protocol overrides force the upgrade to TLS even
  # when the client originally sent the request without a port.
  # ---------------------------------------------------------------------------
  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }

  tags = merge(local.common_tags, {
    Name = "${local.resource_name_prefix}-alb-http-listener"
  })
}
