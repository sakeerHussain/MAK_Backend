package com.vehiclerental.mak.auth;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface AuthControlRepository extends JpaRepository<AuthControl,Integer> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from AuthControl c where c.id=1") Optional<AuthControl> lockControl();
}
