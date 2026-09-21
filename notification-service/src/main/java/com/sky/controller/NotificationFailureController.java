package com.sky.controller;

import com.sky.service.NotificationConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/internal/notifications/failures")
@RequiredArgsConstructor
public class NotificationFailureController {
    private final NotificationConsumer consumer;
    @Value("${sky.internal.token}") private String token;
    @GetMapping public Map<Object,Object> list(@RequestHeader("X-Internal-Token") String value){ check(value); return consumer.failures(); }
    @PostMapping("/{id}/replay") public void replay(@RequestHeader("X-Internal-Token") String value,@PathVariable String id){ check(value);consumer.replay(id); }
    private void check(String value){if(!Objects.equals(token,value))throw new IllegalArgumentException("invalid internal token");}
}
