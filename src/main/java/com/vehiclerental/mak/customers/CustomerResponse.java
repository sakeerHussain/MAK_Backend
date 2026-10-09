package com.vehiclerental.mak.customers;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerResponse(
        long id, String type, String name, String phone, String email, String address,
        String status, Instant createdAt, String registrationNumber, String taxNumber,
        String billingContactName, String billingPhone, String billingEmail,
        String billingAddress, String billingCity, String billingCountry, String paymentTerms,
        BigDecimal waitingRatePerHour, Integer includedWaitingMinutes,
        Integer waitingBillingIncrementMinutes) {
    static CustomerResponse from(Customer customer) {
        boolean corporate = "Corporate".equals(customer.getType());
        return new CustomerResponse(customer.getId(), customer.getType(), customer.getName(), customer.getPhone(),
                customer.getEmail(), customer.getAddress(), customer.getStatus(), customer.getCreatedAt(),
                customer.getRegistrationNumber(), customer.getTaxNumber(), customer.getBillingContactName(),
                corporate ? customer.getPhone() : null, corporate ? customer.getEmail() : null,
                customer.getBillingAddress(), customer.getBillingCity(), customer.getBillingCountry(),
                customer.getPaymentTerms(), customer.getWaitingRatePerHour(), customer.getIncludedWaitingMinutes(),
                customer.getWaitingBillingIncrementMinutes());
    }
}
