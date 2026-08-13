package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The guard that every shipped parity case is a case some suite actually runs.
 *
 * <h2>The defect this exists to prevent</h2>
 * <p>Five of the twenty-eight parity suites once built their twenty cases in Java - {@code case01()}
 * through {@code case20()}, private static methods returning a scenario - and never read their
 * {@code src/test/resources/parity/<PROGRAM>/} directory at all. One hundred of the five hundred and
 * sixty shipped case files were therefore <strong>non-operative</strong>: they were reviewed, they were
 * cited in reports, they were counted towards "twenty cases per program, 560 in total", and no assertion
 * anywhere read one. Worse, the two sets had drifted - the same ordinal named a different scenario on
 * each side - so a reader comparing them would have concluded that one was wrong, when in truth only one
 * was running.
 *
 * <p>Nothing in a green build reveals that. Each suite passed its own twenty, the resource files parsed
 * cleanly whenever anything happened to load them, and the count of files on disk was right. The failure
 * mode is silence, which is why the guard has to be an explicit assertion rather than a convention.
 *
 * <h2>What is asserted, and why reflection</h2>
 * <p>For every program directory under {@code parity/}:
 * <ol>
 *   <li>the directory holds exactly {@code case01} through {@code case20}, and all twenty bind through
 *       {@link ParityCase}'s canonical constructor - which {@link ParityHarness#casesOf(String)} already
 *       enforces, and this calls it so that a malformed file fails here too;</li>
 *   <li>the matching {@code <PROGRAM>ParityTest} class exists, and every {@code @MethodSource} supplier
 *       feeding one of its {@code @ParameterizedTest} methods is invoked;</li>
 *   <li>every {@link ParityCase} any of those suppliers yields is <strong>equal</strong> to the shipped
 *       case of the same identifier;</li>
 *   <li>and between them the suppliers yield <strong>all twenty</strong>.</li>
 * </ol>
 *
 * <p>Point 3 is the one that bites. A Java-built case is never equal to the shipped case it shadows:
 * every case file carries several kilobytes of description that no builder reproduces, so equality holds
 * only if the supplier's element came out of the resource. Point 4 closes the other half - a suite could
 * satisfy point 3 by running four of the twenty - and it is why the supplier is invoked rather than the
 * source text scanned. A grep for {@code casesOf} would pass on a class that called it and then ignored
 * the result.
 *
 * <p>Reflection is unavoidable here and is used narrowly: the suppliers are private static methods on
 * twenty-eight classes that share no interface, because each suite's parameter type is its own private
 * scenario record. Rather than requiring twenty-eight classes to expose a seam for this test, the seam
 * that already exists - the {@code @MethodSource} annotation JUnit itself resolves - is resolved the same
 * way. A suite whose supplier cannot be found or invoked fails loudly; nothing is skipped, because a
 * silently skipped program is exactly the defect being guarded against.
 */
@DisplayName("Shipped case adoption - all 560 parity resources are executed, none is decorative")
class ShippedCaseAdoptionTest {

    /** The twenty-eight programs the migration covers, and therefore the directory count expected. */
    private static final int PROGRAM_COUNT = 28;

    /** {@value #PROGRAM_COUNT} programs times {@link ParityHarness#CASES_PER_PROGRAM} cases each. */
    private static final int TOTAL_CASES = PROGRAM_COUNT * ParityHarness.CASES_PER_PROGRAM;

    /** The suffix a program's suite class name carries. */
    private static final String SUITE_SUFFIX = "ParityTest";

    /**
     * Every program directory under {@code parity/} holds exactly twenty binding cases.
     *
     * <p>Asserted separately from adoption so that a directory problem reads as a directory problem: a
     * nineteen-case program and a twenty-case program nobody runs are different faults with different
     * fixes.
     */
    @Test
    @DisplayName("28 programs, 20 cases each, 560 in total - and every one of them binds (G15)")
    void everyProgramShipsTwentyBindingCases() {
        List<String> programs = shippedPrograms();

        assertThat(programs)
                .as("one directory per migrated program under %s", ParityHarness.CASE_RESOURCE_ROOT)
                .hasSize(PROGRAM_COUNT)
                .doesNotHaveDuplicates();

        int loaded = 0;
        for (String program : programs) {
            List<ParityCase> cases = ParityHarness.casesOf(program);
            assertThat(cases)
                    .as("%s ships %d case(s); the gate volume is %d", program, cases.size(),
                            ParityHarness.CASES_PER_PROGRAM)
                    .hasSize(ParityHarness.CASES_PER_PROGRAM);
            assertThat(cases.stream().map(ParityCase::caseId).toList())
                    .as("%s numbers its cases positionally", program)
                    .containsExactlyElementsOf(expectedCaseIds());
            assertThat(cases).allSatisfy(shipped -> assertThat(shipped.program()).isEqualTo(program));
            loaded += cases.size();
        }

        assertThat(loaded).isEqualTo(TOTAL_CASES);
    }

    /**
     * Every shipped case is yielded by its own suite's parameter supplier, and every case a supplier
     * yields is the shipped one rather than a Java-built lookalike.
     */
    @Test
    @DisplayName("every one of the 560 cases is executed by its suite, and no suite builds its own")
    void everySuiteRunsTheCasesItsProgramShips() {
        Map<String, List<String>> unadopted = new LinkedHashMap<>();
        Map<String, List<String>> substituted = new LinkedHashMap<>();

        for (String program : shippedPrograms()) {
            Map<String, ParityCase> shipped = new LinkedHashMap<>();
            for (ParityCase declared : ParityHarness.casesOf(program)) {
                shipped.put(declared.caseId(), declared);
            }
            Set<String> yielded = new LinkedHashSet<>();
            List<String> notShipped = new ArrayList<>();
            for (ParityCase supplied : casesSuppliedBy(suiteOf(program))) {
                ParityCase counterpart = shipped.get(supplied.caseId());
                if (counterpart == null || !counterpart.equals(supplied)) {
                    notShipped.add(supplied.caseId());
                    continue;
                }
                yielded.add(supplied.caseId());
            }
            Set<String> missing = new TreeSet<>(shipped.keySet());
            missing.removeAll(yielded);
            if (!missing.isEmpty()) {
                unadopted.put(program, List.copyOf(missing));
            }
            if (!notShipped.isEmpty()) {
                substituted.put(program, List.copyOf(notShipped));
            }
        }

        assertThat(unadopted)
                .as("these shipped cases are executed by nothing. A case file no assertion reads is not "
                        + "a smaller gate, it is a gate that reports green without asking the question - "
                        + "and it is worse than an absent file, because it is counted and cited as "
                        + "though it ran. Point the suite's supplier at ParityHarness.casesOf(PROGRAM)")
                .isEmpty();
        assertThat(substituted)
                .as("these suites yield cases that are not the ones their program ships: same "
                        + "identifier, different content, which means the case is being built in Java "
                        + "and the file of that name is being shadowed rather than read. The file is the "
                        + "case; delete the builder")
                .isEmpty();
    }

    /**
     * @return {@code case01} through {@code case20}, in order
     */
    private static List<String> expectedCaseIds() {
        List<String> identifiers = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers.add(ParityHarness.caseId(ordinal));
        }
        return List.copyOf(identifiers);
    }

    /**
     * The program directories that exist, read from the classpath rather than listed here so that a
     * twenty-ninth program is covered the moment its directory appears.
     *
     * @return the program names, in directory order
     * @throws IllegalStateException if the case root is absent from the classpath
     */
    private static List<String> shippedPrograms() {
        URL root = Thread.currentThread().getContextClassLoader()
                .getResource(ParityHarness.CASE_RESOURCE_ROOT);
        if (root == null) {
            throw new IllegalStateException("The case root " + ParityHarness.CASE_RESOURCE_ROOT
                    + " is not on the classpath, so no program's cases can be found at all.");
        }
        List<String> programs = new ArrayList<>(PROGRAM_COUNT);
        try (DirectoryStream<Path> directories =
                Files.newDirectoryStream(Path.of(root.toURI()), Files::isDirectory)) {
            for (Path directory : directories) {
                programs.add(directory.getFileName().toString());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not list " + ParityHarness.CASE_RESOURCE_ROOT,
                    failure);
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("The case root resolved to a URL that is not a path: "
                    + root, failure);
        }
        programs.sort(String::compareTo);
        return List.copyOf(programs);
    }

    /**
     * @param program the program name
     * @return that program's suite class
     * @throws IllegalStateException if no {@code <PROGRAM>ParityTest} class exists, which means a shipped
     *                               program has no suite at all
     */
    private static Class<?> suiteOf(String program) {
        String name = ShippedCaseAdoptionTest.class.getPackageName() + '.' + program + SUITE_SUFFIX;
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            throw new IllegalStateException("Program " + program + " ships "
                    + ParityHarness.CASES_PER_PROGRAM + " cases under "
                    + ParityHarness.CASE_RESOURCE_ROOT + program + "/ but no class " + name
                    + " exists to run them.", absent);
        }
    }

    /**
     * Invokes every {@code @MethodSource} supplier the suite's parameterized tests name, and collects
     * every {@link ParityCase} they yield.
     *
     * @param suite the suite class
     * @return the cases its suppliers yield, in supplier order
     * @throws IllegalStateException if the suite names no supplier, or a named supplier cannot be found
     *                               or invoked
     */
    private static List<ParityCase> casesSuppliedBy(Class<?> suite) {
        Set<String> supplierNames = new LinkedHashSet<>();
        for (Method method : suite.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(ParameterizedTest.class)) {
                continue;
            }
            MethodSource source = method.getAnnotation(MethodSource.class);
            if (source == null) {
                continue;
            }
            for (String supplier : source.value()) {
                supplierNames.add(supplier.isEmpty() ? method.getName() : supplier);
            }
        }
        if (supplierNames.isEmpty()) {
            throw new IllegalStateException(suite.getSimpleName() + " declares no @ParameterizedTest "
                    + "with a @MethodSource, so nothing in it can be running twenty cases.");
        }
        List<ParityCase> supplied = new ArrayList<>();
        for (String supplier : supplierNames) {
            supplied.addAll(casesFrom(invoke(suite, supplier), suite, supplier));
        }
        if (supplied.isEmpty()) {
            throw new IllegalStateException(suite.getSimpleName() + "'s supplier(s) " + supplierNames
                    + " yielded no ParityCase at all, so this test cannot tell what it runs.");
        }
        return List.copyOf(supplied);
    }

    /**
     * @param suite    the suite class
     * @param supplier the supplier method name
     * @return whatever it returned
     * @throws IllegalStateException if the method is absent, or throws
     */
    private static Object invoke(Class<?> suite, String supplier) {
        Method method;
        try {
            method = suite.getDeclaredMethod(supplier);
        } catch (NoSuchMethodException absent) {
            throw new IllegalStateException(suite.getSimpleName() + " names @MethodSource(\"" + supplier
                    + "\") but declares no such no-argument method.", absent);
        }
        method.setAccessible(true);
        try {
            return method.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Invoking " + suite.getSimpleName() + '.' + supplier
                    + "() failed, so whether it yields the shipped cases cannot be established.",
                    failure);
        }
    }

    /**
     * Extracts the cases from a supplier's return value, whatever shape the suite chose for it: a
     * {@link Stream} or {@link Collection} of {@link ParityCase}, of {@link Arguments}, or of a wrapper
     * record that carries a case.
     *
     * @param supplied the supplier's return value
     * @param suite    the suite the supplier belongs to, named in the failure message
     * @param supplier the supplier's method name, named in the failure message
     * @return the cases found, in encounter order
     * @throws IllegalStateException if an element neither is nor carries a case
     */
    private static List<ParityCase> casesFrom(Object supplied, Class<?> suite, String supplier) {
        List<Object> elements = new ArrayList<>();
        if (supplied instanceof Stream<?> stream) {
            stream.forEach(elements::add);
        } else if (supplied instanceof Collection<?> collection) {
            elements.addAll(collection);
        } else if (supplied != null) {
            elements.add(supplied);
        }
        List<ParityCase> cases = new ArrayList<>(elements.size());
        for (Object element : elements) {
            List<ParityCase> found = casesIn(element);
            if (found.isEmpty()) {
                throw new IllegalStateException(suite.getSimpleName() + '.' + supplier
                        + "() yielded a "
                        + (element == null ? "null" : element.getClass().getName())
                        + ", which neither is a ParityCase nor carries one, so what it runs cannot be "
                        + "established. A suite's parameter must carry the case it judges.");
            }
            cases.addAll(found);
        }
        return cases;
    }

    /**
     * Every case one supplied element is or carries.
     *
     * <p>Five shapes are recognised, because the twenty-eight suites use five: the case itself; a
     * {@link Named} whose payload is unwrapped, which is how a suite gives its cases readable display
     * names; an {@link Arguments} tuple, whose components are searched in turn; a record with a
     * {@link ParityCase} component, which is the common wrapper; and any object with a no-argument
     * accessor returning one.
     *
     * @param element one element a supplier yielded
     * @return the cases found, empty when the element carries none
     */
    private static List<ParityCase> casesIn(Object element) {
        if (element instanceof ParityCase declared) {
            return List.of(declared);
        }
        if (element == null) {
            return List.of();
        }
        if (element instanceof Named<?> named) {
            return casesIn(named.getPayload());
        }
        if (element instanceof Arguments tuple) {
            List<ParityCase> found = new ArrayList<>();
            for (Object component : tuple.get()) {
                found.addAll(casesIn(component));
            }
            return List.copyOf(found);
        }
        Class<?> type = element.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getType() == ParityCase.class) {
                    return List.of((ParityCase) read(component.getAccessor(), element));
                }
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getReturnType() == ParityCase.class && method.getParameterCount() == 0) {
                return List.of((ParityCase) read(method, element));
            }
        }
        return List.of();
    }

    /**
     * @param accessor the accessor to call
     * @param element  the element to call it on
     * @return what it returned
     * @throws IllegalStateException if the call fails
     */
    private static Object read(Method accessor, Object element) {
        accessor.setAccessible(true);
        try {
            return accessor.invoke(element);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Reading the ParityCase out of a "
                    + element.getClass().getSimpleName() + " failed.", failure);
        }
    }
}
