package com.singingbetter.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayDeque;

final class PitchGraphView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayDeque<Float> notes = new ArrayDeque<>();
    private final float density;

    PitchGraphView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    synchronized void addFrequency(float hz) {
        notes.addLast(hz > 0 ? (float) PitchDetector.midi(hz) : Float.NaN);
        while (notes.size() > 64) notes.removeFirst();
        postInvalidateOnAnimation();
    }

    synchronized void clear() {
        notes.clear();
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        paint.setColor(0xFFF4F4EF);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(0, 0, width, height), 18 * density, 18 * density, paint);

        paint.setColor(0xFFE1E6DF);
        paint.setStrokeWidth(1 * density);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 1; i <= 4; i++) {
            float y = height * i / 5f;
            canvas.drawLine(16 * density, y, width - 16 * density, y, paint);
        }

        if (notes.size() < 2) return;
        float left = 18 * density;
        float right = width - 18 * density;
        float top = 14 * density;
        float bottom = height - 14 * density;
        float step = (right - left) / Math.max(1, notes.size() - 1);
        Path path = new Path();
        boolean started = false;
        int index = 0;
        for (Float midi : notes) {
            float x = left + index * step;
            index++;
            if (midi == null || midi.isNaN()) {
                started = false;
                continue;
            }
            float normalized = Math.max(0, Math.min(1, (midi - 48f) / 36f));
            float y = bottom - normalized * (bottom - top);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        paint.setColor(0xFFC9543D);
        paint.setStrokeWidth(3 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
    }
}
