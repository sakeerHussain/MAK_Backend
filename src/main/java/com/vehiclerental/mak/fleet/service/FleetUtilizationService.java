package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.fleet.dto.response.FleetUtilizationResponse;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class FleetUtilizationService {
    public enum Period { LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH }
    private static final ZoneId KSA = ZoneId.of("Asia/Riyadh");
    private final VehicleRepository vehicles;
    private final RentalRepository rentals;
    private final ObjectMapper json;

    public FleetUtilizationService(VehicleRepository vehicles, RentalRepository rentals, ObjectMapper json) {
        this.vehicles=vehicles; this.rentals=rentals; this.json=json;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public FleetUtilizationResponse get(Period period) {
        return calculate(period, LocalDateTime.now(KSA));
    }

    // KSA calendar periods include today only up to the report time, never future capacity.
    FleetUtilizationResponse calculate(Period period, LocalDateTime end) {
        LocalDate today=end.toLocalDate();
        LocalDateTime start=switch(period) {
            case LAST_7_DAYS -> today.minusDays(6).atStartOfDay();
            case LAST_30_DAYS -> today.minusDays(29).atStartOfDay();
            case THIS_MONTH -> today.withDayOfMonth(1).atStartOfDay();
        };
        long fleetSize=vehicles.count();
        long capacity=Math.multiplyExact(fleetSize, Duration.between(start,end).getSeconds());
        Map<Long,List<Interval>> byVehicle=new HashMap<>();
        int measured=0,omitted=0;
        if(fleetSize>0) for(var source:rentals.utilizationSources()) {
            // Defensive status checks also keep future repository changes from counting reservations.
            if(source.vehicleId()==null || !Set.of("Active","Completed").contains(source.status())) continue;
            LocalDateTime handedOver,returned;
            try {
                var payload=json.readTree(source.payload());
                handedOver=parseTime(payload.path("handedOverAt").asText(""));
                returned="Active".equals(source.status())?end:parseTime(payload.path("actualReturnAt").asText(""));
                if(handedOver==null || returned==null) { omitted++;continue; }
                if(handedOver.isAfter(end)) continue;
                if(returned.isBefore(handedOver)) { omitted++;continue; }
            } catch(Exception invalid) { omitted++;continue; }
            LocalDateTime from=handedOver.isBefore(start)?start:handedOver;
            LocalDateTime until=returned.isAfter(end)?end:returned;
            if(!until.isAfter(from)) continue;
            byVehicle.computeIfAbsent(source.vehicleId(),ignored->new ArrayList<>()).add(new Interval(from,until));
            measured++;
        }
        long used=0;
        for(var intervals:byVehicle.values()) {
            intervals.sort(Comparator.comparing(Interval::start));
            Interval merged=null;
            for(var next:intervals) {
                if(merged==null) merged=next;
                else if(!next.start().isAfter(merged.end())) {
                    if(next.end().isAfter(merged.end())) merged=new Interval(merged.start(),next.end());
                } else { used+=Duration.between(merged.start(),merged.end()).getSeconds();merged=next; }
            }
            if(merged!=null) used+=Duration.between(merged.start(),merged.end()).getSeconds();
        }
        BigDecimal percent=capacity==0?null:BigDecimal.valueOf(used).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(capacity),2,RoundingMode.HALF_UP);
        return new FleetUtilizationResponse(period.name(),KSA.getId(),start,end,fleetSize,used,capacity,percent,measured,omitted);
    }

    private LocalDateTime parseTime(String raw) {
        if(raw==null || raw.isBlank()) return null;
        if(raw.endsWith("Z") || raw.matches(".*[+-]\\d{2}:\\d{2}$"))
            return OffsetDateTime.parse(raw).atZoneSameInstant(KSA).toLocalDateTime();
        return LocalDateTime.parse(raw);
    }
    private record Interval(LocalDateTime start,LocalDateTime end) { }
}
