package app.mangalens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class DualSegmentationDetector private constructor(
    context: Context,
    private val modelFile: java.io.File,
) : AutoCloseable {
    private val interpreter: Interpreter
    private val inputW: Int
    private val inputH: Int
    private val anchors = 44436
    private val protoW = 368
    private val protoH = 368
    private val input: ByteBuffer
    private val detectionOut: ByteBuffer
    private val prototypeOut: ByteBuffer
    private val pixels: IntArray
    private var detectionIndex = 0
    private var prototypeIndex = 1

    data class Detection(
        val box: Rect,
        val confidence: Float,
        val classId: Int,
        val mask: BooleanArray?,
        val maskW: Int,
        val maskH: Int,
    )

    private data class Raw(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val conf: Float, val cls: Int, val coeff: FloatArray
    )

    init {
        FileInputStream(modelFile).use { stream ->
            val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
            interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(4))
        }
        val inputTensor = interpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        require(inputTensor.dataType() == DataType.FLOAT32 && shape.contentEquals(intArrayOf(1, 1472, 1472, 3))) {
            "Unexpected dual detector input"
        }
        inputH = shape[1]
        inputW = shape[2]
        detectionIndex = (0 until interpreter.outputTensorCount).single { i ->
            interpreter.getOutputTensor(i).shape().contentEquals(intArrayOf(1, 38, 44436))
        }
        prototypeIndex = 1 - detectionIndex
        require(interpreter.getOutputTensor(prototypeIndex).shape().contentEquals(intArrayOf(1, 368, 368, 32))) {
            "Unexpected dual detector prototype output"
        }
        input = direct(interpreter.getInputTensor(0).numBytes())
        detectionOut = direct(interpreter.getOutputTensor(detectionIndex).numBytes())
        prototypeOut = direct(interpreter.getOutputTensor(prototypeIndex).numBytes())
        pixels = IntArray(inputW * inputH)
    }

    fun detect(bitmap: Bitmap, minConfidence: Float = 0.32f): List<Detection> {
        val prep = letterbox(bitmap)
        try {
            prep.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            input.clear()
            for (p in pixels) {
                input.putFloat(Color.red(p) / 255f)
                input.putFloat(Color.green(p) / 255f)
                input.putFloat(Color.blue(p) / 255f)
            }
            input.rewind()
            detectionOut.clear()
            prototypeOut.clear()
            interpreter.runForMultipleInputsOutputs(
                arrayOf(input),
                mapOf(detectionIndex to detectionOut, prototypeIndex to prototypeOut)
            )
            return decode(detectionOut.asFloatBuffer(), prototypeOut.asFloatBuffer(), bitmap, minConfidence)
        } finally {
            prep.recycle()
        }
    }

    private fun decode(values: FloatBuffer, proto: FloatBuffer, bitmap: Bitmap, threshold: Float): List<Detection> {
        val candidates = ArrayList<Raw>()
        for (a in 0 until anchors) {
            fun f(c: Int) = values.get(c * anchors + a)
            val b = f(4); val t = f(5)
            if (!b.isFinite() || !t.isFinite()) continue
            val cls = if (b >= t) 0 else 1
            val conf = max(b, t)
            val gate = if (cls == 0) threshold else 0.16f
            if (conf < gate || conf > 1f) continue
            val cx = f(0) * inputW; val cy = f(1) * inputH
            val w = f(2) * inputW; val h = f(3) * inputH
            if (w <= 0f || h <= 0f) continue
            candidates += Raw(cx, cy, w, h, conf, cls, FloatArray(32) { f(6 + it) })
        }
        val kept = ArrayList<Raw>()
        for (candidate in candidates.sortedByDescending { it.conf }) {
            if (kept.none { it.cls == candidate.cls && iou(it, candidate) > 0.45f }) kept += candidate
            if (kept.size >= 300) break
        }
        return kept.mapNotNull { raw ->
            val box = toOriginal(raw, bitmap.width, bitmap.height)
            val mask = maskFor(raw, proto)
            Detection(box, raw.conf, raw.cls, mask?.first, mask?.second ?: 0, mask?.third ?: 0)
        }
    }

    private fun maskFor(raw: Raw, proto: FloatBuffer): Triple<BooleanArray, Int, Int>? {
        val x1 = floor((raw.cx - raw.w / 2f) / inputW * protoW).toInt().coerceIn(0, protoW - 1)
        val y1 = floor((raw.cy - raw.h / 2f) / inputH * protoH).toInt().coerceIn(0, protoH - 1)
        val x2 = ceil((raw.cx + raw.w / 2f) / inputW * protoW).toInt().coerceIn(x1 + 1, protoW)
        val y2 = ceil((raw.cy + raw.h / 2f) / inputH * protoH).toInt().coerceIn(y1 + 1, protoH)
        val mw = x2 - x1; val mh = y2 - y1
        if (mw < 2 || mh < 2) return null
        val rawMask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            var score = 0f
            val base = ((y1 + y) * protoW + (x1 + x)) * 32
            for (k in 0 until 32) score += raw.coeff[k] * proto.get(base + k)
            rawMask[y * mw + x] = score > 0f
        }
        val largest = largestComponent(rawMask, mw, mh) ?: return null
        return Triple(largest, mw, mh)
    }

    private fun toOriginal(r: Raw, ow: Int, oh: Int): Rect {
        val g = min(inputW.toFloat() / ow, inputH.toFloat() / oh)
        val px = (inputW - ow * g) / 2f
        val py = (inputH - oh * g) / 2f
        return Rect(
            ((r.cx-r.w/2f-px)/g).toInt().coerceIn(0, ow-1),
            ((r.cy-r.h/2f-py)/g).toInt().coerceIn(0, oh-1),
            ((r.cx+r.w/2f-px)/g).toInt().coerceIn(1, ow),
            ((r.cy+r.h/2f-py)/g).toInt().coerceIn(1, oh)
        )
    }

    private fun letterbox(src: Bitmap): Bitmap {
        val g = min(inputW.toFloat()/src.width, inputH.toFloat()/src.height)
        val nw = max(1,(src.width*g).toInt()); val nh = max(1,(src.height*g).toInt())
        val out = Bitmap.createBitmap(inputW,inputH,Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.rgb(114,114,114))
            val l=(inputW-nw)/2; val t=(inputH-nh)/2
            drawBitmap(src,null,Rect(l,t,l+nw,t+nh),Paint(Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    override fun close() = interpreter.close()

    companion object {
        @Volatile private var instance: DualSegmentationDetector? = null

        fun get(context: Context): DualSegmentationDetector? {
            if (!ModelInstaller.isInstalled(context)) return null
            val file = ModelInstaller.modelFile(context)
            return instance ?: synchronized(this) {
                instance ?: runCatching { DualSegmentationDetector(context, file) }
                    .getOrNull()?.also { instance = it }
            }
        }

        fun close() {
            synchronized(this) { instance?.close(); instance = null }
        }

        private fun direct(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        private fun iou(a: Raw, b: Raw): Float {
            val l=max(a.cx-a.w/2f,b.cx-b.w/2f); val t=max(a.cy-a.h/2f,b.cy-b.h/2f)
            val r=min(a.cx+a.w/2f,b.cx+b.w/2f); val bot=min(a.cy+a.h/2f,b.cy+b.h/2f)
            val inter=max(0f,r-l)*max(0f,bot-t); val union=a.w*a.h+b.w*b.h-inter
            return if(union>0f) inter/union else 0f
        }

        private fun largestComponent(mask:BooleanArray,w:Int,h:Int):BooleanArray? {
            val seen=BooleanArray(mask.size); val queue=IntArray(mask.size)
            var best:IntArray?=null
            for(start in mask.indices){
                if(!mask[start]||seen[start]) continue
                var head=0; var tail=0; val cells=IntArray(mask.size); var count=0
                queue[tail++]=start; seen[start]=true
                while(head<tail){
                    val p=queue[head++]; cells[count++]=p; val x=p%w; val y=p/w
                    for(yy in max(0,y-1)..min(h-1,y+1)) for(xx in max(0,x-1)..min(w-1,x+1)){
                        val q=yy*w+xx
                        if(mask[q]&&!seen[q]){seen[q]=true;queue[tail++]=q}
                    }
                }
                if(best==null||count>best!!.size) best=cells.copyOf(count)
            }
            val out=BooleanArray(mask.size); best?.forEach{out[it]=true}
            return if(best==null)null else out
        }
    }
}
