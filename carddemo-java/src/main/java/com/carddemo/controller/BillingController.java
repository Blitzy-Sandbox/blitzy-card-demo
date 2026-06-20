package com.carddemo.controller;

import com.carddemo.model.dto.BillPaymentRequest;
import com.carddemo.model.dto.BillPaymentResponse;
import com.carddemo.service.billing.BillPaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing REST controller. Re-platforms the CICS bill-payment program COBIL00C
 * and its BMS screen COBIL00 (reference only, lineage commit 27d6c6f). Stateless
 * JSON endpoint replaces the pseudo-conversational COMMAREA flow.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillPaymentService billPaymentService;

    public BillingController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    @PostMapping("/pay")
    public BillPaymentResponse pay(@Valid @RequestBody BillPaymentRequest request) {
        return billPaymentService.pay(request);
    }
}
