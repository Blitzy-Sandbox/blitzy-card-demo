package com.cardemo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Foundational JPA / Hibernate configuration for the greenfield Java 25 LTS + Spring Boot 3.5.11
 * migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <p>This {@code @Configuration} formalizes the persistence-layer policy that backs the
 * <strong>VSAM&nbsp;KSDS&nbsp;&rarr;&nbsp;PostgreSQL&nbsp;16</strong> data-layer substitution. It is
 * component-scanned by {@code CardDemoApplication} (base package {@code com.cardemo}, decision
 * <strong>D-006</strong> &mdash; deliberately <em>not</em> {@code com.carddemo}), which delegates all
 * cross-cutting configuration to the {@code config} package. The class is intentionally
 * <strong>lean</strong>: its most important contract is what it does <em>not</em> do (it neither owns
 * the schema nor redeclares repository scanning), and its single active behavior is enabling JPA
 * auditing.</p>
 *
 * <h2>Migration provenance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>This is a brand-new file with <strong>no COBOL source equivalent</strong>; it is a net-new
 * technology-substitution layer that has no analogue in the legacy estate. Conceptually it replaces
 * the IDCAMS {@code DEFINE CLUSTER} VSAM provisioning jobs (for example {@code app/jcl/ACCTFILE.jcl},
 * which defines {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} with {@code KEYS(11 0)} and
 * {@code RECORDSIZE(300 300)}), but the relational schema itself is owned by Flyway, not by this class.
 * Behaviour for the wider application is translated from the frozen AWS CardDemo COBOL baseline at
 * commit SHA {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this repository, and
 * traceability to the legacy baseline is by that commit SHA only.</p>
 *
 * <h2>Technology substitution (AAP &sect;0.7.1 &mdash; documented at the point of change)</h2>
 * <dl>
 *   <dt>VSAM KSDS keyed access &rarr; {@code @Entity} + {@code JpaRepository}</dt>
 *   <dd>The governing transformation rule (tech-spec L79&ndash;L81) maps the 10 VSAM KSDS clusters,
 *       their AIX/PATH alternate indexes, and the one sequential PS staging dataset to PostgreSQL
 *       tables accessed through Spring Data JPA. This class supplies the JPA infrastructure that the
 *       {@code com.cardemo.model.entity.*} entities and {@code com.cardemo.repository.*} repositories
 *       rely on; the keyed/alternate-index access patterns are realized by repository query methods,
 *       not here.</dd>
 *
 *   <dt>IDCAMS {@code DEFINE CLUSTER} provisioning &rarr; Flyway (the single schema authority)</dt>
 *   <dd>Flyway &mdash; <strong>not</strong> Hibernate &mdash; creates and seeds the schema. The
 *       versioned scripts run on application startup in strict order before any {@code @Service} or
 *       Spring Batch job executes: {@code V1__create_schema} (all 11 tables) &rarr;
 *       {@code V2__create_indexes} (alternate indexes {@code CXACAIX}, {@code TRANSACT} AIX) &rarr;
 *       {@code V3__seed_data} (the 9 ASCII fixtures). Hibernate is restricted to
 *       <strong>validating</strong> that the JPA entity&harr;table mapping matches the Flyway-created
 *       schema. That policy is configured declaratively as {@code spring.jpa.hibernate.ddl-auto=validate}
 *       in {@code application.yml} and is deliberately <strong>not</strong> redeclared in code: keeping
 *       a single source of truth prevents a code override from silently switching Hibernate to
 *       {@code create}/{@code update} and mutating the carefully precision-mapped
 *       {@code NUMERIC(p,s)} columns that mirror the COBOL {@code PIC} clauses, which would break the
 *       decimal-fidelity guarantee underpinning 100% behavioral parity (AAP &sect;0.7.2 / &sect;0.7.3).</dd>
 * </dl>
 *
 * <h2>JPA auditing (the single active behavior)</h2>
 * <p>{@link EnableJpaAuditing} activates Spring Data's {@code AuditingEntityListener} so that any
 * entity annotated with {@code @CreatedDate} / {@code @LastModifiedDate} has those fields populated
 * automatically from the current time. It is enabled here as the safe, forward-compatible default for
 * the persistence layer. No {@code AuditorAware} bean is declared because the migration uses no
 * {@code @CreatedBy} / {@code @LastModifiedBy} fields (there is no authenticated &quot;auditor&quot;
 * concept to capture), and no custom {@code DateTimeProvider} bean is declared because no entity
 * currently uses creation/modification audit fields &mdash; the {@code Transaction} timestamps
 * ({@code tranOrigTs} &larr; {@code TRAN-ORIG-TS}, {@code tranProcTs} &larr; {@code TRAN-PROC-TS}) are
 * business data carried over from the COBOL records and are set explicitly by the service/batch layer,
 * not derived from an auditing clock. Spring Data's default {@code CurrentDateTimeProvider} therefore
 * governs auditing, and the listener is a harmless no-op for entities that do not opt in.</p>
 *
 * <h2>Transactions &amp; optimistic concurrency (infrastructure only)</h2>
 * <p>This class provides the JPA infrastructure on which transactional and locking semantics are
 * built, but it declares <strong>no</strong> transaction beans of its own. Spring Boot already
 * auto-enables annotation-driven transaction management, so {@code @EnableTransactionManagement} is
 * intentionally omitted (Minimal Change Clause). The legacy transactional surface is reproduced on the
 * service and entity classes, <em>not</em> here:</p>
 * <ul>
 *   <li>The sole COBOL {@code EXEC CICS SYNCPOINT ROLLBACK} (in {@code COACTUPC}, guarding the dual
 *       {@code ACCTDAT}+{@code CUSTDAT} update) maps to Spring {@code @Transactional(rollbackFor=...)}
 *       on {@code AccountUpdateService} (tech-spec L1059, AAP &sect;0.7.5).</li>
 *   <li>The optimistic before/after record-image comparison in {@code COACTUPC} and {@code COCRDUPC}
 *       maps to JPA {@code @Version} on the {@code Account} and {@code Card} entities, with
 *       {@code OptimisticLockException} handling in the service layer (tech-spec L1061,
 *       AAP &sect;0.7.5).</li>
 * </ul>
 *
 * <h2>Minimal Change Clause &amp; secret policy (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>No speculative persistence beans are introduced: there is no custom {@code EntityManagerFactory},
 * no custom {@code DataSource}, no second-level cache, and no explicitly pinned Hibernate dialect
 * (Spring Boot auto-detects the correct PostgreSQL dialect for the connected server). The
 * {@code DataSource} is auto-configured from the {@code ${ENV:default}} placeholders in
 * {@code application*.yml}; consequently this file contains <strong>no</strong> datasource URL,
 * username, password, or any other credential or secret, and performs no {@code @Value} wiring.</p>
 *
 * @see EnableJpaAuditing
 * @see org.springframework.context.annotation.Configuration
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    /*
     * Intentionally NO beans, fields, or constructor logic.
     *
     * The single active behavior of this class is enabling JPA auditing (the class-level
     * @EnableJpaAuditing above). The following are deliberately omitted (AAP §0.7.1 Minimal Change
     * Clause); each would either duplicate Spring Boot auto-configuration or add unused infrastructure:
     *
     *   - spring.jpa.hibernate.ddl-auto : NOT set in code. Flyway is the single schema authority and
     *     the validate policy lives in application.yml. Setting it here (especially to create/update/
     *     create-drop) would let Hibernate mutate the Flyway-owned, precision-mapped schema and break
     *     the BigDecimal/NUMERIC decimal-fidelity guarantee (§0.7.2/§0.7.3).
     *
     *   - @EnableJpaRepositories : NOT declared. spring-boot-starter-data-jpa already auto-configures
     *     repository scanning for the com.cardemo base package (CardDemoApplication deliberately omits
     *     it too). Repositories live in com.cardemo.repository and must remain auto-discovered;
     *     declaring it here would risk narrowing or duplicating the scan.
     *
     *   - @EnableTransactionManagement : NOT declared. Spring Boot auto-enables annotation-driven
     *     transactions; @Transactional on the service classes (e.g. AccountUpdateService <- COACTUPC)
     *     works without it.
     *
     *   - Custom DateTimeProvider / AuditorAware @Bean : NOT declared. No entity uses
     *     @CreatedDate/@LastModifiedDate or @CreatedBy/@LastModifiedBy, so a custom provider would be
     *     unused; Spring Data's default CurrentDateTimeProvider is sufficient. A UTC DateTimeProvider
     *     (java.time, per the CEEDAYS -> java.time rule, tech-spec L90) can be added here later if and
     *     when an entity introduces Spring Data audit fields.
     *
     *   - Custom EntityManagerFactory / DataSource / second-level cache / explicit dialect : NOT
     *     declared. All are provided or auto-detected by Spring Boot's JPA auto-configuration.
     */
}
