package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.common.ConversationStateSeal;

/**
 * The one {@link ConversationStateSeal} the tests build, and the one secret they build it from.
 *
 * <h2>Why the secret lives here rather than in each suite</h2>
 * Eleven suites construct {@code AccountUpdateController} or {@code CardUpdateController} directly, and each
 * needs a seal. A literal per suite would be eleven literals to keep at or above
 * {@link ConversationStateSeal#MINIMUM_SECRET_LENGTH} characters, and the first one written a character
 * short would fail in construction with a message about configuration - which reads, in a controller suite,
 * like a defect in the controller. Naming it once means the length constraint is satisfied in one place and
 * a suite that wants a seal asks for one.
 *
 * <p>It also makes the cross-route property testable. A token is bound to its purpose and its record key,
 * so proving that an account token is refused by the card route requires <em>both</em> routes to hold the
 * same key - a different secret per suite would make every token refused everywhere and the refusal would
 * prove nothing.
 *
 * <h2>This value is a test value and nothing else</h2>
 * It is not a default, not a fallback and not a fixture the application can reach:
 * {@code ConversationStateSeal} has one constructor, it takes the secret, and
 * {@code application.yml} binds it from {@link ConversationStateSeal#SECRET_ENVIRONMENT_VARIABLE} with no
 * default, so a deployment that names none is refused at startup. The pinned value in
 * {@code application-test.yml} and the one here serve the same purpose and are deliberately different, so
 * neither can be mistaken for the other's authority.
 */
public final class ConversationStateSealFixture {

    /**
     * The secret every test seal is derived from - {@value} - comfortably past
     * {@link ConversationStateSeal#MINIMUM_SECRET_LENGTH} and obviously not a production value.
     */
    public static final String SECRET = "unit-test-conversation-state-seal-secret-0001";

    private ConversationStateSealFixture() {
        throw new AssertionError("A fixture holder is not instantiated");
    }

    /**
     * Builds a seal on {@link #SECRET}.
     *
     * <p>A fresh instance per call rather than a shared constant, because a seal holds a
     * {@link java.security.SecureRandom} and a test that wanted to prove two instances interoperate needs
     * two instances. Instances built from the same secret derive the same key, so a token sealed by one is
     * accepted by another - which is exactly the horizontally-scaled deployment the design has to support.
     *
     * @return a seal; never {@code null}
     */
    public static ConversationStateSeal seal() {
        return new ConversationStateSeal(SECRET);
    }
}
