package com.citen.service.impl;

import com.citen.service.IReservationService;
import com.citen.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ReservationStreamProcessingTest {

    @Test
    void lockFailureKeepsStreamRecordPending() {
        ReservationServiceImpl service = newService();
        RLock lock = mock(RLock.class);
        when(getRedisson(service).getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        IReservationService proxy = getProxy(service);

        assertThrows(ReservationServiceImpl.ReservationLockException.class,
                () -> service.processReservationRecord(record(1L)));

        verify(proxy, never()).createReservation(any());
        verify(getStream(service), never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void repeatedDatabaseFailureIsCompensatedAndMovedToFailedStream() {
        ReservationServiceImpl service = newService();
        RLock lock = mockLock(getRedisson(service));
        IReservationService proxy = getProxy(service);
        doThrow(new IllegalStateException("db down")).when(proxy).createReservation(any());
        HashOperations<String, Object, Object> retries = getHash(service);
        when(retries.increment(anyString(), anyString(), anyLong())).thenReturn(1L, 2L, 3L);
        when(getTemplate(service).execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);

        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class, () -> service.processReservationRecord(record(2L)));
        }
        service.processReservationRecord(record(2L));

        verify(getTemplate(service), times(1)).execute(any(DefaultRedisScript.class), anyList(), anyString());
        verify(getStream(service), times(1)).add(
                eq(RedisConstants.RESERVATION_FAILED_STREAM_KEY), anyMap());
        verify(getStream(service), times(1)).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(lock, times(3)).unlock();
    }

    @Test
    void expiredRedisMetadataIsTreatedAsAnAlreadyCompletedRelease() {
        ReservationServiceImpl service = newService();
        when(getTemplate(service).execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(-2L);

        assertTrue(service.releaseRedisReservation(reservation(4L), "test"));
    }

    @Test
    void postPersistenceMarkFailureDoesNotCompensateDatabaseReservation() {
        ReservationServiceImpl service = newService();
        mockLock(getRedisson(service));
        IReservationService proxy = getProxy(service);
        when(getTemplate(service).hasKey(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(RuntimeException.class, () -> service.processReservationRecord(record(3L)));

        verify(proxy).createReservation(any());
        verify(getTemplate(service), never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    void malformedMessageIsRetriedThenMovedWithoutResourceCompensation() {
        ReservationServiceImpl service = newService();
        HashOperations<String, Object, Object> retries = getHash(service);
        when(retries.increment(anyString(), anyString(), anyLong())).thenReturn(1L, 2L, 3L);
        MapRecord<String, Object, Object> invalid = StreamRecords.<String, Object, Object>mapBacked(new HashMap<>())
                .withStreamKey(RedisConstants.RESERVATION_STREAM_KEY)
                .withId(RecordId.of("2-1"));

        assertThrows(RuntimeException.class, () -> service.processReservationRecord(invalid));
        assertThrows(RuntimeException.class, () -> service.processReservationRecord(invalid));
        service.processReservationRecord(invalid);

        verify(getTemplate(service), never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
        verify(getStream(service)).add(eq(RedisConstants.RESERVATION_FAILED_STREAM_KEY), anyMap());
        verify(getStream(service)).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @SuppressWarnings("unchecked")
    private ReservationServiceImpl newService() {
        ReservationServiceImpl service = new ReservationServiceImpl();
        ReflectionTestUtils.setField(service, "redissonClient", mock(RedissonClient.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(service, "reservationServiceProxy", mock(IReservationService.class));
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        StringRedisTemplate template = getTemplate(service);
        when(template.opsForHash()).thenReturn(hash);
        when(template.opsForStream()).thenReturn(stream);
        when(template.hasKey(anyString())).thenReturn(true);
        return service;
    }

    private RLock mockLock(RedissonClient redisson) {
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    private MapRecord<String, Object, Object> record(long id) {
        Map<Object, Object> values = new HashMap<>();
        values.put("id", String.valueOf(id));
        values.put("userId", "10");
        values.put("resourceId", "1");
        values.put("reservationDate", "2026-08-20");
        values.put("startTime", "09:00");
        values.put("endTime", "10:00");
        return StreamRecords.mapBacked(values)
                .withStreamKey(RedisConstants.RESERVATION_STREAM_KEY)
                .withId(RecordId.of("1-" + id));
    }

    private com.citen.entity.Reservation reservation(long id) {
        com.citen.entity.Reservation reservation = new com.citen.entity.Reservation();
        reservation.setId(id);
        reservation.setUserId(10L);
        reservation.setResourceId(1L);
        reservation.setReservationDate(java.time.LocalDate.parse("2026-08-20"));
        reservation.setStartTime(java.time.LocalTime.parse("09:00"));
        reservation.setEndTime(java.time.LocalTime.parse("10:00"));
        return reservation;
    }

    @SuppressWarnings("unchecked")
    private RedissonClient getRedisson(ReservationServiceImpl service) {
        return (RedissonClient) ReflectionTestUtils.getField(service, "redissonClient");
    }

    @SuppressWarnings("unchecked")
    private IReservationService getProxy(ReservationServiceImpl service) {
        return (IReservationService) ReflectionTestUtils.getField(service, "reservationServiceProxy");
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate getTemplate(ReservationServiceImpl service) {
        return (StringRedisTemplate) ReflectionTestUtils.getField(service, "stringRedisTemplate");
    }

    @SuppressWarnings("unchecked")
    private HashOperations<String, Object, Object> getHash(ReservationServiceImpl service) {
        return getTemplate(service).opsForHash();
    }

    @SuppressWarnings("unchecked")
    private StreamOperations<String, Object, Object> getStream(ReservationServiceImpl service) {
        return getTemplate(service).opsForStream();
    }
}
