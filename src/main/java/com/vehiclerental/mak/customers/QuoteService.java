package com.vehiclerental.mak.customers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.vehiclerental.mak.common.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class QuoteService {
    private final QuoteRepository quotes;
    private final CustomerRepository customers;
    private final ObjectMapper json;
    private final QuoteEvidenceRepository evidence;
    public QuoteService(QuoteRepository quotes, CustomerRepository customers, ObjectMapper json, QuoteEvidenceRepository evidence) {
        this.quotes = quotes; this.customers = customers; this.json = json; this.evidence = evidence;
    }

    @Transactional(readOnly = true)
    public Page<QuoteResponse> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Invalid page or size.");
        return quotes.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))).map(this::response);
    }

    @Transactional(readOnly = true)
    public QuoteResponse get(String id) { return response(require(id)); }

    @Transactional
    public QuoteResponse create(long customerId, QuoteRequest request) {
        Customer customer = lockCustomer(customerId);
        if (!"Active".equals(customer.getStatus())) throw bad("Activate the customer before creating a quote.");
        Quote quote = new Quote();
        quote.setId(UUID.randomUUID().toString()); quote.setCustomer(customer);
        quote.setVersion(quotes.findByCustomerIdOrderByVersionDesc(customerId).stream().mapToInt(Quote::getVersion).max().orElse(0) + 1);
        quote.setCreatedAt(Instant.now());
        copy(request, quote);
        quotes.saveAndFlush(quote);
        return response(quote);
    }

    @Transactional
    public QuoteResponse update(String id, QuoteRequest request) {
        Quote quote = lockQuote(id);
        lockCustomer(quote.getCustomer().getId());
        if (!Set.of("Draft", "Shared").contains(quote.getStatus())) throw conflict("Approved, rejected and superseded quotes are locked. Create a revision.");
        copy(request, quote);
        quotes.flush();
        return response(quote);
    }

    @Transactional
    public QuoteResponse approve(String id, QuoteApprovalRequest approval) {
        Quote quote = lockQuote(id);
        Customer customer = lockCustomer(quote.getCustomer().getId());
        if (!"Active".equals(customer.getStatus())) throw bad("Activate the customer before approving rates.");
        if (!"Shared".equals(quote.getStatus())) throw conflict("Only a shared quote can be approved.");
        if (approval.by() == null || approval.by().isBlank() || approval.note() == null || approval.note().isBlank() || approval.date() == null)
            throw bad("Record the approver, date and evidence reference.");
        if (!Set.of("Email", "Signed quote", "Customer portal", "Other").contains(approval.method())) throw bad("Invalid approval method.");
        if (approval.date().isBefore(quote.getIssueDate()) || approval.date().isAfter(LocalDate.now())
                || quote.getOfferValidUntil() == null || approval.date().isAfter(quote.getOfferValidUntil()))
            throw bad("Approval must be between the issue date and offer expiry, and cannot be in the future.");
        boolean overlaps = quotes.findByCustomerIdOrderByVersionDesc(customer.getId()).stream().anyMatch(other ->
                !other.getId().equals(id) && "Approved".equals(other.getStatus())
                && (other.getEffectiveTo() == null || !other.getEffectiveTo().isBefore(quote.getEffectiveFrom()))
                && (quote.getEffectiveTo() == null || !quote.getEffectiveTo().isBefore(other.getEffectiveFrom())));
        if (overlaps) throw conflict("An approved quote overlaps these effective dates. Supersede it before approval.");
        quote.setStatus("Approved"); quote.setApprovedBy(approval.by().trim()); quote.setApprovalDate(approval.date());
        quote.setApprovalMethod(approval.method()); quote.setApprovalNote(approval.note().trim());
        return response(quote);
    }

    @Transactional
    public QuoteResponse transition(String id, String status) {
        Quote quote = lockQuote(id);
        lockCustomer(quote.getCustomer().getId());
        if (!("Rejected".equals(status) && "Shared".equals(quote.getStatus()))
                && !("Superseded".equals(status) && "Approved".equals(quote.getStatus())))
            throw conflict("That quote status transition is not allowed.");
        quote.setStatus(status);
        return response(quote);
    }

    @Transactional
    public QuoteResponse uploadEvidence(String id, MultipartFile file) {
        Quote quote = lockQuote(id);
        if (!"Shared".equals(quote.getStatus())) throw conflict("Evidence can be uploaded while a quote awaits approval.");
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) throw bad("Choose a PDF, PNG or JPEG file up to 5 MB.");
        try {
            byte[] bytes = file.getBytes();
            String type;
            if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) type = "application/pdf";
            else if (bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) type = "image/png";
            else if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) type = "image/jpeg";
            else throw bad("Evidence must be a PDF, PNG or JPEG file.");
            String name = file.getOriginalFilename() == null ? "approval" : file.getOriginalFilename().replaceAll("[\\r\\n\\\\/]", "_");
            if (name.length() > 255) name = name.substring(name.length() - 255);
            QuoteEvidence stored = new QuoteEvidence(); stored.setQuoteId(id); stored.setFileName(name); stored.setContentType(type); stored.setFileData(bytes);
            evidence.save(stored); quote.setApprovalFileName(name);
            return response(quote);
        } catch (IOException error) { throw bad("Could not read the evidence file."); }
    }

    @Transactional(readOnly = true)
    public QuoteEvidence evidence(String id) {
        require(id);
        return evidence.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Approval evidence not found."));
    }

    private void copy(QuoteRequest request, Quote quote) {
        if (!Set.of("Draft", "Shared").contains(request.status())) throw bad("Save as Draft or Shared; record approval separately.");
        validate(request, "Shared".equals(request.status()));
        String number = request.number() == null || request.number().isBlank()
                ? "Q-" + LocalDate.now().getYear() + "-" + quote.getId().substring(0, 8).toUpperCase(Locale.ROOT) : request.number().trim().toUpperCase(Locale.ROOT);
        if (quotes.existsByNumberIgnoreCaseAndIdNot(number, quote.getId())) throw conflict("Quote number already exists.");
        quote.setNumber(number); quote.setStatus(request.status());
        quote.setIssueDate(request.issueDate() == null ? LocalDate.now() : request.issueDate());
        quote.setOfferValidUntil(request.offerValidUntil()); quote.setEffectiveFrom(request.effectiveFrom()); quote.setEffectiveTo(request.effectiveTo());
        quote.setTaxPercent(request.taxPercent() == null ? BigDecimal.ZERO : request.taxPercent());
        quote.setPaymentTerms(request.paymentTerms()); quote.setNotes(request.notes());
        quote.setRoutesJson(json.writeValueAsString(request.routes() == null ? List.of() : request.routes()));
        quote.setMonthlyJson(json.writeValueAsString(request.monthly() == null ? List.of() : request.monthly()));
        quote.setServicesJson(json.writeValueAsString(request.services() == null ? List.of() : request.services()));
        quote.setExtrasJson(json.writeValueAsString(request.extras() == null ? new QuoteRequest.Extras("", "", "", "", "", "") : request.extras()));
    }

    private void validate(QuoteRequest request, boolean sharing) {
        BigDecimal tax = request.taxPercent();
        if (tax != null && (tax.signum() < 0 || tax.compareTo(new BigDecimal("100")) > 0 || tax.scale() > 2)) throw bad("VAT must be between 0 and 100 with at most two decimal places.");
        if (request.effectiveTo() != null && request.effectiveFrom() != null && request.effectiveTo().isBefore(request.effectiveFrom())) throw bad("Effective end cannot precede the start.");
        if (request.offerValidUntil() != null && request.issueDate() != null && request.offerValidUntil().isBefore(request.issueDate())) throw bad("Offer expiry cannot precede the issue date.");
        if (request.extras() != null) {
            QuoteRequest.Extras extra = request.extras();
            for (String value : new String[]{extra.extraKmRate(), extra.includedKm(), extra.overtimeRate(), extra.standardHours(), extra.extraDriverRate(), extra.extraDriverThresholdKm()})
                if (value != null && !value.isBlank() && !number(value, false)) throw bad("Conditional extras must be non-negative numbers.");
        }
        if (!sharing) return;
        if (request.issueDate() == null || request.offerValidUntil() == null || request.effectiveFrom() == null) throw bad("Set issue, offer expiry and effective-from dates before sharing.");
        List<QuoteRequest.Route> routes = request.routes() == null ? List.of() : request.routes();
        List<QuoteRequest.Monthly> monthly = request.monthly() == null ? List.of() : request.monthly();
        List<QuoteRequest.ServiceRate> services = request.services() == null ? List.of() : request.services();
        if (routes.isEmpty() && monthly.isEmpty() && services.isEmpty()) throw bad("Add at least one rate before sharing.");
        Set<String> seen = new HashSet<>();
        for (QuoteRequest.Route route : routes) {
            if (route == null || blank(route.from()) || blank(route.to()) || blank(route.vehicle()) || !number(route.amount(), true)) throw bad("Complete each route with origin, destination, class and a positive charge.");
            String key = normalize(route.from()) + "|" + stops(route.via()) + "|" + normalize(route.to()) + "|" + normalize(route.vehicle());
            if (!seen.add(key)) throw bad("Duplicate fixed route.");
        }
        seen.clear();
        for (QuoteRequest.Monthly rate : monthly) {
            if (rate == null || blank(rate.vehicle()) || !number(rate.amount(), true)) throw bad("Complete each monthly rate with vehicle and positive amount.");
            if (!seen.add(normalize(rate.vehicle()))) throw bad("Duplicate monthly vehicle rate.");
            for (String value : new String[]{rate.includedKm(), rate.includedTrips()})
                if (!blank(value) && !number(value, false)) throw bad("Monthly allowances must be non-negative numbers.");
        }
        seen.clear();
        for (QuoteRequest.ServiceRate rate : services) {
            if (rate == null || blank(rate.name()) || blank(rate.unit()) || !number(rate.amount(), true)) throw bad("Complete each service with name, unit and positive rate.");
            if (!seen.add(normalize(rate.name()))) throw bad("Duplicate service name.");
        }
    }

    private QuoteResponse response(Quote quote) {
        Customer customer = quote.getCustomer();
        return new QuoteResponse(quote.getId(), customer.getId(), customer.getType().toLowerCase(Locale.ROOT) + ":" + customer.getId(),
                quote.getNumber(), quote.getVersion(), quote.getStatus(), quote.getIssueDate(), quote.getOfferValidUntil(),
                quote.getEffectiveFrom(), quote.getEffectiveTo(), "SAR", quote.getTaxPercent(), quote.getPaymentTerms(), quote.getNotes(),
                json.readValue(quote.getRoutesJson(), new TypeReference<List<QuoteRequest.Route>>() { }),
                json.readValue(quote.getMonthlyJson(), new TypeReference<List<QuoteRequest.Monthly>>() { }),
                json.readValue(quote.getServicesJson(), new TypeReference<List<QuoteRequest.ServiceRate>>() { }),
                json.readValue(quote.getExtrasJson(), QuoteRequest.Extras.class),
                quote.getApprovedBy() == null ? null : new QuoteApprovalRequest(quote.getApprovedBy(), quote.getApprovalDate(), quote.getApprovalMethod(), quote.getApprovalNote(), quote.getApprovalFileName()),
                quote.getApprovalFileName() == null ? null : "/api/quotes/" + quote.getId() + "/evidence");
    }

    private Quote require(String id) { return quotes.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quote not found.")); }
    private Quote lockQuote(String id) { return quotes.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quote not found.")); }
    private Customer lockCustomer(long id) { return customers.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Customer not found.")); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String stops(String value) { return String.join("|", java.util.Arrays.stream((value == null ? "" : value).split(",")).map(QuoteService::normalize).filter(part -> !part.isBlank()).toList()); }
    private static boolean number(String value, boolean positive) {
        try { BigDecimal amount = new BigDecimal(value); return amount.scale() <= 2 && amount.precision() <= 12 && (positive ? amount.signum() > 0 : amount.signum() >= 0); }
        catch (RuntimeException ignored) { return false; }
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}
