package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.CardDemoApplication;

import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The entry point for running the application by hand against the {@code test} profile's declared data.
 *
 * <h2>Why a second entry point exists</h2>
 * <p>{@link CardDemoApplication} is the artifact's entry point and it stays exactly what it ships as: it
 * creates no relation and seeds no row, because a deployment provides its own datasets and the module
 * refuses rather than creating (gate G44, deployment obligation 2). Running <em>that</em> class on the
 * {@code test} profile therefore produced a context with all twenty-seven dataset bindings resolved and
 * nothing behind them - every read reaching a relation that did not exist.
 *
 * <p>This class is that same application plus {@link FixtureSeedingConfiguration}, so a hand-started JVM
 * finds the profile's declared fixtures materialised: the nine fixed-width files derived from
 * {@code app/data/ASCII} across their DD names, and the ten {@code USRSEC} rows transcribed from
 * {@code app/jcl/DUSRSECJ.jcl}, each right-padded once to its copybook width.
 *
 * <h2>Why it is here and not in the main tree</h2>
 * <p>It compiles to {@code target/test-classes} and is not packaged, which is the point: the fixture
 * inventory carries ten plaintext {@code USRSEC} rows, and a credential-shaped value inside a
 * distributable artifact is indistinguishable from a real one to a scanner (CWE-798). The seeding is
 * reachable exactly where the data already is - on the test classpath - and nowhere else.
 *
 * <h2>How to run it</h2>
 * <pre>
 * cd app/java
 * mvn -B test-compile
 * mvn -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
 * java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
 *      com.vsergeychik.carddemo.testsupport.FixtureSeededApplication --spring.profiles.active=test
 * </pre>
 *
 * <p>Sign on with {@code POST /api/signon} as {@code ADMIN001} or {@code USER0001}, password
 * {@code PASSWORD}, and {@code navigationContext.pgmContext} set to 1 - the ten seeded rows are the ones
 * {@code README.md} documents.
 */
public final class FixtureSeededApplication {

    /** Not instantiable: this class is an entry point and holds no state. */
    private FixtureSeededApplication() {
        throw new AssertionError("FixtureSeededApplication is an entry point and is never instantiated");
    }

    /**
     * Starts the shipped application with the {@code test} profile's fixture seeding added.
     *
     * <p>The profile is not forced here. It is passed on the command line like any other argument, so
     * that this entry point cannot quietly activate a profile a caller did not ask for - and
     * {@link FixtureSeedingConfiguration} is itself {@code @Profile("test")}, so without that argument
     * the seeding contributes nothing and this class behaves exactly as {@link CardDemoApplication}.
     *
     * @param arguments the command-line arguments, passed through unchanged
     */
    public static void main(String[] arguments) {
        new SpringApplicationBuilder(CardDemoApplication.class)
                .sources(FixtureSeedingConfiguration.class)
                .run(arguments);
    }
}
