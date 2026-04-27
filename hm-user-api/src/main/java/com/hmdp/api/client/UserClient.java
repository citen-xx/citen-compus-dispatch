package com.hmdp.api.client;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * 用户服务 Feign 客户端接口，后续在此补充远程调用方法。
 */
@FeignClient(name = "hm-user-service", path = "/users")
public interface UserClient {
}
