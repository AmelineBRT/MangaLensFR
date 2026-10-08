package app.mangalens.ocr

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import okhttp3.OkHttpClient
import okhttp3.Request

object ModelInstaller {
    private const val MODEL_FILE = "mixed-dual-s-e5_float16.tflite"
    private const val MODEL_ASSET = "assets/models/detection/$MODEL_FILE"
    private const val APK_URL = "https://github.com/jedzqer/manga-translator-android/releases/download/v3.6.4/app-release.apk"
    fun modelFile(context: Context): File = File(context.filesDir, "models/$MODEL_FILE")
    fun isInstalled(context: Context): Boolean = modelFile(context).let { it.isFile && it.length() > 1_000_000L }
    fun prefetch(context: Context) {
        if (isInstalled(context)) return
        Thread({ runCatching { install(context.applicationContext) } }, "MangaLensModelDownload").apply { isDaemon = true }.start()
    }
    @Synchronized fun install(context: Context): File? {
        if (isInstalled(context)) return modelFile(context)
        val target = modelFile(context); target.parentFile?.mkdirs()
        val apk = File(context.cacheDir, "manga-translator-model.apk")
        val tmp = File(context.cacheDir, "$MODEL_FILE.part")
        OkHttpClient().newCall(Request.Builder().url(APK_URL).build()).execute().use { r ->
            if (!r.isSuccessful) error("model download failed: " + r.code)
            val body = r.body ?: error("empty model download")
            body.byteStream().use { input -> FileOutputStream(apk).use { output -> input.copyTo(output) } }
        }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MODEL_ASSET) ?: error("detector model not found in release APK")
            zip.getInputStream(entry).use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output) } }
        }
        if (!tmp.isFile || tmp.length() < 1_000_000L) error("incomplete detector model")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        apk.delete(); return target
    }
}