package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

// App Check contract keys defined by the Firebase SDK and AI Studio runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val INJECTED_DEBUG_TOKEN_KEY = "com.example.appcheck.INJECTED_DEBUG_TOKEN"
private const val APPCHECK_TOKEN_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun configureAppCheck(context: Context, intent: Intent?) {
  try {
    // 1. Ensure FirebaseApp is initialized
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(context)
    }

    val persistenceKey = FirebaseApp.getInstance().persistenceKey
    val debugPrefs =
      context.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)

    if (BuildConfig.DEBUG) {
      // 2. Debug: Read debug token from Intent extra if launched by the AI Studio emulator harness
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

      if (!debugToken.isNullOrEmpty()) {
        // CRITICAL: MUST use commit() instead of apply() so the secret is written
        // synchronously before DebugAppCheckProviderFactory reads it.
        debugPrefs
          .edit()
          .putString(DEBUG_SECRET_KEY, debugToken)
          .putString(INJECTED_DEBUG_TOKEN_KEY, debugToken)
          .commit()
        Log.d(TAG, "App Check debug secret configured successfully from Intent extra.")
      } else {
        Log.w(TAG, "No debug token found in Intent extra.")
      }

      val effectiveDebugToken =
        if (!debugToken.isNullOrEmpty()) {
          debugToken
        } else {
          debugPrefs.getString(INJECTED_DEBUG_TOKEN_KEY, null)
        }

      if (!effectiveDebugToken.isNullOrEmpty()) {
        // Ensure DEBUG_SECRET_KEY matches the injected token synchronously before installing provider
        if (debugPrefs.getString(DEBUG_SECRET_KEY, null) != effectiveDebugToken) {
          debugPrefs.edit().putString(DEBUG_SECRET_KEY, effectiveDebugToken).commit()
        }
        FirebaseAppCheck.getInstance()
          .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        Log.d(TAG, "DebugAppCheckProviderFactory installed with injected token.")
        return
      }

      // If no FIREBASE_APPCHECK_DEBUG_TOKEN was ever injected via Intent (e.g., user downloaded
      // the APK onto a physical phone and launched it from the home screen), clean up any
      // un-registered random UUID that DebugAppCheckProvider may have generated previously and
      // fall through to RecaptchaAppCheckProviderFactory using the provisioned recaptcha_site_key!
      if (debugPrefs.contains(DEBUG_SECRET_KEY)) {
        debugPrefs.edit().remove(DEBUG_SECRET_KEY).commit()
        context
          .getSharedPreferences("$APPCHECK_TOKEN_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
          .edit()
          .clear()
          .commit()
        Log.d(TAG, "Cleared un-registered local debug secret and cached token for physical device.")
      }
    }

    // 3. Release (or physical device without harness debug token):
    // Look up recaptcha_site_key safely without throwing Resources.NotFoundException
    val resId =
      context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
    if (resId != 0) {
      val siteKey = context.getString(resId)
      if (siteKey.isNotBlank()) {
        FirebaseAppCheck.getInstance()
          .installAppCheckProviderFactory(RecaptchaAppCheckProviderFactory.getInstance(siteKey))
        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
      } else {
        Log.e(TAG, "recaptcha_site_key resource string is blank.")
      }
    } else {
      Log.e(TAG, "recaptcha_site_key string resource not found in package.")
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to initialize Firebase App Check", e)
  }
}
