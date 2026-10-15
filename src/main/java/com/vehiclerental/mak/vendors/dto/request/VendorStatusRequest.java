package com.vehiclerental.mak.vendors.dto.request;

import jakarta.validation.constraints.NotBlank;

public record VendorStatusRequest(@NotBlank String status) { }
