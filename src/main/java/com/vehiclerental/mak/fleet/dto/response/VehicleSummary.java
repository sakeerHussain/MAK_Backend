package com.vehiclerental.mak.fleet.dto.response;

public record VehicleSummary(long total, long available, long inUse, long underMaintenance, long inactive) { }
