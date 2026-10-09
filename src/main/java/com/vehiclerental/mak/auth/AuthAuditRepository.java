package com.vehiclerental.mak.auth;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuthAuditRepository extends JpaRepository<AuthAudit,Long> { List<AuthAudit> findTop200ByOrderByIdDesc(); }
