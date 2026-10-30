package com.vehiclerental.mak.auth.service;
import com.vehiclerental.mak.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;

final class AccountPasswordPolicy {
 private AccountPasswordPolicy() {}
 static void validate(String p) {
  if (p == null) throw new ApiException(HttpStatus.BAD_REQUEST,"A password is required.");
if(p.length()<12 || p.getBytes(StandardCharsets.UTF_8).length>72 || !p.matches("(?s).*[a-zA-Z].*") || !p.matches("(?s).*\\d.*") || !p.matches("(?s).*[^a-zA-Z0-9\\s].*"))throw new ApiException(HttpStatus.BAD_REQUEST,"Use 12+ characters with letters, numbers and symbols; maximum 72 UTF-8 bytes.");
 }
}
