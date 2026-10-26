package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.invoices.entity.InvoiceCreditNote;
import com.vehiclerental.mak.invoices.repository.InvoiceCreditNoteRepository;
import com.vehiclerental.mak.invoices.entity.InvoicePayment;
import com.vehiclerental.mak.invoices.entity.InvoiceRecord;
import com.vehiclerental.mak.invoices.entity.InvoiceSource;
import com.vehiclerental.mak.invoices.entity.InvoiceSourceId;
import com.vehiclerental.mak.invoices.repository.InvoicePaymentRepository;
import com.vehiclerental.mak.invoices.repository.InvoiceRepository;
import com.vehiclerental.mak.invoices.repository.InvoiceSourceRepository;
import com.vehiclerental.mak.notifications.email.service.EmailService;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final EmailService emails;
    private final InvoiceTime time;
    private final InvoiceCreditNoteRepository credits;
    private final BillingProfileService billingProfiles;
    private final com.vehiclerental.mak.customers.repository.CustomerRepository customers;
    public InvoiceService(InvoiceRepository invoices, InvoiceSourceRepository sources,
            InvoicePaymentRepository payments, RentalRepository rentals, ObjectMapper json, EmailService emails, InvoiceTime time, InvoiceCreditNoteRepository credits, com.vehiclerental.mak.customers.repository.CustomerRepository customers, BillingProfileService billingProfiles) {
        this.invoices = invoices; this.sources = sources; this.payments = payments; this.rentals = rentals; this.json = json; this.emails = emails; this.time = time; this.credits = credits; this.customers = customers; this.billingProfiles = billingProfiles;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() { return invoices.findAll().stream().filter(invoice -> !"Discarded".equals(invoice.getStatus())).sorted((a,b) -> b.getId().compareTo(a.getId())).map(this::response).toList(); }
    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) { return response(require(id)); }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> create(Map<String, Object> request, boolean issue) {
        String kind = text(request.get("kind"));
        List<RentalRecord> linked = validateSources(kind, request, null);
        InvoiceRecord invoice = new InvoiceRecord();
        invoice.setKind(kind); invoice.setStatus("Draft"); invoice.setCreatedAt(time.now());
        setDraft(invoice, billingProfiles.prefill(request), linked);
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
                payment.setPaidOn(time.today()); payment.setMethod("Opening balance");
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

    /** Keep the draft snapshot and opening-balance history; release only the source reservations. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> discard(long id, long actorId) {
        InvoiceRecord invoice = requireLocked(id);
        // Safe retry: never touch links belonging to a replacement invoice.
        if ("Discarded".equals(invoice.getStatus())) return response(invoice);
        if (!"Draft".equals(invoice.getStatus())) throw conflict("Only saved draft invoices can be discarded. Issued invoices are protected.");
        List<InvoiceSource> linked = sources.findByIdInvoiceId(id);
        for (InvoiceSource source : linked) rentals.lockById(source.getId().getRentalId())
                .orElseThrow(() -> conflict("A linked rental could not be found. The draft was not discarded."));
        Map<String, Object> snapshot = json.readValue(invoice.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {});
        snapshot.put("discardedAt", time.now().toString());
        snapshot.put("discardedBy", actorId);
        invoice.setPayload(json.writeValueAsString(snapshot));
        invoice.setStatus("Discarded");
        sources.deleteByIdInvoiceId(id);
        sources.flush();
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> payment(long id, Map<String, Object> request) {
        InvoiceRecord invoice = requireLocked(id);
        if (!"Issued".equals(invoice.getStatus())) throw conflict("Issue the invoice before recording payments.");
        String kind = InvoicePaymentKind.parse(request.get("kind")).value();
        BigDecimal amount = amount(request.get("amount"));
        if (!Set.of("Payment", "Refund").contains(kind) || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
            throw bad("Enter a payment or refund in SAR with at most two decimal places.");
        BigDecimal next = "Payment".equals(kind) ? invoice.getPaidAmount().add(amount) : invoice.getPaidAmount().subtract(amount);
        BigDecimal adjusted = invoice.getTotalAmount().subtract(invoice.getCreditedAmount());
        BigDecimal limit = "Refund".equals(kind) ? invoice.getPaidAmount().subtract(adjusted).max(BigDecimal.ZERO) : adjusted.subtract(invoice.getPaidAmount()).max(BigDecimal.ZERO);
        if (amount.compareTo(limit) > 0) throw bad("Refund".equals(kind) ? "Issue a credit note first. Refunds cannot exceed the customer credit due." : "Payment exceeds the adjusted invoice balance.");
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(id); payment.setKind(kind); payment.setAmount(amount);
        try { payment.setPaidOn(LocalDate.parse(text(request.get("date")))); }
        catch (Exception e) { throw bad("Enter a valid payment date."); }
        if (payment.getPaidOn().isAfter(time.today())) throw bad("Payment date cannot be in the future.");
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

    /** Immutable credits against original issued lines, serialized with all invoice money actions. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String,Object> credit(long id, Map<String,Object> request, long actorId) {
        InvoiceRecord invoice = requireLocked(id);
        if (!"Issued".equals(invoice.getStatus())) throw conflict("Credit notes require an issued invoice.");
        String reason = text(request.get("reason")), requestKey = text(request.get("requestId"));
        if (reason.length() < 3 || reason.length() > 500) throw bad("Enter a credit reason of 3 to 500 characters.");
        try { java.util.UUID.fromString(requestKey); } catch (Exception e) { throw bad("A valid credit request ID is required."); }
        Map<String,Object> original = json.readValue(invoice.getPayload(), new TypeReference<LinkedHashMap<String,Object>>() {});
        List<Map<String,Object>> originalLines = maps(original.get("lines"));
        var requested = new java.util.TreeMap<Integer,BigDecimal>();
        for (Map<String,Object> row : maps(request.get("lines"))) {
            int index;
            try { index = new BigDecimal(text(row.get("lineIndex"))).intValueExact(); } catch (Exception e) { throw bad("Choose an original invoice line."); }
            BigDecimal value = amount(row.get("amount"));
            if (index < 0 || index >= originalLines.size() || value.signum() <= 0 || value.stripTrailingZeros().scale() > 2 || requested.putIfAbsent(index,value.setScale(2)) != null)
                throw bad("Choose each invoice line once, with a positive SAR amount of at most two decimal places.");
        }
        if (requested.isEmpty()) throw bad("Choose at least one amount to credit.");
        String fingerprint = reason + "\n" + requested.toString();
        try { fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var retry = credits.findByInvoiceIdAndRequestKey(id,requestKey);
        if (retry.isPresent()) {
            if (!retry.get().getRequestFingerprint().equals(fingerprint)) throw conflict("This credit request ID was already used for different details. Refresh the invoice before creating another credit.");
            return response(invoice);
        }
        var previous = credits.findByInvoiceIdOrderByIdAsc(id);
        var lines = new ArrayList<Map<String,Object>>();
        BigDecimal total = BigDecimal.ZERO, netTotal = BigDecimal.ZERO, taxTotal = BigDecimal.ZERO;
        for (var entry : requested.entrySet()) {
            int index = entry.getKey(); BigDecimal value = entry.getValue(); Map<String,Object> line = originalLines.get(index);
            BigDecimal gross = amount(line.get("quantity")).multiply(amount(line.get("unitPrice"))).setScale(2,RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(gross.multiply(amount(line.getOrDefault("discountPercent",0))).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP));
            BigDecimal tax = net.multiply(amount(line.getOrDefault("taxPercent",0))).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
            BigDecimal used = BigDecimal.ZERO, usedNet = BigDecimal.ZERO, usedTax = BigDecimal.ZERO;
            for (InvoiceCreditNote note : previous) {
                Map<String,Object> data = json.readValue(note.getPayload(), new TypeReference<Map<String,Object>>() {});
                for (Map<String,Object> item : maps(data.get("lines"))) if (((Number)item.get("lineIndex")).intValue() == index) {
                    used = used.add(amount(item.get("amount"))); usedNet = usedNet.add(amount(item.get("net"))); usedTax = usedTax.add(amount(item.get("tax")));
                }
            }
            BigDecimal remaining = net.add(tax).subtract(used);
            if (value.compareTo(remaining) > 0) throw conflict("Credit exceeds the remaining amount on invoice line " + (index+1) + ". Refresh and review existing credits.");
            // Allocate cumulative VAT, so splitting a credit never creates or loses a cent.
            BigDecimal creditTax = value.compareTo(remaining) == 0 ? tax.subtract(usedTax)
                : used.add(value).multiply(tax).divide(net.add(tax),2,RoundingMode.HALF_UP).subtract(usedTax);
            BigDecimal creditNet = value.subtract(creditTax);
            lines.add(Map.of("lineIndex",index,"description",text(line.get("description")),"taxPercent",line.getOrDefault("taxPercent",0),"net",creditNet,"tax",creditTax,"amount",value));
            total=total.add(value); netTotal=netTotal.add(creditNet); taxTotal=taxTotal.add(creditTax);
        }
        if (invoice.getCreditedAmount().add(total).compareTo(invoice.getTotalAmount()) > 0) throw conflict("Total credits cannot exceed the original invoice amount.");
        var snapshot = new LinkedHashMap<String,Object>();
        snapshot.put("reason",reason); snapshot.put("issuedBy",actorId); snapshot.put("invoiceNumber",invoice.getNumber());
        snapshot.put("issuer",original.get("issuer")); snapshot.put("billTo",original.get("billTo")); snapshot.put("currency","SAR");
        snapshot.put("lines",lines); snapshot.put("net",netTotal); snapshot.put("tax",taxTotal);
        InvoiceCreditNote note=new InvoiceCreditNote(); note.setInvoiceId(id); note.setRequestKey(requestKey); note.setRequestFingerprint(fingerprint);
        note.setTotalAmount(total); note.setIssuedAt(time.now()); note.setPayload(json.writeValueAsString(snapshot)); credits.saveAndFlush(note);
        invoice.setCreditedAmount(invoice.getCreditedAmount().add(total));
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> creditNote(long invoiceId,long noteId) {
        InvoiceCreditNote note=credits.findById(noteId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"Credit note not found."));
        if (note.getInvoiceId() != invoiceId) throw new ApiException(HttpStatus.NOT_FOUND,"Credit note not found.");
        return creditResponse(note);
    }
    private Map<String,Object> creditResponse(InvoiceCreditNote note) {
        Map<String,Object> data=json.readValue(note.getPayload(),new TypeReference<LinkedHashMap<String,Object>>() {});
        data.put("id",note.getId()); data.put("invoiceId",note.getInvoiceId()); data.put("number","CN-"+(1000+note.getId()));
        data.put("issuedAt",note.getIssuedAt().toString()); data.put("total",note.getTotalAmount()); return data;
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
        safe.remove("creditNotes"); safe.remove("creditedAmount"); safe.remove("payments"); safe.remove("status"); safe.remove("invoiceNumber"); safe.remove("issuedAt");
        safe.put("companyId", "Corporate".equals(invoice.getKind()) ? linked.get(0).getCustomerId() : null);
        safe.put("sources", linked.stream().map(rental -> Map.of("kind", rental.getKind(), "id", rental.getId(),
                "reference", rental.getReference(), "serviceDate", serviceDate(rental))).toList());
        for (String key : List.of("issuer", "billTo", "account")) {
            if (safe.get(key) == null) safe.put(key, new LinkedHashMap<String, Object>());
            else if (!(safe.get(key) instanceof Map<?, ?>)) throw bad("Invalid invoice party or account details.");
        }
        for (String key : List.of("paymentTerms", "notes", "paymentContact", "poNumber", "transactionType", "dueDate"))
            safe.put(key, text(safe.get(key)));
        String companyTerms = "Corporate".equals(invoice.getKind()) ? customers.findById(linked.get(0).getCustomerId()).map(com.vehiclerental.mak.customers.entity.Customer::getPaymentTerms).orElse(null) : null;
        InvoiceTerms.draft(safe,invoice.getKind(),companyTerms,time.today());
        RentalRecord booking=linked.get(0);
        InvoiceBookingCharges.prepare(safe,booking,json.readValue(booking.getPayload(),new TypeReference<Map<String,Object>>() {}));
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
            BigDecimal tax = text(line.get("taxPercent")).isBlank() ? BigDecimal.ZERO : amount(line.get("taxPercent"));
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
        return returned.isBlank() ? rental.getReturnAt().toLocalDate().toString() : time.day(returned);
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
        if (!draft.containsKey("dueDateMode")) InvoiceTerms.draft(draft,invoice.getKind(),null,time.today());
        var missingBilling = BillingProfileService.missing(draft);
        if (!missingBilling.isEmpty()) throw bad("Complete the MAK seller/payment details before issuing: " + String.join(", ",missingBilling));
        InvoiceTerms.issue(draft,time.today());
        if("Individual".equals(invoice.getKind())){
            var linked=sources.findByIdInvoiceId(invoice.getId());
            if(linked.isEmpty())throw bad("Invoice booking source is missing.");
            RentalRecord booking=rentals.lockById(linked.get(0).getId().getRentalId()).orElseThrow(()->bad("Booking not found."));
            InvoiceBookingCharges.validateIssue(draft,booking,json.readValue(booking.getPayload(),new TypeReference<Map<String,Object>>() {}),calculateTotal(draft));
        }
        invoice.setPayload(json.writeValueAsString(draft));
        invoice.setStatus("Issued"); invoice.setIssuedAt(time.now());
        invoice.setNumber("INV-" + (1000 + invoice.getId()));
    }
    @SuppressWarnings("unchecked") private Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }
    private Map<String, Object> response(InvoiceRecord invoice) {
        Map<String, Object> result = json.readValue(invoice.getPayload(), new TypeReference<LinkedHashMap<String, Object>>() {});
        result.put("id", invoice.getId()); result.put("invoiceNumber", invoice.getNumber() == null ? "" : invoice.getNumber());
        if("Draft".equals(invoice.getStatus())&&"Individual".equals(invoice.getKind())){
            var linkedSources=sources.findByIdInvoiceId(invoice.getId());
            if(!linkedSources.isEmpty())rentals.findById(linkedSources.get(0).getId().getRentalId()).ifPresent(booking->InvoiceBookingCharges.prepare(result,booking,json.readValue(booking.getPayload(),new TypeReference<Map<String,Object>>() {})));
        }
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
        result.put("creditedAmount", invoice.getCreditedAmount());
        result.put("creditNotes", credits.findByInvoiceIdOrderByIdAsc(invoice.getId()).stream().map(this::creditResponse).toList());
        if ("Individual".equals(invoice.getKind())) {
            List<InvoiceSource> linked = sources.findByIdInvoiceId(invoice.getId());
            if (!linked.isEmpty()) rentals.findById(linked.get(0).getId().getRentalId()).ifPresent(booking ->
                    result.put("bookingPaymentSummary", Map.of("id", booking.getId(), "receivedAmount", booking.getReceivedAmount(),
                            "refundedAmount", booking.getRefundedAmount(), "invoicedAmount", invoice.getTotalAmount().subtract(invoice.getCreditedAmount()), "invoiceId", invoice.getId())));
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
