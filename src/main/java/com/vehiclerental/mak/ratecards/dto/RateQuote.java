package com.vehiclerental.mak.ratecards.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Server-calculated price for a rental. source is STANDARD_CARD or PENDING (no rate covers it).
 * The rate card revision and digest identify exactly which terms produced the price.
 */
public record RateQuote(
        String source, String reason, Long rateCardId, Integer revision, String digest,
        String vehicleClass, Long vehicleId, String unit, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal subtotal, BigDecimal taxPercent, BigDecimal taxAmount, BigDecimal total, List<Line> lines) {
    public record Line(String description, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) { }

    public static RateQuote pending(String reason) {
        return new RateQuote("PENDING", reason, null, null, null, null, null, null, null, null, null, null, null, null, List.of());
    }
    public boolean priced() { return "STANDARD_CARD".equals(source); }
}
