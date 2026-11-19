package es.openrtve.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

/**
 * Texto de una línea con contorno negro de 1 píxel hacia fuera, legible también sobre fondos
 * claros. Dibuja el mismo [getLayout] dos veces (trazo y relleno) sin cambiar el color del
 * TextView, para no provocar redibujados.
 */
class OutlinedTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TextView(context, attrs) {

    override fun onDraw(canvas: Canvas) {
        val text = layout ?: return
        val p = paint
        val fill = currentTextColor
        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
        p.style = Paint.Style.STROKE
        p.strokeWidth = OUTLINE_STROKE_PX
        p.strokeJoin = Paint.Join.ROUND
        p.color = OUTLINE_COLOR
        text.draw(canvas)
        p.style = Paint.Style.FILL
        p.color = fill
        text.draw(canvas)
        canvas.restore()
    }

    private companion object {
        // Un trazo centrado de 2 px deja 1 px visible por fuera del texto.
        const val OUTLINE_STROKE_PX = 2f
        const val OUTLINE_COLOR = 0xFF000000.toInt()
    }
}
