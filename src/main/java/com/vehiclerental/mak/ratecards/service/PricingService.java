package com.vehiclerental.mak.ratecards.service;

import com.vehiclerental.mak.ratecards.dto.RateQuote;
import com.vehiclerental.mak.ratecards.entity.RateCard;
import com.vehiclerental.mak.ratecards.entity.RateCardLine;
import com.vehiclerental.mak.ratecards.repository.RateCardRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a rental from the standard rate card in effect on the pickup day (KSA).
 * A vehicle override beats its class rate per unit. The duration uses the unit the
 * caller asks for, rounded up (hour: 1-hour minimum then 15-minute steps; day/week/month: whole units).
 * When nothing covers the rental the result is PENDING, never a guessed price.
 */
@Service
public class PricingService {
    private static final Map<String, Long> UNIT_MINUTES = Map.of("HOUR", 60L, "DAY", 1440L, "WEEK", 10080L, "MONTH", 43200L);
    private final RateCardRepository cards;

    public PricingService(RateCardRepository cards) { this.cards = cards; }

    public record Request(String vehicleClass, Long vehicleId, String unit, LocalDateTime pickupAt, LocalDateTime returnAt) { }

    @Transactional(readOnly = true)
    public RateQuote quote(Request request) {
        if (request.vehicleClass() == null || request.pickupAt() == null || request.returnAt() == null || !request.returnAt().isAfter(request.pickupAt()))
            return RateQuote.pending("Enter the vehicle class and a pickup before return to price this rental.");
        String unit = request.unit() == null ? "DAY" : request.unit().toUpperCase(Locale.ROOT);
        if (!UNIT_MINUTES.containsKey(unit)) return RateQuote.pending("Choose an hourly, daily, weekly or monthly rate.");
        var active = cards.findActive(request.pickupAt().toLocalDate());
        if (active.isEmpty()) return RateQuote.pending("No published rate card is in effect on the pickup date.");
        RateCard card = active.getFirst();
        var line = pick(card, request.vehicleClass(), request.vehicleId(), unit);
        if (line.isEmpty()) return RateQuote.pending("Rate card revision " + card.getRevision() + " has no " + unit.toLowerCase(Locale.ROOT)
                + " rate for " + request.vehicleClass() + ". Finance must price this rental.");
        return price(card, line.get(), unit, Duration.between(request.pickupAt(), request.returnAt()));
    }

    static Optional<RateCardLine> pick(RateCard card, String vehicleClass, Long vehicleId, String unit) {
        Optional<RateCardLine> override = vehicleId == null ? Optional.empty() : card.getLines().stream()
                .filter(line -> vehicleId.equals(line.getVehicleId()) && unit.equals(line.getUnit())).findFirst();
        return override.or(() -> card.getLines().stream()
                .filter(line -> line.getVehicleId() == null && vehicleClass.equals(line.getVehicleClass()) && unit.equals(line.getUnit())).findFirst());
    }

    /** Billable quantity: whole units rounded up; hourly bills a 1-hour minimum then 15-minute steps. */
    static BigDecimal quantity(String unit, Duration duration) {
        long minutes = Math.max(1, (duration.toSeconds() + 59) / 60);
        if ("HOUR".equals(unit)) {
            long quarters = Math.max(4, (minutes + 14) / 15);
            return BigDecimal.valueOf(quarters).divide(BigDecimal.valueOf(4), 2, RoundingMode.UNNECESSARY);
        }
        long size = UNIT_MINUTES.get(unit);
        return BigDecimal.valueOf((minutes + size - 1) / size);
    }

    static RateQuote price(RateCard card, RateCardLine line, String unit, Duration duration) {
        var quantity = quantity(unit, duration);
        var subtotal = quantity.multiply(line.getAmount()).setScale(2, RoundingMode.HALF_UP);
        var tax = subtotal.multiply(card.getTaxPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String label = line.getVehicleId() == null ? line.getVehicleClass() + " rental" : line.getVehicleClass() + " rental (vehicle rate)";
        var lines = List.of(new RateQuote.Line(label, unit, quantity, line.getAmount(), subtotal));
        return new RateQuote("STANDARD_CARD", null, card.getId(), card.getRevision(), card.getDigest(), line.getVehicleClass(),
                line.getVehicleId(), unit, quantity, line.getAmount(), subtotal, card.getTaxPercent(), tax, subtotal.add(tax), lines);
    }
}
