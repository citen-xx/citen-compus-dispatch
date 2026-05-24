package com.citen.common;

public enum ReservationStatus {
    PENDING_CONFIRM(1, "待确认"),
    CONFIRMED(2, "已确认"),
    COMPLETED(3, "已完成"),
    CANCELED(4, "已取消"),
    TIMEOUT_BREACH(5, "超时违约");

    private final int code;
    private final String desc;

    ReservationStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
