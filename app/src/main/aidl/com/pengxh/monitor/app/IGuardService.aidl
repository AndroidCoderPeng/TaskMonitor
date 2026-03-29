// IGuardService.aidl
package com.pengxh.monitor.app;

import com.pengxh.monitor.app.IGuardCallback;

interface IGuardService {
    /**
     * 对端(B)调用该方法，上报一次心跳。
     * elapsedRealtimeMs: 建议传 SystemClock.elapsedRealtime()，用于超时判断。
     */
    void beat(String from, long elapsedRealtimeMs, int statusCode, String statusMsg);

    /** A 记录的：最近一次收到 B 心跳的 elapsedRealtime */
    long getLastBeatFromPeerElapsedMs();

    /** A 本地的 elapsedRealtime(便于对端估算时间差，可选) */
    long getLastLocalElapsedMs();

    /** A 返回简单健康状态：0=OK, 1=NEED_AUTH, 2=CAPTURE_ERROR... */
    int getHealthCode();

    /** 注册回调，用于 A 向 B 发送通知 */
    void registerCallback(IGuardCallback cb);

    /** 注销回调 */
    void unregisterCallback(IGuardCallback cb);
}