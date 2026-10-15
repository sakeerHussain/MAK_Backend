package com.vehiclerental.mak.drivers.repository;

import com.vehiclerental.mak.drivers.entity.DriverFile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverFileRepository extends JpaRepository<DriverFile, Long> {
    List<DriverFile> findByDriverIdOrderByIdAsc(long driverId);
    List<DriverFile> findByDriverIdInOrderByIdAsc(Collection<Long> driverIds);
    Optional<DriverFile> findByIdAndDriverId(long id, long driverId);
    Optional<DriverFile> findByDriverIdAndKind(long driverId, String kind);
    Optional<DriverFile> findByDriverIdAndKindAndDocumentType(long driverId, String kind, String type);
    long countByDriverIdAndKind(long driverId, String kind);
}
