package com.vehiclerental.mak.invoices.service;

import java.time.*;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/** Finance instants stay UTC; all business calendar dates use Saudi Arabia time. */
@Component
public class InvoiceTime {
    private static final ZoneId KSA = ZoneId.of("Asia/Riyadh");
    private final Clock clock;
    public InvoiceTime() { this(Clock.systemUTC()); }
    InvoiceTime(Clock clock) { this.clock = clock; }
    public Instant now() { return clock.instant(); }
    public LocalDate today() { return now().atZone(KSA).toLocalDate(); }
    public String day(String value) {
        if (value == null || value.isBlank()) return "";
        // A date-only field is already a business calendar date, not an instant.
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(value).toString();
        try { return OffsetDateTime.parse(value).atZoneSameInstant(KSA).toLocalDate().toString(); }
        catch (DateTimeParseException ignored) {
            // Legacy offset-free rental timestamps represent KSA wall time.
            return LocalDateTime.parse(value).atZone(KSA).toLocalDate().toString();
        }
    }
}
