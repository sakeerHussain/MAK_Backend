package com.vehiclerental.mak.vendors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VendorRequest(
        @NotBlank String vendorType, @NotBlank @Size(max = 150) String name,
        String crNumber, String vatNumber, String nationalAddress, String classification,
        @NotNull BigDecimal commission, @NotBlank String status, @NotNull Integer creditTerms,
        LocalDate contractStart, LocalDate contractEnd, @NotEmpty List<@Valid Contact> contacts,
        @Valid Bank bank) {
    public record Contact(@NotBlank String name, @NotBlank String mobile, String email) { }
    public record Bank(String beneficiaryName, String bankName, String iban, String accountNumber,
            String swiftCode, String currency) { }
}
