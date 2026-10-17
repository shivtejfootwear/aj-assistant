package com.shivtej.aj

import android.os.Handler
import android.os.Looper

/** Service और screen के बीच छोटा सा संदेश-वाहक: AJ क्या कर रहा है, यह screen तक पहुँचाता है। */
object AjBus {
    interface Listener {
        fun onStatus(status: String)
        fun onHistoryChanged()
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

    fun historyChanged() {
        main.post { listeners.toList().forEach { it.onHistoryChanged() } }
    }
}
