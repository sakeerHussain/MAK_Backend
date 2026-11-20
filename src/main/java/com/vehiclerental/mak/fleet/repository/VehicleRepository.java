package com.vehiclerental.mak.fleet.repository;

import com.vehiclerental.mak.fleet.entity.Vehicle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from Vehicle v where v.id=:id")
    java.util.Optional<Vehicle> lockById(@org.springframework.data.repository.query.Param("id") long id);
    boolean existsByRegistrationIgnoreCaseAndIdNot(String registration, Long id);
    boolean existsByVehicleIdIgnoreCaseAndIdNot(String vehicleId, Long id);
    long countByStatus(String status);
    long countByOwnershipAndVendorIgnoreCase(String ownership, String vendor);
    /** Vehicle classes that can currently be booked; a published rate card must price each of them. */
    @org.springframework.data.jpa.repository.Query("select distinct v.type from Vehicle v where v.listedForBooking = true and v.status <> 'Inactive'")
    java.util.List<String> bookableTypes();
}
