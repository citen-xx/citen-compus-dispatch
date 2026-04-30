package com.citen.listener;

import com.citen.config.RabbitMQConfig;
import com.citen.entity.Reservation;
import com.citen.service.IReservationService;
import com.citen.service.IResourceQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationTimeoutListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationTimeoutListener.class);

    private static final int RESERVATION_STATUS_PENDING_CONFIRM = 1;
    private static final int RESERVATION_STATUS_TIMEOUT_BREACH = 5;

    @javax.annotation.Resource
    private IReservationService reservationService;

    @javax.annotation.Resource
    private IResourceQuotaService resourceQuotaService;

    @RabbitListener(queues = RabbitMQConfig.RESERVATION_TIMEOUT_QUEUE)
    @Transactional
    public void listenReservationTimeoutMessage(Reservation reservation) {
        if (reservation == null || reservation.getId() == null || reservation.getResourceId() == null) {
            LOG.error("invalid timeout reservation message, payload={}", reservation);
            return;
        }

        Reservation dbReservation = reservationService.getById(reservation.getId());
        if (dbReservation == null) {
            LOG.warn("timeout reservation not found, reservationId={}", reservation.getId());
            return;
        }

        if (!Integer.valueOf(RESERVATION_STATUS_PENDING_CONFIRM).equals(dbReservation.getStatus())) {
            LOG.info("timeout reservation ignored, reservationId={}, status={}",
                    dbReservation.getId(), dbReservation.getStatus());
            return;
        }

        boolean timeoutUpdated = reservationService.update()
                .set("status", RESERVATION_STATUS_TIMEOUT_BREACH)
                .eq("id", dbReservation.getId())
                .eq("status", RESERVATION_STATUS_PENDING_CONFIRM)
                .update();

        if (!timeoutUpdated) {
            LOG.info("timeout breach mark skipped by concurrent update, reservationId={}", dbReservation.getId());
            return;
        }

        boolean quotaRestored = resourceQuotaService.update()
                .setSql("quota=quota+1")
                .eq("resource_id", dbReservation.getResourceId())
                .update();

        if (!quotaRestored) {
            LOG.warn("resource quota restore failed, reservationId={}, resourceId={}",
                    dbReservation.getId(), dbReservation.getResourceId());
            return;
        }

        LOG.info("timeout reservation compensated, reservationId={}, resourceId={}, status=TIMEOUT_BREACH",
                dbReservation.getId(), dbReservation.getResourceId());
    }
}
