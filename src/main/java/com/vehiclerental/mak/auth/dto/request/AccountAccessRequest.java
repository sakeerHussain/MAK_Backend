package com.vehiclerental.mak.auth.dto.request;

import jakarta.validation.constraints.*;

public record AccountAccessRequest(@NotNull @Pattern(regexp="Admin|Manager|Operations|Finance|Viewer") String role,@NotNull @Pattern(regexp="Pending|Active|Inactive") String status){}
