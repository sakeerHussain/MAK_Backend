package com.vehiclerental.mak.auth.integration;
import com.vehiclerental.mak.auth.service.*;
import com.vehiclerental.mak.auth.repository.*;
import com.vehiclerental.mak.auth.entity.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.auth.admin-seed.enabled=false","spring.datasource.url=jdbc:h2:mem:adminseedtests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@Transactional
class InitialAdminSeedTests {
 @Autowired InitialAdminSeedService seed;
 @Autowired AccountRepository accounts;
 @Autowired AuthControlRepository controls;
 @Autowired AuthAuditRepository audits;
 @Autowired PasswordEncoder passwords;
 @Autowired ApplicationContext context;
 @Test void disabledByDefaultRunnerIsAbsent(){assertTrue(context.getBeansOfType(InitialAdminSeedRunner.class).isEmpty());}
 @Test void freshDatabaseGetsExactlyOneActiveAdminAndAudit(){
  assertTrue(seed.seed("  Deployment Admin  "," ADMIN@example.invalid ","Seed-Example-123!"));
  var a=accounts.findByEmail("admin@example.invalid").orElseThrow();
  assertEquals("Deployment Admin",a.getName());assertEquals("Admin",a.getRole());assertEquals("Active",a.getStatus());assertNull(a.getCustomRoleId());
  assertNotEquals("Seed-Example-123!",a.getPasswordHash());assertTrue(passwords.matches("Seed-Example-123!",a.getPasswordHash()));
  assertTrue(controls.findById(1).orElseThrow().isBootstrapDone());
  assertTrue(audits.findAll().stream().anyMatch(e->e.getEvent().equals("ADMIN_SETUP")&&e.getTargetId().equals(a.getId())));
  assertFalse(seed.seed("Different","other@example.invalid","Changed-Example-123!"));assertEquals(1,accounts.count());assertTrue(passwords.matches("Seed-Example-123!",a.getPasswordHash()));
 }
 @Test void completedSetupSkipsWithoutRequiringSecrets(){controls.findById(1).orElseThrow().setBootstrapDone(true);assertFalse(seed.seed(null,null,null));assertEquals(0,accounts.count());}
 @Test void refusesExistingAccountWithoutPromotingOrResettingIt(){
  var a=new Account();a.setName("Existing viewer");a.setEmail("viewer@example.invalid");a.setPasswordHash("unchanged");a.setRole("Viewer");a.setStatus("Pending");a.setCreatedAt(Instant.now());accounts.saveAndFlush(a);
  assertThrows(IllegalStateException.class,()->seed.seed("Admin","viewer@example.invalid","Seed-Example-123!"));
  assertEquals("Viewer",a.getRole());assertEquals("Pending",a.getStatus());assertEquals("unchanged",a.getPasswordHash());assertFalse(controls.findById(1).orElseThrow().isBootstrapDone());
 }
 @Test void missingNameCreatesNothing(){assertThrows(IllegalStateException.class,()->seed.seed("","admin@example.invalid","Seed-Example-123!"));assertEquals(0,accounts.count());assertFalse(controls.findById(1).orElseThrow().isBootstrapDone());}
 @Test void invalidEmailCreatesNothing(){assertThrows(IllegalStateException.class,()->seed.seed("Admin","invalid","Seed-Example-123!"));assertEquals(0,accounts.count());}
 @Test void weakPasswordCreatesNothing(){assertThrows(IllegalStateException.class,()->seed.seed("Admin","admin@example.invalid","weak"));assertEquals(0,accounts.count());assertFalse(controls.findById(1).orElseThrow().isBootstrapDone());}
 @Test void multibytePasswordLimitMatchesSignUp(){assertThrows(IllegalStateException.class,()->seed.seed("Admin","admin@example.invalid","Long123!"+"界".repeat(30)));assertEquals(0,accounts.count());}
}
