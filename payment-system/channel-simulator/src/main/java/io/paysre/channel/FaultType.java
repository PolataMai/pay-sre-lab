package io.paysre.channel;

public enum FaultType {
    NONE,
    TIMEOUT_BUT_SUCCESS,
    TIMEOUT_BUT_FAILED,
    DECLINE_ALL,
    CALLBACK_LOST,
    CALLBACK_DUPLICATED
}