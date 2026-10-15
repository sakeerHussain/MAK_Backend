package com.vehiclerental.mak.customers.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CustomerStatusRequest(@NotBlank String status) { }
