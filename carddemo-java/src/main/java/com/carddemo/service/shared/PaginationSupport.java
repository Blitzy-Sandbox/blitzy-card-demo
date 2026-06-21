package com.carddemo.service.shared;

/**
 * Shared pagination guards for the browse/list services (card, transaction, and user lists).
 *
 * <p>Spring Data computes a page's SQL offset as {@code pageIndex * pageSize} and exposes it as a
 * {@code long} ({@link org.springframework.data.domain.Pageable#getOffset()}). When that offset
 * exceeds {@link Integer#MAX_VALUE}, the JPA layer cannot narrow it to the {@code int} that
 * {@code jakarta.persistence.Query#setFirstResult(int)} requires and raises
 * {@code org.springframework.dao.InvalidDataAccessApiUsageException} ("Page offset exceeds
 * Integer.MAX_VALUE"). Left unhandled, an absurd-but-valid integer page number (for example
 * {@code ?page=400000000}) therefore surfaces to the client as an HTTP 500 rather than the graceful
 * empty page that every other out-of-range page returns.</p>
 *
 * <p>{@link #clampPageToMaxOffset(int, int)} removes that sharp edge by capping the requested
 * zero-based page index at the largest value whose offset still fits in an {@code int}. Because the
 * backing tables hold at most a few hundred rows, any page at or beyond that cap is, by definition,
 * far past the end of the data, so the clamp yields an empty page (HTTP 200) — identical to the
 * behavior of a merely "too large" page that stays within the safe offset range, and consistent
 * with the existing lower-bound clamp that maps negative pages to the first page. The page size is
 * never altered (the list endpoints keep their fixed server-side page sizes), so pagination stays
 * fully bounded.</p>
 *
 * <p>This is a stateless utility with only static helpers; it is intentionally not a Spring bean.
 * No COBOL equivalent (the mainframe CICS browse tracked a small page counter in the COMMAREA and
 * could not express this offset overflow); the source workload is referenced by commit SHA
 * {@code 27d6c6f} for traceability.</p>
 */
public final class PaginationSupport {

    private PaginationSupport() {
        // Utility class: no instances.
    }

    /**
     * Clamps a zero-based page index so the resulting Spring Data offset
     * ({@code pageIndex * pageSize}) cannot exceed {@link Integer#MAX_VALUE}, preventing the
     * offset-overflow {@code InvalidDataAccessApiUsageException} (HTTP 500) and yielding a graceful
     * empty page instead.
     *
     * <p>The returned index is {@code min(max(requestedPage, 0), Integer.MAX_VALUE / pageSize)}. The
     * lower bound (zero) defends against a negative index even though the callers already clamp
     * negatives; the upper bound is the largest page whose offset still fits in an {@code int}:
     * {@code (Integer.MAX_VALUE / pageSize) * pageSize <= Integer.MAX_VALUE} by integer-division
     * truncation, so the offset is always accepted by {@code setFirstResult(int)}. For the page
     * sizes used by this application the cap is comfortably below the threshold (size 7 &rarr; cap
     * 306,783,378, offset 2,147,483,646; size 10 &rarr; cap 214,748,364, offset 2,147,483,640).</p>
     *
     * @param requestedPage the requested zero-based page index (a negative value is treated as the
     *                      first page)
     * @param pageSize      the fixed number of rows per page; must be at least one
     * @return a zero-based page index whose offset is guaranteed not to exceed
     *         {@link Integer#MAX_VALUE}
     * @throws IllegalArgumentException if {@code pageSize} is less than one
     */
    public static int clampPageToMaxOffset(int requestedPage, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1 but was " + pageSize);
        }
        int maxSafePage = Integer.MAX_VALUE / pageSize;
        int nonNegativePage = Math.max(requestedPage, 0);
        return Math.min(nonNegativePage, maxSafePage);
    }
}
