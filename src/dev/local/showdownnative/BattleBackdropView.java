package dev.local.showdownnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Local landscape shown immediately, including when the optional CDN art is unavailable. */
final class BattleBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String weather = "";

    BattleBackdropView(Context context) { super(context); }
    void setWeather(String weather) { this.weather = weather.toLowerCase(java.util.Locale.ROOT); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean rain = weather.contains("rain"), snow = weather.contains("snow") || weather.contains("hail"),
                sand = weather.contains("sand"), sun = weather.contains("sun");
        int skyTop = rain ? 0xff596f89 : snow ? 0xff88a6bb : sand ? 0xffbca27d : sun ? 0xff5a9cdf : 0xff70bbe7;
        int skyBottom = rain ? 0xffa4b1bd : snow ? 0xffd9eced : sand ? 0xffe6d5aa : 0xffd6e9ed;
        paint.setShader(new LinearGradient(0, 0, 0, h * .68f, skyTop, skyBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
        paint.setColor(sun ? 0x88fff6ab : 0x88ffffff);
        canvas.drawCircle(w * .78f, h * .22f, h * .14f, paint);
        mountain(canvas, w, h, .39f, 0xff9eb7ad);
        mountain(canvas, w, h, .47f, 0xff6f9b93);
        int ground = snow ? 0xffdcebed : sand ? 0xffd2b583 : rain ? 0xff557b62 : 0xff78aa63;
        paint.setShader(new LinearGradient(0, h * .55f, 0, h, ground,
                snow ? 0xffb6cbd1 : sand ? 0xffaa855f : rain ? 0xff426749 : 0xff4f7e48, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * .55f, w, h, paint); paint.setShader(null);
        paint.setColor(snow ? 0x99ffffff : sand ? 0x88f0d4a3 : 0x8899cf77);
        canvas.drawOval(w * .50f, h * .46f, w * .97f, h * .67f, paint);
        paint.setColor(snow ? 0x99ffffff : sand ? 0x99dfbf90 : 0x99add78a);
        canvas.drawOval(w * .04f, h * .72f, w * .53f, h * .97f, paint);
        if (rain || snow || sand) {
            paint.setColor(snow ? 0xbbffffff : sand ? 0x77f6e6c8 : 0x889cd7ef);
            for (int i = 0; i < 32; i++) {
                float x = ((i * 137) % 997) / 997f * w;
                float y = ((i * 239) % 991) / 991f * h;
                if (rain) { paint.setStrokeWidth(2); canvas.drawLine(x, y, x - 6, y + 13, paint); }
                else canvas.drawCircle(x, y, snow ? 2.2f : 1.6f, paint);
            }
        }
    }

    private void mountain(Canvas canvas, float w, float h, float baseline, int color) {
        Path path = new Path();
        path.moveTo(0, h * .58f);
        path.lineTo(0, h * baseline);
        path.lineTo(w * .16f, h * (baseline - .12f));
        path.lineTo(w * .29f, h * (baseline + .02f));
        path.lineTo(w * .48f, h * (baseline - .19f));
        path.lineTo(w * .63f, h * (baseline - .02f));
        path.lineTo(w * .81f, h * (baseline - .15f));
        path.lineTo(w, h * baseline);
        path.lineTo(w, h * .58f); path.close();
        paint.setColor(color); canvas.drawPath(path, paint);
    }
}
