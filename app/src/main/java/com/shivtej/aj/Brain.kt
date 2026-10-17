package com.shivtej.aj

import android.content.Context

/**
 * AJ का internet वाला दिमाग चुनने वाला:
 * पहले Groq (सबसे तेज़, internet search के साथ) → न चले तो Gemini (Google search के साथ)।
 * दोनों free हैं; एक की limit लगे तो दूसरा जवाब देता है।
 */
object Brain {

    fun ask(ctx: Context, question: String): String {
        val store = Store(ctx)
        if (store.groqKey.isBlank() && store.geminiKey.isBlank()) {
            return "यह सवाल internet से पूछना होगा। AJ app की Settings में ‘AI दिमाग’ वाले हिस्से में free Groq या Gemini key डालिए।"
        }
        var error = ""
        if (store.groqKey.isNotBlank()) {
            val r = GroqAi.ask(ctx, question)
            if (r.ok) return r.text
            error = r.text
        }
        if (store.geminiKey.isNotBlank()) {
            val r = GeminiAi.ask(ctx, question)
            if (r.ok) return r.text
            error = r.text
        }
        return error
    }
}
