package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP=1640995200L;
    //序列号的位数
    private static final int COUNT_BITS=32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrifix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=second-BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前精确到天的
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrifix+":"+date);

        //拼接并且返回

        return timestamp<<COUNT_BITS |count;
    }


}
