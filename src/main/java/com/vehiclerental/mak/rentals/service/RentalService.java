package com.vehiclerental.mak.rentals.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.customers.entity.Customer;
import com.vehiclerental.mak.customers.entity.Quote;
import com.vehiclerental.mak.customers.repository.CustomerRepository;
import com.vehiclerental.mak.customers.repository.QuoteRepository;
import com.vehiclerental.mak.drivers.entity.Driver;
import com.vehiclerental.mak.drivers.repository.DriverRepository;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.invoices.repository.InvoiceRepository;
import com.vehiclerental.mak.invoices.repository.InvoiceSourceRepository;
import com.vehiclerental.mak.notifications.booking.service.BookingNotificationService;
import com.vehiclerental.mak.notifications.email.service.EmailService;
import com.vehiclerental.mak.rentals.entity.RentalPayment;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalPaymentRepository;
import com.vehiclerental.mak.rentals.repository.RentalRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class RentalService {
    private final RentalRepository rentals;
    private final RentalPaymentRepository payments;
    private final CustomerRepository customers;
    private final QuoteRepository quotes;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final InvoiceSourceRepository invoiceSources;
    private final InvoiceRepository invoices;
    private final ObjectMapper json;
    private final EmailService emails;
    private final BookingNotificationService notifications;

    public RentalService(RentalRepository rentals, RentalPaymentRepository payments, CustomerRepository customers,
            QuoteRepository quotes, VehicleRepository vehicles, DriverRepository drivers,
            InvoiceSourceRepository invoiceSources, InvoiceRepository invoices, ObjectMapper json, EmailService emails, BookingNotificationService notifications) {
        this.rentals = rentals; this.payments = payments; this.customers = customers; this.quotes = quotes;
        this.vehicles = vehicles; this.drivers = drivers; this.invoiceSources = invoiceSources; this.invoices = invoices; this.json = json; this.emails = emails; this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        return rentals.findByKindOrderByIdDesc(kind).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String kind, long id) { return response(require(kind, id)); }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> create(String kind, Map<String, Object> request) {
        request = new LinkedHashMap<>(request);
        request.remove("handedOverAt"); request.remove("actualReturnAt");
        RentalRecord record = new RentalRecord();
        record.setKind(kind); record.setCreatedAt(Instant.now());
        apply(record, request, false);
        stampLifecycle(record, request);
        rentals.saveAndFlush(record);
        record.setReference((kind.equals("Corporate") ? "CT-" : "BK-") + (kind.equals("Corporate") ? 3000 + record.getId() : 2000 + record.getId()));
        if ("Individual".equals(kind)) {
            BigDecimal deposit = money(request.getOrDefault("receivedAmount", 0));
            if (deposit.signum() < 0 || deposit.compareTo(record.getTotalAmount()) > 0) throw bad("Invalid booking deposit.");
            if (deposit.signum() > 0) {
                RentalPayment entry = new RentalPayment();
                entry.setRentalId(record.getId()); entry.setKind("Payment"); entry.setAmount(deposit); entry.setCreatedAt(Instant.now());
                payments.save(entry); record.setReceivedAmount(deposit);
            }
        }
        record.setPayload(json.writeValueAsString(merge(request, Map.of())));
        rentals.saveAndFlush(record);
        syncResources(record);
        Map<String,Object> saved = response(record);
        emails.rental(kind, saved, "Booking created");
        notifications.bookingChanged(record, "Created");
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> update(String kind, long id, Map<String, Object> request) {
        RentalRecord record = requireLocked(kind, id);
        if (Set.of("Completed", "Cancelled").contains(record.getStatus()) || invoiceSources.existsByIdRentalId(id))
            throw conflict("Completed, cancelled, or invoiced rentals cannot be edited.");
        Map<String, Object> current = response(record);
        Map<String, Object> merged = merge(current, request);
        merged.put("receivedAmount", record.getReceivedAmount()); merged.put("refundedAmount", record.getRefundedAmount());
        merged.put("handedOverAt", current.getOrDefault("handedOverAt", ""));
        merged.remove("actualReturnAt");
        String nextStatus = text(merged.get("status"));
        if (!record.getStatus().equals(nextStatus)) validateTransition(record.getStatus(), nextStatus);
        if ("Active".equals(record.getStatus()) && (!java.util.Objects.equals(record.getVehicleId(), numberId(merged.get("vehicleId")))
                || !java.util.Objects.equals(record.getDriverId(), numberId(merged.get("driverId")))))
            throw conflict("Complete the rental before changing assigned resources.");
        record.setStatus(nextStatus);
        stampLifecycle(record, merged);
        apply(record, merged, true);
        record.setPayload(json.writeValueAsString(merged));
        rentals.saveAndFlush(record);
        syncResources(record);
        Map<String,Object> saved = response(record);
        emails.rental(kind, saved, "Booking updated");
        String notificationType = "Cancelled".equals(saved.get("status")) ? "Cancelled"
                : !java.util.Objects.equals(current.get("pickupAt"), saved.get("pickupAt")) || !java.util.Objects.equals(current.get("returnAt"), saved.get("returnAt")) ? "Rescheduled"
                : !java.util.Objects.equals(current.get("status"), saved.get("status")) ? "Status"
                : !java.util.Objects.equals(current.get("vehicleId"), saved.get("vehicleId")) || !java.util.Objects.equals(current.get("driverId"), saved.get("driverId")) ? "Assigned" : "Updated";
        notifications.bookingChanged(record, notificationType);
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> transition(String kind, long id, String status, Map<String, Object> changes) {
        RentalRecord record = requireLocked(kind, id);
        String from = record.getStatus();
        validateTransition(from, status);
        Map<String, Object> merged = response(record);
        merged.put("status", status);
        if ("Active".equals(status) && (numberId(merged.get("vehicleId")) == null
                || ("Corporate".equals(kind) && !"Self-drive".equals(text(merged.get("serviceMode"))) && numberId(merged.get("driverId")) == null)
                || ("Individual".equals(kind) && !"Self-drive".equals(text(merged.get("driverMode"))) && numberId(merged.get("driverId")) == null)))
            throw bad("Assign the required vehicle and driver before handover.");
        if ("Active".equals(status) && record.getPickupAt().toLocalDate().isAfter(LocalDate.now()))
            throw bad("Handover is only available on or after the pickup day.");
        record.setStatus(status);
        stampLifecycle(record, merged);
        if (!Set.of("Completed", "Cancelled").contains(status)) apply(record, merged, true);
        record.setPayload(json.writeValueAsString(merged));
        rentals.saveAndFlush(record);
        syncResources(record);
        Map<String,Object> saved = response(record);
        emails.rental(kind, saved, "Trip status updated");
        notifications.bookingChanged(record, status.equals("Cancelled") ? "Cancelled" : "Status");
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> assign(String kind, long id, Map<String, Object> changes) {
        RentalRecord record = requireLocked(kind, id);
        if (!"Confirmed".equals(record.getStatus())) throw conflict("Only confirmed rentals can be assigned.");
        if (record.getPickupAt().toLocalDate().isAfter(LocalDate.now())) throw bad("Assign advance rentals on the pickup day.");
        Map<String, Object> merged = response(record);
        for (String key : List.of("vehicleId", "driverId", "driverMode"))
            if (changes.containsKey(key)) merged.put(key, changes.get(key));
        merged.put("status", Boolean.TRUE.equals(changes.get("handedOver")) ? "Active" : "Confirmed");
        if ("Active".equals(merged.get("status"))) {
            if (numberId(merged.get("vehicleId")) == null) throw bad("Select a vehicle for handover.");
            if (!"Self-drive".equals(text(merged.get(kind.equals("Corporate") ? "serviceMode" : "driverMode")))
                    && numberId(merged.get("driverId")) == null) throw bad("Select a driver for handover.");
            merged.put("handedOverAt", LocalDateTime.now().toString());
        }
        record.setStatus(text(merged.get("status")));
        apply(record, merged, true);
        record.setPayload(json.writeValueAsString(merged));
        rentals.saveAndFlush(record);
        syncResources(record);
        Map<String,Object> saved = response(record);
        emails.rental(kind, saved, "Vehicle and driver assignment updated");
        notifications.bookingChanged(record, "Assigned");
        return saved;
    }

    @Transactional
    public Map<String, Object> payment(long id, String kind, BigDecimal amount) {
        RentalRecord record = requireLocked("Individual", id);
        if (kind == null || !Set.of("Payment", "Refund").contains(kind) || amount == null || amount.signum() <= 0 || amount.scale() > 2)
            throw bad("Enter a valid payment or refund amount.");
        if ("Cancelled".equals(record.getStatus()) && "Payment".equals(kind)) throw bad("Cancelled bookings only accept refunds.");
        if (invoiceSources.existsByIdRentalId(id)) throw conflict("Record payments on the invoice after invoicing.");
        BigDecimal paid = record.getReceivedAmount().subtract(record.getRefundedAmount());
        if ("Refund".equals(kind) && amount.compareTo(paid) > 0) throw bad("Refund exceeds the net amount paid.");
        if ("Payment".equals(kind) && paid.add(amount).compareTo(record.getTotalAmount()) > 0) throw bad("Payment exceeds the booking amount.");
        if ("Payment".equals(kind)) record.setReceivedAmount(record.getReceivedAmount().add(amount));
        else record.setRefundedAmount(record.getRefundedAmount().add(amount));
        RentalPayment entry = new RentalPayment();
        entry.setRentalId(id); entry.setKind(kind); entry.setAmount(amount); entry.setCreatedAt(Instant.now());
        payments.save(entry);
        return response(rentals.saveAndFlush(record));
    }

    private void apply(RentalRecord record, Map<String, Object> data, boolean updating) {
        String kind = record.getKind();
        String type = text(data.get("bookingType"));
        String status = text(data.get("status"));
        if (!Set.of("Quick", "Advance").contains(type)) throw bad("Booking type must be Quick or Advance.");
        if (!Set.of("Pending", "Confirmed", "Active", "Completed", "Cancelled").contains(status)) throw bad("Invalid status.");
        if (!updating && !(Set.of("Pending", "Confirmed").contains(status) || ("Quick".equals(type) && "Active".equals(status))))
            throw bad("New rentals must be pending, confirmed, or an active quick handover.");
        if (updating && !record.getStatus().equals(status)) throw conflict("Use the status action to change booking status.");
        LocalDateTime pickup = dateTime(data.get("pickupAt"));
        LocalDateTime returned = dateTime(data.get("returnAt"));
        if (!returned.isAfter(pickup)) throw bad("Return must be after pickup.");
        if ("Advance".equals(type) && !updating && !pickup.isAfter(LocalDateTime.now()))
            throw bad("Advance pickup must be in the future.");
        BigDecimal total = money(data.get("totalAmount"));
        if (total.signum() <= 0) throw bad("Total amount must be positive.");
        if (updating && record.getReceivedAmount().subtract(record.getRefundedAmount()).compareTo(total) > 0)
            throw bad("Booking total cannot be below the payments already received.");
        validateDetails(kind, data, status);
        if ("Corporate".equals(kind) && data.get("quoteSnapshot") == null) {
            BigDecimal base = nonNegative(data.get("unitPrice")).multiply(nonNegative(data.get("quantity"))).setScale(2, RoundingMode.HALF_UP);
            if (nonNegative(data.get("quantity")).signum() <= 0) throw bad("Quantity must be positive.");
            BigDecimal waiting = waitingCharge(data);
            BigDecimal subtotal = base.add(waiting);
            BigDecimal tax = percent(data.get("taxPercent"));
            BigDecimal taxAmount = subtotal.multiply(tax).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            BigDecimal calculated = subtotal.add(taxAmount);
            if (calculated.compareTo(total) != 0) throw bad("Corporate trip total does not match its rates, waiting time, and tax.");
            data.put("base", base); data.put("waiting", waiting); data.put("subtotal", subtotal); data.put("taxAmount", taxAmount);
        }
        Long customerId = numberId(data.get(kind.equals("Corporate") ? "companyId" : "customerId"));
        if (customerId != null) {
            Customer customer = customers.findById(customerId).orElseThrow(() -> bad("Customer not found."));
            if (!kind.equals(customer.getType()) || !"Active".equals(customer.getStatus())) throw bad("Select an active customer of the correct type.");
            if ("Corporate".equals(kind)) data.put("companyName", customer.getName());
        } else if ("Corporate".equals(kind)) throw bad("Select a corporate account.");
        if (kind.equals("Individual") && (text(data.get("customerName")).isBlank() || text(data.get("customerPhone")).isBlank()))
            throw bad("Customer name and phone are required.");
        Long vehicleId = numberId(data.get("vehicleId"));
        Long driverId = numberId(data.get("driverId"));
        if ("Advance".equals(type) && !updating && (vehicleId != null || driverId != null))
            throw bad("Advance rentals are assigned on the pickup day.");
        if ("Advance".equals(type) && pickup.toLocalDate().isAfter(LocalDate.now()) && (vehicleId != null || driverId != null))
            throw bad("Advance rentals are assigned on the pickup day.");
        if ("Quick".equals(type) && vehicleId == null) throw bad("Quick rentals require a vehicle.");
        if ("Active".equals(status) && vehicleId == null) throw bad("Active rentals require a vehicle.");
        if ("Active".equals(status) && !"Self-drive".equals(text(data.get(kind.equals("Corporate") ? "serviceMode" : "driverMode")))
                && driverId == null) throw bad("Active rentals require a driver unless self-drive.");
        if ("Active".equals(status) && pickup.toLocalDate().isAfter(LocalDate.now())) throw bad("Handover is only available on the pickup day.");
        String mode = text(data.get(kind.equals("Corporate") ? "serviceMode" : "driverMode"));
        if (!(kind.equals("Corporate") ? Set.of("With driver", "Self-drive") : Set.of("Assigned", "Unassigned", "Self-drive")).contains(mode))
            throw bad("Select a valid driver arrangement.");
        if ("Self-drive".equals(mode) && driverId != null) throw bad("Self-drive rentals cannot have an assigned driver.");
        if ("Individual".equals(kind) && "Assigned".equals(mode) && driverId == null) throw bad("Select an assigned driver.");
        if ("Quick".equals(type) && "With driver".equals(mode) && driverId == null) throw bad("Quick trips require a driver.");
        if (vehicleId != null && !Set.of("Completed", "Cancelled").contains(status)) {
            Vehicle vehicle = vehicles.lockById(vehicleId).orElseThrow(() -> bad("Vehicle not found."));
            if (!Set.of("Available", "In Use").contains(vehicle.getStatus())) throw bad("Vehicle is unavailable.");
            if ("Advance".equals(type) && !text(data.get("requestedType")).equals(vehicle.getType()))
                throw bad("Select a vehicle of the requested type.");
            if ("Corporate".equals(kind) && integer(data.get("passengerCount"), 1, 1000) > vehicle.getSeats())
                throw bad("The vehicle does not have enough passenger seats.");
        }
        if (driverId != null && !Set.of("Completed", "Cancelled").contains(status)) {
            Driver driver = drivers.findById(driverId).orElseThrow(() -> bad("Driver not found."));
            if (driver.isArchived() || !"Active".equals(driver.getStatus())) throw bad("Driver is inactive.");
        }
        if (!Set.of("Cancelled", "Completed").contains(status) && (vehicleId != null || driverId != null)
                && !rentals.conflicts(record.getId() == null ? -1 : record.getId(), vehicleId == null ? -1L : vehicleId,
                        driverId == null ? -1L : driverId, pickup, returned, LocalDateTime.now()).isEmpty())
            throw conflict("The vehicle or driver is already assigned during this period.");
        Map<String, Object> before = updating ? json.readValue(record.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {}) : Map.of();
        if (!updating || !equivalent(before.get("quoteSnapshot"), data.get("quoteSnapshot")))
            verifyQuote(data, customerId, pickup, total);
        else if (data.get("quoteSnapshot") instanceof Map<?, ?> snapshot
                && (money(snapshot.containsKey("total") ? snapshot.get("total") : snapshot.get("totalAmount")).compareTo(total) != 0
                || !java.util.Objects.equals(record.getCustomerId(), customerId)
                || !record.getPickupAt().toLocalDate().equals(pickup.toLocalDate())
                || !java.util.Objects.equals(before.get("pickupLocation"), data.get("pickupLocation"))
                || !java.util.Objects.equals(before.get("destination"), data.get("destination"))
                || List.of("extraKm", "overtimeHours", "extraDriver", "oneWayKm", "stops", "waitingRatePerHour",
                        "includedWaitingMinutes", "waitingBillingIncrementMinutes").stream()
                        .anyMatch(key -> !equivalent(before.get(key), data.get(key)))))
            throw bad("Reapply the agreed quote after changing customer, schedule, route, or amount.");
        record.setBookingType(type); record.setStatus(status); record.setCustomerId(customerId);
        record.setVehicleId(vehicleId); record.setDriverId(driverId); record.setPickupAt(pickup);
        record.setReturnAt(returned); record.setTotalAmount(total);
        if (!updating) { record.setReceivedAmount(BigDecimal.ZERO); record.setRefundedAmount(BigDecimal.ZERO); }
        record.setPayload(json.writeValueAsString(data));
    }

    @SuppressWarnings("unchecked")
    private void verifyQuote(Map<String, Object> data, Long customerId, LocalDateTime pickup, BigDecimal total) {
        if (!(data.get("quoteSnapshot") instanceof Map<?, ?> raw)) return;
        Map<String, Object> snapshot = (Map<String, Object>) raw;
        String id = text(snapshot.get("quoteId"));
        Quote quote = quotes.findById(id).orElseThrow(() -> bad("Linked quote not found."));
        if (!"Approved".equals(quote.getStatus()) || customerId == null || !quote.getCustomer().getId().equals(customerId))
            throw bad("Choose an approved quote for this customer.");
        LocalDate date = pickup.toLocalDate();
        if ((quote.getEffectiveFrom() != null && date.isBefore(quote.getEffectiveFrom()))
                || (quote.getEffectiveTo() != null && date.isAfter(quote.getEffectiveTo())))
            throw bad("The approved quote is not effective on the pickup date.");
        if (!text(snapshot.get("quoteNumber")).equals(quote.getNumber()) || !text(snapshot.get("version")).equals(Integer.toString(quote.getVersion())))
            throw bad("Quote reference or version does not match.");
        List<Map<String, Object>> rules = json.readValue(quote.getRoutesJson(), new TypeReference<List<Map<String, Object>>>() {});
        Map<String, Object> rule = rules.stream().filter(row -> text(row.get("id")).equals(text(snapshot.get("ruleId"))))
                .findFirst().orElseThrow(() -> bad("The selected quote rate was not found."));
        if (!text(rule.get("vehicle")).equalsIgnoreCase(text(snapshot.get("vehicle"))))
            throw bad("Quoted vehicle class does not match.");
        if (snapshot.containsKey("from") && (!text(rule.get("from")).equalsIgnoreCase(text(snapshot.get("from")))
                || !text(rule.get("to")).equalsIgnoreCase(text(snapshot.get("to")))))
            throw bad("Quoted route does not match.");
        if (!(snapshot.get("lines") instanceof List<?> lines) || lines.isEmpty() || !(lines.get(0) instanceof Map<?, ?> first)
                || money(first.get("unitPrice")).compareTo(money(rule.get("amount"))) != 0)
            throw bad("Quoted base price does not match the approved rate.");
        String from = text(data.get("pickupLocation")), to = text(data.get("destination"));
        String via = "";
        if (data.get("stops") instanceof List<?> stops && stops.size() > 2) {
            via = stops.subList(1, stops.size() - 1).stream().map(stop -> text(((Map<?, ?>) stop).get("location")))
                    .collect(java.util.stream.Collectors.joining(","));
        }
        if (!normalize(from).equals(normalize(text(rule.get("from")))) || !normalize(to).equals(normalize(text(rule.get("to"))))
                || !normalizeStops(via).equals(normalizeStops(text(rule.get("via")))))
            throw bad("The booking route does not match the approved rate.");
        Map<String, Object> extras = json.readValue(quote.getExtrasJson(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> canonical = new ArrayList<>();
        BigDecimal base = money(rule.get("amount"));
        canonical.add(Map.of("description", from + (via.isBlank() ? "" : " Ã¢â€ â€™ " + via) + " Ã¢â€ â€™ " + to + " Ã‚Â· " + text(rule.get("vehicle")),
                "unit", "Trip", "quantity", 1, "unitPrice", base, "ruleId", text(rule.get("id"))));
        BigDecimal subtotal = base;
        BigDecimal km = nonNegative(data.getOrDefault("extraKm", 0));
        BigDecimal kmRate = optionalAmount(extras.get("extraKmRate"));
        if (km.signum() > 0 && kmRate.signum() > 0) {
            canonical.add(Map.of("description", "Outside / extra distance", "unit", "km", "quantity", km, "unitPrice", kmRate));
            subtotal = subtotal.add(km.multiply(kmRate));
        }
        BigDecimal hours = nonNegative(data.getOrDefault("overtimeHours", 0));
        BigDecimal hourly = optionalAmount(extras.get("overtimeRate"));
        if (hours.signum() > 0 && hourly.signum() > 0) {
            canonical.add(Map.of("description", "Overtime", "unit", "hour", "quantity", hours, "unitPrice", hourly));
            subtotal = subtotal.add(hours.multiply(hourly));
        }
        BigDecimal driverRate = optionalAmount(extras.get("extraDriverRate"));
        BigDecimal threshold = optionalAmount(extras.get("extraDriverThresholdKm"));
        if (Boolean.TRUE.equals(data.get("extraDriver")) && driverRate.signum() > 0
                && (threshold.signum() == 0 || nonNegative(data.getOrDefault("oneWayKm", 0)).compareTo(threshold) > 0)) {
            canonical.add(Map.of("description", "Additional driver", "unit", "service", "quantity", 1, "unitPrice", driverRate));
            subtotal = subtotal.add(driverRate);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        if (data.containsKey("companyId")) {
            BigDecimal waiting = waitingCharge(data);
            if (waiting.signum() > 0) canonical.add(Map.of("description", "Billable waiting", "unit", "Service", "quantity", 1, "unitPrice", waiting));
            subtotal = subtotal.add(waiting);
        }
        BigDecimal taxAmount = subtotal.multiply(quote.getTaxPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal calculated = subtotal.add(taxAmount);
        if (calculated.compareTo(total) != 0 || money(snapshot.getOrDefault("total", snapshot.get("totalAmount"))).compareTo(total) != 0)
            throw bad("Booking total does not match the quoted amount.");
        Map<String, Object> approved = new LinkedHashMap<>(snapshot);
        approved.put("lines", canonical); approved.put("taxPercent", quote.getTaxPercent());
        approved.put("subtotal", subtotal); approved.put("taxAmount", taxAmount);
        approved.put(data.containsKey("companyId") ? "totalAmount" : "total", calculated);
        data.put("quoteSnapshot", approved); data.put("chargeLines", canonical); data.put("taxPercent", quote.getTaxPercent());
    }

    private RentalRecord require(String kind, long id) {
        RentalRecord record = rentals.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rental not found."));
        if (!kind.equals(record.getKind())) throw new ApiException(HttpStatus.NOT_FOUND, "Rental not found.");
        return record;
    }
    private RentalRecord requireLocked(String kind, long id) {
        RentalRecord record = rentals.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rental not found."));
        if (!kind.equals(record.getKind())) throw new ApiException(HttpStatus.NOT_FOUND, "Rental not found.");
        return record;
    }
    private Map<String, Object> response(RentalRecord record) {
        Map<String, Object> result = json.readValue(record.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {});
        result.put("id", record.getId());
        result.put(record.getKind().equals("Corporate") ? "tripId" : "bookingId", record.getReference());
        result.put("status", record.getStatus());
        result.put("bookingType", record.getBookingType());
        result.put("vehicleId", record.getVehicleId());
        result.put("driverId", record.getDriverId());
        result.put("totalAmount", record.getTotalAmount());
        result.put("receivedAmount", record.getReceivedAmount());
        result.put("refundedAmount", record.getRefundedAmount());
        result.put("bookedAt", record.getCreatedAt().toString());
        result.put(record.getKind().equals("Corporate") ? "companyId" : "customerId", record.getCustomerId());
        invoices.findForRental(record.getId()).ifPresent(invoice -> {
            result.put("invoiceId", invoice.getId());
            if ("Individual".equals(record.getKind())) result.put("invoicedAmount", invoice.getTotalAmount());
        });
        return result;
    }
    private Map<String, Object> merge(Map<String, Object> original, Map<String, Object> updates) {
        Map<String, Object> result = new LinkedHashMap<>(original); result.putAll(updates); return result;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
            return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> equivalent(a.get(key), b.get(key)));
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int index = 0; index < a.size(); index++) if (!equivalent(a.get(index), b.get(index))) return false;
            return true;
        }
        return java.util.Objects.equals(left, right);
    }
    private Long numberId(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { long id = Long.parseLong(text(value)); if (id <= 0) throw bad("Invalid record ID."); return id; }
        catch (NumberFormatException e) { throw bad("Invalid record ID."); }
    }
    private int integer(Object value, int min, int max) {
        try { int number = Integer.parseInt(text(value)); if (number < min || number > max) throw bad("Invalid passenger count."); return number; }
        catch (NumberFormatException e) { throw bad("Invalid passenger count."); }
    }
    private String normalize(String value) { return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private String normalizeStops(String value) {
        return java.util.Arrays.stream(value.split(",")).map(this::normalize).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(","));
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value, int min, int max, String label) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max || list.stream().anyMatch(row -> !(row instanceof Map<?, ?>)))
            throw bad("Enter valid " + label + ".");
        return (List<Map<String, Object>>) value;
    }
    private void validateDetails(String kind, Map<String, Object> data, String status) {
        String email = text(data.get(kind.equals("Corporate") ? "contactEmail" : "customerEmail"));
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 150)
            throw bad("Enter a valid customer contact email.");
        if (text(data.get("pickupLocation")).isBlank() || text(data.get("destination")).isBlank())
            throw bad("Pickup location and destination are required.");
        if ("Corporate".equals(kind)) {
            if (text(data.get("contactName")).isBlank() || text(data.get("contactPhone")).isBlank()
                    || text(data.get("projectManager")).isBlank() || text(data.get("projectCode")).isBlank())
                throw bad("Complete the corporate contact and project details.");
            int count = integer(data.get("passengerCount"), 1, 1000);
            List<Map<String, Object>> passengers = rows(data.get("passengers"), 1, count, "passengers");
            for (Map<String, Object> passenger : passengers)
                if (text(passenger.get("name")).isBlank() || text(passenger.get("phone")).isBlank()) throw bad("Complete every passenger name and phone.");
            if ("Active".equals(status) && passengers.size() != count) throw bad("Complete the passenger list before handover.");
            List<Map<String, Object>> stops = rows(data.get("stops"), 2, 100, "itinerary stops");
            for (Map<String, Object> stop : stops) {
                if (text(stop.get("location")).isBlank()) throw bad("Enter each stop location.");
                integer(stop.getOrDefault("waitingMinutes", 0), 0, 100000);
            }
            data.put("pickupLocation", text(stops.get(0).get("location")));
            data.put("destination", text(stops.get(stops.size() - 1).get("location")));
            waitingCharge(data);
            percent(data.get("taxPercent"));
        }
    }
    private BigDecimal waitingCharge(Map<String, Object> data) {
        int minutes = rows(data.get("stops"), 2, 100, "itinerary stops").stream()
                .mapToInt(stop -> integer(stop.getOrDefault("waitingMinutes", 0), 0, 100000)).sum();
        int included = integer(data.getOrDefault("includedWaitingMinutes", 0), 0, 100000);
        int increment = integer(data.getOrDefault("waitingBillingIncrementMinutes", 15), 1, 1440);
        int billable = ((Math.max(0, minutes - included) + increment - 1) / increment) * increment;
        return nonNegative(data.getOrDefault("waitingRatePerHour", 0)).multiply(BigDecimal.valueOf(billable))
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
    }
    private BigDecimal optionalAmount(Object value) { return text(value).isBlank() ? BigDecimal.ZERO : nonNegative(value); }
    private BigDecimal nonNegative(Object value) {
        BigDecimal amount = money(value); if (amount.signum() < 0) throw bad("Amounts and quantities cannot be negative."); return amount;
    }
    private BigDecimal percent(Object value) {
        BigDecimal amount = nonNegative(value); if (amount.compareTo(new BigDecimal("100")) > 0) throw bad("Tax rate must be between 0 and 100."); return amount;
    }
    private void validateTransition(String from, String to) {
        if (!(("Pending".equals(from) && Set.of("Confirmed", "Cancelled").contains(to))
                || ("Confirmed".equals(from) && Set.of("Active", "Cancelled").contains(to))
                || ("Active".equals(from) && "Completed".equals(to))))
            throw conflict("Invalid booking status transition.");
    }
    private void stampLifecycle(RentalRecord record, Map<String, Object> data) {
        if ("Active".equals(record.getStatus()) && text(data.get("handedOverAt")).isBlank())
            data.put("handedOverAt", LocalDateTime.now().toString());
        if ("Completed".equals(record.getStatus())) data.put("actualReturnAt", LocalDateTime.now().toString());
    }
    private void syncResources(RentalRecord record) {
        if (record.getVehicleId() != null) {
            Vehicle vehicle = vehicles.lockById(record.getVehicleId()).orElseThrow(() -> bad("Vehicle not found."));
            if ("Active".equals(record.getStatus())) vehicle.setStatus("In Use");
            else if (Set.of("Completed", "Cancelled").contains(record.getStatus()) && "In Use".equals(vehicle.getStatus())
                    && rentals.countByVehicleIdAndStatus(vehicle.getId(), "Active") == 0) vehicle.setStatus("Available");
            vehicles.save(vehicle);
        }
        if (record.getDriverId() != null) {
            Driver driver = drivers.findById(record.getDriverId()).orElseThrow(() -> bad("Driver not found."));
            driver.setBookingCount((int) rentals.countByDriverId(driver.getId()));
            drivers.save(driver);
        }
    }
    private BigDecimal money(Object value) {
        try { BigDecimal n = new BigDecimal(text(value)); if (n.stripTrailingZeros().scale() > 2 || n.abs().compareTo(new BigDecimal("999999999999.99")) > 0) throw bad("Invalid amount."); return n; }
        catch (NumberFormatException e) { throw bad("Invalid amount."); }
    }
    private LocalDateTime dateTime(Object value) {
        try { return LocalDateTime.parse(text(value)); }
        catch (Exception e) { throw bad("Enter a valid pickup and return date and time."); }
    }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
