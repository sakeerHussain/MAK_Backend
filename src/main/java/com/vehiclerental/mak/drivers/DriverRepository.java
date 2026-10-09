package com.vehiclerental.mak.drivers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DriverRepository extends JpaRepository<Driver, Long>, JpaSpecificationExecutor<Driver> {
    boolean existsByEmployeeIdIgnoreCaseAndIdNot(String employeeId, Long id);
    boolean existsByPhoneKeyAndIdNot(String phoneKey, Long id);
    long countByEmploymentTypeAndVendorIgnoreCaseAndArchivedFalse(String employmentType, String vendor);
}
