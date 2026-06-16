package com.carddemo.controller;

import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.dto.TransactionDetailResponse;
import com.carddemo.model.dto.TransactionListResponse;
import com.carddemo.service.transaction.TransactionAddService;
import com.carddemo.service.transaction.TransactionDetailService;
import com.carddemo.service.transaction.TransactionListService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction REST controller. Re-platforms the CICS transaction-list (COTRN00C),
 * transaction-detail (COTRN01C), and transaction-add (COTRN02C) programs and
 * their BMS screens COTRN00 / COTRN01 / COTRN02 (reference only, lineage commit
 * 27d6c6f). Stateless JSON endpoints replace the pseudo-conversational COMMAREA
 * flow.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionListService transactionListService;
    private final TransactionDetailService transactionDetailService;
    private final TransactionAddService transactionAddService;

    public TransactionController(TransactionListService transactionListService,
                                TransactionDetailService transactionDetailService,
                                TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    @GetMapping
    public TransactionListResponse listTransactions(@RequestParam(required = false) String transactionId,
                                                    @RequestParam(defaultValue = "0") int page) {
        return transactionListService.listTransactions(page, transactionId);
    }

    @GetMapping("/{transactionId}")
    public TransactionDetailResponse getTransaction(@PathVariable String transactionId) {
        return transactionDetailService.getTransaction(transactionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionAddResponse addTransaction(@Valid @RequestBody TransactionAddRequest request) {
        return transactionAddService.addTransaction(request);
    }
}
