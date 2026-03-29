// IGuardCallback.aidl
package com.pengxh.monitor.app;

// Declare any non-default types here with import statements

interface IGuardCallback {
    /**
     * 通知对端重要事件
     * @param code 事件代码：1=投屏未授权, 2=投屏失败, 3=心跳超时
     * @param message 事件详情
     * @param wallTimeMs 事件发生时间(毫秒时间戳)
     */
    void onNotification(int code, String message, long wallTimeMs);
}