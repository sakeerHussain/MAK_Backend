package com.vehiclerental.mak.ratecards.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RateCardResponse(
        long id, String name, int revision, String status, LocalDate effectiveFrom, LocalDate effectiveTo,
        BigDecimal taxPercent, int graceMinutes, BigDecimal extraKmRate, BigDecimal overtimeHourRate,
        BigDecimal extraDriverRate, String notes, Long revisedFromId, String digest,
        Instant createdAt, Instant updatedAt, Instant publishedAt, String publishedBy, long version,
        List<Line> lines, Coverage coverage, List<Event> history) {
    public record Line(Long id, String vehicleClass, Long vehicleId, String vehicleLabel, String unit, BigDecimal amount) { }
    /** Classes that have bookable vehicles but no DAY rate block publishing. */
    public record Coverage(List<String> bookableClasses, List<String> missingDayRate, boolean publishable) { }
    public record Event(String action, String actor, String reason, Instant at) { }
}
