package com.vehiclerental.mak.maintenance.dto.response;

import org.springframework.core.io.Resource;

public record MaintenanceReceipt(org.springframework.core.io.Resource resource,String name,String type){}
