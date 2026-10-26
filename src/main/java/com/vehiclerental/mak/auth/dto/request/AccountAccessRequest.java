package com.vehiclerental.mak.auth.dto.request;
import jakarta.validation.constraints.*;
public record AccountAccessRequest(@Size(max=80) String role,@NotNull @Pattern(regexp="Pending|Active|Inactive") String status,Long roleId,Long version){
 public AccountAccessRequest(String role,String status){this(role,status,null,null);}
}
