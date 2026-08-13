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
 */
@DisplayName("Shipped case adoption - all 560 parity resources are executed, none is decorative")
class ShippedCaseAdoptionTest {
    private static final int PROGRAM_COUNT = 28;

    private static final int TOTAL_CASES = PROGRAM_COUNT * ParityHarness.CASES_PER_PROGRAM;

    private static final String SUITE_SUFFIX = "ParityTest";

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

    private static List<String> expectedCaseIds() {
        List<String> identifiers = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers.add(ParityHarness.caseId(ordinal));
        }
        return List.copyOf(identifiers);
    }

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
