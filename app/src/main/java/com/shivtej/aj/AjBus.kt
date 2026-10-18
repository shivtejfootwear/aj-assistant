package com.shivtej.aj

import android.os.Handler
import android.os.Looper

/** Service और screen के बीच छोटा संदेश-वाहक: अभी क्या हो रहा है, वह screen तक पहुँचाता है। */
object AjBus {
    interface Listener {
        fun onStatus(status: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    var status: String = "AJ तैयार है"
        private set

    fun add(listener: Listener) {
        main.post {
            listeners.add(listener)
            listener.onStatus(status)
        }
    }

    fun remove(listener: Listener) {
        main.post { listeners.remove(listener) }
    }

    fun setStatus(newStatus: String) {
        status = newStatus
        main.post { listeners.toList().forEach { it.onStatus(newStatus) } }
    }

    /** पुराने code की जगह (अब कुछ नहीं करता) */
    fun historyChanged() {}
}
