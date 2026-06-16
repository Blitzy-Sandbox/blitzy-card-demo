package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.CustomerReader;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CustomerReader}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/copybook not copied; source commit {@code 27d6c6f}):
 * the reader re-platforms the sequential customer-master scan of batch program
 * {@code app/cbl/CBCUS01C.cbl}. That program opens {@code CUSTFILE} (a VSAM KSDS,
 * {@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL}, {@code RECORD KEY IS
 * FD-CUST-ID}) and loops {@code PERFORM UNTIL END-OF-FILE = 'Y'}, reading each
 * {@code CUSTOMER-RECORD} (copybook {@code app/cpy/CVCUS01Y.cpy}, {@code CUST-ID PIC 9(09)},
 * RECLN 500) exactly once in ascending primary-key order until the {@code '10'} end-of-file
 * status terminates the read. The Java reader reproduces that contract as an ordered, paged
 * repository scan.</p>
 *
 * <p>These tests assert the binding parities of that re-platforming:</p>
 *
 * <ul>
 *   <li><b>Sequential ascending primary-key read</b>: the inherited
 *       {@code RepositoryItemReader} pages through {@link CustomerRepository#findAll(Pageable)}
 *       sorted ascending by {@code custId} (the JPA name of {@code CUST-ID}), reproducing the
 *       VSAM KSDS sequential-by-key access.</li>
 *   <li><b>Fixed page size 100</b>: each fetch requests 100 rows, the configured server-side
 *       page granularity for the scan.</li>
 *   <li><b>Each record emitted once until exhaustion</b>: every row surfaces from {@code read()}
 *       exactly once and, once the backing data is drained, {@code read()} returns {@code null}
 *       — the Java equivalent of the COBOL read-until-EOF loop. The reader detects exhaustion by
 *       fetching one further (empty) page, so a drained scan issues a trailing empty request.</li>
 * </ul>
 *
 * <p>The reader's sole collaborator is the {@link CustomerRepository}, so these tests mock only
 * that repository (Mockito) and feed pages through {@link PageImpl}: no Spring context, no
 * Testcontainers, no LocalStack, and no live AWS dependency. Because the production class
 * configures the inherited {@code RepositoryItemReader} in {@code afterPropertiesSet()} (its
 * constructor only late-binds the step-scoped repository), each test invokes
 * {@code afterPropertiesSet()} exactly as the Spring container would before {@code open()}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerReader - CBCUS01C sequential CUSTDAT scan: ascending custId, page size 100, each row once")
class CustomerReaderTest {

    /** The reader's only collaborator: the paging/sorting customer repository. */
    @Mock
    private CustomerRepository customerRepository;

    /**
     * Builds a {@link Customer} carrying only its identity. The reader emits whatever the
     * repository returns by reference, and these tests assert that identity with
     * {@code isSameAs}, so the customer id is the only field the assertions depend on.
     *
     * @param id the {@code custId} primary key (← {@code CUST-ID PIC 9(09)})
     * @return a customer whose {@code custId} is {@code id}
     */
    private static Customer customer(Long id) {
        Customer c = new Customer();
        c.setCustId(id);
        return c;
    }

    @Test
    @DisplayName("read() pages ascending by custId and emits each row exactly once, then null at end-of-data")
    void pagesAscendingByCustId_pageSize100_emitsEachRowOnce() throws Exception {
        Customer c1 = customer(1L);
        Customer c2 = customer(2L);
        // First fetch returns the data page; the second returns an empty page so the reader
        // detects end-of-data and read() cleanly returns null (mirrors the COBOL EOF loop).
        // Chained single-argument thenReturn calls (not the varargs overload) keep the generic
        // Page stubbing free of the unchecked generic-array-creation warning under -Werror.
        when(customerRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2)))
                .thenReturn(new PageImpl<>(Collections.<Customer>emptyList()));

        CustomerReader reader = new CustomerReader(customerRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isSameAs(c1);
        assertThat(reader.read()).isSameAs(c2);
        assertThat(reader.read()).isNull();

        reader.close();
    }

    @Test
    @DisplayName("first Pageable is page 0, size 100, sorted ascending by custId; a drained scan issues a trailing empty fetch")
    void pageableIsSize100_ascByCustId_startingAtPageZero_andPagesUntilExhaustion() throws Exception {
        when(customerRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(customer(1L), customer(2L))))
                .thenReturn(new PageImpl<>(Collections.<Customer>emptyList()));

        CustomerReader reader = new CustomerReader(customerRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        // Drain the scan to exhaustion so both the data page and the trailing empty
        // (end-of-data detecting) page are fetched.
        reader.read();
        reader.read();
        assertThat(reader.read()).isNull();
        reader.close();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(customerRepository, atLeastOnce()).findAll(captor.capture());

        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        assertThat(first.getSort().getOrderFor("custId")).isNotNull();
        assertThat(first.getSort().getOrderFor("custId").getDirection()).isEqualTo(Sort.Direction.ASC);

        // Exhaustion contract: the drained scan issued exactly one further (empty) fetch, and
        // the page index advanced monotonically (0 -> 1), proving sequential paging to end-of-data.
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(1).getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("afterPropertiesSet() configures the reader name 'customerReader'")
    void configuresReaderName_customerReader() throws Exception {
        CustomerReader reader = new CustomerReader(customerRepository);
        reader.afterPropertiesSet();

        assertThat(reader.getName()).isEqualTo("customerReader");
    }
}
