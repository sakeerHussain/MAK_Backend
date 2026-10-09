package com.vehiclerental.mak.vendors;

import jakarta.validation.constraints.NotBlank;

public record VendorStatusRequest(@NotBlank String status) { }
