/**
 * Test fixtures that carry a Spring stereotype, kept deliberately outside component scope.
 *
 * <h2>Why this package exists</h2>
 * <p>{@code CardDemoApplication} declares its component scope explicitly - eleven named packages,
 * the two foundation packages and the nine domain packages - and its own documentation states the
 * reason: naming them "keeps a package created outside that set out of component scope rather than
 * silently in it". This package is that outside. Nothing here is scanned, by this module's
 * application or by any context that uses it as its configuration.
 *
 * <p>That property is what the package is for. A class annotated {@code @RestController},
 * {@code @Component}, {@code @Service} or {@code @Repository} is a component-scan <em>candidate</em>
 * wherever it sits, and {@code src/test/java} compiles to {@code target/test-classes}, which is on
 * the classpath of every test run and of a local run started from the test output. A stereotyped test
 * fixture inside one of the eleven scanned packages is therefore offered to the container: it was,
 * and an eighteenth {@code @RestController} bean contributing ten fixture routes joined the
 * seventeen the deployed artifact publishes, so the route table a developer could start locally was
 * not the route table that ships. A {@code @SpringBootTest} does not show this, because Spring Boot's
 * test framework installs a filter that excludes classes enclosed by test classes; a plain
 * {@code main} run installs no such filter, and that is the run this package protects.
 *
 * <h2>What belongs here, and what does not</h2>
 * <p>Only a fixture that <strong>must</strong> carry a stereotype. The fixtures here are controllers
 * driven through {@code MockMvcBuilders.standaloneSetup}, which registers the handler methods of a
 * {@code @Controller}-annotated instance; without the stereotype the framework resolves a
 * {@code String} return as a view name and every assertion on a response body fails. The stereotype
 * is required, so the class is placed where a stereotype is harmless.
 *
 * <p>Everything else stays with the test that uses it. A fixture with no stereotype - a stub
 * repository, a capturing sink, a payload record - is a plain object and belongs beside its own
 * suite, where its purpose is legible. This package is not a home for shared test utilities.
 *
 * <p>{@code CardDemoApplicationTest} asserts the invariant both ways: this package is not in the scan
 * list, and no class compiled from the test tree inside a scanned package carries a controller
 * stereotype.
 */
package com.vsergeychik.carddemo.testsupport;
