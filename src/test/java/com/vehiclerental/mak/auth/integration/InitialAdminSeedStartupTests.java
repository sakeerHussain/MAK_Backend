package com.vehiclerental.mak.auth.integration;
import com.vehiclerental.mak.auth.service.*;
import com.vehiclerental.mak.auth.repository.*;
import com.vehiclerental.mak.auth.dto.request.LoginRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Disposable in-memory database only; these credentials never reach a deployment database.
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:adminseedstartup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.auth.admin-seed.enabled=true","app.auth.admin-seed.name=Seed QA Admin","app.auth.admin-seed.email=seed-qa@example.invalid","app.auth.admin-seed.password=Disposable-Seed-123!"})
class InitialAdminSeedStartupTests {
 @Autowired AccountRepository accounts;
 @Autowired AccountService service;
 @Autowired ApplicationContext context;
 @Test void startupAutomaticallySeedsAndAllowsSignIn(){
  assertEquals(1,context.getBeansOfType(InitialAdminSeedRunner.class).size());assertEquals(1,accounts.count());
  assertEquals("Active",accounts.findByEmail("seed-qa@example.invalid").orElseThrow().getStatus());
  assertFalse((Boolean)service.configuration().get("bootstrapRequired"));
  assertNotNull(service.login(new LoginRequest("seed-qa@example.invalid","Disposable-Seed-123!",false)));
 }
}
