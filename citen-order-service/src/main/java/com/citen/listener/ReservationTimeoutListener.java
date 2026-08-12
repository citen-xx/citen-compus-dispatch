package com.citen.listener;

import com.citen.config.RabbitMQConfig;
import com.citen.service.IReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class ReservationTimeoutListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationTimeoutListener.class);

    @Resource
    private IReservationService reservationService;

    @RabbitListener(queues = RabbitMQConfig.RESERVATION_TIMEOUT_QUEUE)
    public void listenReservationTimeoutMessage(String reservationIdText) {
        if (reservationIdText == null) {
            LOG.error("invalid timeout reservation message, payload is null");
            return;
        }
        try {
            reservationService.expireReservation(Long.valueOf(reservationIdText));
        } catch (NumberFormatException e) {
            LOG.error("invalid timeout reservation ID, payload={}", reservationIdText);
            throw e;
        }
    }
}
