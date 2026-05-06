package com.gpslink;

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
 *   [LABEL]  ████░░░░░░  avg SNR dB
 *
 * SNR ranges (dBHz) → bar count (0–10):
 *   0      → no signal    (0 bars,  grey)
 *   1–9    → very weak    (1 bar,   red)
 *   10–18  → weak         (2 bars,  red-orange)
 *   19–27  → below avg    (3 bars,  orange)
 *   28–36  → moderate     (4 bars,  orange-yellow)
 *   37–44  → fair         (5 bars,  yellow)
 *   45–50  → above avg    (6 bars,  yellow-green)
 *   51–56  → good         (7 bars,  light-green)
 *   57–61  → very good    (8 bars,  green)
 *   62–65  → excellent    (9 bars,  strong-green)
 *   66+    → outstanding  (10 bars, vivid-green)
 */
public class SignalBarsView extends View {

    public static class ConstellationSignal {
        public final String label;      // "GPS", "GLO", "GAL", "BDU", "QZS"
        public final int    avgSnr;     // 0–99 dBHz
        public final int    satCount;   // satellites contributing

        public ConstellationSignal(String label, int avgSnr, int satCount) {
            this.label    = label != null ? label : "???";
            this.avgSnr   = Math.max(0, avgSnr);
            this.satCount = Math.max(0, satCount);
        }
    }

    // ── Bar colours (filled) by quality level (index = bar count) ────────────
    private static final int[] BAR_COLORS = {
        0xFF6B7280, //  0 bars – no signal    (grey)
        0xFFEF4444, //  1 bar  – very weak    (red)
        0xFFE95E2F, //  2 bars – weak         (red-orange)
        0xFFF97316, //  3 bars – below avg    (orange)
        0xFFFB923C, //  4 bars – moderate     (orange-yellow)
        0xFFFBBF24, //  5 bars – fair         (yellow)
        0xFFD4D016, //  6 bars – above avg    (yellow-green)
        0xFF86EFAC, //  7 bars – good         (light-green)
        0xFF4ADE80, //  8 bars – very good    (green)
        0xFF22C55E, //  9 bars – excellent    (strong-green)
        0xFF16A34A, // 10 bars – outstanding  (vivid-green)
    };

    private static final int COLOR_BAR_EMPTY = 0xFF1E2A44;
    private static final int COLOR_LABEL     = 0xFF9CA3AF;
    private static final int COLOR_SNR_TEXT  = 0xFF6B7280;
    private static final int COLOR_NO_DATA   = 0xFF374151;

    private static final int NUM_BARS       = 10;
    private static final int ROW_HEIGHT_DP  = 26;

    private final Paint mPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect      = new RectF();

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
            mData    = Collections.unmodifiableList(new ArrayList<>(signals));
            mHasData = true;
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rows = Math.max(1, mData.size());
        float density = getResources().getDisplayMetrics().density;
        int rowH   = (int) (ROW_HEIGHT_DP * density);
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

        float labelW  = 28 * density;
        float snrW    = 28 * density;
        float barZone = w - labelW - snrW - 4 * density;

        if (barZone <= 0) return;

        float barW   = (barZone - (NUM_BARS - 1) * 3 * density) / NUM_BARS;
        float radius = 2 * density;

        // Fixed bar height — all bars the same height (no staircase)
        float barH   = rowH * 0.45f;
        float barTop_offset = (rowH - barH) * 0.5f;  // vertically centred in row

        for (int i = 0; i < mData.size(); i++) {
            ConstellationSignal cs = mData.get(i);
            float top = i * rowH;
            float mid = top + rowH * 0.5f;

            // ── Label ────────────────────────────────────────────────────────
            mTextPaint.setColor(COLOR_LABEL);
            mTextPaint.setTextSize(9 * density);
            mTextPaint.setFakeBoldText(true);
            canvas.drawText(cs.label, 0, mid + 3 * density, mTextPaint);
            mTextPaint.setFakeBoldText(false);

            // ── Bars ─────────────────────────────────────────────────────────
            int filledBars = snrToBars(cs.avgSnr);
            int barColor   = BAR_COLORS[filledBars];

            for (int b = 0; b < NUM_BARS; b++) {
                float left   = labelW + b * (barW + 3 * density);
                float right  = left + barW;
                float bTop   = top + barTop_offset;
                float bBot   = bTop + barH;

                boolean filled = (b < filledBars);
                mPaint.setColor(filled ? barColor : COLOR_BAR_EMPTY);
                mRect.set(left, bTop, right, bBot);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }

            // ── SNR text ─────────────────────────────────────────────────────
            mTextPaint.setColor(cs.avgSnr > 0 ? barColor : COLOR_SNR_TEXT);
            mTextPaint.setTextSize(9 * density);
            String snrStr = cs.avgSnr > 0 ? cs.avgSnr + "dB" : "--";
            float snrX    = labelW + barZone + 4 * density;
            canvas.drawText(snrStr, snrX, mid + 3 * density, mTextPaint);

            // ── Sat-count sub-label ───────────────────────────────────────────
            mTextPaint.setColor(0xFF4B5563);
            mTextPaint.setTextSize(7 * density);
            String cntStr = cs.satCount + (cs.satCount == 1 ? " sat" : " sats");
            canvas.drawText(cntStr, snrX, mid + 12 * density, mTextPaint);
        }
    }

    /**
     * Convert average SNR (dBHz) to 0–10 bar count.
     * Thresholds tuned so typical GNSS range (0–66+ dBHz) maps evenly.
     */
    public static int snrToBars(int snr) {
        if (snr <= 0)  return 0;
        if (snr < 10)  return 1;
        if (snr < 19)  return 2;
        if (snr < 28)  return 3;
        if (snr < 37)  return 4;
        if (snr < 45)  return 5;
        if (snr < 51)  return 6;
        if (snr < 57)  return 7;
        if (snr < 62)  return 8;
        if (snr < 66)  return 9;
        return 10;
    }
}
