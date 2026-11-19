package ru.drivedeck.hsd;

import android.content.res.ColorStateList;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

/** A real dock child: its reserved slot, hit target and accessibility bounds agree. */
public final class CarMenuButton extends ImageButton {
    private int tint;
    private boolean tinted;

    private CarMenuButton(FrameLayout dock) {
        super(dock.getContext());
        setTag("hsd.carMenu");
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String title = "ru".equals(language) ? "Меню автомобиля" : "Car menu";
        setContentDescription(title);
        setTooltipText(title);
        setScaleType(ImageView.ScaleType.FIT_CENTER);
        setImageResource(getResources().getIdentifier("hsd_car_menu", "drawable", getContext().getPackageName()));
        setMinimumWidth(0);
        setMinimumHeight(0);
        setOnClickListener(view -> HSDHost.requestCarMenu(getContext()));
        setVisibility(View.GONE);
        dock.addView(this, new FrameLayout.LayoutParams(0, 0));
    }

    public static ImageButton attach(FrameLayout dock) {
        return HSDHost.supportsCarMenu(dock.getContext()) ? new CarMenuButton(dock) : null;
    }

    public static void place(ImageButton button, RectF menu, RectF split, float gap,
                             boolean horizontal, boolean hidden, int color) {
        if (button == null) return;
        if (hidden || menu.isEmpty()) { button.setVisibility(View.GONE); return; }
        RectF anchor = split.isEmpty() ? menu : split;
        int width = Math.round(menu.width()), height = Math.round(menu.height());
        int left = Math.round(horizontal ? anchor.right + gap : menu.left);
        int top = Math.round(horizontal ? menu.top : anchor.top - gap - height);
        if (button.getLeft() != left || button.getTop() != top ||
                button.getWidth() != width || button.getHeight() != height) {
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(width, height);
            layout.leftMargin = left;
            layout.topMargin = top;
            button.setLayoutParams(layout);
            int padding = Math.round(Math.min(width, height) * .2f);
            button.setPadding(padding, padding, padding, padding);
            button.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            button.layout(left, top, left + width, top + height);
        }
        CarMenuButton car = (CarMenuButton) button;
        if (!car.tinted || car.tint != color) {
            car.tint = color;
            car.tinted = true;
            button.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor((color & 0xffffff) | 0x14000000);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(0xffffffff);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf((color & 0xffffff) | 0x40000000),
                    background, mask));
        }
        button.setVisibility(View.VISIBLE);
    }
}
