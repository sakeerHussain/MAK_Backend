package com.vehiclerental.mak.employees.repository;

import com.vehiclerental.mak.employees.entity.Employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Employee e where e.id=:id")
    java.util.Optional<Employee> lockById(@org.springframework.data.repository.query.Param("id") long id);
    boolean existsByEmployeeIdIgnoreCaseAndIdNot(String employeeId, Long id);
    boolean existsByPhoneKeyAndIdNot(String phoneKey, Long id);
    long countByEmploymentTypeAndVendorIgnoreCaseAndArchivedFalse(String employmentType, String vendor);
}
