package com.vehiclerental.mak.invoices.repository;
import com.vehiclerental.mak.invoices.entity.BillingProfile;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface BillingProfileRepository extends JpaRepository<BillingProfile,Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from BillingProfile p where p.id=1") Optional<BillingProfile> lockProfile();
}
