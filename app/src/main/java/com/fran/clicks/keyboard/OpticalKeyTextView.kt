package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Canvas
import android.widget.TextView

class OpticalKeyTextView(context: Context) : TextView(context) {
    var opticalTextOffsetX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var opticalTextOffsetY: Float = 0f
        set(value) {
            field = value
            invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (opticalTextOffsetX == 0f && opticalTextOffsetY == 0f) {
            super.onDraw(canvas)
            return
        }
        canvas.save()
        canvas.translate(opticalTextOffsetX, opticalTextOffsetY)
        super.onDraw(canvas)
        canvas.restore()
    }
}
