package com.shivtej.aj

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * AJ का असली काम यहाँ होता है: "AJ" सुनना → बीप → आपकी बात सुनना → समझना → बोलना → काम करना।
 * यह notification के साथ चलता है, ताकि app बंद होने पर भी AJ सुनता रहे।
 */
class AjService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.shivtej.aj.START"
        const val ACTION_LISTEN = "com.shivtej.aj.LISTEN"
        const val ACTION_TEXT = "com.shivtej.aj.TEXT"
        const val ACTION_STOP = "com.shivtej.aj.STOP"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "aj_main"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String, text: String? = null) {
            val i = Intent(ctx, AjService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var store: Store
    private lateinit var wake: WakeWordEngine
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private val queuedSpeech = ArrayList<Pair<String, String>>()
    private val afterListen = HashMap<String, Boolean>()
    private val afterAction = HashMap<String, () -> Unit>()
    private var utteranceCounter = 0

    private val worker = Executors.newSingleThreadExecutor()
    private var turnToken = 0
    private var currentSpeechId: String? = null

    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var noSpeechCount = 0
    private var busyRetry = 0

    // ---------------- service lifecycle ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        createChannel()
        goForeground(statusLine())
        wake = WakeWordEngine(this) { main.post { onWakeWord() } }
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusLine())
        when (intent?.action) {
            ACTION_LISTEN -> startConversation()
            ACTION_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    busy = true
                    wake.stop()
                    stopRecognizer()
                    handleText(text)
                }
            }
            ACTION_STOP -> {
                store.wakeEnabled = false
                busy = false
                turnToken++
                stopRecognizer()
                wake.stop()
                stopSpeaking()
                AjBus.setStatus("AJ आराम कर रहा है")
                stopEverything()
                return START_NOT_STICKY
            }
            else -> applyWakeSetting()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecognizer()
        PcmPlayer.stop()
        worker.shutdown()
        wake.release()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun applyWakeSetting() {
        if (busy) return
        if (store.wakeEnabled) {
            wake.start()
            updateNotification()
        } else {
            wake.stop()
            AjBus.setStatus("AJ तैयार है")
            stopEverything()
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- बातचीत ----------------

    private fun onWakeWord() {
        if (busy) return
        startConversation()
    }

    private fun startConversation() {
        if (busy && recognizer != null) return // पहले से सुन रहा है
        busy = true
        noSpeechCount = 0
        turnToken++
        wake.stop()
        stopSpeaking()
        beep()
        main.postDelayed({ startRecognizer() }, 350)
    }

    private fun startRecognizer() {
        if (!PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)) {
            speak("माइक की permission नहीं है। AJ app खोलकर माइक की permission दीजिए।")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("इस फ़ोन में आवाज़ समझने की सुविधा नहीं मिली। Play Store से Google app update कीजिए।")
            return
        }
        stopRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listenListener)
                it.startListening(intent)
            }
            AjBus.setStatus("🎙 सुन रहा हूँ... बोलिए")
        } catch (e: Exception) {
            speak("आवाज़ सुनना शुरू नहीं हुआ: ${e.message ?: ""}")
        }
    }

    private fun stopRecognizer() {
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private val listenListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            AjBus.setStatus("🤔 समझ रहा हूँ...")
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            stopRecognizer()
            busyRetry = 0
            if (text.isNullOrBlank()) onNoSpeech() else handleText(text)
        }

        override fun onError(error: Int) {
            stopRecognizer()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> onNoSpeech()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (busyRetry++ < 2) main.postDelayed({ startRecognizer() }, 600)
                    else speak("आवाज़ पहचानने वाला हिस्सा व्यस्त है, थोड़ी देर बाद कोशिश कीजिए।")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    speak("माइक की permission नहीं है। AJ app खोलकर permission दीजिए।")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    speak("Internet नहीं मिल रहा। हिंदी आवाज़ पहचान के लिए internet चाहिए, या Google की offline हिंदी download कीजिए।")
                else -> speak("आवाज़ ठीक से समझ नहीं आई, फिर से बोलिए।")
            }
        }
    }

    /** "AJ" बोलकर चुप रहे तो AJ खुद पूछेगा; दूसरी बार भी चुप तो वापस इंतज़ार */
    private fun onNoSpeech() {
        if (noSpeechCount == 0) {
            noSpeechCount = 1
            val greeting = store.greeting()
            store.addHistory("AJ", greeting)
            AjBus.historyChanged()
            speak(greeting, listenAgain = true)
        } else {
            finishConversation()
        }
    }

    private fun handleText(text: String) {
        store.addHistory("आप", text)
        AjBus.historyChanged()
        AjBus.setStatus("🤔 समझ रहा हूँ...")
        val token = ++turnToken
        // समझना background में (internet वाले सवाल में कुछ सेकंड लग सकते हैं)
        worker.execute {
            val reply = try {
                CommandEngine.handle(this, text)
            } catch (e: Exception) {
                Reply("माफ़ कीजिए, कुछ गड़बड़ हो गई: ${e.message ?: ""}")
            }
            main.post {
                if (token != turnToken) return@post // बीच में नई बात शुरू हो गई
                store.addHistory("AJ", reply.speak)
                AjBus.historyChanged()
                noSpeechCount = 1 // फिर से पूछने पर चुप रहे तो greeting दोबारा नहीं
                speak(reply.speak, reply.listenAgain, reply.action)
            }
        }
    }

    private fun finishConversation() {
        busy = false
        stopRecognizer()
        if (store.wakeEnabled) {
            AjBus.setStatus("✅ ‘AJ’ बोलिए, मैं सुन रहा हूँ")
            wake.start()
            updateNotification()
        } else {
            AjBus.setStatus("AJ तैयार है")
            stopEverything()
        }
    }

    // ---------------- बोलना ----------------

    override fun onInit(status: Int) {
        val engine = tts
        if (status == TextToSpeech.SUCCESS && engine != null) {
            val result = engine.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            engine.setSpeechRate(1.0f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { afterSpeech(utteranceId) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { afterSpeech(utteranceId) }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { afterSpeech(utteranceId) }
                }
            })
            ttsReady = true
            applyVoiceSettings(engine)
            queuedSpeech.forEach { (text, id) -> engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
        } else {
            ttsFailed = true
            queuedSpeech.forEach { (_, id) -> main.post { afterSpeech(id) } }
        }
        queuedSpeech.clear()
    }

    private fun speak(text: String, listenAgain: Boolean = false, action: (() -> Unit)? = null) {
        busy = true
        val id = "aj_${++utteranceCounter}"
        currentSpeechId = id
        afterListen[id] = listenAgain
        if (action != null) afterAction[id] = action
        AjBus.setStatus("🗣 $text")

        // Gemini की असली आवाज़ चुनी हो तो पहले वो; न बने तो फ़ोन वाली
        if (store.geminiVoiceOn && store.geminiKey.isNotBlank()) {
            worker.execute {
                val audio = try {
                    GeminiAi.synthesize(this, text)
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (currentSpeechId != id) return@post // बीच में रोक दिया गया
                    if (audio != null) PcmPlayer.play(audio.first, audio.second) { afterSpeech(id) }
                    else phoneSpeak(text, id)
                }
            }
            return
        }
        phoneSpeak(text, id)
    }

    /** फ़ोन की आवाज़: आपकी चुनी हुई आवाज़, speed और pitch के साथ */
    private fun phoneSpeak(text: String, id: String) {
        val engine = tts
        when {
            ttsReady && engine != null -> {
                applyVoiceSettings(engine)
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
            ttsFailed || engine == null -> main.post { afterSpeech(id) }
            else -> queuedSpeech.add(text to id)
        }
    }

    private fun applyVoiceSettings(engine: TextToSpeech) {
        try {
            val wanted = store.voiceName
            if (wanted.isNotBlank()) {
                engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it }
            }
            engine.setPitch(store.pitch)
            engine.setSpeechRate(store.rate)
        } catch (_: Exception) {
        }
    }

    /** जो बोल रहा है उसे तुरंत रोकना */
    private fun stopSpeaking() {
        currentSpeechId = null
        tts?.stop()
        PcmPlayer.stop()
    }

    private fun afterSpeech(id: String?) {
        if (id == null) return
        val listenAgain = afterListen.remove(id) ?: return
        afterAction.remove(id)?.let {
            try {
                it()
            } catch (e: Exception) {
                AjBus.setStatus("⚠ काम नहीं हो पाया: ${e.message ?: ""}")
            }
        }
        if (listenAgain) {
            beep()
            main.postDelayed({ startRecognizer() }, 350)
        } else {
            finishConversation()
        }
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tone.release() }, 400)
        } catch (_: Exception) {
        }
    }

    // ---------------- notification ----------------

    private fun statusLine(): String =
        if (store.wakeEnabled) "‘AJ’ बोलिए, मैं सुन रहा हूँ" else "AJ काम कर रहा है"

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "AJ assistant", NotificationManager.IMPORTANCE_LOW)
            ch.description = "AJ के चलते रहने की सूचना"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val listen = PendingIntent.getService(
            this, 1, Intent(this, AjService::class.java).setAction(ACTION_LISTEN), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AjService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AJ")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "🎙 बोलो", listen)
            .addAction(0, "बंद करो", stop)
            .build()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        } catch (e: Exception) {
            // माइक की permission न हो या Android रोक दे, तो crash की जगह चुपचाप रुक जाओ
            AjBus.setStatus("⚠ AJ शुरू नहीं हो पाया। AJ app खोलकर माइक की permission दीजिए।")
            stopSelf()
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusLine()))
    }
}
