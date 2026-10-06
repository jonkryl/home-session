package com.jonkryl.homesession;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import io.appmetrica.analytics.AppMetrica;
import io.appmetrica.analytics.AppMetricaConfig;

/** Release installation/session analytics; app records are never attached to events. */
public final class AnalyticsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.APPMETRICA_API_KEY.trim().isEmpty()) return;

        boolean existingInstallation = false;
        try {
            @SuppressWarnings("deprecation")
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            existingInstallation = packageInfo.lastUpdateTime > packageInfo.firstInstallTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Android normally always resolves the application's own installed package.
        }

        AppMetricaConfig config = AppMetricaConfig.newConfigBuilder(BuildConfig.APPMETRICA_API_KEY)
                .withLocationTracking(false)
                .withAdvIdentifiersTracking(false)
                .withCrashReporting(false)
                .withNativeCrashReporting(false)
                .withAnrMonitoring(false)
                .withRevenueAutoTrackingEnabled(false)
                .withSessionsAutoTrackingEnabled(true)
                .handleFirstActivationAsUpdate(existingInstallation)
                .build();
        AppMetrica.activate(this, config);
    }
}
