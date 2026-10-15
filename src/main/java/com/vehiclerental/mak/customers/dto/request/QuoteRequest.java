package com.vehiclerental.mak.customers.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

public record QuoteRequest(@Size(max = 60) String number, @NotBlank String status, LocalDate issueDate,
        LocalDate offerValidUntil, LocalDate effectiveFrom, LocalDate effectiveTo, BigDecimal taxPercent,
        @Size(max = 100) String paymentTerms, @Size(max = 10000) String notes,
        @Size(max = 1000) List<@NotNull @Valid Route> routes, @Size(max = 1000) List<@NotNull @Valid Monthly> monthly,
        @Size(max = 1000) List<@NotNull @Valid ServiceRate> services, Extras extras) {
    public record Route(@Size(max = 100) String id, @Size(max = 200) String from, @Size(max = 1000) String via,
            @Size(max = 200) String to, @Size(max = 150) String vehicle, @Size(max = 32) String amount) { }
    public record Monthly(@Size(max = 100) String id, @Size(max = 150) String vehicle,
            @Size(max = 32) String amount, @Size(max = 32) String includedKm, @Size(max = 32) String includedTrips) { }
    public record ServiceRate(@Size(max = 100) String id, @Size(max = 200) String name,
            @Size(max = 40) String unit, @Size(max = 32) String amount) { }
    public record Extras(String extraKmRate, String includedKm, String overtimeRate, String standardHours,
            String extraDriverRate, String extraDriverThresholdKm) { }
}
