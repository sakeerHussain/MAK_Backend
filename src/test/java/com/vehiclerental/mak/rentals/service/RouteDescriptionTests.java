package com.vehiclerental.mak.rentals.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Quoted route charge lines appear on invoices and PDFs; they must never contain mis-encoded characters. */
class RouteDescriptionTests {
    @Test void directRouteUsesArrowAndMiddleDot() {
        assertEquals("Al Khobar \u2192 Dammam \u00B7 SUV", RentalService.routeDescription("Al Khobar", "", "Dammam", "SUV"));
    }

    @Test void viaStopsAppearBetweenOriginAndDestination() {
        assertEquals("Al Khobar \u2192 Dhahran \u2192 Dammam \u00B7 Van", RentalService.routeDescription("Al Khobar", "Dhahran", "Dammam", "Van"));
        assertEquals("A \u2192 B \u00B7 Sedan", RentalService.routeDescription("A", null, "B", "Sedan"));
    }

    @Test void containsNoMojibake() {
        String text = RentalService.routeDescription("A", "V", "B", "Bus");
        for (String garbled : new String[]{"\u00C3", "\u00C2", "\u00E2\u20AC"}) assertFalse(text.contains(garbled), garbled);
    }
}
