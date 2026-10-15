package com.vehiclerental.mak.rentals.dto.request;

import java.math.BigDecimal;

public record RentalPaymentRequest(String kind, BigDecimal amount) {}
