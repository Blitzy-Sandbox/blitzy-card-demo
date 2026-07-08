package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MenuResponse}, the aggregate response returned by the
 * menu-routing controller that mirrors the legacy CardDemo main/admin menu
 * screens (COBOL {@code COMEN01C}/{@code COADM01C}, source commit
 * {@code 27d6c6f}). The COBOL programs render a fixed list of numbered options
 * gated by user type; the Java DTO carries that option list plus the menu type
 * and title.
 *
 * <p>These tests pin the record's structural contract and, in particular, the
 * {@code options} list normalization performed by the compact canonical
 * constructor: a {@code null} list is coalesced to an empty list and a supplied
 * list is defensively copied into an unmodifiable {@code List.copyOf} (which
 * rejects {@code null} elements). They contribute to the CP2 test-coverage
 * gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("MenuResponse — menu aggregate response DTO")
class MenuResponseTest {

    /** Menu-type discriminator fixture ({@code MAIN} vs {@code ADMIN}). */
    private static final String MENU_TYPE = "MAIN";

    /** Human-readable menu title fixture. */
    private static final String TITLE = "Main Menu";

    private static MenuOption option(int number, String name) {
        return new MenuOption(number, name, "COMEN01C", "U");
    }

    private static List<MenuOption> twoOptions() {
        return List.of(option(1, "Account View"), option(2, "Card List"));
    }

    @Nested
    @DisplayName("Canonical construction and accessors")
    class Construction {

        @Test
        @DisplayName("preserves menuType, title, and the option list in order")
        void preservesFields() {
            MenuResponse response = new MenuResponse(MENU_TYPE, TITLE, twoOptions());

            assertThat(response.menuType()).isEqualTo(MENU_TYPE);
            assertThat(response.title()).isEqualTo(TITLE);
            assertThat(response.options())
                    .hasSize(2)
                    .extracting(MenuOption::optionName)
                    .containsExactly("Account View", "Card List");
        }

        @Test
        @DisplayName("declares exactly the menuType, title, and options components")
        void declaresComponents() {
            // componentNames() lower-cases each record component name; assert the
            // normalized (lower-cased) names accordingly.
            assertThat(componentNames(MenuResponse.class))
                    .containsExactlyInAnyOrder("menutype", "title", "options");
        }
    }

    @Nested
    @DisplayName("options list normalization (null-safe, immutable, defensively copied)")
    class OptionsNormalization {

        @Test
        @DisplayName("a null options list is coalesced to an empty, non-null list")
        void nullOptionsBecomesEmpty() {
            MenuResponse response = new MenuResponse(MENU_TYPE, TITLE, null);

            assertThat(response.options()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("options are defensively copied so later source mutation is ignored")
        void defensiveCopyOnConstruction() {
            List<MenuOption> source = new ArrayList<>(twoOptions());
            MenuResponse response = new MenuResponse(MENU_TYPE, TITLE, source);

            source.clear();

            assertThat(response.options()).hasSize(2);
        }

        @Test
        @DisplayName("the returned options list is unmodifiable")
        void returnedListUnmodifiable() {
            MenuResponse response = new MenuResponse(MENU_TYPE, TITLE, twoOptions());

            assertThatThrownBy(() -> response.options().add(option(9, "Injected")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null element in the options list is rejected (List.copyOf semantics)")
        void nullElementRejected() {
            List<MenuOption> withNull = new ArrayList<>();
            withNull.add(option(1, "Account View"));
            withNull.add(null);

            assertThatNullPointerException()
                    .isThrownBy(() -> new MenuResponse(MENU_TYPE, TITLE, withNull));
        }
    }

    @Nested
    @DisplayName("Serialization and validation")
    class SerializationAndValidation {

        @Test
        @DisplayName("round-trips through JSON with the option list preserved in order")
        void jsonRoundTrip() {
            MenuResponse original = new MenuResponse(MENU_TYPE, TITLE, twoOptions());

            MenuResponse restored = roundTrip(original, MenuResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.options()).containsExactlyElementsOf(original.options());
        }

        @Test
        @DisplayName("a fully populated response has no Bean Validation violations")
        void noViolations() {
            MenuResponse response = new MenuResponse(MENU_TYPE, TITLE, twoOptions());

            assertThat(validate(response)).isEmpty();
        }
    }
}
