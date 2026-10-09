package com.vehiclerental.mak.invoices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.rentals.RentalRecord;
import com.vehiclerental.mak.rentals.RentalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvoiceService {
    private final InvoiceRepository invoices;
    private final InvoiceSourceRepository sources;
    private final InvoicePaymentRepository payments;
    private final RentalRepository rentals;
    private final ObjectMapper json;
    private final com.vehiclerental.mak.notifications.EmailService emails;
    public InvoiceService(InvoiceRepository invoices, InvoiceSourceRepository sources,
            InvoicePaymentRepository payments, RentalRepository rentals, ObjectMapper json, com.vehiclerental.mak.notifications.EmailService emails) {
        this.invoices = invoices; this.sources = sources; this.payments = payments; this.rentals = rentals; this.json = json; this.emails = emails;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() { return invoices.findAll().stream().sorted((a,b) -> b.getId().compareTo(a.getId())).map(this::response).toList(); }
    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) { return response(require(id)); }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> create(Map<String, Object> request, boolean issue) {
        String kind = text(request.get("kind"));
        List<RentalRecord> linked = validateSources(kind, request, null);
        InvoiceRecord invoice = new InvoiceRecord();
        invoice.setKind(kind); invoice.setStatus("Draft"); invoice.setCreatedAt(Instant.now());
        setDraft(invoice, request, linked);
        invoice = invoices.saveAndFlush(invoice);
        for (RentalRecord rental : linked) {
            InvoiceSource source = new InvoiceSource();
            source.setId(new InvoiceSourceId(invoice.getId(), rental.getId()));
            sources.save(source);
        }
        if ("Individual".equals(kind)) {
            BigDecimal opening = linked.get(0).getReceivedAmount().subtract(linked.get(0).getRefundedAmount());
            if (opening.compareTo(invoice.getTotalAmount()) > 0) throw bad("Invoice total cannot be below the booking payments already received.");
            if (opening.signum() > 0) {
                InvoicePayment payment = new InvoicePayment();
                payment.setInvoiceId(invoice.getId()); payment.setKind("Payment"); payment.setAmount(opening);
                payment.setPaidOn(LocalDate.now()); payment.setMethod("Opening balance");
                payment.setReference("Recorded on " + linked.get(0).getReference()); payments.save(payment);
                invoice.setPaidAmount(opening);
            }
        }
        if (issue) issueRecord(invoice);
        Map<String,Object> saved = response(invoices.saveAndFlush(invoice));
        if (issue) emails.invoice(saved);
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> update(long id, Map<String, Object> request, boolean issue) {
        InvoiceRecord invoice = requireLocked(id);
        if (!"Draft".equals(invoice.getStatus())) throw conflict("Only draft invoices can be edited.");
        List<RentalRecord> linked = validateSources(invoice.getKind(), request, id);
        List<Long> oldIds = sources.findByIdInvoiceId(id).stream().map(source -> source.getId().getRentalId()).sorted().toList();
        List<Long> newIds = linked.stream().map(RentalRecord::getId).sorted().toList();
        if (!oldIds.equals(newIds)) throw bad("Invoice sources cannot be changed after draft creation.");
        setDraft(invoice, request, linked);
        if (invoice.getPaidAmount().compareTo(invoice.getTotalAmount()) > 0)
            throw bad("Invoice total cannot be below the amount paid.");
        if (issue) issueRecord(invoice);
        Map<String,Object> saved = response(invoices.saveAndFlush(invoice));
        if (issue) emails.invoice(saved);
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> issue(long id) {
        InvoiceRecord invoice = requireLocked(id);
        if (!"Draft".equals(invoice.getStatus())) throw conflict("Only draft invoices can be issued.");
        issueRecord(invoice);
        Map<String,Object> saved = response(invoices.saveAndFlush(invoice));
        emails.invoice(saved);
        return saved;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> payment(long id, Map<String, Object> request) {
        InvoiceRecord invoice = requireLocked(id);
        if (!"Issued".equals(invoice.getStatus())) throw conflict("Issue the invoice before recording payments.");
        String kind = text(request.get("kind"));
        BigDecimal amount = amount(request.get("amount"));
        if (!Set.of("Payment", "Refund").contains(kind) || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
            throw bad("Enter a payment or refund in SAR with at most two decimal places.");
        BigDecimal next = "Payment".equals(kind) ? invoice.getPaidAmount().add(amount) : invoice.getPaidAmount().subtract(amount);
        if (next.signum() < 0 || next.compareTo(invoice.getTotalAmount()) > 0)
            throw bad("Payment or refund exceeds the permitted balance.");
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(id); payment.setKind(kind); payment.setAmount(amount);
        try { payment.setPaidOn(LocalDate.parse(text(request.get("date")))); }
        catch (Exception e) { throw bad("Enter a valid payment date."); }
        if (payment.getPaidOn().isAfter(LocalDate.now())) throw bad("Payment date cannot be in the future.");
        if (text(request.get("method")).isBlank() || text(request.get("method")).length() > 80 || text(request.get("reference")).length() > 120)
            throw bad("Enter a payment method and a reference of at most 120 characters.");
        payment.setMethod(text(request.get("method"))); payment.setReference(text(request.get("reference")));
        payments.save(payment);
        invoice.setPaidAmount(next);
        if ("Individual".equals(invoice.getKind())) {
            long rentalId = sources.findByIdInvoiceId(id).get(0).getId().getRentalId();
            RentalRecord rental = rentals.lockById(rentalId).orElseThrow(() -> bad("Booking not found."));
            if ("Payment".equals(kind)) rental.setReceivedAmount(rental.getReceivedAmount().add(amount));
            else rental.setRefundedAmount(rental.getRefundedAmount().add(amount));
            rentals.save(rental);
        }
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional
    public Map<String,Object> email(long id) { return emails.invoice(response(requireLocked(id))); }

    private void setDraft(InvoiceRecord invoice, Map<String, Object> request, List<RentalRecord> linked) {
        if (!"SAR".equals(text(request.get("currency")))) throw bad("Invoice currency must be SAR.");
        BigDecimal total = calculateTotal(request);
        if (total.signum() <= 0) throw bad("Invoice total must be positive.");
        if ("Corporate".equals(invoice.getKind())) {
            Long companyId = linked.get(0).getCustomerId();
            if (companyId == null || linked.stream().anyMatch(r -> !companyId.equals(r.getCustomerId())))
                throw bad("Corporate invoice sources must belong to the same company.");
        }
        Map<String, Object> safe = new LinkedHashMap<>(request);
        safe.remove("payments"); safe.remove("status"); safe.remove("invoiceNumber"); safe.remove("issuedAt");
        safe.put("companyId", "Corporate".equals(invoice.getKind()) ? linked.get(0).getCustomerId() : null);
        safe.put("sources", linked.stream().map(rental -> Map.of("kind", rental.getKind(), "id", rental.getId(),
                "reference", rental.getReference(), "serviceDate", serviceDate(rental))).toList());
        for (String key : List.of("issuer", "billTo", "account")) {
            if (safe.get(key) == null) safe.put(key, new LinkedHashMap<String, Object>());
            else if (!(safe.get(key) instanceof Map<?, ?>)) throw bad("Invalid invoice party or account details.");
        }
        for (String key : List.of("paymentTerms", "notes", "paymentContact", "poNumber", "transactionType", "dueDate"))
            safe.put(key, text(safe.get(key)));
        invoice.setPayload(json.writeValueAsString(safe)); invoice.setTotalAmount(total);
        if (invoice.getPaidAmount() == null) invoice.setPaidAmount(BigDecimal.ZERO);
    }

    private List<RentalRecord> validateSources(String kind, Map<String, Object> request, Long existingId) {
        if (!Set.of("Individual", "Corporate").contains(kind)) throw bad("Invalid invoice kind.");
        List<Map<String, Object>> rows = maps(request.get("sources"));
        if (rows.isEmpty() || ("Individual".equals(kind) && rows.size() != 1)) throw bad("Select completed work; individual invoices use one booking.");
        List<RentalRecord> linked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!kind.equals(text(row.get("kind")))) throw bad("Invoice source kind does not match.");
            Long id = id(row.get("id"));
            RentalRecord rental = rentals.lockById(id).orElseThrow(() -> bad("Source rental not found."));
            if (!kind.equals(rental.getKind()) || !"Completed".equals(rental.getStatus())) throw bad("Only completed rentals can be invoiced.");
            if (sources.existsByIdRentalId(id) && (existingId == null || sources.findByIdInvoiceId(existingId).stream().noneMatch(s -> s.getId().getRentalId().equals(id))))
                throw conflict("A selected rental already belongs to an invoice.");
            if (linked.stream().anyMatch(r -> r.getId().equals(id))) throw bad("Duplicate invoice source.");
            linked.add(rental);
        }
        return linked;
    }

    private BigDecimal calculateTotal(Map<String, Object> request) {
        List<Map<String, Object>> lines = maps(request.get("lines"));
        if (lines.isEmpty()) throw bad("Add at least one invoice line.");
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            if (text(line.get("description")).isBlank() || text(line.get("unit")).isBlank()) throw bad("Complete each invoice line.");
            BigDecimal quantity = amount(line.get("quantity"));
            BigDecimal price = amount(line.get("unitPrice"));
            BigDecimal discount = amount(line.getOrDefault("discountPercent", 0));
            BigDecimal tax = amount(line.getOrDefault("taxPercent", 0));
            if (price.stripTrailingZeros().scale() > 2 || discount.stripTrailingZeros().scale() > 2 || tax.stripTrailingZeros().scale() > 2)
                throw bad("Unit prices, discounts, and tax rates accept at most two decimal places.");
            if (quantity.signum() <= 0 || price.signum() < 0 || discount.signum() < 0 || discount.compareTo(new BigDecimal("100")) > 0
                    || tax.signum() < 0 || tax.compareTo(new BigDecimal("100")) > 0) throw bad("Invalid invoice quantity, price, discount, or tax.");
            BigDecimal gross = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(gross.multiply(discount).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            total = total.add(net.add(net.multiply(tax).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
        }
        if (total.compareTo(new BigDecimal("999999999999.99")) > 0) throw bad("Invoice total exceeds the supported limit.");
        return total;
    }

    private String serviceDate(RentalRecord rental) {
        Map<String, Object> payload = json.readValue(rental.getPayload(), new TypeReference<Map<String, Object>>() {});
        String returned = text(payload.get("actualReturnAt"));
        return returned.isBlank() ? rental.getReturnAt().toLocalDate().toString() : returned.substring(0, 10);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> rows) || rows.size() > 1000) throw bad("Expected a list with at most 1000 invoice items.");
        for (Object row : rows) if (!(row instanceof Map<?, ?>)) throw bad("Invalid invoice item.");
        return (List<Map<String, Object>>) value;
    }
    private void issueRecord(InvoiceRecord invoice) {
        Map<String, Object> draft = json.readValue(invoice.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, Object> seller = draft.get("issuer") instanceof Map<?, ?> m ? cast(m) : Map.of();
        Map<String, Object> buyer = draft.get("billTo") instanceof Map<?, ?> m ? cast(m) : Map.of();
        if (text(seller.get("name")).isBlank() || text(seller.getOrDefault("street", seller.get("address"))).isBlank())
            throw bad("Enter seller name and street.");
        if (text(buyer.get("name")).isBlank() || !text(buyer.get("email")).matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw bad("Enter buyer name and a valid billing email.");
        if ("Corporate".equals(invoice.getKind()) && (text(buyer.getOrDefault("street", buyer.get("address"))).isBlank()
                || text(buyer.get("city")).isBlank() || text(buyer.get("country")).isBlank()))
            throw bad("Complete the corporate buyer address.");
        try { if (LocalDate.parse(text(draft.get("dueDate"))).isBefore(LocalDate.now())) throw bad("Due date cannot be in the past."); }
        catch (java.time.format.DateTimeParseException e) { throw bad("Enter a valid due date."); }
        invoice.setStatus("Issued"); invoice.setIssuedAt(Instant.now());
        invoice.setNumber("INV-" + (1000 + invoice.getId()));
    }
    @SuppressWarnings("unchecked") private Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }
    private Map<String, Object> response(InvoiceRecord invoice) {
        Map<String, Object> result = json.readValue(invoice.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {});
        result.put("id", invoice.getId()); result.put("invoiceNumber", invoice.getNumber() == null ? "" : invoice.getNumber());
        result.put("status", invoice.getStatus()); result.put("kind", invoice.getKind());
        result.put("createdAt", invoice.getCreatedAt().toString());
        result.put("issuedAt", invoice.getIssuedAt() == null ? "" : invoice.getIssuedAt().toString());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (InvoicePayment payment : payments.findByInvoiceIdOrderByIdAsc(invoice.getId())) {
            entries.add(Map.of("id", payment.getId(), "kind", payment.getKind(), "amount", payment.getAmount(),
                    "date", payment.getPaidOn().toString(), "method", payment.getMethod() == null ? "" : payment.getMethod(),
                    "reference", payment.getReference() == null ? "" : payment.getReference()));
        }
        result.put("payments", entries);
        if ("Individual".equals(invoice.getKind())) {
            List<InvoiceSource> linked = sources.findByIdInvoiceId(invoice.getId());
            if (!linked.isEmpty()) rentals.findById(linked.get(0).getId().getRentalId()).ifPresent(booking ->
                    result.put("bookingPaymentSummary", Map.of("id", booking.getId(), "receivedAmount", booking.getReceivedAmount(),
                            "refundedAmount", booking.getRefundedAmount(), "invoicedAmount", invoice.getTotalAmount(), "invoiceId", invoice.getId())));
        }
        return result;
    }
    private InvoiceRecord require(long id) { return invoices.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Invoice not found.")); }
    private InvoiceRecord requireLocked(long id) { return invoices.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Invoice not found.")); }
    private Long id(Object value) { try { return Long.valueOf(text(value)); } catch (Exception e) { throw bad("Invalid source ID."); } }
    private BigDecimal amount(Object value) {
        try { BigDecimal n = new BigDecimal(text(value)); if (n.abs().compareTo(new BigDecimal("999999999999.99")) > 0) throw bad("Amount exceeds the limit."); return n; }
        catch (NumberFormatException e) { throw bad("Enter a valid amount."); }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
