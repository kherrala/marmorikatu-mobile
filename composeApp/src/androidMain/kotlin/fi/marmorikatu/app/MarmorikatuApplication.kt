package fi.marmorikatu.app

import android.app.Application
import fi.marmorikatu.app.di.initKoin
import fi.marmorikatu.core.platform.AndroidContext

class MarmorikatuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Core actuals (audio, speech, permissions) read the app context from
        // this holder, so Koin needs no Android-specific module.
        AndroidContext.init(this)
        initKoin()
    }
}
