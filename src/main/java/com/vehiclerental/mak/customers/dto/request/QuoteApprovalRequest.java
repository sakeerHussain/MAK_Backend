package com.vehiclerental.mak.customers.dto.request;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuoteApprovalRequest(@NotBlank @Size(max = 150) String by, @NotNull LocalDate date,
        @NotBlank String method, @NotBlank @Size(max = 1000) String note,
        @Size(max = 255) String fileName) { }
