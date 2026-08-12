package com.citen.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(
            @Value("${spring.redis.host}") String host,
            @Value("${spring.redis.port}") int port,
            @Value("${spring.redis.password:}") String password) {
        Config config = new Config();
        org.redisson.config.SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port);
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }
        return Redisson.create(config);
    }
}
