package com.citen.listener;

import com.citen.config.RabbitMQConfig;
import com.citen.entity.Reservation;
import com.citen.service.IVoucherOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutListener {

    private static final Logger LOG = LoggerFactory.getLogger(OrderTimeoutListener.class);

    @javax.annotation.Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = RabbitMQConfig.RESERVATION_TIMEOUT_QUEUE)
    public void listenOrderTimeoutMessage(Reservation reservation) {
        if (reservation == null || reservation.getId() == null) {
            LOG.error("invalid timeout reservation message, payload is null");
            return;
        }
        voucherOrderService.cancelTimeoutOrder(reservation.getId());
    }
}
