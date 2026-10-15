package com.vehiclerental.mak.rentals.repository;

import com.vehiclerental.mak.rentals.entity.RentalRecord;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RentalRepository extends JpaRepository<RentalRecord, Long> {
    List<RentalRecord> findByKindOrderByIdDesc(String kind);
    long countByDriverId(Long driverId);
    long countByVehicleIdAndStatus(Long vehicleId, String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalRecord r where r.id = :id")
    Optional<RentalRecord> lockById(long id);
    @Query("select r from RentalRecord r where r.id <> :id and r.status in ('Pending','Confirmed','Active') and (r.vehicleId = :vehicleId or r.driverId = :driverId) and ((r.pickupAt < :returnAt and r.returnAt > :pickupAt) or (r.status = 'Active' and r.returnAt < :now))")
    List<RentalRecord> conflicts(long id, Long vehicleId, Long driverId, LocalDateTime pickupAt, LocalDateTime returnAt, LocalDateTime now);
}
