package com.example.UserManagementSystem.Configuration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String USER_EVENTS_EXCHANGE = "user.events.exchange";
    public static final String USER_EVENTS_QUEUE = "user.events.queue";
    public static final String USER_EVENTS_ROUTING_KEY = "user.events.#";

    @Bean
    public TopicExchange userEventsExchange() {
        return new TopicExchange(USER_EVENTS_EXCHANGE);
    }

    @Bean
    public Queue userEventsQueue() {
        return new Queue(USER_EVENTS_QUEUE, true);
    }

    @Bean
    public Binding userEventsBinding(Queue userEventsQueue, TopicExchange userEventsExchange) {
        return BindingBuilder
                .bind(userEventsQueue)
                .to(userEventsExchange)
                .with(USER_EVENTS_ROUTING_KEY);
    }
}
