package com.vehiclerental.mak.customers.dto.response;

import com.vehiclerental.mak.customers.dto.request.QuoteApprovalRequest;
import com.vehiclerental.mak.customers.dto.request.QuoteRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record QuoteResponse(String id, long customerId, String customerKey, String number, int version,
        String status, LocalDate issueDate, LocalDate offerValidUntil, LocalDate effectiveFrom, LocalDate effectiveTo,
        String currency, BigDecimal taxPercent, String paymentTerms, String notes,
        List<QuoteRequest.Route> routes, List<QuoteRequest.Monthly> monthly, List<QuoteRequest.ServiceRate> services,
        QuoteRequest.Extras extras, QuoteApprovalRequest approval, String evidenceUrl) { }
