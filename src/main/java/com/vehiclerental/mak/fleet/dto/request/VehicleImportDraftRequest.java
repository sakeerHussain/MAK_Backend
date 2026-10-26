package com.vehiclerental.mak.fleet.dto.request;

import jakarta.validation.constraints.NotNull;

// Deliberately not cascaded: missing operational details are allowed only in this separate register.
public record VehicleImportDraftRequest(@NotNull VehicleRequest details, Long version) { }
