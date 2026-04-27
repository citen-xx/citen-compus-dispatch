package com.hmdp.listener;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE)
    public void listenOrderTimeoutMessage(String orderIdMessage) {
        try {
            Long orderId = Long.valueOf(orderIdMessage);
            voucherOrderService.cancelTimeoutOrder(orderId);
        } catch (NumberFormatException e) {
            log.error("invalid timeout order message, payload={}", orderIdMessage, e);
        }
    }
}
