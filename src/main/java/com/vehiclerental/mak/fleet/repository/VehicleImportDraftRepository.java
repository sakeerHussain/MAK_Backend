package com.vehiclerental.mak.fleet.repository;

import com.vehiclerental.mak.fleet.entity.VehicleImportDraft;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface VehicleImportDraftRepository extends JpaRepository<VehicleImportDraft, Long> {
    List<VehicleImportDraft> findAllByOrderByIdDesc();
    boolean existsByRegistrationAndIdNot(String registration, long id);
    boolean existsByVehicleIdAndIdNot(String vehicleId, long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from VehicleImportDraft d where d.id = :id")
    Optional<VehicleImportDraft> lockById(@Param("id") long id);
}
