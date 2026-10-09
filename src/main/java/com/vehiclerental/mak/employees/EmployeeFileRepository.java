package com.vehiclerental.mak.employees;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeFileRepository extends JpaRepository<EmployeeFile, Long> {
    List<EmployeeFile> findByEmployeeIdOrderByIdAsc(long employeeId);
    List<EmployeeFile> findByEmployeeIdInOrderByIdAsc(Collection<Long> employeeIds);
    Optional<EmployeeFile> findByIdAndEmployeeId(long id, long employeeId);
    Optional<EmployeeFile> findByEmployeeIdAndKind(long employeeId, String kind);
    Optional<EmployeeFile> findByEmployeeIdAndKindAndDocumentType(long employeeId, String kind, String type);
    long countByEmployeeIdAndKind(long employeeId, String kind);
}
