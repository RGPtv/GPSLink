package com.ubloxbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays per-constellation GNSS signal strength as stacked bar rows.
 *
 * Each row shows:
 *   [LABEL]  ████░░  avg SNR dB
 *
 * SNR ranges (dBHz):
 *   0     → no signal   (0 bars, grey)
 *   1-14  → very weak   (1 bar, red)
 *   15-24 → weak        (2 bars, orange)
 *   25-34 → moderate    (3 bars, yellow)
 *   35-44 → good        (4 bars, light-green)
 *   45+   → excellent   (5 bars, green)
 */
public class SignalBarsView extends View {

    public static class ConstellationSignal {
        public final String label;      // "GPS", "GLO", "GAL", "BDU", "QZS"
        public final int    avgSnr;     // 0–99 dBHz
        public final int    satCount;   // satellites contributing

        public ConstellationSignal(String label, int avgSnr, int satCount) {
            this.label    = label;
            this.avgSnr   = avgSnr;
            this.satCount = satCount;
        }
    }

    // ── Bar colours (filled) by quality level ────────────────────────────────
    private static final int[] BAR_COLORS = {
        0xFF6B7280, // 0 bars – no signal (grey, unused – just shown as all empty)
        0xFFEF4444, // 1 bar  – very weak  (red)
        0xFFF97316, // 2 bars – weak       (orange)
        0xFFFBBF24, // 3 bars – moderate   (yellow)
        0xFF86EFAC, // 4 bars – good       (light-green)
        0xFF22C55E, // 5 bars – excellent  (green)
    };

    private static final int COLOR_BAR_EMPTY   = 0xFF1E2A44;
    private static final int COLOR_LABEL        = 0xFF9CA3AF;  // grey
    private static final int COLOR_SNR_TEXT     = 0xFF6B7280;
    private static final int COLOR_NO_DATA      = 0xFF374151;

    private static final int NUM_BARS = 5;

    private final Paint mPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect      = new RectF();

    private final List<ConstellationSignal> mData = new ArrayList<>();
    private boolean mHasData = false;

    public SignalBarsView(Context ctx) { super(ctx); init(); }
    public SignalBarsView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public SignalBarsView(Context ctx, AttributeSet a, int s) { super(ctx, a, s); init(); }

    private void init() {
        mTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /** Call this from MainActivity on UI thread to refresh the view. */
    public void setSignals(List<ConstellationSignal> signals) {
        mData.clear();
        if (signals != null) mData.addAll(signals);
        mHasData = !mData.isEmpty();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Height = rows × rowHeight; width fills parent
        int rows = Math.max(1, mData.size());
        float density = getResources().getDisplayMetrics().density;
        int rowH = (int)(34 * density);
        int totalH = rows * rowH + (int)(4 * density); // small bottom padding
        setMeasuredDimension(
            resolveSize(200, widthSpec),
            resolveSize(totalH, heightSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float w       = getWidth();
        float rowH    = 34 * density;

        if (!mHasData) {
            // "No signal data" placeholder
            mTextPaint.setColor(COLOR_NO_DATA);
            mTextPaint.setTextSize(11 * density);
            canvas.drawText("No signal data", 0, rowH * 0.65f, mTextPaint);
            return;
        }

        float labelW  = 34 * density;   // "GPS " label column
        float snrW    = 32 * density;   // "42dB" tail
        float barZone = w - labelW - snrW - 4 * density;
        float barW    = (barZone - (NUM_BARS - 1) * 3 * density) / NUM_BARS;
        float radius  = 2 * density;

        for (int i = 0; i < mData.size(); i++) {
            ConstellationSignal cs = mData.get(i);
            float top = i * rowH;
            float mid = top + rowH * 0.5f;

            // ── Label ────────────────────────────────────────────────────────
            mTextPaint.setColor(COLOR_LABEL);
            mTextPaint.setTextSize(10 * density);
            mTextPaint.setFakeBoldText(true);
            canvas.drawText(cs.label, 0, mid + 4 * density, mTextPaint);
            mTextPaint.setFakeBoldText(false);

            // ── Bars ─────────────────────────────────────────────────────────
            int filledBars = snrToBars(cs.avgSnr);
            int barColor   = BAR_COLORS[filledBars];

            for (int b = 0; b < NUM_BARS; b++) {
                float left  = labelW + b * (barW + 3 * density);
                float right = left + barW;

                // Each bar is taller than the previous for a staircase look
                float pct    = (b + 1) / (float) NUM_BARS;
                float barH   = Math.max(4 * density, rowH * 0.70f * pct);
                float barTop = top + (rowH - barH) - 2 * density;
                float barBot = top + rowH - 2 * density;

                boolean filled = (b < filledBars);
                mPaint.setColor(filled ? barColor : COLOR_BAR_EMPTY);
                mRect.set(left, barTop, right, barBot);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }

            // ── SNR text ─────────────────────────────────────────────────────
            mTextPaint.setColor(cs.avgSnr > 0 ? barColor : COLOR_SNR_TEXT);
            mTextPaint.setTextSize(10 * density);
            String snrStr = cs.avgSnr > 0 ? cs.avgSnr + "dB" : "--";
            // right-align inside snrW zone
            float snrX = labelW + barZone + 4 * density;
            canvas.drawText(snrStr, snrX, mid + 4 * density, mTextPaint);

            // ── sat count sub-label ───────────────────────────────────────────
            mTextPaint.setColor(0xFF4B5563);
            mTextPaint.setTextSize(8 * density);
            String cntStr = cs.satCount + " sat" + (cs.satCount != 1 ? "s" : "");
            canvas.drawText(cntStr, snrX, mid + 14 * density, mTextPaint);
        }
    }

    /** Convert average SNR (dBHz) to 0-5 bar count. */
    public static int snrToBars(int snr) {
        if (snr <= 0)  return 0;
        if (snr < 15)  return 1;
        if (snr < 25)  return 2;
        if (snr < 35)  return 3;
        if (snr < 45)  return 4;
        return 5;
    }
}
