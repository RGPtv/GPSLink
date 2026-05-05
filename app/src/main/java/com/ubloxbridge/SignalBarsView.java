package com.ubloxbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
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
            // FIX: guard against null label to prevent NPE in onDraw
            this.label    = label != null ? label : "???";
            this.avgSnr   = Math.max(0, avgSnr);   // FIX: clamp negative SNR
            this.satCount = Math.max(0, satCount);  // FIX: clamp negative count
        }
    }

    // ── Bar colours (filled) by quality level ────────────────────────────────
    private static final int[] BAR_COLORS = {
        0xFF6B7280, // 0 bars – no signal (grey)
        0xFFEF4444, // 1 bar  – very weak  (red)
        0xFFF97316, // 2 bars – weak       (orange)
        0xFFFBBF24, // 3 bars – moderate   (yellow)
        0xFF86EFAC, // 4 bars – good       (light-green)
        0xFF22C55E, // 5 bars – excellent  (green)
    };

    private static final int COLOR_BAR_EMPTY = 0xFF1E2A44;
    private static final int COLOR_LABEL     = 0xFF9CA3AF;  // grey
    private static final int COLOR_SNR_TEXT  = 0xFF6B7280;
    private static final int COLOR_NO_DATA   = 0xFF374151;

    private static final int NUM_BARS = 5;
    // FIX: expose row height as a named constant so onMeasure and onDraw
    //      always agree — previously they used the same magic number (34)
    //      duplicated in two places.
    private static final int ROW_HEIGHT_DP = 34;

    private final Paint mPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // FIX: reuse a single RectF instead of one field shared across loops
    //      (the original was already doing this correctly; kept for clarity).
    private final RectF mRect      = new RectF();

    // FIX: store an unmodifiable snapshot so callers cannot mutate the list
    //      while onDraw() is iterating it.
    private List<ConstellationSignal> mData = Collections.emptyList();
    private boolean mHasData = false;

    public SignalBarsView(Context ctx)                              { super(ctx);       init(); }
    public SignalBarsView(Context ctx, AttributeSet a)             { super(ctx, a);    init(); }
    public SignalBarsView(Context ctx, AttributeSet a, int s)      { super(ctx, a, s); init(); }

    private void init() {
        mTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /** Call this from MainActivity on UI thread to refresh the view. */
    public void setSignals(List<ConstellationSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            mData    = Collections.emptyList();
            mHasData = false;
        } else {
            // FIX: take a defensive copy so external mutations don't affect rendering
            mData    = Collections.unmodifiableList(new ArrayList<>(signals));
            mHasData = true;
        }
        // FIX: call requestLayout() in addition to invalidate() because the
        //      number of rows may have changed, which changes the measured height.
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rows = Math.max(1, mData.size());
        float density = getResources().getDisplayMetrics().density;
        int rowH  = (int) (ROW_HEIGHT_DP * density);
        int totalH = rows * rowH + (int) (4 * density);
        setMeasuredDimension(
            resolveSize(200, widthSpec),
            resolveSize(totalH, heightSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float w       = getWidth();
        float rowH    = ROW_HEIGHT_DP * density;

        if (!mHasData) {
            mTextPaint.setColor(COLOR_NO_DATA);
            mTextPaint.setTextSize(11 * density);
            mTextPaint.setFakeBoldText(false);
            canvas.drawText("No signal data", 0, rowH * 0.65f, mTextPaint);
            return;
        }

        float labelW  = 34 * density;
        float snrW    = 32 * density;
        float barZone = w - labelW - snrW - 4 * density;

        // FIX: guard against zero/negative barZone (e.g. very narrow view)
        //      to prevent a negative barW which would produce garbage geometry.
        if (barZone <= 0) return;

        float barW   = (barZone - (NUM_BARS - 1) * 3 * density) / NUM_BARS;
        float radius = 2 * density;

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
            float snrX    = labelW + barZone + 4 * density;
            canvas.drawText(snrStr, snrX, mid + 4 * density, mTextPaint);

            // ── Sat-count sub-label ───────────────────────────────────────────
            mTextPaint.setColor(0xFF4B5563);
            mTextPaint.setTextSize(8 * density);
            // FIX: use proper plural form ("1 sat" vs "2 sats") — original had
            //      the condition inverted: satCount != 1 should use "sats".
            String cntStr = cs.satCount + (cs.satCount == 1 ? " sat" : " sats");
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
