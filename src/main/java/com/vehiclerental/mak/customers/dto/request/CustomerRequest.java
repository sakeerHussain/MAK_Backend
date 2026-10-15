package com.vehiclerental.mak.customers.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank String type, @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 40) String phone, @NotBlank @Email @Size(max = 150) String email,
        @Size(max = 500) String address, @NotBlank String status,
        @Size(max = 100) String registrationNumber, @Size(max = 30) String taxNumber, @Size(max = 150) String billingContactName,
        @Size(max = 500) String billingAddress, @Size(max = 100) String billingCity, @Size(max = 100) String billingCountry,
        @Size(max = 100) String paymentTerms, BigDecimal waitingRatePerHour, Integer includedWaitingMinutes,
        Integer waitingBillingIncrementMinutes) { }
