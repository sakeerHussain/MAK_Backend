package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.rentals.repository.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;

class FleetUtilizationTests {
    VehicleRepository vehicles; RentalRepository rentals; FleetUtilizationService service;
    final LocalDateTime now=LocalDateTime.parse("2026-10-26T12:00:00");
    @BeforeEach void setup(){vehicles=mock(VehicleRepository.class);rentals=mock(RentalRepository.class);service=new FleetUtilizationService(vehicles,rentals,new ObjectMapper());when(vehicles.count()).thenReturn(2L);when(rentals.utilizationSources()).thenReturn(List.of());}
    RentalUsageSource usage(long vehicle,String status,String start,String end){
        String payload="{\"handedOverAt\":\""+start+"\",\"actualReturnAt\":\""+end+"\",\"pickupAt\":\"2026-01-01T00:00\",\"returnAt\":\"2026-01-02T00:00\"}";
        return new RentalUsageSource(vehicle,status,payload);
    }
    @Test void ksaCalendarPeriodsExcludeFutureCapacity(){
        var seven=service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now);
        assertEquals(LocalDateTime.parse("2026-10-20T00:00"),seven.start());assertEquals(312*3600,seven.capacitySeconds());
        assertEquals("Asia/Riyadh",seven.timeZone());assertEquals(now,seven.end());
        assertEquals(LocalDateTime.parse("2026-09-27T00:00"),service.calculate(FleetUtilizationService.Period.LAST_30_DAYS,now).start());
        assertEquals(LocalDateTime.parse("2026-10-01T00:00"),service.calculate(FleetUtilizationService.Period.THIS_MONTH,now).start());
    }
    @Test void actualIntervalsAreClippedAndMergedPerVehicleInsteadOfCountingBookings(){
        when(rentals.utilizationSources()).thenReturn(List.of(
            usage(1,"Completed","2026-10-19T22:00","2026-10-20T06:00"),
            usage(1,"Completed","2026-10-20T04:00","2026-10-20T10:00"),
            usage(2,"Active","2026-10-26T08:00","2099-01-01T00:00"),
            usage(1,"Cancelled","2026-10-20T00:00","2026-10-26T12:00"),
            usage(1,"Confirmed","2026-10-20T00:00","2026-10-26T12:00")));
        var result=service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now);
        assertEquals(14*3600,result.rentedSeconds());assertEquals(new BigDecimal("4.49"),result.utilizationPercent());
        assertEquals(3,result.measuredRentals());assertEquals(0,result.omittedRentals());
    }
    @Test void activeRentalsContinueUntilNowEvenWhenScheduledReturnHasPassed(){
        when(rentals.utilizationSources()).thenReturn(List.of(usage(1,"Active","2026-10-20T00:00","2026-10-20T01:00")));
        assertEquals(156*3600,service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now).rentedSeconds());
    }
    @Test void offsetsAreNormalizedToKsaAndPeriodChangesRecalculateTheSameActualRental(){
        when(rentals.utilizationSources()).thenReturn(List.of(usage(1,"Completed","2026-10-19T22:00:00Z","2026-10-20T01:00:00Z"),usage(2,"Completed","2026-09-29T00:00","2026-10-02T00:00")));
        assertEquals(3*3600,service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now).rentedSeconds());
        assertEquals(75*3600,service.calculate(FleetUtilizationService.Period.LAST_30_DAYS,now).rentedSeconds());
        assertEquals(27*3600,service.calculate(FleetUtilizationService.Period.THIS_MONTH,now).rentedSeconds());
    }
    @Test void incompleteOrMalformedActualTimesAreFlaggedAndNeverReplacedBySchedules(){
        when(rentals.utilizationSources()).thenReturn(List.of(usage(1,"Completed","","2026-10-25T00:00"),usage(1,"Completed","2026-10-25T00:00",""),usage(1,"Completed","2026-10-25T02:00","2026-10-25T01:00"),new RentalUsageSource(1L,"Active","not json")));
        var result=service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now);
        assertEquals(4,result.omittedRentals());assertEquals(0,result.rentedSeconds());assertEquals(new BigDecimal("0.00"),result.utilizationPercent());
    }
    @Test void emptyFleetAndZeroElapsedTimeHaveNoPercentageRatherThanNaN(){
        when(vehicles.count()).thenReturn(0L);var empty=service.calculate(FleetUtilizationService.Period.THIS_MONTH,now);
        assertNull(empty.utilizationPercent());assertEquals(0,empty.capacitySeconds());verifyNoInteractions(rentals);
        when(vehicles.count()).thenReturn(2L);assertNull(service.calculate(FleetUtilizationService.Period.THIS_MONTH,LocalDateTime.parse("2026-10-01T00:00")).utilizationPercent());
    }
    @Test void duplicateOverlapsCannotExceedOneHundredPercentAndOutsidePeriodsAreIgnored(){
        when(rentals.utilizationSources()).thenReturn(List.of(usage(1,"Active","2026-01-01T00:00",""),usage(1,"Active","2026-01-01T00:00",""),usage(2,"Active","2026-01-01T00:00",""),usage(1,"Completed","2026-01-01T00:00","2026-02-01T00:00"),usage(1,"Active","2026-11-01T00:00","")));
        assertEquals(new BigDecimal("100.00"),service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now).utilizationPercent());
    }
    @Test void activityAfterTheSnapshotIsIgnoredWithoutCallingItInvalid(){
        when(rentals.utilizationSources()).thenReturn(List.of(usage(1,"Active","2026-10-26T12:00:00.000000001","")));
        var result=service.calculate(FleetUtilizationService.Period.LAST_7_DAYS,now);
        assertEquals(0,result.rentedSeconds());assertEquals(0,result.omittedRentals());
    }
}
