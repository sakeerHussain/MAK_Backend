package com.vehiclerental.mak.maintenance.repository;

import com.vehiclerental.mak.maintenance.entity.MaintenanceJob;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRepository extends JpaRepository<MaintenanceJob,Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from MaintenanceJob j where j.id=:id")
 Optional<MaintenanceJob> lockById(@Param("id")long id);
 @Query("select count(j) from MaintenanceJob j where j.vehicleId=:vehicleId and j.status in ('Open','In Progress')")
 long activeCount(@Param("vehicleId")long id);
}
