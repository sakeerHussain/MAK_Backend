package com.vehiclerental.mak.inspections;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record InspectionRequest(
    @NotNull @Positive Long vehicleId,
    @NotNull @Pattern(regexp="Pre-trip|Post-trip|Routine|Damage assessment") String type,
    @NotNull @Pattern(regexp="Excellent|Good|Fair|Poor|Damaged") String condition,
    @NotNull LocalDate inspectionDate,
    @NotBlank @Size(max=200) String inspector,
    @NotNull @DecimalMin("0") @DecimalMax("9007199254740991") @Digits(integer=16,fraction=0) BigDecimal odometerKm,
    @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=0) BigDecimal fuelLevel,
    @Positive Long bookingId,
    @Size(max=10000) String notes,
    @NotNull @Size(max=10) List<@NotNull @Valid Photo> photos
) {
    public record Photo(
        @Pattern(regexp="[a-zA-Z0-9.-]{1,100}") String id,
        @Pattern(regexp="photo[0-9]{1,2}") String uploadKey
    ) {}
}
