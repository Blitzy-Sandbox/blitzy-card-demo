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
        @DisplayName("main is the class's only authored method, and is public static void(String[])")
        void mainIsTheOnlyAuthoredMethod() throws NoSuchMethodException {
            assertThat(authored(CardDemoApplication.class.getDeclaredMethods()))
                    .extracting(Method::getName)
                    .containsExactly("main");

            Method main = CardDemoApplication.class.getDeclaredMethod("main", String[].class);
            assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
            assertThat(main.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("the class declares no field at all, so it holds no static mutable state")
        void noFieldsAreDeclared() {
            assertThat(authored(CardDemoApplication.class.getDeclaredFields())).isEmpty();
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
        @DisplayName("exactly two imports, both named: SpringApplication and SpringBootApplication")
        void importsAreTwoAndNamed() throws IOException {
            List<String> imports = code().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .toList();

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
                    "WebApplicationType",
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
        @DisplayName("static appears exactly once in the code, in the signature of main")
        void staticAppearsOnlyInMain() throws IOException {
            List<String> staticLines = code().lines()
                    .map(String::strip)
                    .filter(line -> line.contains("static"))
                    .toList();

            assertThat(staticLines).containsExactly("public static void main(String[] args) {");
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
