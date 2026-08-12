package com.citen.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationLuaContractTest {

    @Test
    void reserveScriptChecksUserDuplicateAndCapacityBeforeWritingStream() throws IOException {
        String script = classpathResource("/seckill.lua");

        assertTrue(script.contains("getbit', userSlotsKey"));
        assertTrue(script.contains("occupied >= quota"));
        assertTrue(script.contains("hincrby', slotsKey"));
        assertTrue(script.contains("xadd', streamKey"));
        assertTrue(script.contains("redis.pcall('xadd'"));
        assertTrue(script.contains("return 5"));
        assertFalse(script.contains("redis.call('decr', quotaKey)"));
    }

    @Test
    void repeatedCompensationDoesNotIncreaseCapacityOrReleaseTwice() throws IOException {
        String script = classpathResource("/release-reservation.lua");

        assertTrue(script.contains("state') == 'COMPENSATED'"));
        assertTrue(script.contains("hincrby', KEYS[1], field, -1"));
        assertTrue(script.contains("setbit', KEYS[2], minute, 0"));
        assertFalse(script.contains("redis.call('incr'"));
    }

    private String classpathResource(String path) throws IOException {
        try (java.io.InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing test resource: " + path);
            }
            byte[] bytes = new byte[8192];
            StringBuilder content = new StringBuilder();
            int length;
            while ((length = input.read(bytes)) != -1) {
                content.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
            }
            return content.toString();
        }
    }
}
