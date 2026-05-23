/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Empty-by-default JDBC adapter package for the CardDemo hexagonal architecture.
 *
 * <p>This package is the outer-ring counterpart to
 * {@link com.blitzy.carddemo.adapter.file}: both packages exist to provide
 * concrete implementations of the repository port interfaces declared in
 * {@code com.blitzy.carddemo.domain.port}, but only the file-backed sibling
 * is populated under this refactor. This package intentionally contains no
 * production classes; it exists solely to (1) satisfy the Java compiler's
 * requirement for a real {@code package} declaration at this directory level,
 * (2) prevent the Maven JAR plugin from silently dropping an otherwise empty
 * leaf package from the assembled artifact, and (3) preserve, as
 * documentation-as-code, the architectural rationale for keeping the JDBC
 * adapter empty.
 *
 * <h2>Why this package is empty by default</h2>
 *
 * <p>The Agent Action Plan that drives this migration explicitly overrides
 * the previously documented Spring Boot 3.5.11 + PostgreSQL 16 + Spring Batch
 * target architecture. Per AAP §0.6.12 (Acknowledged Architectural Override),
 * the binding target is:
 *
 * <blockquote>
 *   <em>Persistence: File-based default via {@code java.nio.file}; JDBC adapter
 *   optional.</em>
 * </blockquote>
 *
 * <p>AAP §0.2.1 enumerates the new Java source tree and lists this module as
 * &quot;JDBC repositories (empty/optional by default; created only if
 * DB2/relational adapter is required)&quot;. AAP §0.7.5 reinforces this by
 * resolving the persistence-strategy TODO marker as: &quot;COBOL source uses
 * VSAM KSDS with fixed-width records; default path is {@code java.nio.file}
 * with fixed-width readers. JDBC adapter is empty and conditional.&quot;
 *
 * <h2>Verified absence of EXEC SQL in the COBOL source</h2>
 *
 * <p>A comprehensive grep of the preserved COBOL source tree under
 * {@code app/cbl/} confirms that not one of the 28 translated programs
 * contains an {@code EXEC SQL} statement. Specifically,
 * {@code grep -ric "EXEC SQL" app/cbl/} returns {@code 0} for every program
 * file (CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBSTM03B,
 * CBTRN01C, CBTRN02C, CBTRN03C, COACTUPC, COACTVWC, COADM01C, COBIL00C,
 * COCRDLIC, COCRDSLC, COCRDUPC, COMEN01C, CORPT00C, COSGN00C, COTRN00C,
 * COTRN01C, COTRN02C, COUSR00C, COUSR01C, COUSR02C, COUSR03C, CSUTLDTC).
 * Persistence in the source system is exclusively VSAM KSDS (and a small set
 * of fixed-width sequential files); there is no DB2 or other embedded-SQL
 * surface to translate.
 *
 * <p>For this reason the file-backed adapter
 * {@link com.blitzy.carddemo.adapter.file} is the sole, correct outer-ring
 * implementation under the current scope, and the JDBC adapter requires no
 * implementation work for byte-for-byte parity with the COBOL baseline.
 *
 * <h2>Hexagonal architecture contract</h2>
 *
 * <p>Per AAP §0.3.6 (Hexagonal Architecture Diagram) and §0.3.2 (Repository
 * Pattern — Port + Adapter), every persistence-touching port interface in
 * {@code com.blitzy.carddemo.domain.port} (for example,
 * {@code AccountRepository}, {@code CardRepository}, {@code CardXrefRepository},
 * {@code CustomerRepository}, {@code TransactionRepository},
 * {@code DailyTransactionRepository}, {@code DiscountGroupRepository},
 * {@code TransactionCategoryBalanceRepository}, {@code TransactionTypeRepository},
 * {@code TransactionCategoryRepository}, and {@code UserSecurityRepository})
 * can in principle be implemented by either:
 *
 * <ul>
 *   <li>a file-backed adapter under
 *       {@link com.blitzy.carddemo.adapter.file} (populated, the default), or
 *   <li>a JDBC-backed adapter under this package
 *       {@code com.blitzy.carddemo.adapter.db} (intentionally empty under
 *       the current refactor scope).
 * </ul>
 *
 * <p>The composition root in the {@code carddemo-app} module selects between
 * adapters at startup via plain constructor injection (no Spring container,
 * per AAP §0.6.12). Keeping this package available — even when empty —
 * preserves the architectural seam so that a follow-up effort can add JDBC
 * implementations without restructuring the module tree.
 *
 * <h2>Future extension path</h2>
 *
 * <p>If a future refactor introduces an embedded-SQL surface in the COBOL
 * source (or a parallel relational data store must be supported), the
 * minimal change set is:
 *
 * <ol>
 *   <li>Add the concrete JDBC driver dependency to
 *       {@code java/carddemo-adapter-db/pom.xml} with
 *       {@code <scope>provided</scope>} so the shaded jar in
 *       {@code carddemo-app} keeps the driver out of the assembly and the
 *       deployment environment supplies it at runtime.
 *   <li>Update {@code java/application.properties.example} to document the
 *       new JDBC connection keys (URL, user, optional password source,
 *       fetch size, isolation level).
 *   <li>Append a section to {@code java/MIGRATION_NOTES.md} documenting
 *       <em>why</em> JDBC was required (which COBOL paragraphs grew an
 *       {@code EXEC SQL} surface, or which external store became
 *       authoritative) and how byte-for-byte parity with the file-based
 *       reference is preserved through the JDBC column-to-record translation.
 *   <li>Implement port interfaces from {@code com.blitzy.carddemo.domain.port}
 *       (for example, {@code JdbcAccountRepository implements AccountRepository}).
 *       Place every new class as a direct child of this leaf package —
 *       <em>sibling to this {@code package-info.java}</em> — and do not
 *       introduce sub-packages, mirroring the flat structure of the
 *       populated sibling {@link com.blitzy.carddemo.adapter.file}.
 *   <li>Wire the new adapter in the {@code carddemo-app} composition root
 *       by constructor-injecting the JDBC implementation in place of the
 *       file-backed implementation for the selected ports.
 * </ol>
 *
 * <h2>Architectural constraints (binding even on future JDBC work)</h2>
 *
 * <p>Per AAP §0.6.12, §0.7.3, and §0.7.4, the following are <strong>forbidden
 * </strong> in this package even when JDBC implementations are eventually
 * added:
 *
 * <ul>
 *   <li>Spring annotations: {@code @Component}, {@code @Service},
 *       {@code @Repository}, {@code @Configuration}, {@code @Autowired},
 *       {@code @Bean} — the refactor mandates plain factories and
 *       constructor injection with <em>no Spring container</em>.
 *   <li>Object-relational mapping frameworks: JPA / Hibernate annotations
 *       ({@code @Entity}, {@code @Table}, {@code @Id}, {@code @Column},
 *       {@code @GeneratedValue}, {@code @OneToMany}, etc.) — JDBC adapters
 *       must use {@code java.sql.PreparedStatement} directly with explicit
 *       column-to-record mapping.
 *   <li>Schema-migration tooling: Flyway, Liquibase — schema is owned by
 *       the deployment environment, not the application.
 *   <li>{@link java.io.File} — all filesystem access (including for JDBC
 *       configuration files, schema dumps, or fixture loading) must use
 *       {@link java.nio.file} per AAP §0.6.5.
 *   <li>{@code double} or {@code float} for any monetary or packed-decimal
 *       value — all such values must use {@link java.math.BigDecimal}
 *       through the {@code com.blitzy.carddemo.domain.util.Decimals}
 *       utility, with explicit {@link java.math.MathContext#DECIMAL128}
 *       precision and the appropriate {@link java.math.RoundingMode}
 *       per AAP §0.6.1.
 *   <li>{@link ThreadLocal} — all cross-method context propagation
 *       (batch-run identifier, processing date, tenant, user identity)
 *       must use {@link java.lang.ScopedValue} per AAP §0.6.6 and JEP 506.
 *   <li>Reflection or dynamic proxies, except when faithfully translating
 *       a COBOL construct that genuinely requires them.
 * </ul>
 *
 * <h2>Byte-for-byte fidelity invariant</h2>
 *
 * <p>If a JDBC adapter is ever added to this package, it must preserve the
 * <em>same</em> byte-for-byte parity invariant that the file adapter honors
 * (AAP §0.0.3 surfaced implicit requirements and §0.6.5 File I/O Exactness).
 * Specifically, for every supported record type, the round-trip
 * {@code parse(bytes).encode()} must equal the original byte buffer, and a
 * JDBC implementation must reconstruct the canonical fixed-width byte form
 * from column values such that any external consumer reading the resulting
 * dump file observes identical bytes to the COBOL/VSAM reference output —
 * including sign nybble for COMP-3, padding direction, decimal scale
 * preservation (e.g., {@code 1.20} does not normalize to {@code 1.2}),
 * and EBCDIC↔ASCII transcoding parity.
 *
 * <h2>Why {@code package-info.java} rather than {@code .gitkeep}</h2>
 *
 * <p>A {@code .gitkeep} sentinel would let Git track the directory but
 * would not survive the Maven build pipeline: empty packages are dropped
 * from the assembled JAR, and there would be no compiled
 * {@code package-info.class} carrying the architectural intent. The
 * {@code package-info.java} file, by contrast, compiles cleanly to
 * {@code package-info.class}, is included in the JAR, integrates with
 * the Javadoc tool so that this rationale is rendered at the package
 * level, and serves as durable inline documentation for any contributor
 * who navigates to this directory.
 *
 * @see com.blitzy.carddemo.adapter.file
 */
package com.blitzy.carddemo.adapter.db;
