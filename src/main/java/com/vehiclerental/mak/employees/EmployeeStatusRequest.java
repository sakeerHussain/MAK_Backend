package com.vehiclerental.mak.employees;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmployeeStatusRequest(@NotBlank @Pattern(regexp = "Active|Inactive") String status) { }
