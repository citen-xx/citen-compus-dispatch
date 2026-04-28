package com.citen;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.citen.mapper")
@SpringBootApplication
public class CitenDpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CitenDpApplication.class, args);
    }

}

