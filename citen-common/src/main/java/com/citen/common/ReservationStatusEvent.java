package com.citen.common;

public enum ReservationStatusEvent {
    CONFIRM("CONFIRM", "确认预约"),
    CANCEL("CANCEL", "主动取消"),
    EXPIRE("EXPIRE", "预约过期"),
    COMPLETE("COMPLETE", "完成预约");

    private final String code;
    private final String desc;

    ReservationStatusEvent(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
