package com.vehiclerental.mak.drivers;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CertificationRequest(@NotBlank @Size(max = 150) String name, LocalDate issueDate, LocalDate expiryDate) { }
