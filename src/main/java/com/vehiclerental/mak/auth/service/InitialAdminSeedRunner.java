package com.vehiclerental.mak.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="app.auth.admin-seed.enabled",havingValue="true")
public class InitialAdminSeedRunner implements ApplicationRunner {
 private static final Logger LOG=LoggerFactory.getLogger(InitialAdminSeedRunner.class);
 private final InitialAdminSeedService service;
 private final String name,email,password;
 public InitialAdminSeedRunner(InitialAdminSeedService service,
   @Value("${app.auth.admin-seed.name:}") String name,
   @Value("${app.auth.admin-seed.email:}") String email,
   @Value("${app.auth.admin-seed.password:}") String password) {
  this.service=service;this.name=name;this.email=email;this.password=password;
 }
 @Override public void run(ApplicationArguments args) {
  boolean created=service.seed(name,email,password);
  LOG.info(created?"Initial administrator created. Disable admin seeding and remove seed credentials from the deployment environment.":"Admin seed skipped: initial administrator setup is already complete.");
 }
}
