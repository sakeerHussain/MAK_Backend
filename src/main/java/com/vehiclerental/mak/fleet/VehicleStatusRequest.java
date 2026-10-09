package com.vehiclerental.mak.fleet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VehicleStatusRequest(
        @NotBlank @Pattern(regexp = "Available|In Use|Under Maintenance|Inactive") String status
) { }
