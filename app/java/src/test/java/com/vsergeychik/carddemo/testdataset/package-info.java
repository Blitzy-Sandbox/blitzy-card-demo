/**
 * A DDL-free stand-in for the record-image relations a site's gateway presents, for tests that need a
 * whole dataset rather than a single stubbed call.
 *
 * <h2>Why this package exists</h2>
 * <p>Gate <strong>G44</strong> requires that <em>no DDL, no schema migration files, no entity
 * annotations and no generated table definitions exist anywhere in the module</em>, and AAP 0.2.6 and
 * 0.8.1 say the same thing from the other direction: this migration reaches the existing VSAM and
 * sequential datasets through JDBC and invents no relational schema for them. A test that issued
 * {@code CREATE TABLE} to conjure a relation put executable DDL inside the module, which is what this
 * package removes: {@link com.vsergeychik.carddemo.testdataset.RecordImageDataSource} materialises a
 * relation by <em>declaring</em> it in a map, so there is no schema to create and nothing to migrate.
 *
 * <p>It is a {@code DataSource} rather than a stubbed template on purpose. Everything above the driver
 * stays on its real code path - {@code JdbcTemplate}, every repository, {@code DatasetRelation}'s
 * composed statements, {@code RecordImageForm}'s {@code getString}/{@code getBytes} choice,
 * {@code DatasetUnitOfWork} and a real {@code JdbcTransactionManager} - so a parity case still proves
 * what the job writes rather than what a mock was told to say. Only the storage engine is replaced.
 *
 * <h2>What it is not</h2>
 * <p>Not a SQL engine. It recognises exactly the statement forms {@code DatasetRelation} composes and
 * <strong>refuses anything else loudly</strong>, so a change to a composed statement surfaces as a
 * failure here rather than as a silently different answer. That refusal is the property that makes it
 * safe to put under the parity gate.
 *
 * <h2>Component scanning</h2>
 * <p>{@code CardDemoApplication} names its eleven scanned packages explicitly and this is not one of
 * them, so nothing here can join a context refreshed from {@code target/test-classes}. Nothing here
 * carries a Spring stereotype either, so it is not a scan candidate in the first place - which is why
 * it is a package of its own rather than part of {@code com.vsergeychik.carddemo.testsupport}, whose
 * documented purpose is the narrower one of housing fixtures that <em>must</em> be stereotyped.
 */
package com.vsergeychik.carddemo.testdataset;
