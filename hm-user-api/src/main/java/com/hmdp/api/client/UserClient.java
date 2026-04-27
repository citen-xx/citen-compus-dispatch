package com.hmdp.api.client;

import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务 Feign 客户端。
 */
@FeignClient("user-service")
public interface UserClient {

    /**
     * 根据用户 ID 查询用户信息，对应 UserController 中的查询接口。
     */
    @GetMapping("/user/{id}")
    Result queryUserById(@PathVariable("id") Long userId);
}
