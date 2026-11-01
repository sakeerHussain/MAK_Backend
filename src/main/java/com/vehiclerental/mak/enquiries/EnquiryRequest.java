package com.vehiclerental.mak.enquiries;
import jakarta.validation.constraints.*;import java.time.LocalDate;
public record EnquiryRequest(
 @NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String submissionId,
 @NotBlank @Size(max=120) String name,@NotBlank @Email @Size(max=254) String email,
 @Size(max=40) String phone,@Size(max=150) String company,@Size(max=120) String subject,
 @NotBlank @Size(max=4000) String message,@Size(max=80) String category,
 @Size(max=80) String preference,@Size(max=300) String location,
 LocalDate pickupDate,LocalDate returnDate,@Positive Long vehicleId,
 @Size(max=200) String website) {}
