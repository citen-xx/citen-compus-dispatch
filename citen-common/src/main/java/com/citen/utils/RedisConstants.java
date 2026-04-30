package com.citen.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_LAB_TTL = 30L;
    public static final String CACHE_LAB_KEY = "cache:lab:";
    public static final String CACHE_LAB_TYPE_KEY = "cache:lab:type:list";

    public static final String LOCK_LAB_KEY = "lock:lab:";
    public static final Long LOCK_LAB_TTL = 10L;

    public static final String RESOURCE_QUOTA_KEY = "resource:quota:";
    public static final String LAB_GEO_KEY = "lab:geo:";
    public static final String DELIVERY_RIDER_GEO_KEY = "delivery:rider:geo";
    public static final String USER_SIGN_KEY = "sign:";
}
