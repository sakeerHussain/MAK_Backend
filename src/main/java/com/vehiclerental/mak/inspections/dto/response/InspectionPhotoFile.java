package com.vehiclerental.mak.inspections.dto.response;

import org.springframework.core.io.Resource;

public record InspectionPhotoFile(String name,String type,Resource resource){}
