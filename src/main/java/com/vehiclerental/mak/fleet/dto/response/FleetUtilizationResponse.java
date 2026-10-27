package com.vehiclerental.mak.fleet.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FleetUtilizationResponse(String period, String timeZone, LocalDateTime start,
        LocalDateTime end, long fleetSize, long rentedSeconds, long capacitySeconds,
        BigDecimal utilizationPercent, int measuredRentals, int omittedRentals) { }
