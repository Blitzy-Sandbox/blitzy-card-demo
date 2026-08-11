package com.vsergeychik.carddemo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link CardDemoApplication}, the module's composition root.
 *
 * <p>This suite exists because the entry point's obligations are all <em>declarative</em>, and a
 * declarative mistake is silent. A dropped entry in the component-scan list does not fail to
 * compile: it removes a whole domain package from component scope, and the first symptom is a
 * {@code NoSuchBeanDefinitionException} at runtime, a long way from the edit that caused it.
 * Equally, the class name and package are a published contract - {@code app/java/pom.xml} pins
 * {@code com.vsergeychik.carddemo.CardDemoApplication} as the {@code spring-boot-maven-plugin}
 * {@code mainClass}, and Maven holds that value as a <em>string</em>, entirely outside the compiler's
 * symbol graph. Renaming the class therefore leaves the build green and the jar unbootable. Both
 * hazards are checked here.
 *
 * <h2>Why reflection and the source tree, and not {@code @SpringBootTest}</h2>
 * <p>A full context-load test is the natural way to prove component scan reaches every bean, and it
 * is the right test to have once every bean exists. It is not this test: at this point in the
 * migration many of the beans the eleven packages will hold have not been written, so a
 * {@code @SpringBootTest} would fail for reasons that have nothing to do with the entry point's
 * correctness and would say nothing about the declarations under examination. What can be proven
 * now, and is proven here, is that the declarations themselves are right and complete: the annotation
 * is present, the scan list names every package that actually exists in the source tree and nothing
 * that does not, the published name agrees with the build manifest, and nothing forbidden has crept
 * in. {@code main} is deliberately never invoked - calling it would start the very context this suite
 * is careful not to depend on.
 *
 * <h2>Governing rules</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project, confirmed
 * for this file, so no project rule governs it and none has been invented. The enterprise practices
 * the Agent Action Plan puts in their place bind instead, and the ones this file both obeys and
 * enforces on its subject are:
 * <ul>
 *   <li><strong>B1 / B2</strong> - nothing outside the stack the build already pins. JUnit Jupiter
 *       and AssertJ arrive through {@code spring-boot-starter-test}; {@code SpringBootApplication},
 *       {@code ApplicationRunner} and {@code CommandLineRunner} through
 *       {@code spring-boot-starter-web}. No new dependency, and Spring Boot 3.5.x API only.</li>
 *   <li><strong>B4</strong> - no silent scope creep. The assertions below are deliberately
 *       two-sided: they check what must be present <em>and</em> that nothing else is.</li>
 *   <li><strong>B8</strong> - explicit over implicit. Every charset is named; there are no wildcard
 *       imports here and this suite proves there are none in its subject either.</li>
 *   <li><strong>B9</strong> - no static mutable state, proven rather than asserted in prose.</li>
 * </ul>
 */
@DisplayName("CardDemoApplication - the composition root, and the name the build manifest pins")
class CardDemoApplicationTest {

    /** The published root package. Both the class's own package and the scan-list prefix. */
    private static final String ROOT_PACKAGE = "com.vsergeychik.carddemo";

    /** The published fully-qualified name, which {@code app/java/pom.xml} carries as a string. */
    private static final String PUBLISHED_FQN = ROOT_PACKAGE + ".CardDemoApplication";

    /**
     * The eleven packages the entry point must place in component scope, in the order the
     * architecture reads in: the two foundation packages, then the nine domain packages holding the
     * twenty-eight translated programs.
     */
    private static final List<String> EXPECTED_SCAN_PACKAGES = List.of(
            ROOT_PACKAGE + ".common",
            ROOT_PACKAGE + ".config",
            ROOT_PACKAGE + ".account",
            ROOT_PACKAGE + ".card",
            ROOT_PACKAGE + ".customer",
            ROOT_PACKAGE + ".user",
            ROOT_PACKAGE + ".transaction",
            ROOT_PACKAGE + ".admin",
            ROOT_PACKAGE + ".billing",
            ROOT_PACKAGE + ".statement",
            ROOT_PACKAGE + ".util");

    /**
     * The test-only package that holds the stereotyped fixtures, and the one package under the root
     * that must stay <em>out</em> of the scan list.
     */
    private static final String TEST_SUPPORT_PACKAGE = ROOT_PACKAGE + ".testsupport";

    /** The suffix of a compiled class file. */
    private static final String CLASS_SUFFIX = ".class";

    /** The bytecode descriptor of {@code @Controller}, as it appears in a class file's constant pool. */
    private static final String CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/stereotype/Controller;";

    /** The bytecode descriptor of {@code @RestController}, which carries {@code @Controller}. */
    private static final String REST_CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/web/bind/annotation/RestController;";

    /** Repository-relative path of the module descriptor, used as the checkout marker. */
    private static final String POM_PATH = "app/java/pom.xml";

    /** Repository-relative path of the package this class belongs to, inside the module. */
    private static final String ROOT_PACKAGE_PATH =
            "app/java/src/main/java/com/vsergeychik/carddemo";

    /** Repository-relative path of this test's subject, read as text for the source-level checks. */
    private static final String SUBJECT_SOURCE_PATH =
            ROOT_PACKAGE_PATH + "/CardDemoApplication.java";

    /** Extracts the {@code mainClass} the Boot plugin is configured with. */
    private static final Pattern MAIN_CLASS =
            Pattern.compile("<mainClass>\\s*([^<\\s]+)\\s*</mainClass>");

    /** A star import, in the one shape Java can express it. */
    private static final Pattern WILDCARD_IMPORT =
            Pattern.compile("import\\s+(?:static\\s+)?[\\w.]+\\.\\*\\s*;");

    @Nested
    @DisplayName("The published identity - the name and package the build manifest depends on")
    class PublishedIdentity {

        @Test
        @DisplayName("the class sits in com.vsergeychik.carddemo, the root of every generated import")
        void classSitsInTheRootPackage() {
            assertThat(CardDemoApplication.class.getPackageName()).isEqualTo(ROOT_PACKAGE);
            assertThat(CardDemoApplication.class.getName()).isEqualTo(PUBLISHED_FQN);
        }

        @Test
        @DisplayName("the class is public and not final, as an entry point Boot must proxy-free load")
        void classIsPublicAndNotFinal() {
            int modifiers = CardDemoApplication.class.getModifiers();
            assertThat(Modifier.isPublic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isFalse();
            assertThat(Modifier.isAbstract(modifiers)).isFalse();
        }

        @Test
        @DisplayName("app/java/pom.xml pins exactly this class, so the Boot plugin target resolves")
        void buildManifestPinsThisExactClass() throws IOException, ClassNotFoundException {
            String pom = Files.readString(repositoryFile(POM_PATH), StandardCharsets.UTF_8);
            Matcher matcher = MAIN_CLASS.matcher(pom);

            assertThat(matcher.find())
                    .as("%s must configure a <mainClass> for spring-boot-maven-plugin", POM_PATH)
                    .isTrue();
            String configured = matcher.group(1);

            // The value Maven holds is a plain string, so the compiler never checks it. Loading it is
            // the check: if this resolves, the repackaged jar's Start-Class names a real class.
            assertThat(configured).isEqualTo(PUBLISHED_FQN);
            assertThat(Class.forName(configured)).isSameAs(CardDemoApplication.class);

            assertThat(matcher.find())
                    .as("<mainClass> must be configured exactly once; a second one would make the "
                            + "effective entry point depend on plugin merge order")
                    .isFalse();
        }

        @Test
        @DisplayName("no module-info.java exists: this module is a classpath jar, not a JPMS module")
        void moduleDescriptorIsAbsent() throws IOException {
            Path moduleRoot = repositoryFile(POM_PATH).getParent();
            try (Stream<Path> tree = Files.walk(moduleRoot.resolve("src"))) {
                assertThat(tree.filter(path -> path.getFileName().toString()
                                .equals("module-info.java")))
                        .as("packaging is jar; a module descriptor would force a requires clause for "
                                + "every Spring dependency, which nothing asked for")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("@SpringBootApplication and the eleven-package scan list")
    class ComponentScanDeclaration {

        @Test
        @DisplayName("@SpringBootApplication is present, and is the only annotation on the class")
        void springBootApplicationIsTheSoleAnnotation() {
            Annotation[] declared = CardDemoApplication.class.getDeclaredAnnotations();

            assertThat(declared).hasSize(1);
            assertThat(declared[0].annotationType()).isEqualTo(SpringBootApplication.class);
        }

        @Test
        @DisplayName("scanBasePackages names exactly the eleven packages, in architectural order")
        void scanBasePackagesAreExactlyTheElevenInOrder() {
            assertThat(scanBasePackages())
                    .as("order is foundation first, then the nine domain packages")
                    .containsExactlyElementsOf(EXPECTED_SCAN_PACKAGES);
        }

        @Test
        @DisplayName("every scanned package lies under the root package and none repeats")
        void scannedPackagesAreRootedAndDistinct() {
            List<String> scanned = scanBasePackages();

            assertThat(scanned).doesNotHaveDuplicates();
            assertThat(scanned).allSatisfy(name ->
                    assertThat(name).startsWith(ROOT_PACKAGE + "."));
        }

        @Test
        @DisplayName("the scan list matches the source tree exactly - nothing dropped, nothing invented")
        void scanListMatchesTheSourceTree() throws IOException {
            Path packageRoot = repositoryFile(ROOT_PACKAGE_PATH);
            List<String> onDisk = new ArrayList<>();
            try (Stream<Path> children = Files.list(packageRoot)) {
                children.filter(Files::isDirectory)
                        .map(path -> ROOT_PACKAGE + "." + path.getFileName())
                        .sorted()
                        .forEach(onDisk::add);
            }

            // Two-sided on purpose. A missing entry silently drops a domain package out of component
            // scope; a surplus entry names a package that does not exist, which is just as wrong and
            // would not be caught by a "contains" assertion.
            assertThat(scanBasePackages())
                    .containsExactlyInAnyOrderElementsOf(onDisk)
                    .hasSize(EXPECTED_SCAN_PACKAGES.size());
        }
    }

    @Nested
    @DisplayName("main - one line, and nothing that runs at startup")
    class EntryPointMethod {

        @Test
        @DisplayName("main is the only public method, and is public static void(String[])")
        void mainIsTheOnlyPublicMethod() throws NoSuchMethodException {
            // Everything beside main is package-private and exists for one reason: the launch mode has
            // to be decided before the context exists, so the decision cannot live in a bean, and a
            // decision nobody can call is a decision nobody can test.
            assertThat(authored(CardDemoApplication.class.getDeclaredMethods()).stream()
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .toList())
                    .containsExactly("main");

            Method main = CardDemoApplication.class.getDeclaredMethod("main", String[].class);
            assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
            assertThat(main.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("every authored method is static, because none of them needs an instance")
        void everyAuthoredMethodIsStatic() {
            // The entry point is reached with no instance in existence, and so is the launch-mode
            // decision it takes. An instance method here would imply state this class does not have.
            assertThat(authored(CardDemoApplication.class.getDeclaredMethods()).stream()
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName)
                    .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("the class declares no field at all, so it holds no state of any kind")
        void theClassDeclaresNoField() {
            // Gate G53, asserted at its strongest. "No mutable static" would permit a constant, and a
            // constant here would be the beginning of configuration living in the composition root -
            // the very thing application.yml exists for. A composition root needs no field, so it has
            // none, and this asserts the absence rather than the harmlessness.
            assertThat(authored(CardDemoApplication.class.getDeclaredFields()).stream()
                    .map(java.lang.reflect.Field::getName)
                    .toList())
                    .as("a composition root that needs no state should declare none")
                    .isEmpty();
        }

        @Test
        @DisplayName("only the implicit public no-arg constructor exists")
        void onlyTheImplicitConstructorExists() {
            List<Constructor<?>> constructors =
                    authored(CardDemoApplication.class.getDeclaredConstructors());

            assertThat(constructors).hasSize(1);
            assertThat(constructors.get(0).getParameterCount()).isZero();
            assertThat(Modifier.isPublic(constructors.get(0).getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the class is no runner: starting the context launches no batch job")
        void theClassIsNotARunner() {
            // application.yml sets spring.batch.job.enabled: false so that jobs are launched
            // explicitly. A runner declared here would defeat that, and the job translated from the
            // JCL-orphaned CBTRN01C must stay runnable yet untriggered.
            assertThat(ApplicationRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CardDemoApplication.class.getInterfaces()).isEmpty();
            assertThat(CardDemoApplication.class.getSuperclass()).isEqualTo(Object.class);
        }
    }

    @Nested
    @DisplayName("The scanned packages hold no test-tree component, so the route table is the shipped "
            + "one")
    class TestTreeStaysOutOfComponentScope {

        /**
         * The one thing the existing whole-context tests structurally cannot catch.
         *
         * <p>{@code src/test/java} compiles to {@code target/test-classes}, which is on the classpath
         * of every test run and of a local run started from this module's build output. A test class
         * carrying a bean stereotype inside one of the eleven scanned packages is therefore a
         * component-scan candidate, and it was: a {@code @RestController} fixture nested in a
         * {@code config} suite joined the context as an eighteenth controller publishing ten
         * {@code /webconfig-fixture/**} routes, so a locally started application answered a route table
         * the deployed artifact does not have - and some of those routes abend by design.
         *
         * <p>A {@code @SpringBootTest} cannot see this. Spring Boot's test framework contributes a
         * {@code TypeExcludeFilter} that excludes test classes and everything they enclose, so the
         * fixture is filtered out of exactly the contexts a test could assert on and is registered in
         * the plain {@code main} run nobody asserts on. That is why this reads the compiled test output
         * directly instead: no context, no filter, no class loading - the annotations are read from the
         * class files themselves.
         *
         * @throws IOException if the compiled test output cannot be walked
         */
        @Test
        @DisplayName("no class compiled from the test tree inside a scanned package is a controller")
        void noTestTreeClassInsideAScannedPackageIsAController() throws IOException {
            List<String> offenders = new ArrayList<>();
            Path testOutput = compiledTestOutput();
            try (Stream<Path> files = Files.walk(testOutput)) {
                for (Path file : files.filter(path -> path.toString().endsWith(CLASS_SUFFIX))
                        .toList()) {
                    String className = classNameOf(testOutput, file);
                    if (liesInAScannedPackage(className) && declaresAControllerStereotype(file)) {
                        offenders.add(className);
                    }
                }
            }

            assertThat(offenders)
                    .as("a controller-shaped test fixture inside a scanned package is offered to every "
                            + "context refreshed from target/test-classes; put it in "
                            + "com.vsergeychik.carddemo.testsupport, which is deliberately unscanned")
                    .isEmpty();
        }

        /**
         * The counterpart assertion: the package that holds those fixtures is not scanned.
         *
         * <p>Stated separately because the two can fail independently. The check above passes the
         * moment a fixture moves out of a scanned package; this one fails if the scan list later grows
         * to cover where it moved to, which would restore the defect without touching a single test.
         */
        @Test
        @DisplayName("the test-support package is not in the scan list, which is why it can hold them")
        void theTestSupportPackageIsNotScanned() {
            assertThat(scanBasePackages())
                    .as("com.vsergeychik.carddemo.testsupport exists precisely to be out of component "
                            + "scope; scanning it would put its fixtures into the container")
                    .doesNotContain(TEST_SUPPORT_PACKAGE);
        }

        /**
         * Reports whether a class file declares {@code @Controller} or an annotation meta-annotated
         * with it, {@code @RestController} being the one this module's fixtures used.
         *
         * <p>The class file is read as bytes and the annotation descriptors are matched as text, so
         * nothing is loaded and no static initialiser of a test class runs during this check.
         *
         * @param classFile the compiled class to inspect
         * @return {@code true} when the class declares a controller stereotype
         * @throws IOException if the class file cannot be read
         */
        private boolean declaresAControllerStereotype(Path classFile) throws IOException {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains(CONTROLLER_DESCRIPTOR) || bytes.contains(REST_CONTROLLER_DESCRIPTOR);
        }

        /**
         * Reports whether a class lies in one of the eleven packages the entry point scans, or beneath
         * one of them.
         *
         * @param className the fully-qualified class name, with {@code $} for nesting
         * @return {@code true} when component scanning would reach it
         */
        private boolean liesInAScannedPackage(String className) {
            return scanBasePackages().stream()
                    .anyMatch(scanned -> className.startsWith(scanned + "."));
        }

        /**
         * Derives a class name from the path of its class file, relative to the output root.
         *
         * @param root      the compiled output root
         * @param classFile the class file beneath it
         * @return the fully-qualified class name, with {@code $} retained for nested classes
         */
        private String classNameOf(Path root, Path classFile) {
            String relative = root.relativize(classFile).toString();
            return relative.substring(0, relative.length() - CLASS_SUFFIX.length())
                    .replace(java.io.File.separatorChar, '.');
        }

        /**
         * The directory this suite's own class file was loaded from, which is the compiled test tree.
         *
         * <p>Taken from the code source rather than assembled from a repository-relative path, so the
         * check follows the build rather than a convention about where the build puts things.
         *
         * @return the compiled test output root
         */
        private Path compiledTestOutput() {
            Path location = Path.of(java.net.URI.create(CardDemoApplicationTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toString()));
            assertThat(Files.isDirectory(location))
                    .as("this suite runs from a directory of class files, which is what makes the test "
                            + "tree walkable; a packaged test jar would need a different reader")
                    .isTrue();
            return location;
        }
    }

    @Nested
    @DisplayName("Source-level prohibitions, read from the file itself")
    class SourceLevelProhibitions {

        @Test
        @DisplayName("every import is named, and the set is exactly the two Boot types the entry point "
                + "needs")
        void importsAreNamedAndExactlyWhatIsNeeded() throws IOException {
            List<String> imports = code().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .toList();

            // Stated as an exact set rather than a count, so an import added for anything the entry
            // point has no business doing - persistence, security, cloud, observability, or a launch
            // decision that belongs to configuration - fails here. A composition root declares where
            // beans come from and starts the context; it needs the annotation and the launcher, and
            // nothing else. Two imports is the whole of it.
            assertThat(imports).containsExactly(
                    "import org.springframework.boot.SpringApplication;",
                    "import org.springframework.boot.autoconfigure.SpringBootApplication;");
        }

        @Test
        @DisplayName("no wildcard import, so every type this file uses is named")
        void noWildcardImport() throws IOException {
            assertThat(WILDCARD_IMPORT.matcher(code()).find()).isFalse();
        }

        @Test
        @DisplayName("none of the forbidden enablements or excluded technologies appears in the code")
        void forbiddenDeclarationsAreAbsent() throws IOException {
            // Deliberately over the comment-stripped text. The class documentation explains WHY
            // several of these are absent and therefore names them; a scan over the raw file would
            // read that explanation as the violation it warns against.
            String text = code();
            List<String> forbidden = List.of(
                    // Under Boot 3 this DISABLES batch auto-configuration rather than enabling it.
                    "@EnableBatchProcessing",
                    // One scan mechanism, not two.
                    "@ComponentScan",
                    "@EnableScheduling",
                    "@EnableAsync",
                    "@EnableTransactionManagement",
                    "@EnableJpaRepositories",
                    "@EntityScan",
                    "@PropertySource",
                    "CommandLineRunner",
                    "ApplicationRunner",
                    "setDefaultProperties",
                    // Excluded technologies, every one of them named in the plan's exclusion table.
                    "springframework.security",
                    "hibernate",
                    "flyway",
                    "liquibase",
                    "micrometer",
                    "testcontainers",
                    "lombok",
                    // Gate G46: no dataset name is ever written into Java.
                    "AWS.M2.CARDDEMO.",
                    // Gates G22 and G24: no binary floating point, no non-truncating rounding.
                    "double",
                    "float",
                    "HALF_UP",
                    "HALF_EVEN");

            assertThat(forbidden.stream().filter(text::contains).toList())
                    .as("%s must declare none of these", SUBJECT_SOURCE_PATH)
                    .isEmpty();
        }

        @Test
        @DisplayName("the entry point takes no launch-mode decision, because it is not the entry "
                + "point's to take")
        void theEntryPointTakesNoLaunchModeDecision() throws IOException {
            // It is tempting to have this class choose a non-web mode when a job is being submitted,
            // on the reasoning that a submission must end when its job ends and deliver the
            // RETURN-CODE (gate G35), which a servlet container's non-daemon threads would prevent.
            // That reasoning does not survive reading BatchConfig: JclJobLauncher is an
            // ApplicationRunner and an ExitCodeGenerator, and it ends the process itself with
            //     System.exit(SpringApplication.exit(applicationContext, () -> returnCode));
            // System.exit terminates the JVM whatever else is running, so the RETURN-CODE contract is
            // already satisfied where the batch concern lives. A mode decision here would be a second
            // rule about what kind of process this is, duplicating a condition BatchConfig already
            // owns, and it would put a branch in a composition root that should hold none. An operator
            // who wants no container passes --spring.main.web-application-type=none, which is
            // configuration, not code.
            String text = code();

            assertThat(text)
                    .as("no launch mode is chosen in code, and no bean-lifecycle or property override "
                            + "is applied to the application before it runs")
                    .doesNotContain("setWebApplicationType")
                    .doesNotContain("WebApplicationType")
                    .doesNotContain("setAdditionalProfiles")
                    .doesNotContain("setDefaultProperties");
            assertThat(text)
                    .as("the composition root names no configuration class, so it cannot drift with "
                            + "one: it declares where beans come from and starts the context")
                    .doesNotContain("BatchConfig");
        }

        @Test
        @DisplayName("the executable part is a single statement, so there is nothing in it to get wrong")
        void theExecutablePartIsASingleStatement() throws IOException {
            // The declarations above are what this file is for; the executable part should be the
            // smallest thing that starts a context. Anything that grows a second statement - a runner,
            // a mode decision, an exit-code path, a log line - is logic that belongs in a bean, where
            // it can be injected, mocked and covered. Asserted two ways: the statement is exactly the
            // expected one, and it is the only one the class launches with.
            String text = code();

            assertThat(text)
                    .as("the one statement Boot needs, with this class as the configuration source")
                    .contains("SpringApplication.run(CardDemoApplication.class, args);");
            assertThat(text.split("SpringApplication\\.run", -1).length - 1)
                    .as("started once, in main, and nowhere else")
                    .isOne();

            // Semicolons over the comment-stripped text: the package declaration, the two imports and
            // that single call. A fifth would be a statement this file has no business carrying.
            assertThat(text.chars().filter(character -> character == ';').count())
                    .as("package, two imports, one call")
                    .isEqualTo(4L);
        }

        @Test
        @DisplayName("every static in the code is final or a static method, never static mutable state")
        void everyStaticIsFinalOrAMethod() throws IOException {
            // Read from the source rather than by reflection so that a field declared and never read -
            // which reflection would still report as final - is judged on how it is written. A static
            // that is neither final nor a method signature would be shared mutable state (gate G53).
            List<String> staticLines = code().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("static ") || line.contains(" static "))
                    .filter(line -> !line.contains("static final "))
                    .filter(line -> !line.endsWith("{"))
                    .toList();

            assertThat(staticLines)
                    .as("a static that is neither final nor a method signature is shared mutable state")
                    .isEmpty();
        }
    }

    /**
     * Keeps only the members a human wrote, discarding those the coverage agent adds.
     *
     * <p>This matters, and getting it wrong produces the worst kind of test: one that is green under
     * {@code mvn test} and red under {@code mvn verify}. The build runs the JaCoCo agent, which
     * instruments every loaded class by adding a {@code private static synthetic $jacocoInit} method
     * and a {@code private static transient synthetic $jacocoData} field. A bare
     * {@code getDeclaredMethods().length == 1} therefore holds only while coverage is switched off.
     * Both the synthetic flag and the {@code $} in the generated names are checked, because an
     * instrumenting agent that omitted the flag would otherwise slip through.
     *
     * @param members the reflected members
     * @param <T> the member type
     * @return the authored members, in reflection order
     */
    private static <T extends Member> List<T> authored(T[] members) {
        List<T> result = new ArrayList<>();
        for (T member : members) {
            if (!member.isSynthetic() && !member.getName().contains("$")) {
                result.add(member);
            }
        }
        return result;
    }

    /**
     * The scan list the annotation actually declares, read from the annotation rather than restated.
     *
     * @return the declared {@code scanBasePackages}, in declaration order
     */
    private static List<String> scanBasePackages() {
        SpringBootApplication annotation =
                CardDemoApplication.class.getDeclaredAnnotation(SpringBootApplication.class);
        assertThat(annotation).as("@SpringBootApplication must be present").isNotNull();
        return List.of(annotation.scanBasePackages());
    }

    /**
     * The subject's own source text, decoded with an explicitly named charset.
     *
     * @return the contents of {@code CardDemoApplication.java}
     * @throws IOException if the file cannot be read
     */
    private static String source() throws IOException {
        return Files.readString(repositoryFile(SUBJECT_SOURCE_PATH), StandardCharsets.UTF_8);
    }

    /**
     * The subject's source with every comment removed, which is the text the prohibition checks below
     * are actually about.
     *
     * <p>Stripping matters because the class documentation earns its keep by naming what it must
     * <em>not</em> do and why - {@code @EnableBatchProcessing} disabling Batch auto-configuration
     * under Boot 3, for instance. A scan over the raw file would flag that explanation as the very
     * violation it exists to prevent, which would push the explanation out of the code and leave the
     * next reader to rediscover it.
     *
     * <p>The stripper is a plain two-state scan over block and line comments and takes no account of
     * a comment delimiter appearing inside a string literal. That is sufficient and is checked
     * against the subject: its only literals are the eleven package names, none of which contains
     * one.
     *
     * @return the source with comments replaced by nothing, line structure otherwise preserved
     * @throws IOException if the file cannot be read
     */
    private static String code() throws IOException {
        String text = source();
        StringBuilder stripped = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("/*", index)) {
                int close = text.indexOf("*/", index + 2);
                index = close < 0 ? text.length() : close + 2;
            } else if (text.startsWith("//", index)) {
                int newLine = text.indexOf('\n', index);
                index = newLine < 0 ? text.length() : newLine;
            } else {
                stripped.append(text.charAt(index));
                index++;
            }
        }
        return stripped.toString();
    }

    /**
     * Resolves a repository-relative path by walking up from the working directory to the nearest
     * ancestor that contains it, so the suite runs identically from the repository root, from
     * {@code app/java} and from an IDE. The nearest ancestor wins, so a checkout nested inside
     * another cannot be read by mistake.
     *
     * @param relativePath the repository-relative path
     * @return the resolved absolute path
     */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath() + "; this suite reads the module descriptor and its "
                + "own subject from the checkout rather than from a copy of them");
    }
}
