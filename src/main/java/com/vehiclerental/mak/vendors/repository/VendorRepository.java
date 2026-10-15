package com.vehiclerental.mak.vendors.repository;

import com.vehiclerental.mak.vendors.entity.Vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VendorRepository extends JpaRepository<Vendor, Long>, JpaSpecificationExecutor<Vendor> {
    boolean existsByNameKeyAndIdNot(String nameKey, Long id);
}
