package com.citen.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.citen.common.ReservationStatus;
import com.citen.common.ReservationStatusEvent;
import com.citen.entity.Reservation;
import com.citen.mapper.ReservationMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReservationStateTransitionService {

    private static final Map<Integer, EnumMap<ReservationStatusEvent, ReservationStatus>> TRANSITION_RULES = new HashMap<>();

    static {
        addRule(ReservationStatus.PENDING_CONFIRM, ReservationStatusEvent.CONFIRM, ReservationStatus.CONFIRMED);
        addRule(ReservationStatus.PENDING_CONFIRM, ReservationStatusEvent.CANCEL, ReservationStatus.CANCELED);
        addRule(ReservationStatus.PENDING_CONFIRM, ReservationStatusEvent.TIMEOUT, ReservationStatus.TIMEOUT_BREACH);
        addRule(ReservationStatus.CONFIRMED, ReservationStatusEvent.COMPLETE, ReservationStatus.COMPLETED);
    }

    @Resource
    private ReservationMapper reservationMapper;

    public boolean canTransit(Integer currentStatus, ReservationStatusEvent event) {
        return targetStatus(currentStatus, event) != null;
    }

    public ReservationStatus targetStatus(Integer currentStatus, ReservationStatusEvent event) {
        if (currentStatus == null || event == null) {
            return null;
        }
        Map<ReservationStatusEvent, ReservationStatus> eventRuleMap = TRANSITION_RULES.get(currentStatus);
        if (eventRuleMap == null) {
            return null;
        }
        return eventRuleMap.get(event);
    }

    public boolean transitionReservationStatus(Long reservationId, Long userId, ReservationStatusEvent event) {
        if (reservationId == null || event == null) {
            return false;
        }

        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            return false;
        }

        Integer currentStatus = reservation.getStatus();
        ReservationStatus targetStatus = targetStatus(currentStatus, event);
        if (targetStatus == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        Reservation updateEntity = new Reservation();
        updateEntity.setStatus(targetStatus.getCode());
        updateEntity.setUpdateTime(now);
        switch (event) {
            case CONFIRM:
                updateEntity.setConfirmTime(now);
                break;
            case CANCEL:
                updateEntity.setCancelTime(now);
                break;
            case COMPLETE:
                updateEntity.setCompleteTime(now);
                break;
            default:
                break;
        }

        LambdaUpdateWrapper<Reservation> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Reservation::getId, reservationId)
                .eq(Reservation::getStatus, currentStatus);
        if (userId != null) {
            updateWrapper.eq(Reservation::getUserId, userId);
        }

        return reservationMapper.update(updateEntity, updateWrapper) > 0;
    }

    private static void addRule(ReservationStatus fromStatus, ReservationStatusEvent event, ReservationStatus targetStatus) {
        TRANSITION_RULES
                .computeIfAbsent(fromStatus.getCode(), key -> new EnumMap<>(ReservationStatusEvent.class))
                .put(event, targetStatus);
    }
}
