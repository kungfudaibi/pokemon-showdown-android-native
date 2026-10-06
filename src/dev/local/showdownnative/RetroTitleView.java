package dev.local.showdownnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;

/** Original monochrome title art inspired by the constraints of handheld RPG screens. */
final class RetroTitleView extends View {
    private final Paint paint = new Paint();
    private static final int INK = Color.rgb(24, 29, 27);
    private static final int MID = Color.rgb(91, 101, 94);
    private static final int PALE = Color.rgb(202, 209, 198);
    private static final int PAPER = Color.rgb(239, 241, 232);
    private final Paint spritePaint = new Paint();
    private final Bitmap stadium;
    private final Bitmap incineroar;
    private final Bitmap garchomp;

    RetroTitleView(Context context) {
        super(context);
        paint.setAntiAlias(false);
        stadium = asset("title-stadium.png");
        incineroar = asset("title-incineroar.png");
        garchomp = asset("title-garchomp.png");
        ColorMatrix monochrome = new ColorMatrix();
        monochrome.setSaturation(0);
        spritePaint.setColorFilter(new ColorMatrixColorFilter(monochrome));
        spritePaint.setFilterBitmap(false);
    }

    private Bitmap asset(String name) {
        try (InputStream stream = getContext().getAssets().open(name)) {
            return BitmapFactory.decodeStream(stream);
        } catch (IOException error) {
            return null;
        }
    }

    @Override protected void onDraw(Canvas actual) {
        super.onDraw(actual);
        actual.drawColor(INK);
        if (stadium != null) {
            float targetRatio = getWidth() / (float) getHeight();
            float imageRatio = stadium.getWidth() / (float) stadium.getHeight();
            int sourceWidth = stadium.getWidth(), sourceHeight = stadium.getHeight();
            if (imageRatio > targetRatio) sourceWidth = Math.round(sourceHeight * targetRatio);
            else sourceHeight = Math.round(sourceWidth / targetRatio);
            int sx = (stadium.getWidth() - sourceWidth) / 2;
            int sy = (stadium.getHeight() - sourceHeight) / 2;
            actual.drawBitmap(stadium, new Rect(sx, sy, sx + sourceWidth, sy + sourceHeight),
                    new RectF(0, 0, getWidth(), getHeight()), paint);
        }
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                new int[]{0x960D1515, 0x230D1515, 0xC40D1515},
                new float[]{0, .49f, 1}, Shader.TileMode.CLAMP));
        actual.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private void sprite(Canvas canvas, Bitmap bitmap, float left, float top, float right, float bottom, boolean flip) {
        if (bitmap == null) return;
        canvas.save();
        if (flip) canvas.scale(-1, 1, (left + right) / 2f, 0);
        canvas.drawBitmap(bitmap, null, new RectF(left, top, right, bottom), spritePaint);
        canvas.restore();
    }

    private void rect(Canvas canvas, float left, float top, float right, float bottom, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(left, top, right, bottom, paint);
    }

    private void text(Canvas canvas, String value, float x, float y, int size, int color) {
        paint.setColor(color);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
    }
}
