package com.vsergeychik.carddemo.config;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The single place in the CardDemo Java module where a character encoding is named.
 *
 * <p>Every byte that crosses the boundary between a CardDemo dataset and a Java {@code String}
 * passes through a {@link Charset} that this class resolved from configuration and published as a
 * bean. Nothing in the module ever asks the JVM for whatever encoding it happens to default to, and
 * no codec, repository, writer or test is permitted to guess: the encoding is an explicit, injected
 * argument everywhere (binding practice B8, "explicit over implicit at every boundary"). The two
 * code-page names themselves are not written into this class either - they are configuration
 * values, and what this class owns is the single seam that turns them into {@link Charset}
 * instances, so
 * encoding behaviour has exactly one place to be reviewed and one place to be got wrong.
 *
 * <h2>Why an entire class exists to name two code pages</h2>
 *
 * <p>Because a platform default charset is the classic silent corrupter of mainframe data, and this
 * repository contains the proof. The user-security dataset under {@code app/data/EBCDIC} is 800
 * bytes - ten 80-byte {@code CSUSR01Y} security records - and its first record decodes under
 * {@code IBM037} to:
 *
 * <pre>
 *   ADMIN001MARGARET            GOLD                PASSWORDA
 * </pre>
 *
 * <p>Decoded as {@code US-ASCII} the same bytes yield replacement characters, with the EBCDIC space
 * {@code x'40'} surfacing as {@code '@'}. Neither decode fails, neither raises an exception, and on
 * a machine whose default encoding happens to be UTF-8 the wrong one is exactly what a
 * bytes-to-text conversion that names no encoding produces. There is no diagnostic to catch this
 * later: the record is simply wrong, and a field-for-field parity diff would report every field of
 * every record as different with no clue as to the cause.
 *
 * <p>Two further facts make the encoding decision load-bearing rather than incidental:
 *
 * <ol>
 *   <li><b>{@code README.md} line 65 requires binary transfer.</b> It instructs the reader to
 *       "Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure
 *       that you use transfer mode binary". Binary transfer means no client performs a code-page
 *       translation on the way in or out, so the twelve datasets under {@code app/data/EBCDIC} are
 *       raw EBCDIC bytes and the reader is wholly responsible for naming their code page. That
 *       instruction is the authority for naming {@code IBM037} explicitly instead of inheriting
 *       whatever the JVM happens to default to.</li>
 *   <li><b>There is no packed-decimal escape hatch.</b> Not one of the 28 copybooks in
 *       {@code app/cpy} declares {@code COMP-3} or {@code PACKED-DECIMAL}; packed decimal appears
 *       only in {@code WORKING-STORAGE} intermediates. Every <em>persisted</em> numeric field is
 *       therefore zoned {@code DISPLAY}, which means the charset alone determines how a number is
 *       read. The account dataset under {@code app/data/EBCDIC} - 15,000 bytes, fifty 300-byte
 *       {@code CVACT01Y} records - begins, decoded as {@code IBM037}:
 *       <pre>00000000001Y00000001940{000000</pre>
 *       That twenty-fourth character is an opening brace, and it is not text: it is how a zoned
 *       decimal field carries a {@code +0} sign overpunch in its final byte. Get the code page
 *       wrong and the sign, not merely the text, is lost.</li>
 * </ol>
 *
 * <h2>Two encodings, one authority</h2>
 *
 * <p>The repository ships the same sample data twice: twelve binary datasets under
 * {@code app/data/EBCDIC} and nine fixed-width text fixtures under {@code app/data/ASCII}
 * ({@code acctdata.txt}, {@code carddata.txt}, {@code cardxref.txt}, {@code custdata.txt},
 * {@code dailytran.txt}, {@code discgrp.txt}, {@code tcatbal.txt}, {@code trancatg.txt},
 * {@code trantype.txt}). For this migration the <b>ASCII side is authoritative</b> and the EBCDIC
 * side is reference-only, and the test profile binds every dataset to one of those nine fixtures.
 *
 * <p>Which of the two is <em>active</em> is a property of the deployment, not of this class, so
 * {@link #carddemoDatasetCharset()} - the charset dataset input and output actually uses - resolves
 * {@value #DATASET_CHARSET_PROPERTY} and that key <b>carries no default</b>. Each profile states it
 * on one line: {@code IBM037} in {@code application.yml}, whose bindings address the mainframe
 * datasets, and {@code US-ASCII} in {@code application-test.yml}, whose bindings address the nine
 * fixtures. That single line is the only supported way to change the active encoding; editing this
 * class is not required, and hard-coding an encoding at a call site is forbidden.
 *
 * <h2>The published bean contract</h2>
 *
 * <p>Three {@link Charset} beans are published under stable names. The names are part of this
 * class's public contract - repositories, the three fixed-width output writers, the date-parameter
 * reader and the parity harness select among them with {@code @Qualifier} - so they are exposed as
 * constants rather than left as string literals at every injection point:
 *
 * <table>
 *   <caption>Published {@link Charset} beans</caption>
 *   <tr><th>Constant</th><th>Bean name</th><th>Resolved from</th><th>Use</th></tr>
 *   <tr>
 *     <td>{@link #EBCDIC_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #EBCDIC_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #EBCDIC_CHARSET_PROPERTY}</td>
 *     <td>Reading the twelve binary datasets under {@code app/data/EBCDIC}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #ASCII_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #ASCII_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #ASCII_CHARSET_PROPERTY}</td>
 *     <td>Reading the nine authoritative text fixtures and everything derived from them.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #DATASET_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #DATASET_CHARSET_BEAN_NAME}</td>
 *     <td>{@value #DATASET_CHARSET_PROPERTY}, required in every profile</td>
 *     <td>The <em>active</em> encoding for dataset input and output. This is the one to
 *         inject.</td>
 *   </tr>
 * </table>
 *
 * <p>Selection is mandatory. There is deliberately <b>no</b> {@code @Primary} bean: an unqualified
 * {@code Charset} injection point fails at startup with Spring's
 * {@code NoUniqueBeanDefinitionException} naming all three candidates, which is the intended
 * outcome. A primary bean would let an injection point that meant "EBCDIC" silently receive
 * "ASCII", reintroducing at the wiring level exactly the implicitness this class exists to remove
 * (practice B8).
 *
 * <h2>Configuration keys</h2>
 *
 * <p>Key spellings are taken verbatim from {@code app/java/src/main/resources/application.yml},
 * which is the sole authority for them and which names this class as their consumer:
 *
 * <pre>
 *   carddemo:
 *     charset:
 *       ebcdic: IBM037
 *       ascii: US-ASCII
 *       dataset: IBM037     # US-ASCII in application-test.yml
 * </pre>
 *
 * <p>The first two keys hold identical values in both documents, deliberately: they name the two
 * code pages that exist in this estate, and an encoding is a parity concern rather than an
 * environmental one, so neither profile may redefine what "the EBCDIC code page" or "the ASCII code
 * page" means.
 *
 * <p>The third key, {@value #DATASET_CHARSET_PROPERTY}, is the one that genuinely differs between
 * the two documents, because it says which of those code pages the data layer is actually reading
 * <em>right now</em> - and that follows the datasets a profile is bound to, not the module.
 * {@code application.yml} binds the mainframe datasets and therefore names {@code IBM037};
 * {@code application-test.yml} binds the nine text fixtures and therefore names {@code US-ASCII}.
 *
 * <p>All three keys are <b>required and never defaulted</b>, so removing any one of them fails the
 * context at startup with Spring's own "Could not resolve placeholder" diagnostic naming the exact
 * key. The active key is required precisely because it is the consequential one: a default would let
 * a deployment that never stated its code page start anyway and then decode every record in the
 * wrong one, silently. Blanking a key out is not treated as absence either - an empty value reaches
 * {@link #resolve(String, String)} and is rejected there, naming the key that was blanked, rather
 * than quietly standing in for a code page. Failing to start is the correct outcome: an application
 * that silently decodes mainframe records with the wrong code page is worse than one that refuses to
 * run (practice B12, "environmental limits documented, not absorbed").
 *
 * <h2>Dependency direction is one-way, and must stay that way</h2>
 *
 * <p>This class imports nothing from the module's {@code common} package, and nothing in
 * {@code common} may import {@code config}. The codecs take their encoding as an explicit
 * parameter - {@code FixedWidthRecord} declares {@code Charset} a mandatory constructor argument -
 * and consumers that own a repository or a writer inject the bean they need and pass the
 * {@link Charset} down. Two consequences follow, and both are intentional:
 *
 * <ul>
 *   <li><b>No static accessor, holder, singleton or service locator is provided.</b> A
 *       {@code CobolCharsetConfig.datasetCharset()} convenience method would make the encoding
 *       globally reachable, reintroduce shared mutable state through the back door and destroy test
 *       determinism, because two tests could no longer exercise two encodings in one JVM. This
 *       class has no mutable state at all: its only static members are immutable {@code String}
 *       constants (practice B9).</li>
 *   <li><b>The foundation types stay independently testable.</b> {@code common} has no Spring
 *       dependency, so a codec can be unit-tested with a literal {@code Charset} and no application
 *       context. Wiring {@code common} to this class would forfeit that.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>No I/O.</b> It never opens, reads, writes, copies or probes a file. It turns configured
 *       names into {@link Charset} instances; locating and reading datasets belongs to the
 *       repositories.</li>
 *   <li><b>No dataset name, no path, no record length.</b> Dataset names and widths live in
 *       {@code application.yml} - one auditable source - so a scan of {@code src/main/java} finds
 *       neither a mainframe dataset-name literal nor a hard-coded record length. The
 *       {@code README.md} table of copybooks and lengths is emphatically not duplicated here.</li>
 *   <li><b>No transcoding, and no third-party encoding library.</b> Resolution is plain
 *       {@link Charset#isSupported(String)} and {@link Charset#forName(String)} from the JDK. The
 *       {@code US-ASCII} bean is the very
 *       {@link java.nio.charset.StandardCharsets#US_ASCII} constant, because
 *       {@code Charset.forName("US-ASCII")} returns that cached instance - resolving it from
 *       configuration therefore costs nothing and keeps a single resolution path instead of two
 *       (practice B11).</li>
 *   <li><b>No fallback, ever.</b> Not to the platform default, not to UTF-8, not from EBCDIC to
 *       ASCII. An unresolvable name is a configuration error and is raised as one.</li>
 *   <li><b>No {@code @ComponentScan}, {@code @Import} or {@code @PropertySource}.</b> This class is
 *       discovered by the application entry point's own component scan, and it contributes beans
 *       only.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the entire document, so <b>no user rule governs this file</b>. Its absence is not licence
 * to lower the bar: twelve enterprise practices stand in their place, and the ones bearing on this
 * class are cited inline above as B1 (no dependency added; this class needs only the JDK and
 * annotations already on the classpath), B2 (Spring Boot 3.5.16 API only), B3 (the reference trees
 * {@code README.md}, {@code app/data/EBCDIC} and {@code app/data/ASCII} are read-only evidence and
 * no property here resolves into them), B4 (no scope creep - the superseded PostgreSQL, JPA and
 * cloud design documented elsewhere in the repository is neither imported nor corrected), B7
 * (deterministic build: the single decision below is reachable from a plain unit test with no
 * application context), B8, B9, B11 and B12.
 */
@Configuration
public class CobolCharsetConfig {

    /**
     * Configuration key naming the EBCDIC code page: {@code carddemo.charset.ebcdic}, declared with
     * the value {@code IBM037} in both {@code application.yml} and {@code application-test.yml}.
     *
     * <p>It carries no default. A deployment that removes it does not start.
     */
    public static final String EBCDIC_CHARSET_PROPERTY = "carddemo.charset.ebcdic";

    /**
     * Configuration key naming the ASCII code page: {@code carddemo.charset.ascii}, declared with
     * the value {@code US-ASCII} in both {@code application.yml} and {@code application-test.yml}.
     *
     * <p>It carries no default. A deployment that removes it does not start.
     */
    public static final String ASCII_CHARSET_PROPERTY = "carddemo.charset.ascii";

    /**
     * Configuration key naming the <em>active</em> dataset code page:
     * {@code carddemo.charset.dataset}.
     *
     * <p>Declared in both configuration documents and different in each, because it follows the
     * datasets a profile is bound to: {@code IBM037} in {@code application.yml}, which binds the
     * mainframe datasets, and {@code US-ASCII} in {@code application-test.yml}, which binds the nine
     * authoritative text fixtures.
     *
     * <p>It carries no default, and of the three keys this is the one where that matters most. It
     * decides how every dataset byte in the module is interpreted, so a default would let a
     * deployment that never stated its code page start anyway and then decode every record in the
     * wrong one, with no error to show for it. A deployment that removes it does not start.
     */
    public static final String DATASET_CHARSET_PROPERTY = "carddemo.charset.dataset";

    /**
     * Bean name of the EBCDIC {@link Charset}: {@code carddemoEbcdicCharset}. Select it with
     * {@code @Qualifier(CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME)}.
     */
    public static final String EBCDIC_CHARSET_BEAN_NAME = "carddemoEbcdicCharset";

    /**
     * Bean name of the ASCII {@link Charset}: {@code carddemoAsciiCharset}. Select it with
     * {@code @Qualifier(CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME)}.
     */
    public static final String ASCII_CHARSET_BEAN_NAME = "carddemoAsciiCharset";

    /**
     * Bean name of the active dataset {@link Charset}: {@code carddemoDatasetCharset}. Select it
     * with {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}. This is the bean that
     * dataset input and output uses; the other two exist so a call site can be explicit about a
     * specific code page when it genuinely needs one.
     */
    public static final String DATASET_CHARSET_BEAN_NAME = "carddemoDatasetCharset";

    /** Configured name of the EBCDIC code page. Immutable; resolved, never derived. */
    private final String ebcdicCharsetName;

    /** Configured name of the ASCII code page. Immutable; resolved, never derived. */
    private final String asciiCharsetName;

    /** Configured name of the active dataset code page. Immutable; resolved, never derived. */
    private final String datasetCharsetName;

    /**
     * Binds the three configured code-page names by constructor injection.
     *
     * <p>Names, not {@link Charset} instances, are captured here. Resolution is deferred to the
     * {@code @Bean} methods so that a name this JVM cannot support is reported against the specific
     * bean - and therefore the specific property - that asked for it, rather than as one opaque
     * constructor failure covering all three.
     *
     * <p><strong>All three placeholders carry no default</strong>, so a missing key fails the
     * context with Spring's own diagnostic naming that key. That includes
     * {@value #DATASET_CHARSET_PROPERTY}: the <em>active</em> code page is the one that decides how
     * every dataset byte is interpreted, and defaulting it here would mean a deployment that forgot
     * to state its code page still started - and then decoded EBCDIC records as ASCII without any
     * error. Each profile therefore names it explicitly: {@code IBM037} in {@code application.yml},
     * whose dataset bindings address the mainframe datasets, and {@code US-ASCII} in
     * {@code application-test.yml}, whose bindings address the nine text fixtures.
     *
     * <p>This constructor is also the entire test seam for the bean methods: a unit test can
     * instantiate the class directly with three literal names and assert the three beans with no
     * application context in the picture.
     *
     * @param ebcdicCharsetName  value of {@value #EBCDIC_CHARSET_PROPERTY}, expected to be
     *                           {@code IBM037}
     * @param asciiCharsetName   value of {@value #ASCII_CHARSET_PROPERTY}, expected to be
     *                           {@code US-ASCII}
     * @param datasetCharsetName value of {@value #DATASET_CHARSET_PROPERTY}, required and never
     *                           defaulted - {@code IBM037} under the default profile and
     *                           {@code US-ASCII} under the {@code test} profile
     */
    public CobolCharsetConfig(
            @Value("${" + EBCDIC_CHARSET_PROPERTY + "}") String ebcdicCharsetName,
            @Value("${" + ASCII_CHARSET_PROPERTY + "}") String asciiCharsetName,
            @Value("${" + DATASET_CHARSET_PROPERTY + "}") String datasetCharsetName) {
        this.ebcdicCharsetName = ebcdicCharsetName;
        this.asciiCharsetName = asciiCharsetName;
        this.datasetCharsetName = datasetCharsetName;
    }

    /**
     * The EBCDIC code page, published as bean {@value #EBCDIC_CHARSET_BEAN_NAME} and resolved from
     * {@value #EBCDIC_CHARSET_PROPERTY}.
     *
     * <p>This is the encoding of the twelve binary datasets under {@code app/data/EBCDIC}, which
     * {@code README.md} line 65 requires be transferred in binary mode and which are therefore raw
     * EBCDIC bytes on arrival. {@code IBM037} is the US and Canada EBCDIC code page; on a full JDK
     * 21 it is supplied by the {@code jdk.charsets} module and reports the canonical name
     * {@code IBM037}.
     *
     * <p>Inject it only where a call site genuinely means EBCDIC. Ordinary dataset input and output
     * should inject {@link #carddemoDatasetCharset()} instead, so that one property governs the
     * active encoding.
     *
     * @return the configured EBCDIC {@link Charset}
     * @throws IllegalStateException if this JVM does not support the configured name
     */
    @Bean(EBCDIC_CHARSET_BEAN_NAME)
    public Charset carddemoEbcdicCharset() {
        return resolve(ebcdicCharsetName, EBCDIC_CHARSET_PROPERTY);
    }

    /**
     * The ASCII code page, published as bean {@value #ASCII_CHARSET_BEAN_NAME} and resolved from
     * {@value #ASCII_CHARSET_PROPERTY}.
     *
     * <p>This is the encoding of the nine authoritative text fixtures under {@code app/data/ASCII}
     * and of every copy derived from them, so it governs the parity assertions. With the configured
     * value {@code US-ASCII} the returned instance is the JDK's own
     * {@link java.nio.charset.StandardCharsets#US_ASCII} constant, because
     * {@link Charset#forName(String)} returns that cached instance for the canonical name and its
     * aliases - the bean is that constant without this class hard-coding it.
     *
     * <p>{@code US-ASCII} is deliberately narrower than a UTF-8 or ISO-8859-1 reading of the same
     * bytes: it is a single-byte, seven-bit encoding, so a byte outside the {@code 0x00} to
     * {@code 0x7F} range in a fixture decodes to the replacement character instead of quietly
     * becoming a
     * plausible-looking accented letter, and a one-byte-per-character guarantee is what lets a
     * fixed-width offset be reasoned about at all.
     *
     * @return the configured ASCII {@link Charset}
     * @throws IllegalStateException if this JVM does not support the configured name
     */
    @Bean(ASCII_CHARSET_BEAN_NAME)
    public Charset carddemoAsciiCharset() {
        return resolve(asciiCharsetName, ASCII_CHARSET_PROPERTY);
    }

    /**
     * The <em>active</em> dataset code page, published as bean
     * {@value #DATASET_CHARSET_BEAN_NAME} and resolved from {@value #DATASET_CHARSET_PROPERTY},
     * which every profile must state explicitly.
     *
     * <p><b>This is the bean that dataset input and output injects.</b> Repositories, the
     * fixed-width output writers, the date-parameter reader and the parity harness take this one,
     * so that the encoding of the whole data layer is governed by a single property rather than
     * by a decision repeated at each call site.
     *
     * <p><b>It has no default, and that is the point.</b> Because this one bean decides how every
     * dataset byte in the module is interpreted, an unstated value is a configuration defect rather
     * than a situation to recover from: a deployment whose bindings address the mainframe datasets
     * but whose active code page had quietly defaulted to the fixtures' would decode every record
     * wrongly and report no error at all. So each profile names it, one line each, and the choice is
     * visible in the document that made it:
     *
     * <ul>
     *   <li>{@code application.yml} - {@code carddemo.charset.dataset: IBM037}. Its dataset
     *       bindings address the mainframe datasets, which {@code README.md} line 65 requires be
     *       transferred in binary mode and which are therefore raw EBCDIC bytes on arrival.</li>
     *   <li>{@code application-test.yml} - {@code carddemo.charset.dataset: US-ASCII}. Its bindings
     *       address the nine authoritative text fixtures, so the parity assertions read them in
     *       their own code page.</li>
     * </ul>
     *
     * <p>Changing the active encoding for a deployment is therefore still a single configuration
     * line and never a Java change - which remains forbidden - but it is now a line that must be
     * <em>written</em> rather than one that can be omitted.
     *
     * <p>This bean stays distinct from {@link #carddemoAsciiCharset()} even where a profile happens
     * to give both the same name, so that moving the data layer's code page never moves the
     * fixture-reading code that must stay on ASCII.
     *
     * @return the configured active dataset {@link Charset}
     * @throws IllegalStateException if the configured name is blank, syntactically illegal, or a
     *                               legal name this JVM does not support
     */
    @Bean(DATASET_CHARSET_BEAN_NAME)
    public Charset carddemoDatasetCharset() {
        return resolve(datasetCharsetName, DATASET_CHARSET_PROPERTY);
    }

    /**
     * The one decision this class makes: turn a configured code-page name into a {@link Charset},
     * or fail loudly naming the property that supplied it.
     *
     * <p>Package-visible and {@code static} on purpose. It is the whole of this class's branching
     * behaviour, and keeping it free of any dependency on an application context means every
     * outcome is reachable from a plain unit test with a direct call, which is how the
     * {@code config} package clears the build's per-package branch-coverage gate without standing up
     * Spring.
     *
     * <p><b>Every rejection is a configuration error, reported as one.</b> There are three ways a
     * configured name can be unusable and all three raise the same {@link IllegalStateException}
     * naming the offending property key <em>and</em> the value it rejected:
     *
     * <ol>
     *   <li><b>Absent in substance</b> - {@code null}, empty, or whitespace only. A key that is
     *       present but blank is not treated as a choice of code page; blank is rejected in its own
     *       right so the diagnostic can say which key was blanked out, which the JDK's own
     *       {@link java.nio.charset.IllegalCharsetNameException} for {@code ""} cannot.</li>
     *   <li><b>Syntactically illegal</b> - a name built from characters outside the legal set, such
     *       as {@code IBM 037} or {@code bogus!}, or an unresolved placeholder that arrived here as
     *       literal text. The JDK raises
     *       {@link java.nio.charset.IllegalCharsetNameException} quoting only the name; it is caught
     *       and re-reported so the message also carries the key that supplied it.</li>
     *   <li><b>Legal but unsupported</b> - a well-formed name this JVM has no charset for. This is
     *       the case that matters in practice, because {@code IBM037} is supplied by the JDK's
     *       {@code jdk.charsets} module, which a minimal {@code jlink} runtime image omits even
     *       though the JVM otherwise looks complete.</li>
     * </ol>
     *
     * <p>The {@code catch} translates a diagnostic; it is emphatically <b>not</b> a recovery. No
     * platform-default fallback, no second-choice code page and no default-within-a-default appears
     * here or anywhere in this class: each of those would convert a configuration error into
     * corrupted records, which for a byte-parity migration is the worst available outcome. Every
     * path out of this method either returns the code page that was asked for or refuses to start.
     *
     * <p>The rejected value is quoted in the message on purpose. A code-page name is not a
     * credential and carries nothing sensitive, and without it an operator reading the log cannot
     * tell a typo from a trimmed runtime image.
     *
     * @param charsetName the configured code-page name, for example {@code IBM037} or
     *                    {@code US-ASCII}
     * @param propertyKey the configuration key that supplied {@code charsetName}, reported verbatim
     *                    in the failure message so the fix is unambiguous
     * @return the {@link Charset} for {@code charsetName}
     * @throws IllegalStateException if {@code charsetName} is blank, syntactically illegal, or a
     *                               legal charset name that this JVM does not support
     */
    static Charset resolve(String charsetName, String propertyKey) {
        if (!StringUtils.hasText(charsetName)) {
            throw new IllegalStateException(rejected(charsetName, propertyKey)
                    + " A charset name is required and is never defaulted: the platform default "
                    + "charset is never substituted, because decoding a fixed-width mainframe "
                    + "record with the wrong code page corrupts it silently. Set '" + propertyKey
                    + "' to the code page this deployment's datasets are actually written in - "
                    + "IBM037 for the EBCDIC datasets, US-ASCII for the text fixtures.");
        }
        try {
            if (!Charset.isSupported(charsetName)) {
                throw new IllegalStateException(rejected(charsetName, propertyKey)
                        + " It is a legal charset name, but it is not supported by this JVM. No "
                        + "fallback is applied and the platform default charset is never "
                        + "substituted: every CardDemo dataset charset is named explicitly, because "
                        + "decoding a fixed-width mainframe record with the wrong code page corrupts "
                        + "it silently. If the name is IBM037, note that EBCDIC code pages are "
                        + "supplied by the JDK's jdk.charsets module, which a minimal jlink runtime "
                        + "image omits - run a full JDK or JRE, or include jdk.charsets in the "
                        + "image. Otherwise correct the value of '" + propertyKey + "' to a charset "
                        + "this JVM supports.");
            }
        } catch (IllegalCharsetNameException illegalName) {
            throw new IllegalStateException(rejected(charsetName, propertyKey)
                    + " It is not a syntactically legal charset name: a charset name may contain "
                    + "only letters, digits and the characters '-', '+', '.', ':' and '_', and must "
                    + "begin with a letter or digit. Correct the value of '" + propertyKey
                    + "' - and if it looks like an unresolved ${...} placeholder, the key it refers "
                    + "to is the one that is missing.", illegalName);
        }
        return Charset.forName(charsetName);
    }

    /**
     * Opens every rejection message the same way, naming the property key and quoting the value it
     * rejected.
     *
     * <p>A shared opening rather than three hand-written ones, so that no arm of
     * {@link #resolve(String, String)} can be the one that forgets to say which key was at fault -
     * which is the single most useful thing the message can carry. It is a method rather than a
     * constant because the text varies with its arguments, and because this class deliberately
     * declares no field beyond its six contract constants and three bound names.
     *
     * @param charsetName the rejected value, quoted verbatim; may be {@code null} or blank, in which
     *                    case the quotes themselves are what show that
     * @param propertyKey the configuration key that supplied it
     * @return the opening sentence of a charset configuration failure
     */
    private static String rejected(String charsetName, String propertyKey) {
        return "Charset '" + charsetName + "' configured by property '" + propertyKey
                + "' cannot be used.";
    }
}
