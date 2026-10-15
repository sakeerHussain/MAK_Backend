package com.vehiclerental.mak.drivers.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DriverStatusRequest(@NotBlank @Pattern(regexp = "Active|Inactive") String status) { }
