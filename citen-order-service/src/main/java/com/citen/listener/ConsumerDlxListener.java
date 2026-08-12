package com.citen.listener;

import com.citen.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumerDlxListener {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerDlxListener.class);

    @RabbitListener(queues = RabbitMQConfig.CONSUMER_DLX_QUEUE_NAME)
    public void listenConsumerDlxMessage(Object message) {
        LOG.error("消费者重试全部失败，消息进入死信队列，message={}", message);
    }
}
