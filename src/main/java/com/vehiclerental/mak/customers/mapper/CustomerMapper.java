package com.vehiclerental.mak.customers.mapper;

import com.vehiclerental.mak.customers.dto.response.CustomerResponse;
import com.vehiclerental.mak.customers.entity.Customer;

public final class CustomerMapper {
    private CustomerMapper() { }

    public static CustomerResponse toResponse(Customer customer) {
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
