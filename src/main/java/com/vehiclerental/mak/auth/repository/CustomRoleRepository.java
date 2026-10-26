package com.vehiclerental.mak.auth.repository;
import com.vehiclerental.mak.auth.entity.CustomRole;import java.util.*;import jakarta.persistence.LockModeType;import org.springframework.data.jpa.repository.*;
public interface CustomRoleRepository extends JpaRepository<CustomRole,Long>{
 List<CustomRole> findAllByOrderByNameAsc();
 boolean existsByNameIgnoreCase(String name);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from CustomRole r where r.id=:id") Optional<CustomRole> lockById(long id);
}
