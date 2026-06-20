package com.carddemo.batch.readers;

import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the customer master, replacing COBOL batch reader CBCUS01C
 * (source commit 27d6c6f; REFERENCE ONLY, COBOL is not copied). Streams {@link Customer}
 * rows in ascending CUST-ID order via a sorted, paged scan over {@link CustomerRepository},
 * reproducing the VSAM KSDS sequential primary-key read of CUSTFILE.
 *
 * <p>The repository and paging strategy are configured in {@link #afterPropertiesSet()}
 * (not the constructor) so no partially-constructed instance escapes through an overridable
 * setter. Resource exhaustion yields {@code null} (the COBOL FILE STATUS {@code '10'}
 * end-of-file that stops the step); a hard read failure surfaces as a Spring Data exception
 * that fails the step rather than falling through silently.
 */
@Component
@StepScope
public class CustomerReader extends RepositoryItemReader<Customer> {

    /** Customer master repository (re-platforms the VSAM CUSTDAT KSDS) backing the paged scan. */
    private final CustomerRepository customerRepository;

    /**
     * Creates a step-scoped reader bound to the customer master repository. Only the repository
     * reference is captured here; the paging strategy is applied in {@link #afterPropertiesSet()}
     * so no overridable setter is invoked while {@code this} is still under construction.
     *
     * @param customerRepository the customer repository supplying {@code findAll(Pageable)}
     */
    public CustomerReader(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Configures the deterministic ascending-by-{@code custId} paged scan that mirrors the VSAM
     * KSDS primary-key read order, then delegates to the superclass for its own validation and
     * initialization. Invoked by the Spring {@code InitializingBean} lifecycle for the
     * step-scoped instance before the step opens the reader.
     *
     * @throws Exception if superclass initialization fails (e.g. a required property is unset)
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(customerRepository);
        setMethodName("findAll");
        setSort(Map.of("custId", Sort.Direction.ASC));
        setPageSize(100);
        setName("customerReader");
        super.afterPropertiesSet();
    }
}
