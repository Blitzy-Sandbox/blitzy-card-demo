###############################################################################
# infrastructure/terraform/msk.tf
#
# Amazon MSK (Managed Streaming for Apache Kafka) - event-driven messaging
# layer for the CardDemo migration.
#
# Purpose:
#   Provisions the Multi-AZ Amazon MSK cluster that hosts the four primary
#   Kafka topics underpinning the CardDemo event-driven transaction pipeline.
#   The cluster terminates client traffic on SASL_SSL (port 9098) with IAM
#   authentication, encrypts all data at rest with the shared CardDemo
#   customer-managed KMS key (aws_kms_key.carddemo from kms.tf), and ships
#   broker logs to a KMS-encrypted CloudWatch log group with 365-day
#   retention (PCI-DSS-aligned audit log).
#
# Topics (created post-cluster-provisioning via an init job - see comments at
# the bottom of this file; topic creation is NOT a Terraform resource for
# MSK with the hashicorp/aws provider):
#
#   transaction.posted  - partition_key = ACCT-ID (11-digit, zero-padded)
#                         Producer:  TransactionAddService (COBOL: COTRN02C),
#                                    BillPaymentService    (COBOL: COBIL00C)
#                         Consumer:  AuditLogService, ReportingService,
#                                    downstream account services
#
#   account.updated     - partition_key = ACCT-ID
#                         Producer:  AccountUpdateService (COBOL: COACTUPC),
#                                    BillPaymentService   (COBOL: COBIL00C),
#                                    InterestCalculationService
#                                                          (COBOL: CBACT04C)
#                         Consumer:  CacheService (Redis cache invalidation),
#                                    AuditLogService
#
#   ledger.balanced     - partition_key = ACCT-ID
#                         Producer:  End-of-day reconciliation Spring Batch
#                                    jobs (CombineTransactionsJob etc.)
#                         Consumer:  ReportingService
#
#   report.requested    - partition_key = USER-ID (8-char SEC-USR-ID)
#                         Producer:  ReportSubmissionService (COBOL: CORPT00C)
#                                    REPLACES the CICS TDQ "JOBS" queue that
#                                    CORPT00C originally wrote to as the
#                                    online-to-batch bridge.
#                         Consumer:  Step Functions trigger Lambda - submits
#                                    an AWS Batch job for the requested
#                                    report (see stepfunctions.tf, batch.tf).
#
# Replaces:
#   CICS Transient Data Queue (TDQ) - the only explicit online-to-batch bridge
#   in the source z/OS CardDemo system. Per AAP S0.1.1, the CICS TDQ JOBS
#   queue used by app/cbl/CORPT00C.cbl maps to the report.requested topic on
#   this cluster, and inter-service event communication between
#   TransactionAddService / BillPaymentService / AccountUpdateService /
#   InterestCalculationService maps to the transaction.posted /
#   account.updated topics. See app/jcl/POSTTRAN.jcl (STEP15 PGM=CBTRN02C)
#   for the source batch DALYTRAN/DALYREJS flow that produces events now
#   published onto transaction.posted.
#
# Ordering guarantee (AAP S0.6.5):
#   Every topic uses partition_count >= producer concurrency
#   (var.msk_topic_partitions, default 12 - sized to a 12-task ECS service).
#   The partition key (account ID for transactional topics, user ID for
#   report.requested) is hashed by the default DefaultPartitioner (murmur2),
#   so every event for a given key lands on a single partition. With
#   min.insync.replicas=2, replication_factor=3, and
#   unclean.leader.election.enable=false (set in the configuration below),
#   per-key ordering is preserved end-to-end across leader elections and
#   AZ failures - matching the COBOL "process each account's transactions
#   in sequence" semantics of the original CICS pipeline.
#
# Security posture (AAP S0.6.6, S0.7.1):
#   * IAM authentication only - client_authentication { sasl { iam = true } }.
#     No SASL/SCRAM, no plaintext, no anonymous. The ECS task role and Batch
#     job role attach kafka-cluster:Connect|Describe*|WriteData|ReadData
#     policies (see iam.tf) to obtain credentials at runtime.
#   * TLS 1.2+ in transit - encryption_in_transit { client_broker = "TLS";
#     in_cluster = true }. Both client->broker (port 9098) and broker->broker
#     traffic are TLS-encrypted. No plaintext listener is exposed.
#   * KMS CMK at rest - encryption_at_rest_kms_key_arn references
#     aws_kms_key.carddemo from kms.tf. The CMK's policy already includes an
#     AllowMSK statement granting kafka.amazonaws.com kms:Decrypt /
#     GenerateDataKey / DescribeKey (see kms.tf Statement 11).
#   * Broker logs encrypted - the CloudWatch log group provisioned in
#     Section 3 uses the same KMS CMK. The log group's name follows the
#     CardDemo convention "/aws/msk/carddemo-<env>/broker" and retains
#     entries for 365 days (long enough to satisfy PCI-DSS DSS Requirement
#     10.7 "audit trail history retained for at least one year").
#   * Network isolation - brokers reside in the private subnets supplied via
#     data.aws_subnets.private (main.tf); the MSK security group exposes no
#     ingress in this file - ingress rules from ECS / Batch SGs are attached
#     in ecs.tf and batch.tf as aws_security_group_rule resources to break
#     potential SG dependency cycles.
#
# Configuration (AAP S0.6.5 - PCI-DSS-hardened server.properties):
#   * auto.create.topics.enable=false        - no accidental topic creation;
#                                              every topic must be explicit
#   * default.replication.factor=3           - via var.msk_topic_replication_factor
#   * min.insync.replicas=2                  - producer with acks=all guarantees
#                                              no data loss even with one broker
#                                              failure
#   * num.partitions=12                      - via var.msk_topic_partitions
#   * offsets.retention.minutes=10080        - 7-day consumer offset retention
#   * log.retention.hours=168                - 7-day topic data retention
#   * log.retention.bytes=-1                 - no byte-based pruning
#   * delete.topic.enable=true               - operationally allow topic deletion
#                                              (gated by IAM, not the broker)
#   * unclean.leader.election.enable=false   - never elect from an out-of-sync
#                                              replica - guarantees consistency
#                                              at the cost of availability
#   * compression.type=producer              - preserve producer-side compression
#   * transaction.state.log.replication.factor=3 / .min.isr=2
#                                            - durable transactional state log
#
# Monitoring:
#   * enhanced_monitoring = "PER_TOPIC_PER_BROKER" - finest-grained AWS/Kafka
#     CloudWatch metrics. Required so that ECS Service Auto Scaling can react
#     to consumer lag at per-topic-per-broker resolution per AAP S0.1.1
#     ("auto-scaling policies based on CPU and MSK consumer lag").
#   * open_monitoring.prometheus.{jmx,node}_exporter.enabled_in_broker = true
#     - exposes the Kafka JMX metrics and node exporter on the broker for
#     scraping by a Prometheus-compatible agent (sidecar or AMP). Disable in
#     dev to save broker CPU if not used.
#
# Resources provisioned (in dependency order Terraform will resolve):
#   1. aws_security_group.msk             - broker ENI security group.
#   2. aws_msk_configuration.carddemo     - immutable revisioned server.properties.
#   3. aws_cloudwatch_log_group.msk_broker - KMS-encrypted log group for the
#                                            broker logs referenced by (4).
#   4. aws_msk_cluster.carddemo           - the cluster itself. First-apply
#                                            takes 25-45 minutes - this is
#                                            the longest-provisioning resource
#                                            in the entire CardDemo module.
#
# Outputs (consumed via outputs.tf):
#   * msk_cluster_arn               = aws_msk_cluster.carddemo.arn
#   * msk_bootstrap_servers         = aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam
#   * msk_zookeeper_connect_string  = aws_msk_cluster.carddemo.zookeeper_connect_string_tls
#   * msk_log_group_name            = aws_cloudwatch_log_group.msk_broker.name
#   The msk_bootstrap_servers value is surfaced to the Spring Boot container
#   as the MSK_BOOTSTRAP_SERVERS environment variable (AAP S0.7.2 non-sensitive
#   variable contract). The Spring KafkaConfig (src/main/java/com/awsm2/
#   carddemo/config/KafkaConfig.java) reads MSK_BOOTSTRAP_SERVERS at boot.
#
# Tag set:
#   The four mandatory tags (Project, Environment, Owner, ManagedBy) are
#   applied automatically by the provider default_tags block declared in
#   main.tf. The two additional tags merged in here (Name, Purpose) provide
#   human-readable identifiers in the AWS Console.
#
# References:
#   * AAP S0.1.1  - Core refactoring objective; event-driven messaging via MSK.
#   * AAP S0.6.5  - MSK Topic Ordering Guarantees; partition strategy.
#   * AAP S0.6.6  - Cross-Cutting: Audit, Observability, PCI-DSS (TLS+KMS).
#   * AAP S0.7.1  - Refactoring-Specific Rules (KMS CMK encryption at rest).
#   * AAP S0.7.2  - Non-sensitive env-var contract (MSK_BOOTSTRAP_SERVERS).
#   * Folder summary in ../README.md - mandatory tag set + naming convention.
#   * app/cbl/CORPT00C.cbl - source online program that previously published
#                            to the CICS TDQ JOBS queue; now publishes to the
#                            report.requested MSK topic.
###############################################################################

# =============================================================================
# Section 1 - MSK broker security group
# =============================================================================
# The MSK broker ENIs reside in the private subnets supplied by
# data.aws_subnets.private (main.tf). This security group governs network
# access to those ENIs:
#
#   * Ingress  - intentionally omitted in this file. ecs.tf and batch.tf
#                attach aws_security_group_rule resources whose source is
#                their own task / job security group and whose destination
#                is this SG (port 9098 / TCP). Attaching ingress rules in
#                the consumer files prevents a Terraform dependency cycle
#                between this SG and the consumer SGs - the consumer SGs
#                already need to exist to reference them as the source of a
#                rule, but they often also need to reference the MSK SG to
#                allow egress (or in IAM auth flows the egress is open
#                anyway). Keeping ingress here would create the cycle.
#
#   * Egress   - permissive (all protocols, all destinations). Brokers
#                legitimately need outbound connectivity for intra-cluster
#                replication, KMS data-key decryption, CloudWatch log
#                shipping, Secrets Manager access (if rotating credentials),
#                and Glue / S3 (for connector sinks if enabled later).
#                Rather than enumerating every AWS service endpoint, the
#                Interface VPC endpoints provisioned in main.tf keep that
#                traffic on the AWS backbone and the actual authorization
#                surface is enforced by KMS / Secrets Manager / IAM policies.
#
# Lifecycle:
#   create_before_destroy = true. Replacing the SG (e.g., on rename) forces
#   replacement of the MSK cluster ("security_groups" on
#   broker_node_group_info is a ForceNew attribute); create_before_destroy
#   minimises blast radius if a name collision forces an in-place rename.
#
# Members exposed (per the file schema):
#   id, arn, name, vpc_id, description, egress, owner_id, tags_all.
# =============================================================================

resource "aws_security_group" "msk" {
  name        = "carddemo-${var.environment}-msk-sg"
  description = "Amazon MSK - inbound from ECS task, AWS Batch on port 9098 (IAM auth); ingress rules attached by ecs.tf and batch.tf to avoid SG cycles"
  vpc_id      = data.aws_vpc.carddemo.id

  # NOTE: ingress rules are intentionally omitted here. ecs.tf and batch.tf
  # attach aws_security_group_rule resources that reference this SG's id as
  # the destination on port 9098 (SASL_SSL / IAM). This prevents a Terraform
  # dependency cycle between the MSK SG and the consumer (ECS / Batch) SGs.

  egress {
    description = "All outbound - intra-cluster replication, KMS, Secrets Manager, CloudWatch Logs via VPC endpoints"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-msk-sg"
    Purpose = "Amazon MSK broker security group (port 9098 SASL_SSL+IAM)"
  })

  # Replacing this SG would force a replacement of the MSK cluster
  # (security_groups is ForceNew on aws_msk_cluster.broker_node_group_info).
  # create_before_destroy minimises blast radius if a name collision forces
  # an in-place rename.
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 2 - MSK configuration (server.properties overrides)
# =============================================================================
# aws_msk_configuration is an immutable, revisioned resource: every time the
# server_properties heredoc changes, Terraform creates a new revision. The
# aws_msk_cluster.configuration_info block references both the configuration
# arn and the latest_revision attribute below; modifying server_properties
# therefore triggers a configuration update on the cluster (rolling restart
# of the brokers - typically 20-30 minutes for a 3-node cluster).
#
# server.properties hardening (AAP S0.6.5 - PCI-DSS):
#
#   auto.create.topics.enable=false
#     Disable Kafka's default behaviour of creating a topic on first
#     produce / consume. Topics must be explicit per AAP S0.6.5 so that
#     partition count, replication factor, and retention can be sized
#     intentionally. The four CardDemo topics (transaction.posted,
#     account.updated, ledger.balanced, report.requested) are created
#     by an init job documented at the bottom of this file.
#
#   default.replication.factor=${var.msk_topic_replication_factor}
#     Per-topic default; explicit topic creation may override. The default
#     of 3 (variables.tf) tolerates one AZ failure with min.insync.replicas=2
#     still met.
#
#   min.insync.replicas=2
#     With acks=all on the producer side, a produce request only succeeds
#     once 2 replicas (out of 3) have written the message. A single broker
#     failure does not block production; a double failure (two brokers down)
#     fails the produce. This is the durability / availability tradeoff
#     required by AAP S0.6.5 ("end-to-end ordering invariant").
#
#   num.partitions=${var.msk_topic_partitions}
#     Per-topic default (12 by default - variables.tf). Sized to match
#     producer concurrency (12-task ECS service). Sufficient for per-account
#     ordering with high parallelism.
#
#   offsets.retention.minutes=10080
#     7-day consumer-offset retention. A consumer group that disappears for
#     up to 7 days can resume from its last committed offset; longer absences
#     reset to earliest / latest per the consumer's auto.offset.reset config.
#
#   log.retention.hours=168
#     7-day topic data retention. Beyond 7 days the broker prunes segments.
#     Per-topic overrides (retention.ms on individual topics) can extend
#     retention for compliance topics if required.
#
#   log.retention.bytes=-1
#     Disable size-based pruning - retention is purely time-based at the
#     server level. Per-topic retention.bytes overrides this.
#
#   delete.topic.enable=true
#     Allow operational topic deletion (e.g., for misconfigured topics).
#     IAM policies on the cluster restrict who can issue DeleteTopic.
#
#   unclean.leader.election.enable=false
#     CRITICAL for AAP S0.6.5 ordering. NEVER elect a replica that is not
#     in-sync as the new leader. This guarantees no data loss but allows
#     a partition to become unavailable if all in-sync replicas fail. For
#     financial event streams this is the correct tradeoff.
#
#   compression.type=producer
#     Preserve whatever compression the producer chose (snappy / lz4 / zstd
#     / none). The broker does not re-compress; this saves CPU at the broker
#     and respects the producer's tradeoff between CPU and network.
#
#   transaction.state.log.replication.factor / .min.isr
#     Replication factor and ISR floor for the internal __transaction_state
#     topic that backs Kafka's transactional producer guarantees. Matched
#     to the topic-level settings for durability.
#
# Members exposed (per the file schema):
#   arn, id, latest_revision, name, description, kafka_versions, server_properties.
# =============================================================================

resource "aws_msk_configuration" "carddemo" {
  name           = "carddemo-${var.environment}-msk-config"
  kafka_versions = [var.msk_kafka_version]
  description    = "Custom Kafka server.properties for CardDemo (PCI-DSS hardened per AAP S0.6.5)"

  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=${var.msk_topic_replication_factor}
    min.insync.replicas=2
    num.partitions=${var.msk_topic_partitions}
    offsets.retention.minutes=10080
    log.retention.hours=168
    log.retention.bytes=-1
    delete.topic.enable=true
    unclean.leader.election.enable=false
    compression.type=producer
    transaction.state.log.replication.factor=${var.msk_topic_replication_factor}
    transaction.state.log.min.isr=2
  EOT
}

# =============================================================================
# Section 3 - MSK broker CloudWatch log group
# =============================================================================
# The MSK cluster ships INFO-level broker logs (controller events, ISR
# changes, leader elections, partition reassignments, OOMs) to this log
# group. Retention is 365 days (PCI-DSS Requirement 10.7: "Audit trail
# history retained for at least one year"). The log group is encrypted at
# rest with the shared CardDemo CMK so that even broker-internal logs
# (which can include client connection metadata) are protected per
# AAP S0.6.6.
#
# Naming convention: /aws/msk/carddemo-<env>/broker
#   * "/aws/msk/" is the AWS-recommended prefix for MSK log groups (mirrored
#     by ElastiCache, RDS, ECS, etc. in this module).
#   * "carddemo-<env>" scopes the log group to the deployment environment.
#   * "/broker" distinguishes broker logs from any future log destinations
#     (e.g., connector logs, MSK Connect logs).
#
# KMS policy:
#   The AllowCloudWatchLogs statement in kms.tf (Statement 2) grants the
#   logs.<region>.amazonaws.com service principal kms:Encrypt* /
#   Decrypt* / ReEncrypt* / GenerateDataKey* / Describe* with an
#   ArnEquals condition on kms:EncryptionContext:aws:logs:arn restricted
#   to this account's log-group ARNs. This log group satisfies that
#   condition automatically.
#
# Members exposed (per the file schema):
#   arn, id, name, retention_in_days, kms_key_id, tags_all.
# =============================================================================

resource "aws_cloudwatch_log_group" "msk_broker" {
  name              = "/aws/msk/carddemo-${var.environment}/broker"
  retention_in_days = 365
  # F-CP6-TF-KMS-01: CloudWatch log group uses the dedicated
  # aws_kms_key.cloudwatch_kms CMK.
  kms_key_id = aws_kms_key.cloudwatch_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-msk-broker-log"
    Purpose = "Amazon MSK broker logs (ISR / controller / leader election / OOM)"
  })
}

# =============================================================================
# Section 4 - MSK cluster
# =============================================================================
# The CardDemo MSK cluster. Critical configuration choices:
#
#   * number_of_broker_nodes = var.msk_number_of_broker_nodes (default 3)
#     Must be a multiple of the AZ count covered by client_subnets. With
#     the default 3 brokers across 3 private subnets in 3 AZs, each AZ
#     hosts exactly one broker - the Multi-AZ failure mode this cluster
#     is designed for.
#
#   * broker_node_group_info.instance_type = var.msk_broker_instance_type
#     Defaults to "kafka.t3.small" (dev). Staging recommends "kafka.m5.large";
#     prod should size by observed throughput. The instance type drives both
#     compute (vCPU/RAM) and the maxmemory budget that the broker uses for
#     its page cache.
#
#   * broker_node_group_info.client_subnets = data.aws_subnets.private.ids
#     Brokers reside in private subnets only (AAP S0.7.1 network isolation).
#     The operator-supplied private_subnet_ids variable (variables.tf) must
#     cover at least number_of_broker_nodes / AZs to satisfy MSK's even-
#     distribution requirement.
#
#   * broker_node_group_info.security_groups = [aws_security_group.msk.id]
#     Single SG attachment - the MSK SG from Section 1. Additional SGs can
#     be appended here if future requirements (e.g., a separate
#     observability SG for Prometheus scraping) emerge.
#
#   * broker_node_group_info.storage_info.ebs_storage_info.volume_size
#     Per-broker EBS volume size from var.msk_ebs_volume_size. Sized to
#     absorb the rolling 7-day retention window of all topics + safety
#     margin; default 100 GiB for dev.
#
#   * client_authentication.sasl.iam = true
#     IAM-only client authentication. Clients (Spring Boot tasks in ECS,
#     batch jobs in AWS Batch, the Step Functions trigger Lambda) obtain
#     IAM-signed SASL credentials via the AWS MSK IAM auth library
#     (software.amazon.msk:aws-msk-iam-auth in pom.xml). No SASL/SCRAM
#     and no plaintext - this is the AAP S0.7.1 "no plaintext credentials"
#     guarantee for the event-stream layer.
#
#   * encryption_info.encryption_at_rest_kms_key_arn = aws_kms_key.carddemo.arn
#     KMS CMK encryption at rest (AAP S0.7.1). The CMK is shared across
#     every CardDemo data store - its policy in kms.tf (Statement 11
#     "AllowMSK") explicitly grants the kafka.amazonaws.com service
#     principal kms:Decrypt / GenerateDataKey / DescribeKey.
#
#   * encryption_info.encryption_in_transit.client_broker = "TLS"
#     Client->broker traffic uses TLS 1.2+ (the AWS-managed CA chain).
#     The companion port for plaintext (9092) is NOT exposed - clients
#     can only connect via the SASL_SSL listener on port 9098.
#
#   * encryption_info.encryption_in_transit.in_cluster = true
#     Broker->broker (replication) traffic is also TLS-encrypted, so the
#     replication path between brokers in different AZs is protected.
#
#   * configuration_info { arn / revision }
#     References the aws_msk_configuration.carddemo from Section 2.
#     Changes to server.properties trigger a rolling restart.
#
#   * enhanced_monitoring = "PER_TOPIC_PER_BROKER"
#     Finest-grained AWS/Kafka CloudWatch metric granularity. Required so
#     that ECS Service Auto Scaling can target consumer lag at per-topic-
#     per-broker resolution per AAP S0.1.1 ("auto-scaling policies based
#     on CPU and MSK consumer lag"). The other options (DEFAULT,
#     PER_BROKER, PER_TOPIC_PER_PARTITION) trade detail for cost.
#
#   * logging_info.broker_logs.cloudwatch_logs.enabled = true
#     Ship broker logs to the log group from Section 3. The companion
#     destinations (firehose_logs, s3_logs) are NOT enabled - CloudWatch
#     is the canonical destination per the AAP S0.6.6 audit-trail design.
#
#   * open_monitoring.prometheus.{jmx,node}_exporter.enabled_in_broker
#     Expose Kafka JMX metrics and node-level metrics on the broker for
#     Prometheus-compatible scraping. Disable in dev to save broker CPU
#     if not used.
#
# Lifecycle considerations:
#   * First-apply takes 25-45 minutes. This is the slowest resource in the
#     CardDemo module to provision. Subsequent applies that only modify
#     configuration / monitoring are faster (5-10 minutes rolling-restart).
#   * Updates to broker_node_group_info.instance_type or .storage_info
#     trigger a rolling restart. Updates to number_of_broker_nodes
#     trigger broker addition (subtraction requires manual intervention).
#   * Recreating the cluster (forced replacement) requires manual data
#     migration - there is no automatic data preservation across cluster
#     recreation. Avoid changes that force replacement in production.
#
# Members exposed (per the file schema):
#   arn, id, cluster_name, cluster_uuid, bootstrap_brokers,
#   bootstrap_brokers_sasl_iam, bootstrap_brokers_sasl_scram,
#   bootstrap_brokers_tls, bootstrap_brokers_public_sasl_iam,
#   zookeeper_connect_string, zookeeper_connect_string_tls,
#   current_version, encryption_info, kafka_version, number_of_broker_nodes,
#   tags_all.
# =============================================================================

resource "aws_msk_cluster" "carddemo" {
  cluster_name           = "carddemo-${var.environment}-msk"
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = var.msk_number_of_broker_nodes

  # ---------------------------------------------------------------------------
  # Broker compute, storage, network attachment.
  # ---------------------------------------------------------------------------
  broker_node_group_info {
    instance_type   = var.msk_broker_instance_type
    client_subnets  = data.aws_subnets.private.ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.msk_ebs_volume_size
      }
    }
  }

  # ---------------------------------------------------------------------------
  # IAM-only client authentication (AAP S0.7.1 - no plaintext credentials).
  # The omission of `sasl.scram = true` and the absence of any `tls` block
  # ensures that no other auth mechanism is enabled.
  # ---------------------------------------------------------------------------
  client_authentication {
    sasl {
      iam = true
    }
  }

  # ---------------------------------------------------------------------------
  # Encryption (AAP S0.6.6 + S0.7.1).
  # F-CP6-TF-KMS-01: Per-service CMK separation. MSK broker storage uses
  # the dedicated aws_kms_key.msk_kms rather than the shared
  # aws_kms_key.carddemo.
  # ---------------------------------------------------------------------------
  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.msk_kms.arn

    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  # ---------------------------------------------------------------------------
  # Custom server.properties (Section 2). Both arn and revision are pinned
  # so a future server-properties change must be acknowledged by Terraform
  # plan / apply rather than silently picked up.
  # ---------------------------------------------------------------------------
  configuration_info {
    arn      = aws_msk_configuration.carddemo.arn
    revision = aws_msk_configuration.carddemo.latest_revision
  }

  # ---------------------------------------------------------------------------
  # Enhanced monitoring (per-topic-per-broker CloudWatch metrics).
  # Required by ECS Service Auto Scaling for consumer-lag-based scaling.
  # ---------------------------------------------------------------------------
  enhanced_monitoring = "PER_TOPIC_PER_BROKER"

  # ---------------------------------------------------------------------------
  # Broker logs to the KMS-encrypted CloudWatch log group from Section 3.
  # ---------------------------------------------------------------------------
  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk_broker.name
      }
    }
  }

  # ---------------------------------------------------------------------------
  # Prometheus open-monitoring exporters - expose Kafka JMX and node-level
  # metrics for Prometheus-compatible scraping (e.g., AWS Managed Prometheus
  # or a sidecar scraper). Optional but valuable for advanced observability.
  # ---------------------------------------------------------------------------
  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-msk"
    Purpose = "Event-driven transaction pipeline (replaces CICS TDQ per AAP S0.1.1)"
  })

  # Cluster recreation is operationally expensive (manual data migration);
  # the AAP S0.6.5 ordering guarantees rely on continuous cluster identity
  # across deploys. Lifecycle is intentionally NOT create_before_destroy
  # here - we want destroy-then-create on the rare occasions a force-
  # replacement plan is acknowledged by the operator.
}

# =============================================================================
# Section 5 - Topic creation (NOT a Terraform resource for the hashicorp/aws
# provider; documented here for the topic-init job that runs post-apply)
# =============================================================================
# The hashicorp/aws provider does NOT include a Kafka topic resource - it
# manages the MSK control plane (clusters, configurations, scram-secrets)
# but not the Kafka data plane (topics, ACLs, partitions). Topics for this
# cluster are created out-of-band by one of the following mechanisms:
#
#   (a) An init job (Lambda + IAM auth) that runs once post-cluster-create
#       and calls AdminClient.createTopics(...) via the Kafka Java client.
#       See localstack/init/init-aws.sh for the local-dev equivalent that
#       creates the same topics on the LocalStack Kafka emulator.
#
#   (b) Manually via kafka-topics.sh with --command-config msk-iam.properties
#       (operator-only; recorded in infrastructure/README.md as the break-
#       glass procedure).
#
#   (c) The third-party mongey/kafka Terraform provider, which manages
#       topics declaratively (commented examples below for reference).
#       Adopting this would split provider state between hashicorp/aws and
#       mongey/kafka; the AAP does not mandate it and the init-job
#       approach is the chosen path.
#
# Topics to be created (all four use the partitions / replication-factor
# defaults set in Section 2's server.properties, which are sourced from
# var.msk_topic_partitions and var.msk_topic_replication_factor):
#
#   ---------------------------------------------------------------------------
#   Topic:      transaction.posted
#   Partitions: var.msk_topic_partitions (default 12)
#   Replicas:   var.msk_topic_replication_factor (default 3)
#   Compression:snappy (producer-side; broker preserves)
#   Retention:  604800000 ms (7 days)
#   Cleanup:    delete
#   PartitionKey: ACCT-ID (11-digit, zero-padded - per AAP S0.6.5)
#   Producer:   TransactionAddService (COBOL: COTRN02C),
#               BillPaymentService    (COBOL: COBIL00C)
#   Consumer:   AuditLogService, ReportingService, downstream account services
#   ---------------------------------------------------------------------------
#   Topic:      account.updated
#   Partitions: var.msk_topic_partitions (default 12)
#   Replicas:   var.msk_topic_replication_factor (default 3)
#   Compression:snappy
#   Retention:  604800000 ms (7 days)
#   Cleanup:    delete
#   PartitionKey: ACCT-ID
#   Producer:   AccountUpdateService    (COBOL: COACTUPC),
#               BillPaymentService      (COBOL: COBIL00C),
#               InterestCalculationService (COBOL: CBACT04C)
#   Consumer:   CacheService (Redis invalidation), AuditLogService
#   ---------------------------------------------------------------------------
#   Topic:      ledger.balanced
#   Partitions: var.msk_topic_partitions (default 12)
#   Replicas:   var.msk_topic_replication_factor (default 3)
#   Compression:snappy
#   Retention:  604800000 ms (7 days)
#   Cleanup:    delete
#   PartitionKey: ACCT-ID
#   Producer:   End-of-day reconciliation Spring Batch jobs
#   Consumer:   ReportingService
#   ---------------------------------------------------------------------------
#   Topic:      report.requested
#   Partitions: var.msk_topic_partitions (default 12)
#   Replicas:   var.msk_topic_replication_factor (default 3)
#   Compression:snappy
#   Retention:  604800000 ms (7 days)
#   Cleanup:    delete
#   PartitionKey: USER-ID (8-char SEC-USR-ID)
#   Producer:   ReportSubmissionService (COBOL: CORPT00C)
#               REPLACES: the CICS TDQ "JOBS" queue that CORPT00C originally
#               wrote to as the online-to-batch bridge.
#   Consumer:   Step Functions trigger Lambda -> submits AWS Batch job
#   ---------------------------------------------------------------------------
#
# F-CP6-TF-MSK-01: The four required topics are now provisioned as
# active Terraform resources using the Mongey/kafka provider declared
# in main.tf required_providers. The provider configuration consumes
# the aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam attribute,
# which means a first-time apply requires a 2-phase rollout:
#
#   1. terraform apply -target=aws_msk_cluster.carddemo
#   2. terraform apply  (creates the four topics)
#
# After the first apply, normal terraform apply lifecycle applies.

provider "kafka" {
  # Bootstrap servers come from the MSK cluster's SASL-IAM endpoint.
  # The list is split into a slice because the AWS-provided string is
  # comma-separated.
  bootstrap_servers = split(",", aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam)

  # SASL/IAM auth (preferred for AWS-only Kafka clients per AAP §0.6.5).
  sasl_mechanism       = "aws-iam"
  tls_enabled          = true
  sasl_aws_region      = var.aws_region
  skip_tls_verify      = false
  sasl_aws_creds_debug = false
}

resource "kafka_topic" "transaction_posted" {
  name               = "transaction.posted"
  partitions         = var.msk_topic_partitions
  replication_factor = var.msk_topic_replication_factor

  # AAP §0.6.5: producer ordering guarantees require per-account
  # partitioning + acks=all on the producer side. The cluster-side
  # config below ensures topic durability matches the producer
  # contract.
  config = {
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
    "retention.ms"        = "604800000"
    "cleanup.policy"      = "delete"
  }
}

resource "kafka_topic" "account_updated" {
  name               = "account.updated"
  partitions         = var.msk_topic_partitions
  replication_factor = var.msk_topic_replication_factor

  config = {
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
    "retention.ms"        = "604800000"
    "cleanup.policy"      = "delete"
  }
}

resource "kafka_topic" "ledger_balanced" {
  name               = "ledger.balanced"
  partitions         = var.msk_topic_partitions
  replication_factor = var.msk_topic_replication_factor

  config = {
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
    "retention.ms"        = "604800000"
    "cleanup.policy"      = "delete"
  }
}

resource "kafka_topic" "report_requested" {
  name               = "report.requested"
  partitions         = var.msk_topic_partitions
  replication_factor = var.msk_topic_replication_factor

  config = {
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
    "retention.ms"        = "604800000"
    "cleanup.policy"      = "delete"
  }
}
#
# Bootstrap servers (consumed by outputs.tf and by Spring Boot
# KafkaConfig via the MSK_BOOTSTRAP_SERVERS env var):
#   aws_msk_cluster.carddemo.bootstrap_brokers_sasl_iam
#
# Validation checklist after `terraform apply`:
#   1. aws kafka describe-cluster --cluster-arn <arn>  -> State: ACTIVE
#   2. aws kafka get-bootstrap-brokers --cluster-arn <arn>
#        -> BootstrapBrokerStringSaslIam populated
#   3. /aws/msk/carddemo-<env>/broker log group receives broker logs
#   4. AWS/Kafka CloudWatch namespace shows MaxOffsetLag metric (the
#      ECS auto-scaling target per AAP S0.1.1)
#   5. kafka-topics.sh --bootstrap-server <brokers> --list
#        --command-config msk-iam.properties shows the four topics
# =============================================================================
