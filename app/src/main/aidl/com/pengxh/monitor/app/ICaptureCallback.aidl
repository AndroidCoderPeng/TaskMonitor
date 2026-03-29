// ICaptureCallback.aidl
package com.pengxh.monitor.app;

interface ICaptureCallback {
    void onCaptureSuccess(String imageUri, long wallTimeMs);

    void onCaptureError(int code, String message, long wallTimeMs);
}