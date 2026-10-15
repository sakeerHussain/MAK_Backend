package com.vehiclerental.mak.auth.dto.request;

import jakarta.validation.constraints.*;

public record LoginRequest(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=200) String password,Boolean remember){}
