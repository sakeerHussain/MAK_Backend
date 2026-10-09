package com.vehiclerental.mak.customers;

import jakarta.validation.constraints.NotBlank;

public record CustomerStatusRequest(@NotBlank String status) { }
