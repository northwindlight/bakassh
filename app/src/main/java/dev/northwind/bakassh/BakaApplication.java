package dev.northwind.bakassh;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/**
 * 应用程序入口：启用 Pixel Material You (Monet) 动态颜色系统。
 */
public class BakaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
