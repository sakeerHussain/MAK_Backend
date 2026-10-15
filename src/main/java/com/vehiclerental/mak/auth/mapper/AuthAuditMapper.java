package com.vehiclerental.mak.auth.mapper;

import com.vehiclerental.mak.auth.dto.response.AuthAuditResponse;
import com.vehiclerental.mak.auth.entity.AuthAudit;

public final class AuthAuditMapper {
    private AuthAuditMapper() { }

    public static AuthAuditResponse toResponse(AuthAudit audit) {
        return new AuthAuditResponse(audit.getId(), audit.getActorId(), audit.getTargetId(),
                audit.getEvent(), audit.getSummary(), audit.getCreatedAt());
    }
}
