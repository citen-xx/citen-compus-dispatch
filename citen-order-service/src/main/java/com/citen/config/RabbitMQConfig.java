package com.citen.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String RESERVATION_EVENT_EXCHANGE = "reservation.event.exchange";
    public static final String RESERVATION_DELAY_QUEUE = "reservation.queue";
    public static final String RESERVATION_DELAY_ROUTING_KEY = "reservation.delay";

    public static final String RESERVATION_DLX_EXCHANGE = "reservation.dlx.exchange";
    public static final String RESERVATION_TIMEOUT_QUEUE = "reservation.timeout.queue";
    public static final String RESERVATION_TIMEOUT_ROUTING_KEY = "reservation.timeout";
    public static final String CONSUMER_DLX_EXCHANGE = "consumer.dlx.exchange";
    public static final String CONSUMER_DLX_ROUTING_KEY = "consumer.dlx.routing.key";
    public static final String CONSUMER_DLX_QUEUE_NAME = "consumer.dlx.queue";

    @Bean
    public DirectExchange reservationEventExchange() {
        return new DirectExchange(RESERVATION_EVENT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange reservationDlxExchange() {
        return new DirectExchange(RESERVATION_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue reservationDelayQueue() {
        return QueueBuilder.durable(RESERVATION_DELAY_QUEUE)
                .deadLetterExchange(RESERVATION_DLX_EXCHANGE)
                .deadLetterRoutingKey(RESERVATION_TIMEOUT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue reservationTimeoutQueue() {
        return QueueBuilder.durable(RESERVATION_TIMEOUT_QUEUE)
                .deadLetterExchange(CONSUMER_DLX_EXCHANGE)
                .deadLetterRoutingKey(CONSUMER_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange consumerDlxExchange() {
        return new DirectExchange(CONSUMER_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue consumerDlxQueue() {
        return QueueBuilder.durable(CONSUMER_DLX_QUEUE_NAME).build();
    }

    @Bean
    public Binding reservationDelayBinding() {
        return BindingBuilder.bind(reservationDelayQueue())
                .to(reservationEventExchange())
                .with(RESERVATION_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding reservationTimeoutBinding() {
        return BindingBuilder.bind(reservationTimeoutQueue())
                .to(reservationDlxExchange())
                .with(RESERVATION_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding consumerDlxBinding() {
        return BindingBuilder.bind(consumerDlxQueue())
                .to(consumerDlxExchange())
                .with(CONSUMER_DLX_ROUTING_KEY);
    }
}
