package com.citen.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data

public class RedisDate {
    private LocalDateTime expireTime;
    private Object data;//shop
}
