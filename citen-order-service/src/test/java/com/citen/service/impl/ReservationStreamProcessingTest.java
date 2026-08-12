package com.citen.service.impl;

import com.citen.service.IReservationService;
import com.citen.utils.RedisConstants;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ReservationStreamProcessingTest {

    @Test
    void normalSuccessfulMessageIsAckedAndNotRecoveredAgain() {
        ReservationServiceImpl service = spy(newService());
        mockLock(getRedisson(service));
        doReturn(ReservationServiceImpl.AutoClaimResult.empty())
                .when(service).claimStalePendingMessages();

        service.processReservationRecord(record(10L));
        service.recoverStalePendingMessages();

        verify(getProxy(service), times(1)).createReservation(any());
        verify(getStream(service), times(1))
                .acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshPendingMessageIsNotClaimedBeforeIdleTimeout() throws Exception {
        ReservationServiceImpl service = newService();
        StringRedisTemplate template = getTemplate(service);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamAsyncCommands<byte[], byte[]> commands = mock(RedisStreamAsyncCommands.class);
        RedisFuture<ClaimedMessages<byte[], byte[]>> future = mock(RedisFuture.class);
        ReflectionTestUtils.setField(service, "pendingIdleTimeout", Duration.ofSeconds(60));
        ReflectionTestUtils.setField(service, "pendingRecoveryBatchSize", 10);
        when(connection.getNativeConnection()).thenReturn(commands);
        when(commands.xautoclaim(any(byte[].class), any(XAutoClaimArgs.class))).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new ClaimedMessages<>("0-0", Collections.emptyList()));
        when(template.execute(any(RedisCallback.class))).thenAnswer(invocation ->
                ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection));

        service.recoverStalePendingMessages();

        org.mockito.ArgumentCaptor<XAutoClaimArgs<byte[]>> arguments =
                org.mockito.ArgumentCaptor.forClass(XAutoClaimArgs.class);
        verify(commands).xautoclaim(any(byte[].class), arguments.capture());
        assertEquals(60000L, ReflectionTestUtils.getField(arguments.getValue(), "minIdleTime"));
        assertEquals(10L, ReflectionTestUtils.getField(arguments.getValue(), "count"));
        assertEquals("0-0", ReflectionTestUtils.getField(arguments.getValue(), "startId"));
        verify(getProxy(service), never()).createReservation(any());
        verify(getStream(service), never())
                .acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void stalePendingFromCrashedConsumerIsClaimedProcessedAndAcked() {
        ReservationServiceImpl service = spy(newService());
        mockLock(getRedisson(service));
        doReturn(claimed(record(11L))).when(service).claimStalePendingMessages();

        service.recoverStalePendingMessages();

        verify(getProxy(service)).createReservation(any());
        verify(getStream(service)).acknowledge(
                eq(RedisConstants.RESERVATION_STREAM_KEY), eq("g1"), any(RecordId.class));
    }

    @Test
    void staleClaimAfterDatabaseCommitIsIdempotentlyAckedWithoutCompensation() {
        ReservationServiceImpl service = spy(newService());
        mockLock(getRedisson(service));
        MapRecord<String, Object, Object> record = record(12L);
        com.citen.entity.Reservation existing = reservation(12L);
        doReturn(existing).when(service).getById(12L);
        IReservationService proxy = getProxy(service);
        doAnswer(invocation -> {
            service.createReservation(invocation.getArgument(0));
            return null;
        }).when(proxy).createReservation(any());
        doReturn(claimed(record)).when(service).claimStalePendingMessages();

        service.recoverStalePendingMessages();

        verify(getTemplate(service), never())
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
        verify(getStream(service)).acknowledge(
                eq(RedisConstants.RESERVATION_STREAM_KEY), eq("g1"), eq(record.getId()));
    }

    @Test
    void finalDatabaseFailureAfterClaimUsesExistingCompensationFailedStreamAndAckFlow() {
        ReservationServiceImpl service = spy(newService());
        mockLock(getRedisson(service));
        IReservationService proxy = getProxy(service);
        doThrow(new IllegalStateException("db down"))
                .when(proxy).createReservation(any());
        when(getHash(service).increment(anyString(), anyString(), anyLong())).thenReturn(3L);
        when(getTemplate(service).execute(
                any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);
        doReturn(claimed(record(13L))).when(service).claimStalePendingMessages();

        service.recoverStalePendingMessages();

        verify(getTemplate(service)).execute(
                any(DefaultRedisScript.class), anyList(), anyString());
        verify(getStream(service)).add(
                eq(RedisConstants.RESERVATION_FAILED_STREAM_KEY), anyMap());
        verify(getStream(service)).acknowledge(
                eq(RedisConstants.RESERVATION_STREAM_KEY), eq("g1"), any(RecordId.class));
    }

    @Test
    void lettuceAutoClaimResponseIsConvertedToExistingMapRecordFormat() {
        Map<byte[], byte[]> fields = new HashMap<>();
        fields.put(bytes("id"), bytes("14"));
        fields.put(bytes("userId"), bytes("10"));
        fields.put(bytes("resourceId"), bytes("1"));
        fields.put(bytes("reservationDate"), bytes("2026-08-20"));
        fields.put(bytes("startTime"), bytes("09:00"));
        fields.put(bytes("endTime"), bytes("10:00"));
        StreamMessage<byte[], byte[]> message = new StreamMessage<>(
                bytes(RedisConstants.RESERVATION_STREAM_KEY), "9-14", fields);
        ClaimedMessages<byte[], byte[]> claimed = new ClaimedMessages<>(
                "9-15", Collections.singletonList(message));

        ReservationServiceImpl.AutoClaimResult result =
                ReservationServiceImpl.toAutoClaimResult(claimed);

        assertEquals("9-15", result.nextCursor);
        assertEquals(1, result.records.size());
        assertEquals("9-14", result.records.get(0).getId().getValue());
        assertEquals("14", result.records.get(0).getValue().get("id"));
    }

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

    private ReservationServiceImpl.AutoClaimResult claimed(
            MapRecord<String, Object, Object> record) {
        return new ReservationServiceImpl.AutoClaimResult(
                "0-0", Collections.singletonList(record));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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
