package com.vehiclerental.mak.inspections;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface InspectionRepository extends JpaRepository<Inspection,Long> {
    List<Inspection> findAllByOrderByInspectionDateDescIdDesc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inspection i where i.id=:id")
    Optional<Inspection> lockById(@Param("id") long id);
}
