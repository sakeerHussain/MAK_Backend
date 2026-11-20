package com.vehiclerental.mak.ratecards.service;

import com.vehiclerental.mak.ratecards.entity.RateCard;
import com.vehiclerental.mak.ratecards.entity.RateCardLine;
import com.vehiclerental.mak.ratecards.repository.RateCardRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pricing arithmetic, rounding and override precedence, independent of the database. */
class PricingServiceTests {
    static BigDecimal bd(String value) { return new BigDecimal(value); }
    static RateCard card() {
        var card = new RateCard(); card.setId(9L); card.setRevision(3); card.setDigest("abc"); card.setTaxPercent(bd("15.00"));
        card.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        add(card, "SUV", null, "DAY", "400.00"); add(card, "SUV", null, "HOUR", "60.00");
        add(card, "SUV", 77L, "DAY", "550.00"); add(card, "Sedan", null, "MONTH", "6000.00");
        return card;
    }
    static void add(RateCard card, String type, Long vehicle, String unit, String amount) {
        var line = new RateCardLine(); line.setCard(card); line.setVehicleClass(type); line.setVehicleId(vehicle); line.setUnit(unit); line.setAmount(bd(amount));
        card.getLines().add(line);
    }
    static PricingService service(List<RateCard> active) {
        var repo = mock(RateCardRepository.class); when(repo.findActive(any())).thenReturn(active); return new PricingService(repo);
    }
    final LocalDateTime pickup = LocalDateTime.of(2026, 11, 20, 9, 0);

    @Test void hourlyHasOneHourMinimumThenQuarterHourSteps() {
        assertEquals(0, bd("1").compareTo(PricingService.quantity("HOUR", Duration.ofMinutes(20))));
        assertEquals(0, bd("2.25").compareTo(PricingService.quantity("HOUR", Duration.ofMinutes(126))));
        assertEquals(0, bd("2").compareTo(PricingService.quantity("HOUR", Duration.ofMinutes(120))));
    }

    @Test void longerUnitsRoundUpToWholeUnits() {
        assertEquals(0, bd("1").compareTo(PricingService.quantity("DAY", Duration.ofHours(3))));
        assertEquals(0, bd("2").compareTo(PricingService.quantity("DAY", Duration.ofHours(24).plusMinutes(1))));
        assertEquals(0, bd("1").compareTo(PricingService.quantity("MONTH", Duration.ofDays(30))));
        assertEquals(0, bd("2").compareTo(PricingService.quantity("WEEK", Duration.ofDays(8))));
    }

    @Test void classRateWithTaxAndRevisionTraceability() {
        var quote = service(List.of(card())).quote(new PricingService.Request("SUV", null, "DAY", pickup, pickup.plusDays(3)));
        assertTrue(quote.priced());
        assertEquals(0, bd("1200.00").compareTo(quote.subtotal()));
        assertEquals(0, bd("180.00").compareTo(quote.taxAmount()));
        assertEquals(0, bd("1380.00").compareTo(quote.total()));
        assertEquals(3, quote.revision()); assertEquals("abc", quote.digest()); assertEquals(1, quote.lines().size());
    }

    @Test void vehicleOverrideBeatsClassRateOnlyForThatVehicleAndUnit() {
        var service = service(List.of(card()));
        assertEquals(0, bd("550.00").compareTo(service.quote(new PricingService.Request("SUV", 77L, "DAY", pickup, pickup.plusDays(1))).unitPrice()));
        assertEquals(0, bd("400.00").compareTo(service.quote(new PricingService.Request("SUV", 12L, "DAY", pickup, pickup.plusDays(1))).unitPrice()));
        assertEquals(0, bd("60.00").compareTo(service.quote(new PricingService.Request("SUV", 77L, "HOUR", pickup, pickup.plusHours(1))).unitPrice()));
    }

    @Test void uncoveredRentalsArePendingNotGuessed() {
        var service = service(List.of(card()));
        assertEquals("PENDING", service.quote(new PricingService.Request("Van", null, "DAY", pickup, pickup.plusDays(1))).source());
        assertEquals("PENDING", service.quote(new PricingService.Request("Sedan", null, "DAY", pickup, pickup.plusDays(1))).source());
        assertEquals("PENDING", service(List.of()).quote(new PricingService.Request("SUV", null, "DAY", pickup, pickup.plusDays(1))).source());
        assertEquals("PENDING", service.quote(new PricingService.Request("SUV", null, "DAY", pickup, pickup)).source());
        assertNull(service(List.of()).quote(new PricingService.Request("SUV", null, "DAY", pickup, pickup.plusDays(1))).total());
    }
}
