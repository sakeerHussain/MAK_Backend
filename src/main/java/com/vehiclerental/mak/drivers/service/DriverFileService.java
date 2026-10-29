package com.vehiclerental.mak.drivers.service;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.drivers.dto.request.CertificationRequest;
import com.vehiclerental.mak.drivers.dto.response.DriverFileResponse;
import com.vehiclerental.mak.drivers.entity.Driver;
import com.vehiclerental.mak.drivers.entity.DriverFile;
import com.vehiclerental.mak.drivers.mapper.DriverFileMapper;
import com.vehiclerental.mak.drivers.repository.DriverFileRepository;

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
public class DriverFileService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> DOCUMENT_TYPES = Set.of("Iqama", "Passport copy", "License copy", "Contract copy", "PCC", "Medical", "Home address Proof", "Others");
    private static final Set<String> EXPIRING_DOCUMENTS = Set.of("Iqama", "License copy");
    private final Path root;
    private final DriverService drivers;
    private final DriverFileRepository files;

    public DriverFileService(@Value("${app.upload-dir}") String uploadDir, DriverService drivers, DriverFileRepository files) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.drivers = drivers;
        this.files = files;
    }

    @Transactional
    public DriverFileResponse uploadPhoto(long driverId, MultipartFile upload) {
        DriverFile existing = files.findByDriverIdAndKind(driverId, "PHOTO").orElse(null);
        DriverFileResponse saved = save(driverId, "PHOTO", null, null, null, null, upload);
        replace(existing);
        return saved;
    }

    @Transactional
    public DriverFileResponse uploadDocument(long driverId, String type, LocalDate expiryDate, MultipartFile upload) {
        if (!DOCUMENT_TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported driver document type.");
        if (EXPIRING_DOCUMENTS.contains(type) && expiryDate == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Expiry date is required for Iqama and License copy.");
        DriverFile existing = files.findByDriverIdAndKindAndDocumentType(driverId, "DOCUMENT", type).orElse(null);
        if (expiryDate == null) {
            expiryDate = existing != null && existing.getExpiryDate() != null ? existing.getExpiryDate()
                    : DriverCompliance.profileDates(drivers.require(driverId)).get(type);
        }
        if (DriverCompliance.EXPIRING_COPIES.contains(type) && expiryDate == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Expiry date is required for " + type + ".");
        DriverFileResponse saved = save(driverId, "DOCUMENT", type, null, null, expiryDate, upload);
        replace(existing);
        DriverCompliance.syncDate(drivers.require(driverId), type, expiryDate);
        return saved;
    }

    @Transactional
    public DriverFileResponse addCertification(long driverId, CertificationRequest request, MultipartFile upload) {
        if (files.countByDriverIdAndKind(driverId, "CERTIFICATION") >= 10) throw new ApiException(HttpStatus.BAD_REQUEST, "A driver can have up to 10 certifications.");
        validateCertification(request);
        return save(driverId, "CERTIFICATION", null, request.name().trim(), request.issueDate(), request.expiryDate(), upload);
    }

    @Transactional
    public DriverFileResponse updateCertification(long driverId, long fileId, CertificationRequest request) {
        DriverFile file = requireFile(driverId, fileId);
        if (!"CERTIFICATION".equals(file.getKind())) throw new ApiException(HttpStatus.BAD_REQUEST, "This is not a certification.");
        validateCertification(request);
        file.setCertificateName(request.name().trim());
        file.setIssueDate(request.issueDate());
        file.setExpiryDate(request.expiryDate());
        return DriverFileMapper.toResponse(file);
    }

    @Transactional(readOnly = true)
    public FileDownload download(long driverId, long fileId) {
        DriverFile file = requireFile(driverId, fileId);
        if (file.getStorageKey() == null) throw new ApiException(HttpStatus.NOT_FOUND, "No file is attached to this certification.");
        Path location = path(file);
        if (!Files.isRegularFile(location)) throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable.");
        try { return new FileDownload(new UrlResource(location.toUri()), file.getOriginalName(), file.getContentType()); }
        catch (MalformedURLException error) { throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable."); }
    }

    @Transactional
    public void delete(long driverId, long fileId) {
        DriverFile file = requireFile(driverId, fileId);
        files.delete(file);
        if (file.getStorageKey() != null) afterCommitDelete(path(file));
    }

    private DriverFileResponse save(long driverId, String kind, String type, String certificateName,
            LocalDate issueDate, LocalDate expiryDate, MultipartFile upload) {
        Driver driver = drivers.require(driverId);
        if (upload == null && !"CERTIFICATION".equals(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a file.");
        Path location = null;
        MediaType detected = null;
        if (upload != null) {
            if (upload.isEmpty() || upload.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a non-empty file up to 5 MB.");
            detected = detect(upload);
            if (detected == null || ("PHOTO".equals(kind) && "application/pdf".equals(detected.contentType())))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported file. Use PNG, JPG, WebP, or PDF for documents; photos cannot be PDF.");
            location = root.resolve("drivers").resolve(String.valueOf(driverId)).resolve(UUID.randomUUID() + detected.extension()).normalize();
            if (!location.startsWith(root)) throw new IllegalStateException("Invalid storage location.");
        }
        Path stored = location;
        try {
            if (upload != null) {
                Files.createDirectories(stored.getParent());
                try (InputStream stream = upload.getInputStream()) { Files.copy(stream, stored, StandardCopyOption.REPLACE_EXISTING); }
            }
            DriverFile file = new DriverFile();
            file.setDriver(driver);
            file.setKind(kind);
            file.setDocumentType(type);
            file.setCertificateName(certificateName);
            file.setIssueDate(issueDate);
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
            return DriverFileMapper.toResponse(file);
        } catch (IOException | RuntimeException error) {
            if (stored != null) try { Files.deleteIfExists(stored); } catch (IOException ignored) { }
            throw error instanceof IOException ? new IllegalStateException("Could not store the uploaded file.", error) : (RuntimeException) error;
        }
    }

    private void replace(DriverFile existing) {
        if (existing == null) return;
        files.delete(existing);
        if (existing.getStorageKey() != null) afterCommitDelete(path(existing));
    }

    private void validateCertification(CertificationRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().length() > 150)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a certification name up to 150 characters.");
        if (request.issueDate() != null && request.expiryDate() != null && request.expiryDate().isBefore(request.issueDate()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Certification expiry must be on or after its issue date.");
    }

    private DriverFile requireFile(long driverId, long fileId) {
        drivers.require(driverId);
        return files.findByIdAndDriverId(fileId, driverId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Driver file not found."));
    }

    private Path path(DriverFile file) {
        return root.resolve("drivers").resolve(String.valueOf(file.getDriver().getId())).resolve(file.getStorageKey()).normalize();
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
