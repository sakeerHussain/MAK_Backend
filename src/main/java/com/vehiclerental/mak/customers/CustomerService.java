package com.vehiclerental.mak.customers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.vehiclerental.mak.common.ApiException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
    private static final Set<String> TYPES = Set.of("Individual", "Corporate");
    private static final Set<String> STATUSES = Set.of("Active", "Inactive");
    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) { this.customers = customers; }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String search, String type, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Page must be 0 or greater and size must be between 1 and 100.");
        if (type != null && !type.isBlank() && !TYPES.contains(type)) throw bad("Choose Individual or Corporate.");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("Choose Active or Inactive.");
        Specification<Customer> query = (root, ignored, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            if (type != null && !type.isBlank()) parts.add(cb.equal(root.get("type"), type));
            if (status != null && !status.isBlank()) parts.add(cb.equal(root.get("status"), status));
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                parts.add(cb.or(cb.like(cb.lower(root.get("name")), term), cb.like(cb.lower(root.get("phone")), term),
                        cb.like(cb.lower(root.get("email")), term)));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
        return customers.findAll(query, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"))).map(CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(long id) { return CustomerResponse.from(require(id)); }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        validate(request, -1);
        Customer customer = new Customer();
        customer.setCreatedAt(Instant.now());
        copy(request, customer);
        return CustomerResponse.from(customers.saveAndFlush(customer));
    }

    @Transactional
    public CustomerResponse update(long id, CustomerRequest request) {
        Customer customer = require(id);
        if (!customer.getType().equals(request.type())) throw bad("Customer type cannot be changed.");
        validate(request, id);
        copy(request, customer);
        customers.flush();
        return CustomerResponse.from(customer);
    }

    @Transactional
    public CustomerResponse status(long id, CustomerStatusRequest request) {
        if (!STATUSES.contains(request.status())) throw bad("Status must be Active or Inactive.");
        Customer customer = require(id);
        customer.setStatus(request.status());
        return CustomerResponse.from(customer);
    }

    @Transactional(readOnly = true)
    public Customer require(long id) {
        return customers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Customer not found."));
    }

    private void validate(CustomerRequest request, long id) {
        if (!TYPES.contains(request.type())) throw bad("Choose Individual or Corporate.");
        if (!STATUSES.contains(request.status())) throw bad("Status must be Active or Inactive.");
        String nameKey = request.name().trim().toLowerCase(Locale.ROOT);
        if (customers.existsByTypeAndNameKeyAndIdNot(request.type(), nameKey, id))
            throw new ApiException(HttpStatus.CONFLICT, "A customer of this type with that name already exists.");
        if (request.waitingRatePerHour() != null && (request.waitingRatePerHour().compareTo(BigDecimal.ZERO) < 0
                || request.waitingRatePerHour().scale() > 2)) throw bad("Waiting rate must be a non-negative SAR amount.");
        if (request.includedWaitingMinutes() != null && request.includedWaitingMinutes() < 0) throw bad("Included waiting minutes cannot be negative.");
        if (request.waitingBillingIncrementMinutes() != null && request.waitingBillingIncrementMinutes() < 1)
            throw bad("Billing increment minutes must be at least one.");
    }

    private void copy(CustomerRequest request, Customer customer) {
        customer.setType(request.type()); customer.setName(request.name().trim());
        customer.setNameKey(request.name().trim().toLowerCase(Locale.ROOT));
        customer.setPhone(request.phone().trim()); customer.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        customer.setAddress(clean(request.address())); customer.setStatus(request.status());
        customer.setRegistrationNumber(clean(request.registrationNumber())); customer.setTaxNumber(clean(request.taxNumber()));
        customer.setBillingContactName(clean(request.billingContactName()));
        customer.setBillingAddress(clean(request.billingAddress())); customer.setBillingCity(clean(request.billingCity()));
        customer.setBillingCountry(clean(request.billingCountry())); customer.setPaymentTerms(clean(request.paymentTerms()));
        customer.setWaitingRatePerHour(request.waitingRatePerHour());
        customer.setIncludedWaitingMinutes(request.includedWaitingMinutes());
        customer.setWaitingBillingIncrementMinutes(request.waitingBillingIncrementMinutes());
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
