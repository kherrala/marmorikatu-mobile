package fi.marmorikatu.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual class AppForeground actual constructor() {
    private val _isForeground = MutableStateFlow(false)
    actual val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        // Lifecycle observers must be registered on the main thread.
        CoroutineScope(Dispatchers.Main).launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    _isForeground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    _isForeground.value = false
                }
            })
        }
    }
}
