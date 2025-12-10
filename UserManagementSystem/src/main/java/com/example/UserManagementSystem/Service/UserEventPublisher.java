package com.example.UserManagementSystem.Service;


import com.example.UserManagementSystem.Configuration.RabbitConfig;
import com.example.UserManagementSystem.DTO.UserActivityEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishUserEvent(UserActivityEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.USER_EVENTS_EXCHANGE,
                "user.events." + event.getType().toLowerCase(), // e.g. user.events.register
                event
        );
    }
}

