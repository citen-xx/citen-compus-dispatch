package com.citen.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final String RESOURCE_QUOTA_KEY = "resource:quota:";
    public static final String RESOURCE_RESERVATION_SLOT_KEY = "reservation:slots:";
    public static final String USER_RESERVATION_SLOT_KEY = "reservation:user:slots:";
    public static final String RESERVATION_META_KEY = "reservation:meta:";
    public static final String RESERVATION_STREAM_KEY = "stream.reservations";
    public static final String RESERVATION_FAILED_STREAM_KEY = "stream.reservations.failed";
    public static final String RESERVATION_RETRY_KEY = "reservation:stream:retry";
    public static final String LAB_GEO_KEY = "lab:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
