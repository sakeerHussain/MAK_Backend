package com.vehiclerental.mak.auth.repository;
import com.vehiclerental.mak.auth.entity.Invitation;import java.util.*;import jakarta.persistence.LockModeType;import org.springframework.data.jpa.repository.*;
public interface InvitationRepository extends JpaRepository<Invitation,Long>{
 List<Invitation> findAllByOrderByCreatedAtDesc();
 Optional<Invitation> findByTokenHash(String tokenHash);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from Invitation i where i.id=:id") Optional<Invitation> lockById(long id);
}
