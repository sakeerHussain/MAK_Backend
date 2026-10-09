package com.vehiclerental.mak.vendors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.drivers.DriverRepository;
import com.vehiclerental.mak.fleet.VehicleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VendorService {
    private static final Set<String> TYPES = Set.of("Business", "Individual");
    private static final Set<String> STATUSES = Set.of("Active", "Inactive");
    private static final Set<String> CLASSIFICATIONS = Set.of("Transport Company", "Authorized Dealer", "Service Provider",
            "Distributor", "Retailers", "Spare Parts Supplier", "Contractor", "Fuel Supplier", "Others");
    private static final Set<Integer> TERMS = Set.of(15, 30, 45, 60, 90);
    private static final Set<String> CURRENCIES = Set.of("SAR", "USD", "EUR", "AED");
    private final VendorRepository vendors;
    private final VendorFileRepository files;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final com.vehiclerental.mak.employees.EmployeeRepository employees;

    public VendorService(VendorRepository vendors, VendorFileRepository files, VehicleRepository vehicles,
            DriverRepository drivers, com.vehiclerental.mak.employees.EmployeeRepository employees) {
        this.vendors = vendors; this.files = files; this.vehicles = vehicles; this.drivers = drivers; this.employees = employees;
    }

    @Transactional(readOnly = true)
    public Page<VendorResponse> list(String search, String type, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Page must be 0 or greater and size must be between 1 and 100.");
        Specification<Vendor> query = (root, ignored, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.isFalse(root.get("archived")));
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                parts.add(cb.or(cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("classification")), term),
                        cb.like(cb.lower(root.get("nationalAddress")), term)));
            }
            if (type != null && !type.isBlank()) parts.add(cb.equal(root.get("vendorType"), type));
            if (status != null && !status.isBlank()) parts.add(cb.equal(root.get("status"), status));
            return cb.and(parts.toArray(Predicate[]::new));
        };
        Page<Vendor> result = vendors.findAll(query, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, List<VendorFile>> byVendor = new HashMap<>();
        if (!result.isEmpty()) files.findByVendorIdInOrderByIdAsc(result.getContent().stream().map(Vendor::getId).toList())
                .forEach(file -> byVendor.computeIfAbsent(file.getVendor().getId(), unused -> new ArrayList<>()).add(file));
        return result.map(vendor -> response(vendor, byVendor.getOrDefault(vendor.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public VendorResponse get(long id) { return response(require(id), files.findByVendorIdOrderByIdAsc(id)); }

    @Transactional
    public VendorResponse create(VendorRequest request) {
        validate(request, null);
        Vendor vendor = new Vendor();
        copy(request, vendor);
        vendors.saveAndFlush(vendor);
        return response(vendor, List.of());
    }

    @Transactional
    public VendorResponse update(long id, VendorRequest request) {
        Vendor vendor = require(id);
        validate(request, id);
        String newName = request.name().trim();
        if (!vendor.getName().equals(newName) && references(vendor) > 0)
            throw new ApiException(HttpStatus.CONFLICT, "This vendor is linked to fleet, driver, or employee records. Reassign those records before renaming it.");
        copy(request, vendor);
        vendors.flush();
        return response(vendor, files.findByVendorIdOrderByIdAsc(id));
    }

    @Transactional
    public VendorResponse updateStatus(long id, VendorStatusRequest request) {
        Vendor vendor = require(id);
        if (!STATUSES.contains(request.status())) throw bad("Status must be Active or Inactive.");
        vendor.setStatus(request.status());
        return response(vendor, files.findByVendorIdOrderByIdAsc(id));
    }

    @Transactional
    public void archive(long id) {
        Vendor vendor = require(id);
        if (references(vendor) > 0) throw new ApiException(HttpStatus.CONFLICT,
                "This vendor is linked to fleet, driver, or employee records. Deactivate it instead.");
        vendor.setArchived(true);
    }

    @Transactional(readOnly = true)
    public Vendor require(long id) {
        Vendor vendor = vendors.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vendor not found."));
        if (vendor.isArchived()) throw new ApiException(HttpStatus.NOT_FOUND, "Vendor not found.");
        return vendor;
    }

    private long references(Vendor vendor) {
        return vehicles.countByOwnershipAndVendorIgnoreCase("Vendor", vendor.getName())
                + drivers.countByEmploymentTypeAndVendorIgnoreCaseAndArchivedFalse("Vendor", vendor.getName())
                + employees.countByEmploymentTypeAndVendorIgnoreCaseAndArchivedFalse("Vendor", vendor.getName());
    }

    private VendorResponse response(Vendor vendor, List<VendorFile> attachments) {
        List<VendorResponse.Contact> contacts = new ArrayList<>();
        for (int i = 0; i < vendor.getContacts().size(); i++) {
            Vendor.Contact person = vendor.getContacts().get(i);
            contacts.add(new VendorResponse.Contact("contact-" + i, person.getName(), person.getMobile(), person.getEmail()));
        }
        return new VendorResponse(vendor.getId(), "VEN-" + String.format("%03d", vendor.getId()), vendor.getVendorType(),
                vendor.getName(), vendor.getCrNumber(), vendor.getVatNumber(), vendor.getNationalAddress(),
                vendor.getClassification(), vendor.getCommission(), vendor.getStatus(), vendor.getCreditTerms(),
                vendor.getContractStart(), vendor.getContractEnd(), contacts,
                new VendorResponse.Bank(vendor.getBeneficiaryName(), vendor.getBankName(), vendor.getIban(),
                        vendor.getAccountNumber(), vendor.getSwiftCode(), vendor.getCurrency()),
                attachments.stream().map(VendorResponse.Attachment::from).toList(), references(vendor));
    }

    private void validate(VendorRequest request, Long id) {
        if (!TYPES.contains(request.vendorType())) throw bad("Choose a valid vendor type.");
        if (!STATUSES.contains(request.status())) throw bad("Status must be Active or Inactive.");
        String name = request.name().trim();
        if (name.length() > 150 || vendors.existsByNameKeyAndIdNot(name.toLowerCase(Locale.ROOT), id == null ? -1L : id))
            throw new ApiException(HttpStatus.CONFLICT, "A vendor with this name already exists.");
        if (request.crNumber() != null && !request.crNumber().isBlank() && !request.crNumber().trim().matches("[A-Za-z0-9 /-]{1,100}"))
            throw bad("CR No. can contain letters, numbers, spaces, slashes, and hyphens.");
        if (request.vatNumber() != null && !request.vatNumber().isBlank() && !request.vatNumber().trim().matches("\\d{15}"))
            throw bad("VAT No. must contain exactly 15 digits.");
        if (request.nationalAddress() != null && request.nationalAddress().length() > 500) throw bad("National address is too long.");
        if (request.classification() != null && !request.classification().isBlank() && !CLASSIFICATIONS.contains(request.classification()))
            throw bad("Choose a valid business classification.");
        if (request.commission() == null || request.commission().compareTo(BigDecimal.ZERO) < 0
                || request.commission().compareTo(new BigDecimal("100")) > 0 || request.commission().scale() > 2)
            throw bad("Commission must be between 0 and 100% with up to two decimal places.");
        if (!TERMS.contains(request.creditTerms())) throw bad("Choose valid credit terms.");
        if (request.contractStart() != null && request.contractEnd() != null && request.contractEnd().isBefore(request.contractStart()))
            throw bad("Contract end date must be on or after the start date.");
        if (request.contacts() == null || request.contacts().isEmpty()) throw bad("Add at least one contact person.");
        for (VendorRequest.Contact contact : request.contacts()) {
            if (contact.name() == null || contact.name().isBlank() || contact.name().trim().length() > 150)
                throw bad("Enter a contact name up to 150 characters.");
            String mobile = contact.mobile() == null ? "" : contact.mobile().trim();
            if (!mobile.matches("[+0-9 ()-]+") || mobile.replaceAll("\\D", "").length() < 8
                    || mobile.replaceAll("\\D", "").length() > 15 || mobile.length() > 40)
                throw bad("Enter a valid contact mobile number.");
            if (contact.email() != null && !contact.email().isBlank()
                    && (contact.email().length() > 150 || !contact.email().trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                throw bad("Enter a valid contact email address.");
        }
        VendorRequest.Bank bank = request.bank();
        if (bank != null) {
            if (bank.currency() != null && !CURRENCIES.contains(bank.currency())) throw bad("Choose a valid bank currency.");
            if (bank.iban() != null && !bank.iban().isBlank() && !bank.iban().replaceAll("\\s", "").matches("(?i)[A-Z]{2}\\d{2}[A-Z0-9]{11,30}"))
                throw bad("Enter a valid IBAN or leave it blank.");
        }
    }

    private void copy(VendorRequest request, Vendor vendor) {
        vendor.setVendorType(request.vendorType());
        vendor.setName(request.name().trim());
        vendor.setNameKey(request.name().trim().toLowerCase(Locale.ROOT));
        vendor.setCrNumber(clean(request.crNumber()));
        vendor.setVatNumber(clean(request.vatNumber()));
        vendor.setNationalAddress(clean(request.nationalAddress()));
        vendor.setClassification(clean(request.classification()));
        vendor.setCommission(request.commission());
        vendor.setStatus(request.status());
        vendor.setCreditTerms(request.creditTerms());
        vendor.setContractStart(request.contractStart());
        vendor.setContractEnd(request.contractEnd());
        VendorRequest.Bank bank = request.bank();
        vendor.setBeneficiaryName(bank == null ? null : clean(bank.beneficiaryName()));
        vendor.setBankName(bank == null ? null : clean(bank.bankName()));
        vendor.setIban(bank == null ? null : clean(bank.iban()));
        vendor.setAccountNumber(bank == null ? null : clean(bank.accountNumber()));
        vendor.setSwiftCode(bank == null ? null : clean(bank.swiftCode()));
        vendor.setCurrency(bank == null || bank.currency() == null ? "SAR" : bank.currency());
        vendor.getContacts().clear();
        for (VendorRequest.Contact person : request.contacts()) {
            Vendor.Contact contact = new Vendor.Contact();
            contact.setName(person.name().trim()); contact.setMobile(person.mobile().trim()); contact.setEmail(clean(person.email()));
            vendor.getContacts().add(contact);
        }
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
