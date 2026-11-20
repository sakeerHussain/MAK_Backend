package com.vehiclerental.mak.ratecards.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Draft terms. Drafts may be incomplete except for name and effective date; publishing enforces coverage. */
public record RateCardRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal taxPercent,
        @NotNull @Min(0) @Max(720) Integer graceMinutes,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal extraKmRate,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal overtimeHourRate,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal extraDriverRate,
        @Size(max = 1000) String notes,
        @NotNull @Size(max = 500) List<@Valid Line> lines,
        Long version) {
    public record Line(
            @NotBlank @Pattern(regexp = "Sedan|SUV|Van|Bus|Electric|Luxury") String vehicleClass,
            Long vehicleId,
            @NotBlank @Pattern(regexp = "HOUR|DAY|WEEK|MONTH") String unit,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) { }
}
