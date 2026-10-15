package com.vehiclerental.mak.auth.repository;

import com.vehiclerental.mak.auth.entity.AuthControl;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface AuthControlRepository extends JpaRepository<AuthControl,Integer> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from AuthControl c where c.id=1") Optional<AuthControl> lockControl();
}
