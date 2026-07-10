package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserListResponse}, the aggregate response for the List
 * Users transaction (CU00) implemented by COBOL {@code COUSR00C} (source commit
 * {@code 27d6c6f}). The DTO carries an optional user-id filter plus a
 * {@link PageResponse} of {@link UserListItem} rows.
 *
 * <p>These tests pin the structural contract, null-filter tolerance, and JSON
 * round-trip fidelity of the paginated user list. They contribute to the CP2
 * test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("UserListResponse — user-list aggregate response DTO")
class UserListResponseTest {

    /** User-id filter fixture ({@code SEC-USR-ID} PIC X(08)). */
    private static final String USER_ID_FILTER = "USER0001";

    private static PageResponse<UserListItem> onePage() {
        List<UserListItem> rows = List.of(
                new UserListItem("USER0001", "Alice", "Adams", "U"),
                new UserListItem("ADMIN001", "Bob", "Brown", "A"));
        return PageResponse.of(rows, 1, 10, 2);
    }

    @Nested
    @DisplayName("Construction and accessors")
    class Construction {

        @Test
        @DisplayName("carries the user-id filter and page verbatim")
        void carriesFields() {
            PageResponse<UserListItem> page = onePage();

            UserListResponse response = new UserListResponse(USER_ID_FILTER, page);

            assertThat(response.userIdFilter()).isEqualTo(USER_ID_FILTER);
            assertThat(response.page()).isSameAs(page);
        }

        @Test
        @DisplayName("null filter is permitted (unfiltered listing) and preserves the user-type flags")
        void nullFilterAllowed() {
            UserListResponse response = new UserListResponse(null, onePage());

            assertThat(response.userIdFilter()).isNull();
            assertThat(response.page().content())
                    .extracting(UserListItem::userType)
                    .containsExactly("U", "A");
        }

        @Test
        @DisplayName("declares exactly userIdFilter and page")
        void declaresComponents() {
            // componentNames() lower-cases each record component name; assert the
            // normalized (lower-cased) names accordingly.
            assertThat(componentNames(UserListResponse.class))
                    .containsExactlyInAnyOrder("useridfilter", "page");
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("round-trips through JSON preserving the ordered user rows")
        void jsonRoundTrip() {
            UserListResponse original = new UserListResponse(USER_ID_FILTER, onePage());

            UserListResponse restored = roundTrip(original, UserListResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.page().content())
                    .hasSize(2)
                    .extracting(UserListItem::userId)
                    .containsExactly("USER0001", "ADMIN001");
        }
    }
}
