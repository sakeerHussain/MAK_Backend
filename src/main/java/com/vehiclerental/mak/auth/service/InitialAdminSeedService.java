package com.vehiclerental.mak.auth.service;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.entity.AuthAudit;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.repository.AuthAuditRepository;
import com.vehiclerental.mak.auth.repository.AuthControlRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdminSeedService {
 private final AuthControlRepository controls;
 private final AccountRepository accounts;
 private final AuthAuditRepository audits;
 private final PasswordEncoder passwords;
 public InitialAdminSeedService(AuthControlRepository controls,AccountRepository accounts,AuthAuditRepository audits,PasswordEncoder passwords) {
  this.controls=controls;this.accounts=accounts;this.audits=audits;this.passwords=passwords;
 }

 /** Shares the first-admin signup lock; restarts never reset or promote existing users. */
 @Transactional
 public boolean seed(String name,String email,String password) {
  var control=controls.lockControl().orElseThrow(()->new IllegalStateException("Administrator setup state is missing. Check database migrations."));
  if (control.isBootstrapDone()) return false;
  if (accounts.count()!=0) throw new IllegalStateException("Admin seed refused: accounts already exist but initial setup is incomplete. Review the database setup state manually.");
  String normalizedName=name==null?"":name.trim();
  String normalizedEmail=email==null?"":email.trim().toLowerCase(Locale.ROOT);
  if (normalizedName.isBlank() || normalizedName.length()>200) throw new IllegalStateException("Set ADMIN_SEED_NAME to a name of 1–200 characters.");
  if (normalizedEmail.length()>254 || !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalStateException("Set ADMIN_SEED_EMAIL to a valid email address.");
  try { AccountPasswordPolicy.validate(password); }
  catch (com.vehiclerental.mak.common.exception.ApiException invalid) { throw new IllegalStateException("Set ADMIN_SEED_PASSWORD to 12+ characters with letters, numbers and symbols; maximum 72 UTF-8 bytes."); }
  var account=new Account();account.setName(normalizedName);account.setEmail(normalizedEmail);
  account.setPasswordHash(passwords.encode(password));account.setRole("Admin");account.setStatus("Active");account.setCreatedAt(Instant.now());
  accounts.saveAndFlush(account);
  control.setBootstrapDone(true);
  var audit=new AuthAudit();audit.setActorId(account.getId());audit.setTargetId(account.getId());audit.setEvent("ADMIN_SETUP");audit.setSummary("Initial administrator created by deployment seed");audit.setCreatedAt(Instant.now());audits.save(audit);
  return true;
 }
}
