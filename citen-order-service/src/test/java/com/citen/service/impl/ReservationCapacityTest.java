package com.citen.service.impl;

import com.citen.entity.Reservation;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

class ReservationCapacityTest {

    @Test
    void duplicateStreamMessageWithSameReservationIdIsIdempotent() {
        ReservationServiceImpl service = spy(new ReservationServiceImpl());
        com.citen.mapper.ReservationMapper mapper = mock(com.citen.mapper.ReservationMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", mapper);
        Reservation existing = reservation(7L, "09:00", "10:00");
        existing.setId(100L);
        doReturn(existing).when(service).getById(100L);

        service.createReservation(existing);

        verifyNoInteractions(mapper);
    }

    @Test
    void multipleUsersCannotExceedFiniteCapacity() {
        Reservation existing = reservation(1L, "09:00", "10:00");

        assertTrue(ReservationServiceImpl.hasCapacityConflict(
                Collections.singletonList(existing), 1, time("09:30"), time("10:30")));
        assertFalse(ReservationServiceImpl.hasCapacityConflict(
                Collections.singletonList(existing), 2, time("09:30"), time("10:30")));
    }

    @Test
    void nonOverlappingReservationsDoNotConsumeEachOthersCapacity() {
        Reservation morning = reservation(1L, "09:00", "10:00");

        assertFalse(ReservationServiceImpl.hasCapacityConflict(
                Collections.singletonList(morning), 1, time("10:00"), time("11:00")));
    }

    @Test
    void capacityIsCheckedForEveryMinuteInsteadOfCountingAllOverlappingRows() {
        Reservation first = reservation(1L, "09:00", "10:00");
        Reservation second = reservation(2L, "10:00", "11:00");

        assertFalse(ReservationServiceImpl.hasCapacityConflict(
                Arrays.asList(first, second), 2, time("09:30"), time("10:30")));
    }

    @Test
    void sameUserCannotReserveAnOverlappingSlotTwice() {
        Reservation existing = reservation(7L, "09:00", "10:00");

        assertTrue(ReservationServiceImpl.hasDuplicateUserReservation(
                Collections.singletonList(existing), 7L));
        assertFalse(ReservationServiceImpl.hasDuplicateUserReservation(
                Collections.singletonList(existing), 8L));
    }

    private Reservation reservation(Long userId, String start, String end) {
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setStartTime(time(start));
        reservation.setEndTime(time(end));
        return reservation;
    }

    private LocalTime time(String value) {
        return LocalTime.parse(value);
    }
}
