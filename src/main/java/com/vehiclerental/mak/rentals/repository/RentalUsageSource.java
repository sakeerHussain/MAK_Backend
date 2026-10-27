package com.vehiclerental.mak.rentals.repository;

// Internal projection; customer and payment payload fields never leave the utilization service.
public record RentalUsageSource(Long vehicleId, String status, String payload) { }
