package com.vehiclerental.mak.fleet.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record VehicleDraftCompletionRequest(@NotNull @Valid VehicleRequest details, @NotNull Long version) { }
