package com.vehiclerental.mak.drivers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
public class DriverService {
    private final DriverRepository drivers;
    private final DriverFileRepository files;

    public DriverService(DriverRepository drivers, DriverFileRepository files) {
        this.drivers = drivers;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public Page<DriverResponse> list(String search, String category, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be 0 or greater and size must be between 1 and 100.");
        Specification<Driver> query = (root, ignored, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.isFalse(root.get("archived")));
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                parts.add(cb.or(cb.like(cb.lower(root.get("employeeId")), term), cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("phone")), term)));
            }
            if (category != null && !category.isBlank()) parts.add(cb.equal(root.get("category"), category));
            if (status != null && !status.isBlank()) parts.add(cb.equal(root.get("status"), status));
            return cb.and(parts.toArray(Predicate[]::new));
        };
        Page<Driver> result = drivers.findAll(query, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, List<DriverFile>> byDriver = new HashMap<>();
        if (!result.isEmpty()) files.findByDriverIdInOrderByIdAsc(result.getContent().stream().map(Driver::getId).toList())
                .forEach(file -> byDriver.computeIfAbsent(file.getDriver().getId(), unused -> new ArrayList<>()).add(file));
        return result.map(driver -> response(driver, byDriver.getOrDefault(driver.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public DriverResponse get(long id) {
        Driver driver = require(id);
        return response(driver, files.findByDriverIdOrderByIdAsc(id));
    }

    @Transactional
    public DriverResponse create(DriverRequest request) {
        validate(request, null);
        Driver driver = new Driver();
        copy(request, driver);
        drivers.saveAndFlush(driver);
        return response(driver, List.of());
    }

    @Transactional
    public DriverResponse update(long id, DriverRequest request) {
        Driver driver = require(id);
        validate(request, id);
        copy(request, driver);
        drivers.flush();
        return response(driver, files.findByDriverIdOrderByIdAsc(id));
    }

    @Transactional
    public DriverResponse updateStatus(long id, DriverStatusRequest request) {
        Driver driver = require(id);
        driver.setStatus(request.status());
        return response(driver, files.findByDriverIdOrderByIdAsc(id));
    }

    @Transactional
    public void archive(long id) {
        Driver driver = require(id);
        if (driver.getBookingCount() > 0 || driver.getPaymentCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Driver has booking or payment history. Deactivate instead.");
        }
        driver.setArchived(true);
        driver.setStatus("Inactive");
    }

    Driver require(long id) {
        return drivers.findById(id).filter(driver -> !driver.isArchived())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Driver not found."));
    }

    private void validate(DriverRequest request, Long id) {
        long excluded = id == null ? -1L : id;
        String phoneKey = phoneKey(request.phone());
        if (phoneKey.length() < 7 || phoneKey.length() > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "Phone must contain 7 to 20 digits.");
        if (drivers.existsByEmployeeIdIgnoreCaseAndIdNot(request.employeeId().trim(), excluded)) throw new ApiException(HttpStatus.CONFLICT, "Employee ID already exists.");
        if (drivers.existsByPhoneKeyAndIdNot(phoneKey, excluded)) throw new ApiException(HttpStatus.CONFLICT, "Phone number already exists.");
        if ("Vendor".equals(request.employmentType()) && (request.vendor() == null || request.vendor().isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "Vendor is required for vendor drivers.");
    }

    private void copy(DriverRequest r, Driver d) {
        d.setEmployeeId(r.employeeId().trim().toUpperCase(Locale.ROOT));
        d.setName(r.name().trim());
        d.setPhone(r.phone().trim());
        d.setPhoneKey(phoneKey(r.phone()));
        d.setEmail(trim(r.email()));
        d.setCategory(r.category());
        d.setNationality(trim(r.nationality()));
        d.setDesignation(r.designation().trim());
        d.setIdNumber(trim(r.idNumber()));
        d.setIqamaExpiry(r.iqamaExpiry());
        d.setYearsExperience(r.yearsExperience());
        d.setEmploymentType(r.employmentType());
        d.setVendor("Vendor".equals(r.employmentType()) ? trim(r.vendor()) : null);
        d.setStatus(r.status());
        d.setCompanyId(trim(r.companyId()));
        d.setJoinedOn(r.joinedOn());
        d.setOfficialNumber(trim(r.officialNumber()));
        d.setHourlyRate(r.hourlyRate());
        d.setDailyRate(r.dailyRate());
        d.setMonthlyRate(r.monthlyRate());
        d.setPassportNumber(trim(r.passportNumber()));
        d.setPassportExpiry(r.passportExpiry());
        d.setContractId(trim(r.contractId()));
        d.setContractExpiry(r.contractExpiry());
        d.setLicenceNumber(trim(r.licenceNumber()));
        d.setLicenceExpiry(r.licenceExpiry());
        d.setBloodGroup(trim(r.bloodGroup()));
        d.setInsuranceNumber(trim(r.insuranceNumber()));
        d.setInsuranceExpiry(r.insuranceExpiry());
        d.setEmergencyKsa(trim(r.emergencyKsa()));
        d.setEmergencyHome(trim(r.emergencyHome()));
        d.setHomeAddress(trim(r.homeAddress()));
    }

    private DriverResponse response(Driver d, List<DriverFile> records) {
        DriverRequest details = new DriverRequest(d.getEmployeeId(), d.getName(), d.getPhone(), d.getEmail(),
                d.getCategory(), d.getNationality(), d.getDesignation(), d.getIdNumber(), d.getIqamaExpiry(),
                d.getYearsExperience(), d.getEmploymentType(), d.getVendor(), d.getStatus(), d.getCompanyId(),
                d.getJoinedOn(), d.getOfficialNumber(), d.getHourlyRate(), d.getDailyRate(), d.getMonthlyRate(),
                d.getPassportNumber(), d.getPassportExpiry(), d.getContractId(), d.getContractExpiry(),
                d.getLicenceNumber(), d.getLicenceExpiry(), d.getBloodGroup(), d.getInsuranceNumber(),
                d.getInsuranceExpiry(), d.getEmergencyKsa(), d.getEmergencyHome(), d.getHomeAddress());
        DriverFileResponse photo = records.stream().filter(file -> "PHOTO".equals(file.getKind()))
                .findFirst().map(DriverFileResponse::from).orElse(null);
        List<DriverFileResponse> documents = records.stream().filter(file -> "DOCUMENT".equals(file.getKind()))
                .map(DriverFileResponse::from).toList();
        List<DriverFileResponse> certifications = records.stream().filter(file -> "CERTIFICATION".equals(file.getKind()))
                .map(DriverFileResponse::from).toList();
        return new DriverResponse(d.getId(), details, photo, documents, certifications,
                d.getBookingCount(), d.getPaymentCount());
    }

    private String phoneKey(String phone) { return phone.replaceAll("\\D", ""); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
