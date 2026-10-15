package com.vehiclerental.mak.auth.dto.request;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{43}") String token,@NotBlank @Size(min=12,max=72) String password){}
