package com.citen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citen.dto.Result;
import com.citen.entity.Reservation;

public interface IReservationService extends IService<Reservation> {

    Result reserveResource(Long resourceId);

    Result queryAdminReservationPage(Long current, Long size);

    Result confirmReservation(Long reservationId);

    Result cancelReservation(Long reservationId);

    void createReservation(Reservation reservation);

    void markTimeoutBreach(Long reservationId);
}
