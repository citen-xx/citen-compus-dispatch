package com.citen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citen.dto.Result;
import com.citen.dto.ReservationRequest;
import com.citen.entity.Reservation;

public interface IReservationService extends IService<Reservation> {

    Result reserveResource(Long resourceId, ReservationRequest request);

    Result queryAdminReservationPage(Long current, Long size);

    Result confirmReservation(Long reservationId);

    Result cancelReservation(Long reservationId);

    Result completeReservation(Long reservationId);

    void createReservation(Reservation reservation);

    void expireReservation(Long reservationId);
}
