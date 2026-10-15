package com.vehiclerental.mak.employees.service;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.dto.response.EmployeeFileResponse;
import com.vehiclerental.mak.employees.entity.Employee;
import com.vehiclerental.mak.employees.entity.EmployeeFile;
import com.vehiclerental.mak.employees.mapper.EmployeeFileMapper;
import com.vehiclerental.mak.employees.repository.EmployeeFileRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EmployeeFileService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> DOCUMENT_TYPES = Set.of("Iqama", "Passport copy", "License copy", "Contract copy", "PCC", "Medical", "Home address Proof", "Others");
    private final Path root;
    private final EmployeeService employees;
    private final EmployeeFileRepository files;

    public EmployeeFileService(@Value("${app.upload-dir}") String uploadDir, EmployeeService employees, EmployeeFileRepository files) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.employees = employees;
        this.files = files;
    }

    @Transactional
    public EmployeeFileResponse uploadPhoto(long employeeId, MultipartFile upload) {
        employees.requireLocked(employeeId);
        EmployeeFile existing = files.findByEmployeeIdAndKind(employeeId, "PHOTO").orElse(null);
        EmployeeFileResponse saved = save(employeeId, "PHOTO", null, null, upload);
        replace(existing);
        return saved;
    }

    @Transactional
    public EmployeeFileResponse uploadDocument(long employeeId, String type, LocalDate expiryDate, MultipartFile upload) {
        employees.requireLocked(employeeId);
        if (!DOCUMENT_TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported employee document type.");
        EmployeeFile existing = files.findByEmployeeIdAndKindAndDocumentType(employeeId, "DOCUMENT", type).orElse(null);
        EmployeeFileResponse saved = save(employeeId, "DOCUMENT", type, expiryDate, upload);
        replace(existing);
        return saved;
    }

    @Transactional(readOnly = true)
    public FileDownload download(long employeeId, long fileId) {
        EmployeeFile file = requireFile(employeeId, fileId);
        if (file.getStorageKey() == null) throw new ApiException(HttpStatus.NOT_FOUND, "No file is attached to this certification.");
        Path location = path(file);
        if (!Files.isRegularFile(location)) throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable.");
        try { return new FileDownload(new UrlResource(location.toUri()), file.getOriginalName(), file.getContentType()); }
        catch (MalformedURLException error) { throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable."); }
    }

    @Transactional
    public void delete(long employeeId, long fileId) {
        EmployeeFile file = requireFile(employeeId, fileId);
        files.delete(file);
        if (file.getStorageKey() != null) afterCommitDelete(path(file));
    }

    private EmployeeFileResponse save(long employeeId, String kind, String type, LocalDate expiryDate, MultipartFile upload) {
        Employee employee = employees.requireLocked(employeeId);
        if (upload == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a file.");
        Path location = null;
        MediaType detected = null;
        if (upload != null) {
            if (upload.isEmpty() || upload.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a non-empty file up to 5 MB.");
            detected = detect(upload);
            if (detected == null || ("PHOTO".equals(kind) && "application/pdf".equals(detected.contentType())))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported file. Use PNG, JPG, WebP, or PDF for documents; photos cannot be PDF.");
            location = root.resolve("employees").resolve(String.valueOf(employeeId)).resolve(UUID.randomUUID() + detected.extension()).normalize();
            if (!location.startsWith(root)) throw new IllegalStateException("Invalid storage location.");
        }
        Path stored = location;
        try {
            if (upload != null) {
                Files.createDirectories(stored.getParent());
                try (InputStream stream = upload.getInputStream()) { Files.copy(stream, stored, StandardCopyOption.REPLACE_EXISTING); }
            }
            EmployeeFile file = new EmployeeFile();
            file.setEmployee(employee);
            file.setKind(kind);
            file.setDocumentType(type);
            file.setExpiryDate(expiryDate);
            file.setStorageKey(stored == null ? null : stored.getFileName().toString());
            file.setOriginalName(upload == null ? null : cleanName(upload.getOriginalFilename()));
            file.setContentType(detected == null ? null : detected.contentType());
            file.setSizeBytes(upload == null ? null : upload.getSize());
            file.setCreatedAt(Instant.now());
            files.saveAndFlush(file);
            if (stored != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) try { Files.deleteIfExists(stored); } catch (IOException ignored) { }
                }
            });
            return EmployeeFileMapper.toResponse(file);
        } catch (IOException | RuntimeException error) {
            if (stored != null) try { Files.deleteIfExists(stored); } catch (IOException ignored) { }
            throw error instanceof IOException ? new IllegalStateException("Could not store the uploaded file.", error) : (RuntimeException) error;
        }
    }

    private void replace(EmployeeFile existing) {
        if (existing == null) return;
        files.delete(existing);
        if (existing.getStorageKey() != null) afterCommitDelete(path(existing));
    }

    private EmployeeFile requireFile(long employeeId, long fileId) {
        employees.require(employeeId);
        return files.findByIdAndEmployeeId(fileId, employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee file not found."));
    }

    private Path path(EmployeeFile file) {
        return root.resolve("employees").resolve(String.valueOf(file.getEmployee().getId())).resolve(file.getStorageKey()).normalize();
    }

    private void afterCommitDelete(Path location) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { try { Files.deleteIfExists(location); } catch (IOException ignored) { } }
        });
    }

    private MediaType detect(MultipartFile upload) {
        try (InputStream stream = upload.getInputStream()) {
            byte[] first = stream.readNBytes(12);
            if (first.length >= 8 && Arrays.equals(Arrays.copyOf(first, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return new MediaType("image/png", ".png");
            if (first.length >= 3 && (first[0] & 255) == 255 && (first[1] & 255) == 216 && (first[2] & 255) == 255) return new MediaType("image/jpeg", ".jpg");
            if (first.length >= 12 && new String(first, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(first, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return new MediaType("image/webp", ".webp");
            if (first.length >= 4 && new String(first, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF")) return new MediaType("application/pdf", ".pdf");
            return null;
        } catch (IOException error) { throw new IllegalStateException("Could not inspect uploaded file.", error); }
    }

    private String cleanName(String original) {
        String name = original == null ? "upload" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", " ");
        return name.isBlank() ? "upload" : name.length() > 255 ? name.substring(0, 255) : name;
    }

    private record MediaType(String contentType, String extension) { }

}
