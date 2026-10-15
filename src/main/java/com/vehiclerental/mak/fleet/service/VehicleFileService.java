package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.dto.response.VehicleFileResponse;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.entity.VehicleFile;
import com.vehiclerental.mak.fleet.mapper.VehicleFileMapper;
import com.vehiclerental.mak.fleet.repository.VehicleFileRepository;

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
public class VehicleFileService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> DOCUMENT_TYPES = Set.of("Istimara", "Insurance", "Fahas", "Others");
    private final Path root;
    private final VehicleService vehicles;
    private final VehicleFileRepository files;

    public VehicleFileService(@Value("${app.upload-dir}") String uploadDir, VehicleService vehicles, VehicleFileRepository files) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.vehicles = vehicles;
        this.files = files;
    }

    @Transactional
    public VehicleFileResponse uploadImage(long vehicleId, MultipartFile upload) {
        if (files.countByVehicleIdAndKind(vehicleId, "IMAGE") >= 8) throw new ApiException(HttpStatus.BAD_REQUEST, "A car can have up to 8 images.");
        return save(vehicleId, upload, "IMAGE", null, null);
    }

    @Transactional
    public VehicleFileResponse uploadDocument(long vehicleId, String type, LocalDate expiryDate, MultipartFile upload) {
        if (!DOCUMENT_TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "Document type must be Istimara, Insurance, Fahas, or Others.");
        if (!"Others".equals(type) && expiryDate == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Expiry date is required for Istimara, Insurance, and Fahas.");
        VehicleFile existing = files.findByVehicleIdAndKindAndDocumentType(vehicleId, "DOCUMENT", type).orElse(null);
        VehicleFileResponse saved = save(vehicleId, upload, "DOCUMENT", type, expiryDate);
        if (existing != null) {
            files.delete(existing);
            afterCommitDelete(path(existing));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public FileDownload download(long vehicleId, long fileId) {
        VehicleFile file = requireFile(vehicleId, fileId);
        Path location = path(file);
        if (!Files.isRegularFile(location)) throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable.");
        try {
            return new FileDownload(new UrlResource(location.toUri()), file.getOriginalName(), file.getContentType());
        } catch (MalformedURLException error) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable.");
        }
    }

    @Transactional
    public void delete(long vehicleId, long fileId) {
        VehicleFile file = requireFile(vehicleId, fileId);
        files.delete(file);
        afterCommitDelete(path(file));
    }

    private VehicleFileResponse save(long vehicleId, MultipartFile upload, String kind, String type, LocalDate expiryDate) {
        Vehicle vehicle = vehicles.require(vehicleId);
        if (upload == null || upload.isEmpty() || upload.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a non-empty file up to 5 MB.");
        MediaType detected = detect(upload);
        if (detected == null || ("IMAGE".equals(kind) && "application/pdf".equals(detected.contentType()))) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported file. Use PNG, JPG, WebP, or PDF for documents; images cannot be PDF.");
        String storageKey = UUID.randomUUID() + detected.extension();
        Path location = root.resolve("vehicles").resolve(String.valueOf(vehicleId)).resolve(storageKey).normalize();
        if (!location.startsWith(root)) throw new IllegalStateException("Invalid storage location.");
        try {
            Files.createDirectories(location.getParent());
            try (InputStream stream = upload.getInputStream()) { Files.copy(stream, location, StandardCopyOption.REPLACE_EXISTING); }
            VehicleFile file = new VehicleFile();
            file.setVehicle(vehicle);
            file.setKind(kind);
            file.setDocumentType(type);
            file.setExpiryDate(expiryDate);
            file.setStorageKey(storageKey);
            file.setOriginalName(cleanName(upload.getOriginalFilename()));
            file.setContentType(detected.contentType());
            file.setSizeBytes(upload.getSize());
            file.setCreatedAt(Instant.now());
            files.saveAndFlush(file);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(location); } catch (IOException ignored) { }
                    }
                }
            });
            return VehicleFileMapper.toResponse(file);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(location); } catch (IOException ignored) { }
            throw error instanceof IOException ? new IllegalStateException("Could not store the uploaded file.", error) : (RuntimeException) error;
        }
    }

    private MediaType detect(MultipartFile upload) {
        try (InputStream stream = upload.getInputStream()) {
            byte[] first = stream.readNBytes(12);
            if (first.length >= 8 && Arrays.equals(Arrays.copyOf(first, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return new MediaType("image/png", ".png");
            if (first.length >= 3 && (first[0] & 255) == 255 && (first[1] & 255) == 216 && (first[2] & 255) == 255) return new MediaType("image/jpeg", ".jpg");
            if (first.length >= 12 && new String(first, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(first, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return new MediaType("image/webp", ".webp");
            if (first.length >= 4 && new String(first, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF")) return new MediaType("application/pdf", ".pdf");
            return null;
        } catch (IOException error) {
            throw new IllegalStateException("Could not inspect the uploaded file.", error);
        }
    }

    private VehicleFile requireFile(long vehicleId, long fileId) {
        return files.findByIdAndVehicleId(fileId, vehicleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "File not found."));
    }

    private Path path(VehicleFile file) {
        return root.resolve("vehicles").resolve(String.valueOf(file.getVehicle().getId())).resolve(file.getStorageKey()).normalize();
    }

    private String cleanName(String original) {
        String name = original == null ? "upload" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", " ");
        return name.isBlank() ? "upload" : name.length() > 255 ? name.substring(0, 255) : name;
    }

    private void afterCommitDelete(Path location) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try { Files.deleteIfExists(location); } catch (IOException ignored) { }
            }
        });
    }

    private record MediaType(String contentType, String extension) { }

}
