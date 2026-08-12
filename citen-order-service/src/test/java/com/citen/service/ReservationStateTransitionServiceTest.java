package com.citen.service;

import com.citen.common.ReservationStatus;
import com.citen.common.ReservationStatusEvent;
import com.citen.entity.Reservation;
import com.citen.mapper.ReservationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationStateTransitionServiceTest {

    @Test
    void shouldAllowOnlyExpectedTransitions() {
        ReservationStateTransitionService service = new ReservationStateTransitionService();

        assertEquals(ReservationStatus.CONFIRMED,
                service.targetStatus(ReservationStatus.PENDING.getCode(), ReservationStatusEvent.CONFIRM));
        assertEquals(ReservationStatus.CANCELLED,
                service.targetStatus(ReservationStatus.PENDING.getCode(), ReservationStatusEvent.CANCEL));
        assertEquals(ReservationStatus.EXPIRED,
                service.targetStatus(ReservationStatus.PENDING.getCode(), ReservationStatusEvent.EXPIRE));
        assertNull(service.targetStatus(ReservationStatus.EXPIRED.getCode(), ReservationStatusEvent.CONFIRM));
        assertNull(service.targetStatus(ReservationStatus.CANCELLED.getCode(), ReservationStatusEvent.EXPIRE));
    }

    @Test
    void duplicateTimeoutMessagesCanOnlyExpireOnce() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationStateTransitionService service = serviceWithMapper(mapper);
        Reservation pending = pendingReservation(LocalDateTime.now().minusMinutes(1));
        AtomicBoolean updated = new AtomicBoolean();
        when(mapper.selectById(1L)).thenReturn(pending);
        when(mapper.update(any(Reservation.class), any(LambdaUpdateWrapper.class)))
                .thenAnswer(invocation -> updated.compareAndSet(false, true) ? 1 : 0);

        assertTrue(service.transitionReservationStatus(1L, null, ReservationStatusEvent.EXPIRE));
        assertFalse(service.transitionReservationStatus(1L, null, ReservationStatusEvent.EXPIRE));
        verify(mapper, times(2)).update(any(Reservation.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void confirmAndTimeoutRaceCanOnlyHaveOneWinner() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationStateTransitionService service = serviceWithMapper(mapper);
        Reservation pending = pendingReservation(LocalDateTime.now().plusMinutes(1));
        AtomicBoolean updated = new AtomicBoolean();
        when(mapper.selectById(1L)).thenReturn(pending);
        when(mapper.update(any(Reservation.class), any(LambdaUpdateWrapper.class)))
                .thenAnswer(invocation -> updated.compareAndSet(false, true) ? 1 : 0);

        boolean confirmed = service.transitionReservationStatus(1L, 10L, ReservationStatusEvent.CONFIRM);
        boolean expired = service.transitionReservationStatus(1L, null, ReservationStatusEvent.EXPIRE);

        assertTrue(confirmed ^ expired);
    }

    @Test
    void confirmTransitionWritesExpectedTargetStatus() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationStateTransitionService service = serviceWithMapper(mapper);
        when(mapper.selectById(1L)).thenReturn(pendingReservation(LocalDateTime.now().plusMinutes(1)));
        when(mapper.update(any(Reservation.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(service.transitionReservationStatus(1L, 10L, ReservationStatusEvent.CONFIRM));

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(mapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        assertEquals(ReservationStatus.CONFIRMED.getCode(), captor.getValue().getStatus());
        assertTrue(captor.getValue().getConfirmTime() != null);
    }

    private ReservationStateTransitionService serviceWithMapper(ReservationMapper mapper) {
        ReservationStateTransitionService service = new ReservationStateTransitionService();
        ReflectionTestUtils.setField(service, "reservationMapper", mapper);
        return service;
    }

    private Reservation pendingReservation(LocalDateTime expireAt) {
        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setUserId(10L);
        reservation.setStatus(ReservationStatus.PENDING.getCode());
        reservation.setExpireAt(expireAt);
        return reservation;
    }
}
