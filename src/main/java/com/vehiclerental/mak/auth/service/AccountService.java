package com.vehiclerental.mak.auth.service;

import com.vehiclerental.mak.auth.dto.response.AuthAuditResponse;
import com.vehiclerental.mak.auth.mapper.AuthAuditMapper;
import com.vehiclerental.mak.auth.dto.request.AccountAccessRequest;
import com.vehiclerental.mak.auth.dto.request.LoginRequest;
import com.vehiclerental.mak.auth.dto.request.ResetPasswordRequest;
import com.vehiclerental.mak.auth.dto.request.SignupRequest;
import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.entity.AuthAudit;
import com.vehiclerental.mak.auth.entity.PasswordReset;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.repository.AuthAuditRepository;
import com.vehiclerental.mak.auth.repository.AuthControlRepository;
import com.vehiclerental.mak.auth.repository.PasswordResetRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.Permissions;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.notifications.email.service.EmailService;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
 private final AccountRepository accounts;private final AuthControlRepository controls;private final AuthAuditRepository audits;private final PasswordResetRepository resets;
 private final RoleService roles;private final InvitationService invitations;
 private final PasswordEncoder passwords;private final EmailService emails;private final String bootstrapCode,frontendUrl;private final boolean mailEnabled;private final String dummyHash;
 public AccountService(AccountRepository accounts,AuthControlRepository controls,AuthAuditRepository audits,PasswordResetRepository resets,PasswordEncoder passwords,EmailService emails,RoleService roles,InvitationService invitations,
  @Value("${app.auth.bootstrap-code:}") String bootstrapCode,@Value("${app.auth.frontend-url:http://localhost:5173}") String frontendUrl,@Value("${app.mail.enabled:false}") boolean mailEnabled){
  this.roles=roles;this.invitations=invitations;this.accounts=accounts;this.controls=controls;this.audits=audits;this.resets=resets;this.passwords=passwords;this.emails=emails;this.bootstrapCode=bootstrapCode;this.frontendUrl=frontendUrl;this.mailEnabled=mailEnabled;dummyHash=passwords.encode(UUID.randomUUID().toString());
 }
 @Transactional(readOnly=true) public Map<String,Object> configuration(){return Map.of("bootstrapRequired",!controls.findById(1).orElseThrow().isBootstrapDone(),"bootstrapConfigured",bootstrapCode.length()>=24,"passwordResetEnabled",mailEnabled,"providers",List.of());}
 @Transactional public Map<String,Object> signup(SignupRequest request){
  passwordPolicy(request.password());var control=controls.lockControl().orElseThrow();boolean bootstrap=!control.isBootstrapDone();
  if(bootstrap && (bootstrapCode.length()<24 || request.bootstrapCode()==null || !MessageDigest.isEqual(bootstrapCode.getBytes(StandardCharsets.UTF_8),request.bootstrapCode().getBytes(StandardCharsets.UTF_8))))throw new ApiException(HttpStatus.FORBIDDEN,"A valid administrator setup code is required. Configure AUTH_BOOTSTRAP_CODE on the backend.");
  String email=normalize(request.email());if(accounts.findByEmail(email).isPresent())throw new ApiException(HttpStatus.CONFLICT,"An account with this email already exists.");
  var account=new Account();account.setName(request.name().trim());account.setEmail(email);account.setPasswordHash(passwords.encode(request.password()));account.setRole(bootstrap?"Admin":"Viewer");account.setStatus(bootstrap?"Active":"Pending");account.setCreatedAt(Instant.now());boolean invited=!bootstrap&&request.invitationToken()!=null&&!request.invitationToken().isBlank();if(invited)invitations.accept(request.invitationToken(),email,account);accounts.saveAndFlush(account);
  if(bootstrap)control.setBootstrapDone(true);audit(account.getId(),account.getId(),bootstrap?"ADMIN_SETUP":"ACCOUNT_REGISTERED",bootstrap?"Initial administrator created":"Account registered; awaiting administrator approval");
  return Map.of("status",account.getStatus(),"message",bootstrap?"Administrator created. You can now sign in.":invited?"Invitation accepted. You can now sign in.":"Account created. An administrator must approve your access before you can sign in.");
 }
 @Transactional(noRollbackFor=ApiException.class) public AccountPrincipal login(LoginRequest request){
  var account=accounts.lockByEmail(normalize(request.email())).orElse(null);
  if(account==null){passwords.matches(request.password(),dummyHash);throw invalidLogin();}
  if(account.getLockedUntil()!=null && account.getLockedUntil().isAfter(Instant.now()))throw invalidLogin();
  if(!passwords.matches(request.password(),account.getPasswordHash())){
   account.setFailedAttempts(account.getFailedAttempts()+1);if(account.getFailedAttempts()>=5){account.setLockedUntil(Instant.now().plusSeconds(900));account.setFailedAttempts(0);}throw invalidLogin();
  }
  if(!"Active".equals(account.getStatus()))throw new ApiException(HttpStatus.FORBIDDEN,"Your account is awaiting approval or has been deactivated. Contact an administrator.");
  account.setFailedAttempts(0);account.setLockedUntil(null);account.setLastLoginAt(Instant.now());audit(account.getId(),account.getId(),"SIGNED_IN","Email/password sign-in");return new AccountPrincipal(account.getId(),account.getSessionVersion());
 }
 @Transactional(readOnly=true) public Account active(AccountPrincipal principal){return accounts.findById(principal.id()).filter(a->a.getStatus().equals("Active") && a.getSessionVersion()==principal.version()).orElse(null);}
 public Map<String,Object> response(Account a){var result=new LinkedHashMap<String,Object>();result.put("id",a.getId());result.put("name",a.getName());result.put("email",a.getEmail());result.put("role",roles.name(a));result.put("baseRole",a.getRole());result.put("roleId",a.getCustomRoleId());result.put("version",a.getSessionVersion());result.put("status",a.getStatus());result.put("permissions",new TreeSet<>(permissions(a)));result.put("createdAt",a.getCreatedAt());result.put("lastLoginAt",a.getLastLoginAt());return result;}
 @Transactional(readOnly=true) public List<Map<String,Object>> list(){return accounts.findAllByOrderByCreatedAtDesc().stream().map(this::response).toList();}
 @Transactional public Map<String,Object> update(long actor,long id,AccountAccessRequest access){
  controls.lockControl().orElseThrow();var a=accounts.lockById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Account not found."));
  if(access.version()!=null&&access.version()!=a.getSessionVersion())throw new ApiException(HttpStatus.CONFLICT,"This account changed. Reload it before saving access.");
  roles.validateAssignment(access.role(),access.roleId());
  if(a.getRole().equals("Admin") && a.getStatus().equals("Active") && (access.roleId()!=null || !"Admin".equals(access.role()) || !access.status().equals("Active")) && accounts.countByRoleAndStatus("Admin","Active")<=1)throw new ApiException(HttpStatus.CONFLICT,"Keep at least one active administrator.");
  if(!a.getRole().equals(access.roleId()==null?access.role():"Viewer") || !Objects.equals(a.getCustomRoleId(),access.roleId()) || !a.getStatus().equals(access.status())){String before=roles.name(a)+" / "+a.getStatus();a.setRole(access.roleId()==null?access.role():"Viewer");a.setCustomRoleId(access.roleId());a.setStatus(access.status());a.setSessionVersion(a.getSessionVersion()+1);audit(actor,id,"ACCESS_CHANGED",before+" -> "+roles.name(a)+" / "+access.status());}return response(a);
 }
 @Transactional public void forgot(String email){
  if(!mailEnabled)return;var account=accounts.lockByEmail(normalize(email)).filter(a->a.getStatus().equals("Active")).orElse(null);if(account==null)return;
  resets.deleteByAccountId(account.getId());resets.flush();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  var reset=new PasswordReset();reset.setTokenHash(hash(raw));reset.setAccountId(account.getId());reset.setExpiresAt(Instant.now().plusSeconds(1800));resets.save(reset);
  emails.passwordReset(account.getId(),account.getEmail(),frontendUrl.replaceAll("/+$","")+"/#/reset-password/"+raw);
 }
 @Transactional public void reset(ResetPasswordRequest request){
  passwordPolicy(request.password());String hash=hash(request.token());var found=resets.findById(hash).orElseThrow(this::invalidReset);
  var account=accounts.lockById(found.getAccountId()).filter(a->a.getStatus().equals("Active")).orElseThrow(this::invalidReset);
  var reset=resets.lockByHash(hash).filter(r->r.getExpiresAt().isAfter(Instant.now())).orElseThrow(this::invalidReset);
  account.setPasswordHash(passwords.encode(request.password()));account.setSessionVersion(account.getSessionVersion()+1);account.setFailedAttempts(0);account.setLockedUntil(null);resets.delete(reset);audit(account.getId(),account.getId(),"PASSWORD_RESET","Password reset; existing sessions revoked");
 }
 @Transactional public void audit(Long actor,Long target,String event,String summary){var record=new AuthAudit();record.setActorId(actor);record.setTargetId(target);record.setEvent(event);record.setSummary(summary);record.setCreatedAt(Instant.now());audits.save(record);}
 @Transactional(readOnly=true) public List<AuthAuditResponse> auditLog(){return audits.findTop200ByOrderByIdDesc().stream().map(AuthAuditMapper::toResponse).toList();}
 public Set<String> permissions(Account account){return roles.permissions(account);}
 public void requirePermission(String permission){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();var account=auth!=null&&auth.getPrincipal() instanceof AccountPrincipal p?active(p):null;if(account==null||!permissions(account).contains(permission))throw new ApiException(HttpStatus.FORBIDDEN,"Your role does not allow this action.");}
 private void passwordPolicy(String p){if(p.length()<12 || p.getBytes(StandardCharsets.UTF_8).length>72 || !p.matches("(?s).*[a-zA-Z].*") || !p.matches("(?s).*\\d.*") || !p.matches("(?s).*[^a-zA-Z0-9\\s].*"))throw new ApiException(HttpStatus.BAD_REQUEST,"Use 12+ characters with letters, numbers and symbols; maximum 72 UTF-8 bytes.");}
 private String normalize(String s){return s.trim().toLowerCase(Locale.ROOT);}
 private ApiException invalidLogin(){return new ApiException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect, or sign-in is temporarily locked. Try again later.");}
 private ApiException invalidReset(){return new ApiException(HttpStatus.BAD_REQUEST,"This password reset link is invalid or expired.");}
 private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
