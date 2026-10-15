package com.vehiclerental.mak.customers.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerResponse(
        long id, String type, String name, String phone, String email, String address,
        String status, Instant createdAt, String registrationNumber, String taxNumber,
        String billingContactName, String billingPhone, String billingEmail,
        String billingAddress, String billingCity, String billingCountry, String paymentTerms,
        BigDecimal waitingRatePerHour, Integer includedWaitingMinutes,
        Integer waitingBillingIncrementMinutes) {

}
