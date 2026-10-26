package com.vehiclerental.mak;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.entity.VehicleFile;
import com.vehiclerental.mak.fleet.repository.VehicleFileRepository;
import java.time.*;
import java.util.*;
public final class RentalReadyFixture {
 public static void documents(VehicleFileRepository files, Vehicle v) {
  for(String type:List.of("Istimara","Insurance","Fahas")) {
   var f=new VehicleFile();f.setVehicle(v);f.setKind("DOCUMENT");f.setDocumentType(type);f.setExpiryDate(LocalDate.now(ZoneId.of("Asia/Riyadh")).plusYears(1));f.setStorageKey(UUID.randomUUID().toString());f.setOriginalName(type+".pdf");f.setContentType("application/pdf");f.setSizeBytes(1);f.setCreatedAt(Instant.now());files.saveAndFlush(f);
  }
 }
}
