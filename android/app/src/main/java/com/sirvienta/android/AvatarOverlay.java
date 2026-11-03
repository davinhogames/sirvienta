package com.sirvienta.android;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.animation.ValueAnimator;

public final class AvatarOverlay {
    private static final String TAG = "SirvientaAvatar";
    private final Context context;
    private final WindowManager windowManager;
    private View avatarView;
    private Animator avatarAnimation;

    public AvatarOverlay(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean show() {
        if (!Settings.canDrawOverlays(context)) {
            return false;
        }
        if (avatarView != null) {
            return true;
        }
        if (windowManager == null) {
            Log.e(TAG, "El servicio de ventanas no está disponible.");
            return false;
        }

        FrameLayout bubble = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xe6111b2b);
        background.setStroke(dp(2), 0xff75cbff);
        bubble.setBackground(background);
        bubble.setElevation(dp(8));
        bubble.setContentDescription("Sirvienta está escuchando. Toca para abrir la aplicación.");
        bubble.setFocusable(true);
        bubble.setClickable(true);

        ImageView imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.sirvienta_avatar);
        imageView.setContentDescription("Avatar animado de Sirvienta");
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubble.addView(
                imageView,
                new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        bubble.setOnClickListener(view -> {
            Intent openApp = new Intent(context, MainActivity.class);
            openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(openApp);
        });

        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                dp(80),
                dp(80),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.END;
        layout.x = dp(12);
        layout.y = dp(180);

        try {
            windowManager.addView(bubble, layout);
            avatarView = bubble;
            animate(imageView);
            return true;
        } catch (WindowManager.BadTokenException | IllegalStateException | SecurityException error) {
            Log.e(TAG, "No se pudo mostrar el avatar flotante.", error);
            return false;
        }
    }

    public void hide() {
        if (avatarView == null) {
            return;
        }
        View view = avatarView;
        avatarView = null;
        if (avatarAnimation != null) {
            avatarAnimation.cancel();
            avatarAnimation = null;
        }
        try {
            windowManager.removeView(view);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "El avatar ya no estaba adjunto a la pantalla.", error);
        }
    }

    private void animate(ImageView imageView) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(imageView, View.SCALE_X, 0.94f, 1.04f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(imageView, View.SCALE_Y, 0.94f, 1.04f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(imageView, View.ALPHA, 0.78f, 1.0f);
        for (ObjectAnimator animator : new ObjectAnimator[] {scaleX, scaleY, alpha}) {
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
        }
        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(scaleX, scaleY, alpha);
        pulse.setDuration(1_100L);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.setStartDelay(200L);
        avatarAnimation = pulse;
        pulse.start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
