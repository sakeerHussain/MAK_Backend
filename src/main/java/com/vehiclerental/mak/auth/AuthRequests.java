package com.vehiclerental.mak.auth;
import jakarta.validation.constraints.*;
public final class AuthRequests {
 private AuthRequests(){}
 public record Signup(@NotBlank @Size(max=200) String name,@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(min=12,max=72) String password,@AssertTrue boolean terms,@Size(max=200) String bootstrapCode){}
 public record Login(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=200) String password,Boolean remember){}
 public record EmailRequest(@NotBlank @Email @Size(max=254) String email){}
 public record Reset(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{43}") String token,@NotBlank @Size(min=12,max=72) String password){}
 public record Access(@NotNull @Pattern(regexp="Admin|Manager|Operations|Finance|Viewer") String role,@NotNull @Pattern(regexp="Pending|Active|Inactive") String status){}
}
