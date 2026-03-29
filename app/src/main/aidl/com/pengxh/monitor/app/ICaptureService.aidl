// ICaptureService.aidl
package com.pengxh.monitor.app;

import com.pengxh.monitor.app.ICaptureCallback;

interface ICaptureService {
    void registerCallback(ICaptureCallback cb);

    void unregisterCallback(ICaptureCallback cb);

    String getLatestCapturePath();

    long getLatestCaptureWallTimeMs();
}