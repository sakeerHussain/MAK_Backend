package com.vehiclerental.mak.fleet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleFileRepository extends JpaRepository<VehicleFile, Long> {
    List<VehicleFile> findByVehicleIdInOrderByIdAsc(Collection<Long> vehicleIds);
    List<VehicleFile> findByVehicleIdOrderByIdAsc(Long vehicleId);
    Optional<VehicleFile> findByIdAndVehicleId(Long id, Long vehicleId);
    Optional<VehicleFile> findByVehicleIdAndKindAndDocumentType(Long vehicleId, String kind, String documentType);
    long countByVehicleIdAndKind(Long vehicleId, String kind);
}
