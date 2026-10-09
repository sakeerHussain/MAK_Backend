package com.vehiclerental.mak.vendors;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorFileRepository extends JpaRepository<VendorFile, Long> {
    List<VendorFile> findByVendorIdOrderByIdAsc(long vendorId);
    List<VendorFile> findByVendorIdInOrderByIdAsc(Collection<Long> vendorIds);
    Optional<VendorFile> findByIdAndVendorId(long id, long vendorId);
    Optional<VendorFile> findByVendorIdAndDocumentType(long vendorId, String documentType);
}
