package com.shivtej.aj

import android.content.Context
import org.json.JSONObject

/** फ़ोटो/आवाज़ को private Supabase storage (bucket: aj-media) में रखना और admin के लिए link बनाना */
object MediaUpload {

    private const val BUCKET = "aj-media"

    /** upload; सफल हो तो storage में path, नहीं तो null */
    fun upload(ctx: Context, path: String, bytes: ByteArray, contentType: String): String? {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        return try {
            val res = Http.requestRaw(
                "POST", "${store.cloudUrl}/storage/v1/object/$BUCKET/$path",
                headers + mapOf("x-upsert" to "true"),
                bytes, contentType
            )
            if (res.ok) path else null
        } catch (e: Exception) {
            null
        }
    }

    /** admin के खोलने लायक signed link (कुछ घंटे चलता है) */
    fun sign(ctx: Context, path: String, expiresSec: Int = 21600): String? {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        return try {
            val res = Http.request(
                "POST", "${store.cloudUrl}/storage/v1/object/sign/$BUCKET/$path",
                headers, JSONObject().put("expiresIn", expiresSec).toString()
            )
            if (!res.ok) return null
            val signed = JSONObject(res.body).optString("signedURL")
            if (signed.isBlank()) null else "${store.cloudUrl}/storage/v1$signed"
        } catch (e: Exception) {
            null
        }
    }
}
