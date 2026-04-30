package com.citen.config;

import com.citen.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WebExceptionAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(WebExceptionAdvice.class);

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        LOG.error(e.toString(), e);
        return Result.fail("服务异常");
    }
}
