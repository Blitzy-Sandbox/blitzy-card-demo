package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.AccountReader;
import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link AccountReader}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/copybook not copied; source commit {@code 27d6c6f}):
 * the reader re-platforms the sequential {@code ACCTFILE-FILE} read of batch program
 * {@code app/cbl/CBACT01C.cbl} ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS
 * SEQUENTIAL}, {@code RECORD KEY IS FD-ACCT-ID}) over the {@code ACCOUNT-RECORD} layout of
 * copybook {@code app/cpy/CVACT01Y.cpy}. The COBOL {@code PERFORM UNTIL END-OF-FILE} loop reads
 * each KSDS record exactly once in ascending primary-key order until end-of-file; this test
 * proves the Java equivalent — a sorted, paged {@link org.springframework.batch.item.data.RepositoryItemReader}
 * scan over {@link AccountRepository} — preserves that contract:</p>
 *
 * <ul>
 *   <li><b>Ordered sequential read</b> (AAP section 0.8.1 behavioral parity): rows are fetched
 *       through {@link AccountRepository#findAll(Pageable)} sorted ascending by the primary key
 *       {@code acctId}, mirroring the VSAM KSDS sequential primary-key order.</li>
 *   <li><b>Fixed page size</b> (AAP section 0.8.5 batch pipeline): each page request carries a
 *       page size of {@code 100} — a throughput setting that does not change the set or order of
 *       emitted records.</li>
 *   <li><b>Emit-once until exhaustion</b>: every account row is returned exactly once and the
 *       reader yields {@code null} (end-of-file) once the backing pages are drained, matching the
 *       COBOL {@code END-OF-FILE = 'Y'} termination.</li>
 * </ul>
 *
 * <p>The reader's only collaborator is the {@link AccountRepository}, so these tests mock just
 * that repository (Mockito) and stub {@code findAll(Pageable)} with successive {@link Page}
 * results: no Spring context, no Testcontainers, no LocalStack, and no live AWS dependency.
 * Because {@link AccountReader} configures the inherited {@code RepositoryItemReader} in
 * {@code afterPropertiesSet()} (not in its constructor, which only late-binds the step-scoped
 * repository), each test invokes {@code afterPropertiesSet()} exactly as the Spring container
 * would before {@code open()}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountReader - ascending acctId paging, page size 100, each CVACT01Y row emitted once")
class AccountReaderTest {

    /** Repository whose {@code findAll(Pageable)} backs the reader's paged sequential scan. */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Builds a minimal {@link Account} carrying only the primary key {@code acctId}. The reader
     * passes repository results straight through by reference (the tests assert object identity),
     * and no JPA persistence or bean validation runs in this pure-JVM unit test, so the remaining
     * columns are intentionally left unset to keep the fixture lightweight.
     *
     * @param id the account identifier (primary key)
     * @return an {@link Account} whose {@code acctId} is {@code id}
     */
    private static Account account(Long id) {
        Account account = new Account();
        account.setAcctId(id);
        return account;
    }

    @Test
    @DisplayName("read() pages ascending by acctId at page size 100 and emits each row exactly once")
    void pagesAscendingByAcctId_pageSize100_emitsEachRowOnce() throws Exception {
        Account a1 = account(1L);
        Account a2 = account(2L);

        // Page 0 carries the two seeded rows; page 1 is empty. RepositoryItemReader does not stop
        // on a partial page, so the empty second page is what drives the clean end-of-file null.
        Page<Account> firstPage = new PageImpl<>(List.of(a1, a2));
        Page<Account> emptyPage = new PageImpl<>(List.of());
        when(accountRepository.findAll(any(Pageable.class)))
                .thenReturn(firstPage)
                .thenReturn(emptyPage);

        AccountReader reader = new AccountReader(accountRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        // Each seeded account is returned once, in order, then the reader signals end-of-file.
        assertThat(reader.read()).isSameAs(a1);
        assertThat(reader.read()).isSameAs(a2);
        assertThat(reader.read()).isNull();

        reader.close();

        // Capture every Pageable the reader handed to the repository (page 0 fetch + page 1 fetch).
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(accountRepository, atLeastOnce()).findAll(captor.capture());
        List<Pageable> pageables = captor.getAllValues();

        // First request: page 0, size 100, sorted ascending by the acctId primary key.
        Pageable first = pageables.get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        Sort.Order acctOrder = first.getSort().getOrderFor("acctId");
        assertThat(acctOrder).isNotNull();
        assertThat(acctOrder.getDirection()).isEqualTo(Sort.Direction.ASC);

        // Second request advances to the next page (proving sequential, non-overlapping paging).
        assertThat(pageables.get(1).getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("afterPropertiesSet() configures the reader name as 'accountReader'")
    void configuresReaderName_asAccountReader() throws Exception {
        AccountReader reader = new AccountReader(accountRepository);
        reader.afterPropertiesSet();

        // The name becomes the ExecutionContext key prefix used for restart state; it is set
        // during afterPropertiesSet() and exposed by the inherited public getName().
        assertThat(reader.getName()).isEqualTo("accountReader");
    }
}
