package fi.marmorikatu.core.platform

import android.annotation.SuppressLint
import android.content.Context

/**
 * Application context holder for core actuals (audio, speech, permissions).
 * Initialized once from the Application class before Koin starts.
 */
@SuppressLint("StaticFieldLeak")
object AndroidContext {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }
}
