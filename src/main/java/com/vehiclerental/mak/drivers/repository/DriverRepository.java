package com.vehiclerental.mak.drivers.repository;

import com.vehiclerental.mak.drivers.entity.Driver;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DriverRepository extends JpaRepository<Driver, Long>, JpaSpecificationExecutor<Driver> {
    java.util.List<com.vehiclerental.mak.drivers.entity.Driver> findByArchivedFalseAndStatus(String status);
    boolean existsByEmployeeIdIgnoreCaseAndIdNot(String employeeId, Long id);
    boolean existsByPhoneKeyAndIdNot(String phoneKey, Long id);
    long countByEmploymentTypeAndVendorIgnoreCaseAndArchivedFalse(String employmentType, String vendor);
}
