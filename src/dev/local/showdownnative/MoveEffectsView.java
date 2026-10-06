package dev.local.showdownnative;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import java.util.ArrayDeque;
import java.util.Locale;

/** A small, data-driven native effect renderer; battle rules remain on the server. */
final class MoveEffectsView extends View {
    private static final class Cue {
        final DexNames.MoveInfo move;
        final boolean fromLeft, self;
        final int seed;
        Cue(DexNames.MoveInfo move, boolean fromLeft, boolean self) {
            this.move = move; this.fromLeft = fromLeft; this.self = self;
            this.seed = move.english.toLowerCase(Locale.ROOT).hashCode() & 0x7fffffff;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayDeque<Cue> queue = new ArrayDeque<>();
    private Cue current;
    private ValueAnimator animator;
    private float progress;

    MoveEffectsView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

    void play(DexNames.MoveInfo move, boolean fromLeft, boolean self) {
        Log.d("ShowdownNative", "Move visual: " + move.english + " / " + move.type + " / " + move.category);
        if (queue.size() > 3) queue.removeFirst();
        queue.addLast(new Cue(move, fromLeft, self));
        if (current == null) next();
    }

    void clear() {
        queue.clear(); current = null;
        if (animator != null) animator.cancel();
        animator = null; invalidate();
    }

    private void next() {
        if (queue.isEmpty()) { current = null; progress = 0; invalidate(); return; }
        current = queue.removeFirst();
        progress = 0;
        ValueAnimator active = ValueAnimator.ofFloat(0, 1);
        animator = active;
        active.setDuration(680 + Math.min(220, current.move.power * 2));
        active.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
        active.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator == active) { animator = null; next(); }
            }
        });
        active.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Cue cue = current;
        if (cue == null) return;
        float scale = Math.min(2f, getResources().getDisplayMetrics().density);
        canvas.save(); canvas.scale(scale, scale);
        float w = getWidth() / scale, h = getHeight() / scale;
        if (w == 0 || h == 0) { canvas.restore(); return; }
        int color = typeColor(cue.move.type);
        float sx = w * (cue.fromLeft ? .27f : .73f), sy = h * (cue.fromLeft ? .72f : .44f);
        float tx = w * (cue.fromLeft ? .73f : .27f), ty = h * (cue.fromLeft ? .44f : .72f);
        if (cue.self) { tx = sx; ty = sy; }
        float t = Math.min(1, progress * 1.22f);
        String name = cue.move.english.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (cue.move.category == 1 || cue.self) drawAura(canvas, cue, tx, ty, color);
        else {
            drawTravel(canvas, cue, sx, sy, tx, ty, t, color);
            if (progress > .55f) drawImpact(canvas, cue, tx, ty, color);
        }
        if (name.contains("beam") || name.contains("ray") || name.contains("laser") || name.contains("cannon"))
            drawBeam(canvas, sx, sy, tx, ty, color);
        if (name.contains("wave") || name.contains("surf") || name.contains("tide"))
            drawWave(canvas, tx, ty, w, color);
        if (name.contains("quake") || name.contains("earth") || name.contains("magnitude"))
            drawQuake(canvas, w, h, color);
        if (name.contains("slash") || name.contains("cut") || name.contains("claw") || name.contains("blade"))
            drawSlashes(canvas, cue, tx, ty, color);
        if (name.contains("protect") || name.contains("shield") || name.contains("screen"))
            drawShield(canvas, tx, ty, color);
        if (name.contains("heal") || name.contains("recover") || name.contains("rest") || name.contains("drain"))
            drawHeal(canvas, cue, tx, ty);
        drawMoveName(canvas, cue, w, h, color);
        canvas.restore();
    }

    private void drawTravel(Canvas canvas, Cue cue, float sx, float sy, float tx, float ty, float t, int color) {
        float arc = ((cue.seed % 7) - 3) * getHeight() * .012f;
        float x = sx + (tx - sx) * t;
        float y = sy + (ty - sy) * t - (float) Math.sin(Math.PI * t) * arc;
        float radius = 6 + Math.min(12, cue.move.power / 18f) + cue.seed % 5;
        if (cue.move.category == 2) {
            paint.setColor(withAlpha(color, 155)); paint.setStrokeWidth(3 + cue.seed % 4);
            for (int i = 0; i < 5; i++) {
                float offset = i * 11 + (cue.seed % 9);
                float direction = cue.fromLeft ? -1 : 1;
                canvas.drawLine(x + direction * offset, y - 15 + i * 6,
                        x + direction * (offset + 18), y - 15 + i * 6, paint);
            }
        } else {
            paint.setColor(withAlpha(color, 60)); canvas.drawCircle(x, y, radius * 2.1f, paint);
            paint.setColor(withAlpha(color, 220)); canvas.drawCircle(x, y, radius, paint);
            paint.setColor(0xeeffffff); canvas.drawCircle(x - radius * .25f, y - radius * .25f, radius * .3f, paint);
        }
        int particles = 9 + cue.seed % 8;
        for (int i = 0; i < particles; i++) {
            float lag = i * .045f;
            float p = Math.max(0, t - lag);
            if (p <= 0) continue;
            float px = sx + (tx - sx) * p;
            float py = sy + (ty - sy) * p + (float) Math.sin(i * 2.1 + cue.seed) * (8 + cue.seed % 10);
            float size = 2 + (cue.seed + i * 13) % 6;
            drawMotif(canvas, cue.move.type, px, py, size, color, (cue.seed + i) % 4);
        }
    }

    private void drawImpact(Canvas canvas, Cue cue, float x, float y, int color) {
        float life = Math.min(1, (progress - .55f) / .45f);
        float radius = (18 + cue.move.power * .18f) * (1 + life);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 - life * 2);
        paint.setColor(withAlpha(color, (int) (210 * (1 - life))));
        canvas.drawCircle(x, y, radius, paint);
        canvas.drawCircle(x, y, radius * .58f, paint);
        paint.setStyle(Paint.Style.FILL);
        int rays = 8 + cue.seed % 7;
        for (int i = 0; i < rays; i++) {
            float angle = (float) (i * Math.PI * 2 / rays + cue.seed * .01);
            float distance = radius * (.55f + life * .5f);
            float px = x + (float) Math.cos(angle) * distance;
            float py = y + (float) Math.sin(angle) * distance;
            drawMotif(canvas, cue.move.type, px, py, 3 + cue.seed % 5, color, i % 4);
        }
    }

    private void drawAura(Canvas canvas, Cue cue, float x, float y, int color) {
        float r = 28 + (cue.seed % 25) + progress * 36;
        paint.setColor(withAlpha(color, (int) (100 * (1 - progress))));
        canvas.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(withAlpha(color, (int) (220 * (1 - progress))));
        canvas.drawCircle(x, y, r * .65f, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 14; i++) {
            double angle = i * Math.PI * 2 / 14 + progress * (cue.seed % 2 == 0 ? 3 : -3);
            float px = x + (float) Math.cos(angle) * r;
            float py = y + (float) Math.sin(angle) * r * .55f;
            drawMotif(canvas, cue.move.type, px, py, 3 + i % 4, color, i % 4);
        }
    }

    private void drawMotif(Canvas canvas, String type, float x, float y, float size, int color, int variant) {
        paint.setColor(withAlpha(color, 160 + variant * 20));
        switch (type) {
            case "Electric":
                paint.setStrokeWidth(Math.max(2, size / 2));
                Path bolt = new Path(); bolt.moveTo(x - size, y - size);
                bolt.lineTo(x + size * .2f, y); bolt.lineTo(x - size * .2f, y);
                bolt.lineTo(x + size, y + size); canvas.drawPath(bolt, paint); break;
            case "Grass": case "Bug":
                canvas.save(); canvas.rotate(variant * 45 + progress * 220, x, y);
                canvas.drawOval(x - size * .4f, y - size, x + size * .4f, y + size, paint);
                canvas.restore(); break;
            case "Ice": case "Steel": case "Rock":
                Path crystal = new Path(); crystal.moveTo(x, y - size);
                crystal.lineTo(x + size, y); crystal.lineTo(x, y + size);
                crystal.lineTo(x - size, y); crystal.close(); canvas.drawPath(crystal, paint); break;
            case "Fairy": case "Psychic":
                paint.setStrokeWidth(2); canvas.drawLine(x - size, y, x + size, y, paint);
                canvas.drawLine(x, y - size, x, y + size, paint); break;
            case "Fire":
                Path flame = new Path(); flame.moveTo(x, y - size * 1.5f);
                flame.quadTo(x + size * 1.2f, y, x, y + size);
                flame.quadTo(x - size, y, x, y - size * 1.5f); canvas.drawPath(flame, paint); break;
            default: canvas.drawCircle(x, y, size, paint);
        }
    }

    private void drawBeam(Canvas canvas, float sx, float sy, float tx, float ty, int color) {
        float end = Math.min(1, progress * 1.5f);
        paint.setColor(withAlpha(color, (int) (180 * (1 - progress * .6f))));
        paint.setStrokeWidth(11); canvas.drawLine(sx, sy, sx + (tx - sx) * end, sy + (ty - sy) * end, paint);
        paint.setColor(0xccffffff); paint.setStrokeWidth(3);
        canvas.drawLine(sx, sy, sx + (tx - sx) * end, sy + (ty - sy) * end, paint);
    }

    private void drawWave(Canvas canvas, float x, float y, float w, int color) {
        paint.setColor(withAlpha(color, (int) (170 * (1 - progress))));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5);
        Path wave = new Path();
        for (int i = -8; i <= 8; i++) {
            float px = x + i * w * .045f;
            float py = y + (float) Math.sin(i * .8 + progress * 12) * 13;
            if (i == -8) wave.moveTo(px, py); else wave.lineTo(px, py);
        }
        canvas.drawPath(wave, paint); paint.setStyle(Paint.Style.FILL);
    }

    private void drawQuake(Canvas canvas, float w, float h, int color) {
        paint.setColor(withAlpha(color, (int) (160 * (1 - progress)))); paint.setStrokeWidth(5);
        for (int i = 0; i < 4; i++) {
            float x = w * (i + 1) / 5f + (float) Math.sin(progress * 20 + i) * 10;
            canvas.drawLine(x, h * .78f, x + 15, h * .96f, paint);
        }
    }

    private void drawSlashes(Canvas canvas, Cue cue, float x, float y, int color) {
        paint.setStrokeWidth(4 + cue.seed % 5);
        paint.setColor(withAlpha(color, (int) (230 * (1 - progress * .7f))));
        for (int i = 0; i < 2 + cue.seed % 3; i++) {
            float shift = (i - 1) * 13;
            canvas.drawLine(x - 28 + shift, y + 22, x + 24 + shift, y - 28, paint);
        }
    }

    private void drawShield(Canvas canvas, float x, float y, int color) {
        float r = 36 + progress * 24;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5);
        paint.setColor(withAlpha(color, (int) (220 * (1 - progress))));
        Path shield = new Path();
        for (int i = 0; i <= 6; i++) {
            double angle = i * Math.PI / 3 - Math.PI / 2;
            float px = x + (float) Math.cos(angle) * r, py = y + (float) Math.sin(angle) * r;
            if (i == 0) shield.moveTo(px, py); else shield.lineTo(px, py);
        }
        canvas.drawPath(shield, paint); paint.setStyle(Paint.Style.FILL);
    }

    private void drawHeal(Canvas canvas, Cue cue, float x, float y) {
        paint.setColor(withAlpha(0xff89ffb2, (int) (220 * (1 - progress))));
        for (int i = 0; i < 8; i++) {
            float px = x + ((cue.seed + i * 37) % 70 - 35);
            float py = y + 35 - progress * 70 - (i % 3) * 12;
            canvas.drawCircle(px, py, 3 + i % 3, paint);
        }
    }

    private void drawMoveName(Canvas canvas, Cue cue, float w, float h, int color) {
        String name = cue.move.chinese;
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.min(31, h * .085f));
        while (paint.measureText(name) > w - 32 && paint.getTextSize() > 14)
            paint.setTextSize(paint.getTextSize() - 1);
        float x = (w - paint.measureText(name)) / 2;
        float top = h * .18f;
        paint.setColor(0xd0202834);
        canvas.drawRoundRect(new RectF(x - 12, top, w - x + 12, top + 36), 9, 9, paint);
        paint.setColor(color);
        canvas.drawText(name, x, top + 27, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static int typeColor(String type) {
        switch (type) {
            case "Fire": return 0xffff743e;
            case "Water": return 0xff4dbbff;
            case "Electric": return 0xffffe04f;
            case "Grass": return 0xff7bdf65;
            case "Ice": return 0xff9aeaff;
            case "Fighting": return 0xffff915d;
            case "Poison": return 0xffbf79e6;
            case "Ground": return 0xffd6a563;
            case "Flying": return 0xffa6c7ff;
            case "Psychic": return 0xffff86bf;
            case "Bug": return 0xffa9cb57;
            case "Rock": return 0xffccbd87;
            case "Ghost": return 0xffa08bd9;
            case "Dragon": return 0xff8f81ff;
            case "Dark": return 0xff87818f;
            case "Steel": return 0xffa4beca;
            case "Fairy": return 0xffffafe5;
            default: return 0xffe4e5ee;
        }
    }
}
