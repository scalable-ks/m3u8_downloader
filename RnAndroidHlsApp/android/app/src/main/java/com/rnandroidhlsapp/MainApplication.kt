package com.rnandroidhlsapp

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.rnandroidhlsapp.HlsDownloaderPackage
import com.rnandroidhlsapp.storage.SafPackage
import io.sentry.android.core.SentryAndroid
import io.sentry.SentryLevel

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          add(SafPackage())
          add(HlsDownloaderPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()

    // Initialize Sentry BEFORE React Native loads
    SentryAndroid.init(this) { options ->
      options.dsn = "https://6cd2a148004f57764226c7fb5a12b4af@o4510716831399936.ingest.de.sentry.io/4510716835070032"

      // Enable NDK crash reporting
      options.isEnableNdk = true

      // Performance monitoring
      options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.1
      options.profilesSampleRate = 1.0

      // Debug logging
      options.isDebug = BuildConfig.DEBUG
      options.setDiagnosticLevel(if (BuildConfig.DEBUG) SentryLevel.DEBUG else SentryLevel.ERROR)

      // Environment
      options.environment = if (BuildConfig.DEBUG) "development" else "production"

      // Auto session tracking
      options.isEnableAutoSessionTracking = true

      // Attach threads
      options.isAttachThreads = true

      // Attach stack traces
      options.isAttachStacktrace = true
    }

    loadReactNative(this)
  }
}
