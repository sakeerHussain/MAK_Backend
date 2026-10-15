package com.vehiclerental.mak.auth.dto.response;

import java.time.Instant;

public record AuthAuditResponse(Long id, Long actorId, Long targetId, String event,
        String summary, Instant createdAt) { }
