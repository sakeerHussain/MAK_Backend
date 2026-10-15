package com.vehiclerental.mak.auth.repository;

import com.vehiclerental.mak.auth.entity.PasswordReset;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PasswordResetRepository extends JpaRepository<PasswordReset,String> {
 void deleteByAccountId(Long accountId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PasswordReset r where r.tokenHash=:hash") Optional<PasswordReset> lockByHash(@Param("hash") String hash);
}
