package com.vehiclerental.mak.employees.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.request.EmployeeStatusRequest;
import com.vehiclerental.mak.employees.dto.response.EmployeeFileResponse;
import com.vehiclerental.mak.employees.dto.response.EmployeeResponse;
import com.vehiclerental.mak.employees.entity.Employee;
import com.vehiclerental.mak.employees.entity.EmployeeFile;
import com.vehiclerental.mak.employees.mapper.EmployeeFileMapper;
import com.vehiclerental.mak.employees.repository.EmployeeFileRepository;
import com.vehiclerental.mak.employees.repository.EmployeeRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {
    private final EmployeeRepository employees;
    private final EmployeeFileRepository files;
    private final com.vehiclerental.mak.payroll.PayrollService payroll;

    public EmployeeService(EmployeeRepository employees, EmployeeFileRepository files, com.vehiclerental.mak.payroll.PayrollService payroll) {
        this.employees = employees;
        this.files = files;
        this.payroll = payroll;
    }

    @Transactional(readOnly = true)
    public Page<EmployeeResponse> list(String search, String department, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be 0 or greater and size must be between 1 and 100.");
        Specification<Employee> query = (root, ignored, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.isFalse(root.get("archived")));
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                parts.add(cb.or(cb.like(cb.lower(root.get("employeeId")), term), cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("phone")), term)));
            }
            if (department != null && !department.isBlank()) parts.add(cb.equal(root.get("department"), department));
            if (status != null && !status.isBlank()) parts.add(cb.equal(root.get("status"), status));
            return cb.and(parts.toArray(Predicate[]::new));
        };
        Page<Employee> result = employees.findAll(query, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, List<EmployeeFile>> byEmployee = new HashMap<>();
        if (!result.isEmpty()) files.findByEmployeeIdInOrderByIdAsc(result.getContent().stream().map(Employee::getId).toList())
                .forEach(file -> byEmployee.computeIfAbsent(file.getEmployee().getId(), unused -> new ArrayList<>()).add(file));
        return result.map(employee -> response(employee, byEmployee.getOrDefault(employee.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(long id) {
        Employee employee = require(id);
        return response(employee, files.findByEmployeeIdOrderByIdAsc(id));
    }

    @Transactional
    public EmployeeResponse create(EmployeeRequest request) {
        validate(request, null);
        Employee employee = new Employee();
        copy(request, employee);
        employees.saveAndFlush(employee);
        return response(employee, List.of());
    }

    @Transactional
    public EmployeeResponse update(long id, EmployeeRequest request) {
        Employee employee = requireLocked(id);
        validate(request, id);
        copy(request, employee);
        employees.flush();
        return response(employee, files.findByEmployeeIdOrderByIdAsc(id));
    }

    @Transactional
    public EmployeeResponse updateStatus(long id, EmployeeStatusRequest request) {
        Employee employee = requireLocked(id);
        employee.setStatus(request.status());
        return response(employee, files.findByEmployeeIdOrderByIdAsc(id));
    }

    @Transactional
    public void archive(long id) {
        payroll.guardStaffArchive("EMPLOYEE", id);
        Employee employee = requireLocked(id);
        if (employee.getPayrollCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Employee has payroll history. Deactivate instead.");
        }
        employee.setArchived(true);
        employee.setStatus("Inactive");
    }

    Employee requireLocked(long id) { return employees.lockById(id).filter(e -> !e.isArchived()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found.")); }

    Employee require(long id) {
        return employees.findById(id).filter(employee -> !employee.isArchived())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found."));
    }

    private void validate(EmployeeRequest request, Long id) {
        long excluded = id == null ? -1L : id;
        String phoneKey = phoneKey(request.phone());
        if (phoneKey.length() < 7 || phoneKey.length() > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "Phone must contain 7 to 20 digits.");
        if (employees.existsByEmployeeIdIgnoreCaseAndIdNot(request.employeeId().trim(), excluded)) throw new ApiException(HttpStatus.CONFLICT, "Employee ID already exists.");
        if (employees.existsByPhoneKeyAndIdNot(phoneKey, excluded)) throw new ApiException(HttpStatus.CONFLICT, "Phone number already exists.");
        if ("Vendor".equals(request.employmentType()) && (request.vendor() == null || request.vendor().isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "Vendor is required for vendor employees.");
    }

    private void copy(EmployeeRequest r, Employee d) {
        d.setEmployeeId(r.employeeId().trim().toUpperCase(Locale.ROOT));
        d.setName(r.name().trim());
        d.setPhone(r.phone().trim());
        d.setPhoneKey(phoneKey(r.phone()));
        d.setEmail(trim(r.email()));
        d.setCategory(r.category());
        d.setNationality(trim(r.nationality()));
        d.setJobTitle(r.jobTitle().trim());
        d.setDepartment(r.department()); d.setManager(trim(r.manager()));
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
        d.setMonthlySalary(r.monthlySalary());
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

    private EmployeeResponse response(Employee d, List<EmployeeFile> records) {
        EmployeeRequest details = new EmployeeRequest(d.getEmployeeId(), d.getName(), d.getPhone(), d.getEmail(),
                d.getCategory(), d.getNationality(), d.getJobTitle(), d.getDepartment(), d.getManager(), d.getIdNumber(), d.getIqamaExpiry(),
                d.getYearsExperience(), d.getEmploymentType(), d.getVendor(), d.getStatus(), d.getCompanyId(),
                d.getJoinedOn(), d.getOfficialNumber(), d.getHourlyRate(), d.getDailyRate(), d.getMonthlySalary(),
                d.getPassportNumber(), d.getPassportExpiry(), d.getContractId(), d.getContractExpiry(),
                d.getLicenceNumber(), d.getLicenceExpiry(), d.getBloodGroup(), d.getInsuranceNumber(),
                d.getInsuranceExpiry(), d.getEmergencyKsa(), d.getEmergencyHome(), d.getHomeAddress());
        EmployeeFileResponse photo = records.stream().filter(file -> "PHOTO".equals(file.getKind()))
                .findFirst().map(EmployeeFileMapper::toResponse).orElse(null);
        List<EmployeeFileResponse> documents = records.stream().filter(file -> "DOCUMENT".equals(file.getKind()))
                .map(EmployeeFileMapper::toResponse).toList();
        return new EmployeeResponse(d.getId(), details, photo, documents, d.getPayrollCount());
    }

    private String phoneKey(String phone) { return phone.replaceAll("\\D", ""); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
