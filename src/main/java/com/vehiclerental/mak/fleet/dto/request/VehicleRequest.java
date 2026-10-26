package com.vehiclerental.mak.fleet.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VehicleRequest(
        @NotBlank @Size(max = 100) String make,
        @NotBlank @Size(max = 100) String model,
        @NotBlank @Size(max = 50) String registration,
        @NotNull @Min(1980) Integer year,
        @Size(max = 60) String color,
        @NotBlank @Size(max = 50) String vehicleId,
        @Size(max = 60) String doorNo,
        @Size(max = 100) String chassisNo,
        @NotBlank @Pattern(regexp = "Sedan|SUV|Van|Bus|Electric|Luxury") String type,
        @NotNull @Min(1) @Max(60) Integer seats,
        @NotBlank @Pattern(regexp = "Automatic|Manual") String transmission,
        @NotBlank @Pattern(regexp = "Petrol|Diesel|Hybrid|Electric") String fuelType,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> featureTags,
        @DecimalMin("0.00") BigDecimal hourlyRate,
        @NotNull @DecimalMin("0.01") BigDecimal dailyRate,
        @DecimalMin("0.00") BigDecimal monthlyRate,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String rateCurrency,
        @NotBlank @Pattern(regexp = "Owned|Leased|Vendor") String ownership,
        @Size(max = 150) String vendor,
        @NotBlank @Pattern(regexp = "Available|In Use|Under Maintenance|Inactive") String status,
        @Size(max = 150) String custodian,
        @NotNull Boolean onSite,
        @NotNull Boolean listedForBooking,
        @NotNull Boolean showOnPublicSite,
        @Min(1) Long serviceIntervalKm,
        @Min(0) Long lastServiceKm,
        @Min(0) Long currentOdometerKm,
        @Min(1) Long oilIntervalKm,
        @Min(0) Long lastOilKm,
        @Min(1) Long tyreIntervalKm,
        @Min(0) Long lastTyreKm,
        @Min(1) Long brakeIntervalKm,
        @Min(0) Long lastBrakeKm,
        LocalDate registrationExpiryDate,
        LocalDate insuranceExpiryDate,
        LocalDate inspectionExpiryDate
) { }
