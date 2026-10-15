package com.vehiclerental.mak.vendors.mapper;

import com.vehiclerental.mak.vendors.dto.response.VendorResponse;
import com.vehiclerental.mak.vendors.entity.VendorFile;

public final class VendorAttachmentMapper {
    private VendorAttachmentMapper() { }

    public static VendorResponse.Attachment toResponse(VendorFile file) {
            return new VendorResponse.Attachment(file.getId(), file.getDocumentType(), file.getOriginalName(), file.getSizeBytes(),
                    "/api/vendors/" + file.getVendor().getId() + "/files/" + file.getId());
        }
}
