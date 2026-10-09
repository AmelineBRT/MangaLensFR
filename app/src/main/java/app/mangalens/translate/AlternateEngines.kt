package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * MyMemory's public translation endpoint. No API key is needed, but the service
 * has daily limits and may be unavailable; TranslationService treats it as a
 * fallback rather than promising unlimited use.
 */
class MyMemoryEngine : TranslationEngine {
    override val label = "MyMemory · gratuit"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            items.map { source ->
                if (source.isBlank() || Script.cjkCount(source) == 0) return@map source
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.mymemory.translated.net")
                    .addPathSegment("get")
                    .addQueryParameter("q", source)
                    .addQueryParameter("langpair", sourceCode(source, lang) + "|fr")
                    .build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("MyMemory HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    if (root.optString("responseStatus") !in listOf("200", "202")) {
                        throw RuntimeException(root.optString("responseDetails", "MyMemory indisponible"))
                    }
                    root.optJSONObject("responseData")?.optString("translatedText")
                        ?.takeIf { it.isNotBlank() } ?: source
                }
            }
        }

    private fun sourceCode(text: String, lang: SourceLang): String =
        if (Script.cjkCount(text) == 0) "en" else when (lang) {
            SourceLang.EN -> "en"
            SourceLang.KO -> "ko"
            SourceLang.JA -> "ja"
            SourceLang.ZH -> "zh"
            SourceLang.AUTO -> when {
                text.any { it.code in 0xAC00..0xD7AF } -> "ko"
                text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF } -> "ja"
                else -> "zh"
            }
        }
}

/**
 * DeepL API engine. A Free API key (usually ending in :fx) uses api-free;
 * other keys use the paid API host. The provider's own usage limits and billing
 * apply to the supplied key.
 */
class DeepLEngine(private val apiKey: String) : TranslationEngine {
    override val label = "DeepL · API"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw RuntimeException("Ajoute ta clé API DeepL dans les réglages.")
            if (items.isEmpty()) return@withContext emptyList()
            val host = if (apiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val form = FormBody.Builder().add("target_lang", "FR")
            items.forEach { form.add("text", it) }
            val request = Request.Builder()
                .url("https://$host/v2/translate")
                .header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                .post(form.build())
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                    throw RuntimeException("DeepL HTTP ${resp.code}" + if (detail.isBlank()) "" else ": $detail")
                }
                val arr = JSONObject(body).optJSONArray("translations")
                    ?: throw RuntimeException("Réponse DeepL invalide")
                val out = (0 until arr.length()).map { arr.optJSONObject(it)?.optString("text").orEmpty() }
                if (out.size != items.size) throw RuntimeException("Réponse DeepL incomplète")
                out
            }
        }
}

/**
 * Official Microsoft Azure Translator API. Its F0 tier has a monthly free
 * character quota; the user supplies the key and Azure resource region.
 */
class MicrosoftTranslatorEngine(
    private val apiKey: String,
    private val region: String,
) : TranslationEngine {
    override val label = "Microsoft Translator · API"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw RuntimeException("Ajoute ta clé API Microsoft Translator.")
            if (items.isEmpty()) return@withContext emptyList()
            val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=fr"
            val payload = org.json.JSONArray()
            items.forEach { payload.put(JSONObject().put("Text", it)) }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val builder = Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey.trim())
                .header("Content-Type", "application/json; charset=utf-8")
                .post(body)
            if (region.isNotBlank()) builder.header("Ocp-Apim-Subscription-Region", region.trim())
            client.newCall(builder.build()).execute().use { resp ->
                val response = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("Microsoft Translator HTTP ${resp.code}" +
                        if (response.isBlank()) "" else ": ${response.take(180)}")
                }
                val arr = org.json.JSONArray(response)
                val out = (0 until arr.length()).map { i ->
                    arr.optJSONObject(i)?.optJSONArray("translations")
                        ?.optJSONObject(0)?.optString("text").orEmpty()
                }
                if (out.size != items.size || out.any { it.isBlank() }) {
                    throw RuntimeException("Réponse Microsoft Translator incomplète")
                }
                out
            }
        }
}
