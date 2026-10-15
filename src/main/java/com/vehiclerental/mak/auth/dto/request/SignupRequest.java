package com.vehiclerental.mak.auth.dto.request;

import jakarta.validation.constraints.*;

public record SignupRequest(@NotBlank @Size(max=200) String name,@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(min=12,max=72) String password,@AssertTrue boolean terms,@Size(max=200) String bootstrapCode){}
