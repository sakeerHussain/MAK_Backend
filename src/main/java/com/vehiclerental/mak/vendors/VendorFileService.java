package com.vehiclerental.mak.vendors;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.vehiclerental.mak.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VendorFileService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("Company Profile", "CR Copy", "VAT Certificate", "Bank IBAN", "National Address");
    private final Path root;
    private final VendorService vendors;
    private final VendorFileRepository files;

    public VendorFileService(@Value("${app.upload-dir}") String uploadDir, VendorService vendors, VendorFileRepository files) {
        root = Path.of(uploadDir).toAbsolutePath().normalize(); this.vendors = vendors; this.files = files;
    }

    @Transactional
    public VendorResponse.Attachment upload(long vendorId, String type, MultipartFile upload) {
        Vendor vendor = vendors.require(vendorId);
        if (!TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported vendor attachment type.");
        if (upload == null || upload.isEmpty() || upload.getSize() > MAX_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a non-empty file up to 5 MB.");
        MediaType detected = detect(upload);
        if (detected == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Use a PNG, JPG, WebP, or PDF file.");
        VendorFile previous = files.findByVendorIdAndDocumentType(vendorId, type).orElse(null);
        Path location = root.resolve("vendors").resolve(String.valueOf(vendorId)).resolve(UUID.randomUUID() + detected.extension()).normalize();
        if (!location.startsWith(root)) throw new IllegalStateException("Invalid storage location.");
        try {
            Files.createDirectories(location.getParent());
            try (InputStream stream = upload.getInputStream()) { Files.copy(stream, location, StandardCopyOption.REPLACE_EXISTING); }
            if (previous != null) { files.delete(previous); files.flush(); }
            VendorFile file = new VendorFile();
            file.setVendor(vendor); file.setDocumentType(type); file.setStorageKey(location.getFileName().toString());
            file.setOriginalName(cleanName(upload.getOriginalFilename())); file.setContentType(detected.contentType());
            file.setSizeBytes(upload.getSize()); file.setCreatedAt(Instant.now());
            files.saveAndFlush(file);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) try { Files.deleteIfExists(location); } catch (IOException ignored) { }
                }
            });
            if (previous != null) afterCommitDelete(path(previous));
            return VendorResponse.Attachment.from(file);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(location); } catch (IOException ignored) { }
            throw error instanceof IOException ? new IllegalStateException("Could not store the uploaded file.", error) : (RuntimeException) error;
        }
    }

    @Transactional(readOnly = true)
    public FileDownload download(long vendorId, long fileId) {
        VendorFile file = require(vendorId, fileId);
        Path location = path(file);
        if (!Files.isRegularFile(location)) throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable.");
        try { return new FileDownload(new UrlResource(location.toUri()), file.getOriginalName(), file.getContentType()); }
        catch (MalformedURLException error) { throw new ApiException(HttpStatus.NOT_FOUND, "File is unavailable."); }
    }

    @Transactional
    public void delete(long vendorId, long fileId) {
        VendorFile file = require(vendorId, fileId);
        files.delete(file);
        afterCommitDelete(path(file));
    }

    private VendorFile require(long vendorId, long fileId) {
        vendors.require(vendorId);
        return files.findByIdAndVendorId(fileId, vendorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vendor attachment not found."));
    }

    private Path path(VendorFile file) {
        return root.resolve("vendors").resolve(String.valueOf(file.getVendor().getId())).resolve(file.getStorageKey()).normalize();
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
            if (first.length >= 12 && new String(first, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(first, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return new MediaType("image/webp", ".webp");
            if (first.length >= 4 && new String(first, 0, 4, StandardCharsets.US_ASCII).equals("%PDF"))
                return new MediaType("application/pdf", ".pdf");
            return null;
        } catch (IOException error) { throw new IllegalStateException("Could not inspect uploaded file.", error); }
    }

    private String cleanName(String original) {
        String name = original == null ? "upload" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", " ");
        return name.isBlank() ? "upload" : name.length() > 255 ? name.substring(0, 255) : name;
    }

    private record MediaType(String contentType, String extension) { }
    public record FileDownload(Resource resource, String name, String contentType) { }
}
