package com.vehiclerental.mak.auth.repository;

import com.vehiclerental.mak.auth.entity.Account;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account,Long> {
 Optional<Account> findByEmail(String email);
 List<Account> findAllByOrderByCreatedAtDesc();
 List<Account> findByCustomRoleId(Long customRoleId);
 long countByCustomRoleId(Long customRoleId);
 long countByRoleAndStatus(String role,String status);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Account a where a.email=:email") Optional<Account> lockByEmail(@Param("email") String email);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Account a where a.id=:id") Optional<Account> lockById(@Param("id") long id);
}
