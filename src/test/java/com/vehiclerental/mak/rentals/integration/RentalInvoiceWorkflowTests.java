package com.vehiclerental.mak.rentals.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.customers.dto.request.CustomerRequest;
import com.vehiclerental.mak.customers.dto.request.QuoteApprovalRequest;
import com.vehiclerental.mak.customers.dto.request.QuoteRequest;
import com.vehiclerental.mak.customers.dto.response.QuoteResponse;
import com.vehiclerental.mak.customers.service.CustomerService;
import com.vehiclerental.mak.customers.service.QuoteService;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleFileRepository;
import com.vehiclerental.mak.RentalReadyFixture;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.invoices.service.InvoiceService;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import com.vehiclerental.mak.rentals.service.RentalService;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RentalInvoiceWorkflowTests {
    @Autowired RentalService rentals;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired InvoiceService invoices;
    @Autowired VehicleRepository vehicles;
    @Autowired VehicleFileRepository vehicleFiles;
    @Autowired CustomerService customers;
    @Autowired QuoteService quotes;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired EmailRepository emails;

    private Vehicle vehicle() {
        Vehicle v = new Vehicle();
        String key = UUID.randomUUID().toString().substring(0, 8);
        v.setRegistration("T-" + key); v.setVehicleId("V-" + key);
        v.setMake("Toyota"); v.setModel("Camry"); v.setYear(2025); v.setType("Sedan");
        v.setSeats(5); v.setTransmission("Automatic"); v.setFuelType("Petrol");
        v.setDailyRate(new BigDecimal("200")); v.setRateCurrency("SAR"); v.setOwnership("Owned");
        v.setStatus("Available");
        v.setOnSite(true); vehicles.saveAndFlush(v); RentalReadyFixture.documents(vehicleFiles,v); return v;
    }

    @Test void quickBookingDepositInvoiceAndPaymentPersist() {
        long vehicleId = vehicle().getId();
        LocalDateTime start = LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).minusHours(2).withSecond(0).withNano(0);
        Map<String, Object> booking = rentals.create("Individual", Map.ofEntries(
                Map.entry("bookingType", "Quick"), Map.entry("status", "Active"),
                Map.entry("customerName", "Test Guest"), Map.entry("customerPhone", "+966500000001"),
                Map.entry("customerEmail", "guest@example.com"), Map.entry("driverMode", "Self-drive"),
                Map.entry("emailNotifications",true),
                Map.entry("vehicleId", vehicleId), Map.entry("pickupAt", start.toString()),
                Map.entry("returnAt", start.plusDays(1).toString()),
                Map.entry("pickupLocation", "Al Khobar"), Map.entry("destination", "Dammam"),
                Map.entry("totalAmount", 230), Map.entry("receivedAmount", 30)));
        long id = ((Number) booking.get("id")).longValue();
        assertEquals(1,emails.findByRecordKindAndRecordIdOrderByIdDesc("Individual",id).size());
        assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(booking.get("receivedAmount").toString())));
        assertEquals("BK-" + (2000 + id), booking.get("bookingId"));
        assertThrows(ApiException.class, () -> rentals.payment(id, "Refund", new BigDecimal("31")));
        rentals.transition("Individual", id, "Completed", Map.of());
        assertEquals(2,emails.findByRecordKindAndRecordIdOrderByIdDesc("Individual",id).size());
        assertEquals("Available", vehicles.findById(vehicleId).orElseThrow().getStatus());
        Map<String, Object> draft = Map.ofEntries(
                Map.entry("bookingChargeReview",Map.of("confirmed",true)), Map.entry("kind", "Individual"), Map.entry("currency", "SAR"),
                Map.entry("sources", List.of(Map.of("kind", "Individual", "id", id, "reference", booking.get("bookingId")))),
                Map.entry("lines", List.of(Map.of("description", "Rental", "unit", "Trip", "quantity", 1,
                        "unitPrice", 200, "discountPercent", 0, "taxPercent", 15))),
                Map.entry("issuer", Map.of("name", "Rental Company", "street", "Main Street")),
                Map.entry("billTo", Map.of("name", "Test Guest", "email", "guest@example.com")),
                Map.entry("dueDate", LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).plusDays(7).toString()));
        Map<String, Object> invoice = invoices.create(draft, true);
        long invoiceId = ((Number) invoice.get("id")).longValue();
        assertEquals("Issued", invoice.get("status"));
        assertEquals(1, emails.findByRecordKindAndRecordIdOrderByIdDesc("Invoice",invoiceId).size());
        try {
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/invoices/"+invoiceId+"/pdf")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".pdf")));
            mvc.perform(post("/api/invoices/"+invoiceId+"/email")).andExpect(status().isOk());
            assertEquals(1, emails.findByRecordKindAndRecordIdOrderByIdDesc("Invoice",invoiceId).size());
        } catch(Exception failure) { throw new AssertionError(failure); }
        assertEquals(1, ((List<?>) invoice.get("payments")).size());
        assertThrows(ApiException.class, () -> invoices.create(draft, false));
        Map<String, Object> paid = invoices.payment(invoiceId, Map.of("kind", "Payment", "amount", 200,
                "date", LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).toString(), "method", "Card", "reference", "TX-1"));
        assertEquals(2, ((List<?>) paid.get("payments")).size());
        assertEquals(0, new BigDecimal("230").compareTo(new BigDecimal(rentals.get("Individual", id).get("receivedAmount").toString())));
        assertThrows(ApiException.class, () -> invoices.payment(invoiceId, Map.of("kind", "Payment", "amount", 1, "date", LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).toString())));
    }

    private Map<String, Object> individual(String type, String status, LocalDateTime start) {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("bookingType", type), Map.entry("status", status),
                Map.entry("customerName", "API Guest"), Map.entry("customerPhone", "+966500000002"),
                Map.entry("customerEmail", "guest@example.com"), Map.entry("driverMode", "Self-drive"),
                Map.entry("pickupLocation", "Al Khobar"), Map.entry("destination", "Dammam"),
                Map.entry("pickupAt", start.toString()), Map.entry("returnAt", start.plusDays(1).toString()),
                Map.entry("requestedType", "Sedan"), Map.entry("totalAmount", 200)));
    }

    private long company() {
        return customers.create(new CustomerRequest("Corporate", "Company " + UUID.randomUUID(), "+966500000003",
                "billing@example.com", "Al Khobar", "Active", "CR-1", "", "Accounts", "Main Street",
                "Al Khobar", "Saudi Arabia", "Net 30", new BigDecimal("50"), 15, 15)).id();
    }

    private Map<String, Object> corporate(long company, long vehicle) {
        LocalDateTime start = LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).minusHours(1).withSecond(0).withNano(0);
        Map<String, Object> data = new LinkedHashMap<>(Map.ofEntries(Map.entry("bookingType", "Quick"), Map.entry("status", "Active"),
                Map.entry("companyId", company), Map.entry("companyName", "Test Corporate Account"),
                Map.entry("contactName", "Coordinator"), Map.entry("contactPhone", "+966500000004"), Map.entry("contactEmail", "contact@example.com"),
                Map.entry("projectManager", "Project Manager"), Map.entry("projectCode", "PRJ-1"), Map.entry("passengerCount", 2),
                Map.entry("passengers", List.of(Map.of("name", "Passenger 1", "phone", "111"), Map.of("name", "Passenger 2", "phone", "222"))),
                Map.entry("stops", List.of(Map.of("location", "Al Khobar", "waitingMinutes", 15), Map.of("location", "Dammam", "waitingMinutes", 30))),
                Map.entry("pickupLocation", "Al Khobar"), Map.entry("destination", "Dammam"),
                Map.entry("pickupAt", start.toString()), Map.entry("returnAt", start.plusDays(1).toString()),
                Map.entry("serviceMode", "Self-drive"), Map.entry("vehicleId", vehicle), Map.entry("requestedType", "Sedan"),
                Map.entry("unitPrice", 200), Map.entry("quantity", 1), Map.entry("waitingRatePerHour", 50),
                Map.entry("includedWaitingMinutes", 15), Map.entry("waitingBillingIncrementMinutes", 15),
                Map.entry("taxPercent", 15), Map.entry("totalAmount", new BigDecimal("258.75"))));
        return data;
    }

    @Test void advanceAssignmentIsDeferredAndStatusEditsAreAtomic() {
        LocalDateTime tomorrow = LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).plusDays(1).withSecond(0).withNano(0);
        Map<String, Object> saved = rentals.create("Individual", individual("Advance", "Pending", tomorrow));
        long id = ((Number) saved.get("id")).longValue();
        assertNull(saved.get("vehicleId"));
        rentals.update("Individual", id, Map.of("status", "Confirmed", "receivedAmount", 999, "refundedAmount", 999));
        assertEquals("Confirmed", rentals.get("Individual", id).get("status"));
        assertEquals(0, new BigDecimal(rentals.get("Individual", id).get("receivedAmount").toString()).signum());
        long vehicle = vehicle().getId();
        assertThrows(ApiException.class, () -> rentals.assign("Individual", id, Map.of("vehicleId", vehicle)));
        assertThrows(ApiException.class, () -> rentals.update("Individual", id, Map.of("vehicleId", vehicle)));
        assertThrows(ApiException.class, () -> rentals.update("Individual", id, Map.of("status", "Completed")));

        Map<String, Object> today = individual("Advance", "Confirmed", tomorrow);
        long todayId = ((Number) rentals.create("Individual", today).get("id")).longValue();
        // Simulate reaching pickup day without a fixture that crosses midnight.
        LocalDateTime duePickup = LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).minusMinutes(1);
        today.put("pickupAt", duePickup.toString());
        jdbc.update("update rental_records set pickup_at=?,payload=? where id=?", duePickup, json.writeValueAsString(today), todayId);
        Map<String, Object> assigned = rentals.assign("Individual", todayId, Map.of("vehicleId", vehicle, "handedOver", true));
        assertEquals("Active", assigned.get("status"));
        assertEquals("In Use", vehicles.findById(vehicle).orElseThrow().getStatus());
        rentals.transition("Individual", todayId, "Completed", Map.of("totalAmount", 1));
        assertEquals(0, new BigDecimal("200").compareTo(new BigDecimal(rentals.get("Individual", todayId).get("totalAmount").toString())));
    }

    @Test void corporatePricingConflictChecksAndConsolidation() {
        long account = company();
        long car = vehicle().getId();
        Map<String, Object> data = corporate(account, car);
        Map<String, Object> trip = rentals.create("Corporate", data);
        long tripId = ((Number) trip.get("id")).longValue();
        Map<String, Object> conflicting = individual("Quick", "Confirmed", LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).withSecond(0).withNano(0));
        conflicting.put("vehicleId", car);
        assertThrows(ApiException.class, () -> rentals.create("Individual", conflicting));
        Map<String, Object> invalidPrice = corporate(account, vehicle().getId());
        invalidPrice.put("totalAmount", 100);
        assertThrows(ApiException.class, () -> rentals.create("Corporate", invalidPrice));
        Map<String, Object> second = rentals.create("Corporate", corporate(account, vehicle().getId()));
        long secondId = ((Number) second.get("id")).longValue();
        rentals.transition("Corporate", tripId, "Completed", Map.of());
        rentals.transition("Corporate", secondId, "Completed", Map.of());
        Map<String, Object> draft = new LinkedHashMap<>(Map.of("kind", "Corporate", "currency", "SAR",
                "sources", List.of(Map.of("kind", "Corporate", "id", tripId), Map.of("kind", "Corporate", "id", secondId)),
                "lines", List.of(Map.of("description", "Two trips", "unit", "Trip", "quantity", 2, "unitPrice", 225, "taxPercent", 15)),
                "issuer", Map.of("name", "Rental Company", "street", "Main Street"),
                "billTo", Map.of("name", "Company", "email", "billing@example.com", "street", "Main Street", "city", "Al Khobar", "country", "Saudi Arabia"),
                "dueDate", LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).plusDays(30).toString()));
        Map<String, Object> invoice = invoices.create(draft, true);
        assertEquals(2, ((List<?>) invoice.get("sources")).size());
        assertEquals("Issued", invoice.get("status"));
        assertEquals(account, ((Number) invoice.get("companyId")).longValue());
        assertThrows(ApiException.class, () -> invoices.create(draft, false));
        long invoiceId = ((Number) invoice.get("id")).longValue();
        assertThrows(ApiException.class, () -> invoices.update(invoiceId, draft, false));
        assertThrows(ApiException.class, () -> invoices.payment(invoiceId, Map.of("kind", "Payment", "amount", new BigDecimal("0.001"),
                "date", LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).toString(), "method", "Cash")));
    }

    @Test void approvedQuoteIsPricedOnServerAndSnapshotSurvivesRevision() {
        long customerId = customers.create(new CustomerRequest("Individual", "Quoted " + UUID.randomUUID(), "123", "quoted@example.com",
                "", "Active", "", "", "", "", "", "", "", BigDecimal.ZERO, 0, 15)).id();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Riyadh"));
        QuoteResponse shared = quotes.create(customerId, new QuoteRequest("Q-" + UUID.randomUUID(), "Shared", today, today.plusDays(30),
                today, today.plusMonths(1), new BigDecimal("15"), "", "",
                List.of(new QuoteRequest.Route("route-1", "Al Khobar", "", "Dammam", "Sedan", "100")),
                List.of(), List.of(), new QuoteRequest.Extras("2.5", "", "50", "", "", "")));
        QuoteResponse approved = quotes.approve(shared.id(), new QuoteApprovalRequest("Customer", today, "Email", "Approval email TEST", null));
        Map<String, Object> booking = individual("Advance", "Confirmed", LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).plusDays(1).withSecond(0).withNano(0));
        booking.put("customerId", customerId); booking.put("extraKm", 10); booking.put("overtimeHours", 1);
        booking.put("totalAmount", new BigDecimal("201.25"));
        booking.put("quoteSnapshot", Map.of("quoteId", approved.id(), "quoteNumber", approved.number(), "version", approved.version(),
                "ruleId", "route-1", "vehicle", "Sedan", "from", "Al Khobar", "to", "Dammam",
                "lines", List.of(Map.of("unitPrice", 100)), "total", new BigDecimal("201.25")));
        Map<String, Object> saved = rentals.create("Individual", booking);
        Map<?, ?> snapshot = (Map<?, ?>) saved.get("quoteSnapshot");
        assertEquals(3, ((List<?>) snapshot.get("lines")).size());
        long id = ((Number) saved.get("id")).longValue();
        quotes.transition(approved.id(), "Superseded");
        rentals.update("Individual", id, Map.of("notes", "Preserve contracted rates"));
        Map<String, Object> browserSnapshot = new LinkedHashMap<>();
        snapshot.forEach((key, value) -> browserSnapshot.put(key.toString(), value));
        browserSnapshot.put("taxPercent", 15);
        rentals.update("Individual", id, Map.of("quoteSnapshot", browserSnapshot, "notes", "Browser numeric values are equivalent"));
        assertThrows(ApiException.class, () -> rentals.update("Individual", id, Map.of("totalAmount", 1)));
    }

    @Test void apiReturnsPersistedRecordsAndValidationErrors() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Map<String, Object> request = individual("Advance", "Pending", LocalDateTime.now(java.time.ZoneId.of("Asia/Riyadh")).plusDays(2).withSecond(0).withNano(0));
        mvc.perform(post("/api/bookings").contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").exists()).andExpect(jsonPath("$.status").value("Pending"));
        mvc.perform(get("/api/bookings")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(post("/api/bookings").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/invoices")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
