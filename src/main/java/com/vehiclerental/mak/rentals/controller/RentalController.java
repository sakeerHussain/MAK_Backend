package com.vehiclerental.mak.rentals.controller;

import com.vehiclerental.mak.rentals.dto.request.RentalPaymentRequest;
import com.vehiclerental.mak.rentals.service.RentalService;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class RentalController {
    private final com.vehiclerental.mak.auth.service.AccountService accounts;
    private final RentalService service;
    public RentalController(RentalService service,com.vehiclerental.mak.auth.service.AccountService accounts) { this.accounts=accounts;this.service = service; }
    @GetMapping("/api/bookings") public List<Map<String, Object>> bookings() { return service.list("Individual"); }
    @GetMapping("/api/corporate-trips") public List<Map<String, Object>> trips() { return service.list("Corporate"); }
    @GetMapping("/api/bookings/{id}") public Map<String, Object> booking(@PathVariable long id) { return service.get("Individual", id); }
    @GetMapping("/api/corporate-trips/{id}") public Map<String, Object> trip(@PathVariable long id) { return service.get("Corporate", id); }
    @PostMapping("/api/bookings") public Map<String, Object> addBooking(@RequestBody Map<String, Object> data) { requireDepositPermission(data); return service.create("Individual", data); }
    @PostMapping("/api/corporate-trips") public Map<String, Object> addTrip(@RequestBody Map<String, Object> data) { return service.create("Corporate", data); }
    @PutMapping("/api/bookings/{id}") public Map<String, Object> editBooking(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.update("Individual", id, data); }
    @PutMapping("/api/corporate-trips/{id}") public Map<String, Object> editTrip(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.update("Corporate", id, data); }
    @PatchMapping("/api/bookings/{id}/status") public Map<String, Object> bookingStatus(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.transition("Individual", id, String.valueOf(data.get("status")), data); }
    @PatchMapping("/api/corporate-trips/{id}/status") public Map<String, Object> tripStatus(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.transition("Corporate", id, String.valueOf(data.get("status")), data); }
    @PatchMapping("/api/bookings/{id}/assignment") public Map<String, Object> assignBooking(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.assign("Individual", id, data); }
    @PatchMapping("/api/corporate-trips/{id}/assignment") public Map<String, Object> assignTrip(@PathVariable long id, @RequestBody Map<String, Object> data) { return service.assign("Corporate", id, data); }
    /** Collecting money is a Finance action: payments need payments.record, refunds need payments.refund. */
    @PostMapping("/api/bookings/{id}/payments") public Map<String, Object> bookingPayment(@PathVariable long id, @RequestBody RentalPaymentRequest data) { accounts.requirePermission("Refund".equals(data.kind())?"payments.refund":"payments.record");return service.payment(id, data.kind(), data.amount()); }

    /** A deposit at creation is a payment; booking creators without payments.record cannot record one. */
    private void requireDepositPermission(Map<String, Object> data) {
        Object deposit = data.get("receivedAmount");
        if (deposit == null || String.valueOf(deposit).isBlank()) return;
        boolean positive;
        try { positive = new java.math.BigDecimal(String.valueOf(deposit).trim()).signum() != 0; }
        catch (NumberFormatException invalid) { positive = true; }
        if (positive) accounts.requirePermission("payments.record");
    }

}
