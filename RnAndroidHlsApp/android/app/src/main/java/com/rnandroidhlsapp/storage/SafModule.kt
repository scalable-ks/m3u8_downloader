package com.rnandroidhlsapp.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil

class SafModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    private var pendingPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String = "SafModule"

    @ReactMethod
    fun pickDirectory(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No current Activity")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            promise.reject("NO_ACTIVITY", "Activity is not in a usable state")
            return
        }
        if (pendingPromise != null) {
            promise.reject("IN_PROGRESS", "Another picker is active")
            return
        }
        pendingPromise = promise
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        UiThreadUtil.runOnUiThread {
            activity.startActivityForResult(intent, REQUEST_CODE)
        }
    }

    override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode != REQUEST_CODE) {
            return
        }
        val promise = pendingPromise
        pendingPromise = null
        if (promise == null) {
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            promise.resolve(null)
            return
        }
        val uri: Uri? = data?.data
        if (uri == null) {
            promise.resolve(null)
            return
        }
        val flags =
            data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            reactContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            promise.reject("PERMISSION_ERROR", e.message, e)
            return
        }
        promise.resolve(uri.toString())
    }

    override fun onNewIntent(intent: Intent) {
        // No-op
    }

    private companion object {
        const val REQUEST_CODE = 9321
    }
}
