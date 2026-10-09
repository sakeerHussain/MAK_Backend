package com.vehiclerental.mak.employees;

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

public record EmployeeRequest(
        @NotBlank @Size(max = 50) String employeeId,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 40) String phone,
        @Email @Size(max = 150) String email,
        @NotBlank @Pattern(regexp = "Operations|Management|Administration|Customer Service|Technical|Finance|Other") String category,
        @Size(max = 100) String nationality,
        @NotBlank @Size(max = 100) String jobTitle,
        @NotBlank @Pattern(regexp="Operations|Customer Service|Fleet|Finance|Human Resources|Sales|Administration") String department,
        @Size(max=150) String manager,
        @Size(max = 100) String idNumber,
        LocalDate iqamaExpiry,
        @Min(0) @Max(80) Integer yearsExperience,
        @NotBlank @Pattern(regexp = "Full-time|Part-time|Contract|Vendor") String employmentType,
        @Size(max = 150) String vendor,
        @NotBlank @Pattern(regexp = "Active|Inactive") String status,
        @Size(max = 100) String companyId,
        LocalDate joinedOn,
        @Size(max = 40) String officialNumber,
        @DecimalMin("0.00") @jakarta.validation.constraints.Digits(integer=10,fraction=2) BigDecimal hourlyRate,
        @DecimalMin("0.00") @jakarta.validation.constraints.Digits(integer=10,fraction=2) BigDecimal dailyRate,
        @NotNull @DecimalMin("0.00") @jakarta.validation.constraints.Digits(integer=10,fraction=2) BigDecimal monthlySalary,
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
