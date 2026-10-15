package com.vehiclerental.mak.drivers.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DriverRequest(
        @NotBlank @Size(max = 50) String employeeId,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 40) String phone,
        @Email @Size(max = 150) String email,
        @NotBlank @Pattern(regexp = "Light Vehicle|Heavy Vehicle|Chauffeur|Delivery") String category,
        @Size(max = 100) String nationality,
        @NotBlank @Size(max = 100) String designation,
        @Size(max = 100) String idNumber,
        LocalDate iqamaExpiry,
        @Min(0) @Max(80) Integer yearsExperience,
        @NotBlank @Pattern(regexp = "Full-time|Part-time|Contract|Vendor") String employmentType,
        @Size(max = 150) String vendor,
        @NotBlank @Pattern(regexp = "Active|Inactive") String status,
        @Size(max = 100) String companyId,
        LocalDate joinedOn,
        @Size(max = 40) String officialNumber,
        @DecimalMin("0.00") BigDecimal hourlyRate,
        @NotNull @DecimalMin("0.00") BigDecimal dailyRate,
        @DecimalMin("0.00") BigDecimal monthlyRate,
        @Size(max = 100) String passportNumber,
        LocalDate passportExpiry,
        @Size(max = 100) String contractId,
        LocalDate contractExpiry,
        @Size(max = 100) String licenceNumber,
        LocalDate licenceExpiry,
        @Size(max = 5) String bloodGroup,
        @Size(max = 100) String insuranceNumber,
        LocalDate insuranceExpiry,
        @Size(max = 40) String emergencyKsa,
        @Size(max = 40) String emergencyHome,
        @Size(max = 500) String homeAddress
) { }
