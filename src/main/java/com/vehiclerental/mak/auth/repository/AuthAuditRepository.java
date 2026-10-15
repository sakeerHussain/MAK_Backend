package com.vehiclerental.mak.auth.repository;

import com.vehiclerental.mak.auth.entity.AuthAudit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAuditRepository extends JpaRepository<AuthAudit,Long> { List<AuthAudit> findTop200ByOrderByIdDesc(); }
