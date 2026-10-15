package com.vehiclerental.mak.common.dto.response;

import org.springframework.core.io.Resource;

public record FileDownload(Resource resource, String name, String contentType) { }
