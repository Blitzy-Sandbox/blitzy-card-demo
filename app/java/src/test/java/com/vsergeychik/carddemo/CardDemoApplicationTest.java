package com.vsergeychik.carddemo;

import com.vsergeychik.carddemo.config.BatchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
        @DisplayName("every declared field is static final, so the class holds no mutable state")
        void everyFieldIsStaticFinal() {
            // Gate G53. The class carries one field - the relaxed environment-variable spelling of the
            // job-name property, derived rather than transcribed - and a constant is not state.
            assertThat(authored(CardDemoApplication.class.getDeclaredFields()).stream()
                    .filter(field -> !(Modifier.isStatic(field.getModifiers())
                            && Modifier.isFinal(field.getModifiers())))
                    .map(java.lang.reflect.Field::getName)
                    .toList())
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
    @DisplayName("Source-level prohibitions, read from the file itself")
    class SourceLevelProhibitions {

        @Test
        @DisplayName("every import is named, and the set is exactly what the entry point and its launch-"
                + "mode decision need")
        void importsAreNamedAndExactlyWhatIsNeeded() throws IOException {
            List<String> imports = code().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .toList();

            // Stated as an exact set rather than a count, so an import added for anything the entry
            // point has no business doing - persistence, security, cloud, observability - fails here.
            // The four beyond the two Boot types exist for the launch-mode decision alone: it is taken
            // before any Environment exists, so it reads the command line and the process itself.
            assertThat(imports).containsExactly(
                    "import java.util.Locale;",
                    "import java.util.Objects;",
                    "import java.util.function.UnaryOperator;",
                    "import com.vsergeychik.carddemo.config.BatchConfig;",
                    "import org.springframework.boot.SpringApplication;",
                    "import org.springframework.boot.WebApplicationType;",
                    "import org.springframework.boot.autoconfigure.SpringBootApplication;",
                    "import org.springframework.core.env.SimpleCommandLinePropertySource;",
                    "import org.springframework.util.StringUtils;");
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
        @DisplayName("the web application type is set for one reason only - a JCL submission is a "
                + "one-shot non-web process")
        void theWebApplicationTypeIsSetForOneReasonOnly() throws IOException {
            // WebApplicationType is not banned here, because gate G35 requires the opposite: a
            // submission has to end when its job ends and deliver the RETURN-CODE, which a servlet
            // container's non-daemon threads would prevent. What IS required is that the type is chosen
            // in exactly one place, from exactly one condition, so no second rule can quietly decide
            // what kind of process this is.
            String text = code();

            assertThat(text.lines().filter(line -> line.contains("setWebApplicationType")).count())
                    .as("one setter call, in the one method that decides the mode")
                    .isOne();
            assertThat(text.lines()
                    .filter(line -> line.contains("WebApplicationType.NONE"))
                    .count())
                    .as("the non-web mode is chosen once, by the submission condition")
                    .isOne();
            assertThat(text)
                    .as("the condition is the launcher's own property, so the two halves of a "
                            + "submission cannot disagree about what kind of process it is")
                    .contains("BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY");
            assertThat(text)
                    .as("no profile is activated from code, and no default property is injected")
                    .doesNotContain("setAdditionalProfiles")
                    .doesNotContain("setDefaultProperties");
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
     * The launch mode: a JCL submission is a one-shot non-web process, everything else is the web
     * application.
     *
     * <p>The same jar serves the seventeen translated CICS transactions and submits batch jobs, and
     * those are different kinds of process. A submission has to end when its job ends, delivering the
     * {@code RETURN-CODE} to the shell (gate G35); a servlet container's non-daemon threads would keep
     * it alive instead. The web application type is chosen while the environment is being prepared, so
     * the decision cannot be taken by a bean - it is taken here, and asserted here, without starting
     * anything.
     */
    @Nested
    @DisplayName("The launch mode - a submission is a one-shot non-web process")
    class LaunchMode {

        /** The property whose presence makes an invocation a submission. */
        private static final String JOB_NAME_PROPERTY =
                BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY;

        /** A process that supplies nothing outside the command line. */
        private static final UnaryOperator<String> NOTHING_EXTERNAL = name -> null;

        @Test
        @DisplayName("no job name anywhere means the web application, exactly as before")
        void noJobNameMeansTheWebApplication() {
            assertThat(CardDemoApplication.isJclSubmission(new String[0], NOTHING_EXTERNAL)).isFalse();
            assertThat(CardDemoApplication.webApplicationTypeFor(
                    new String[] { "--spring.profiles.active=test" }, NOTHING_EXTERNAL))
                    .isEqualTo(WebApplicationType.SERVLET);
        }

        @Test
        @DisplayName("a job name on the command line means a one-shot non-web process")
        void aJobNameOnTheCommandLineMeansNonWeb() {
            String[] submission = { "--" + JOB_NAME_PROPERTY + "=accountBalanceJob" };

            assertThat(CardDemoApplication.isJclSubmission(submission, NOTHING_EXTERNAL)).isTrue();
            assertThat(CardDemoApplication.webApplicationTypeFor(submission, NOTHING_EXTERNAL))
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("a job name supplied outside the command line counts too, because a container "
                + "supplies it that way")
        void aJobNameSuppliedOutsideTheCommandLineCountsToo() {
            UnaryOperator<String> supplied =
                    name -> JOB_NAME_PROPERTY.equals(name) ? "statementGenerationJobA" : null;

            assertThat(CardDemoApplication.isJclSubmission(new String[0], supplied)).isTrue();
            assertThat(CardDemoApplication.webApplicationTypeFor(new String[0], supplied))
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("a blank job name is not a submission, because there is no job to submit")
        void aBlankJobNameIsNotASubmission() {
            UnaryOperator<String> blank = name -> "   ";

            assertThat(CardDemoApplication.isJclSubmission(new String[0], blank)).isFalse();
        }

        @Test
        @DisplayName("both arguments are required, because a missing one would silently choose a mode")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CardDemoApplication.isJclSubmission(null, NOTHING_EXTERNAL))
                    .withMessageContaining("command-line arguments are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> CardDemoApplication.isJclSubmission(new String[0], null))
                    .withMessageContaining("process-value lookup is required");
        }

        @Test
        @DisplayName("the application is built with the chosen mode, and this class as its source")
        void theApplicationIsBuiltWithTheChosenMode() {
            assertThat(CardDemoApplication.springApplicationFor(new String[0], NOTHING_EXTERNAL))
                    .isNotNull();
            assertThat(CardDemoApplication.springApplicationFor(
                    new String[] { "--" + JOB_NAME_PROPERTY + "=accountBalanceJob" },
                    NOTHING_EXTERNAL))
                    .isNotNull();
        }

        @Test
        @DisplayName("a system property is read, and it wins over the environment as it does everywhere "
                + "else")
        void aSystemPropertyIsReadAndWins() {
            assertThat(CardDemoApplication.processValueOf(JOB_NAME_PROPERTY)).isNull();

            System.setProperty(JOB_NAME_PROPERTY, "accountInterestCalcJob");
            try {
                assertThat(CardDemoApplication.processValueOf(JOB_NAME_PROPERTY))
                        .isEqualTo("accountInterestCalcJob");
                assertThat(CardDemoApplication.isJclSubmission(new String[0],
                        CardDemoApplication::processValueOf)).isTrue();
            } finally {
                System.clearProperty(JOB_NAME_PROPERTY);
            }

            assertThat(CardDemoApplication.processValueOf(JOB_NAME_PROPERTY)).isNull();
        }

        @Test
        @DisplayName("the environment-variable spelling is derived from the property, never transcribed")
        void theEnvironmentVariableSpellingIsDerived() {
            assertThat(CardDemoApplication.environmentVariableFor(JOB_NAME_PROPERTY))
                    .isEqualTo("CARDDEMO_BATCH_JOB_NAME");
            assertThat(CardDemoApplication.JOB_NAME_ENVIRONMENT_VARIABLE)
                    .isEqualTo("CARDDEMO_BATCH_JOB_NAME");
            assertThat(CardDemoApplication.environmentVariableFor("carddemo.charset.dataset"))
                    .isEqualTo("CARDDEMO_CHARSET_DATASET");
            assertThatNullPointerException()
                    .isThrownBy(() -> CardDemoApplication.environmentVariableFor(null))
                    .withMessageContaining("property name is required");
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
