package com.jonkryl.homesession.ads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jonkryl.homesession.BuildConfig;
import com.yandex.mobile.ads.banner.BannerAdEventListener;
import com.yandex.mobile.ads.banner.BannerAdSize;
import com.yandex.mobile.ads.banner.BannerAdView;
import com.yandex.mobile.ads.common.AdRequest;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.YandexAds;

/**
 * Owns one foreground banner outside cleaning controls. All state changes happen on the main thread.
 * Privacy settings precede manual SDK initialization. No room, task, or completion content is passed to an ad request.
 */
public final class BannerController {
    private static final long REQUEST_TIMEOUT_MS = 45_000L;
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AdRetryPolicy retries = new AdRetryPolicy();
    private final ConnectivityManager connectivity;
    private final boolean russian;
    private FrameLayout host;
    private BannerAdView banner;
    private AlertDialog privacyDialog;
    private boolean started;
    private boolean destroyed;
    private boolean initialized;
    private boolean initializing;
    private boolean networkRegistered;
    private boolean networkAvailable;
    private boolean loading;
    private Runnable timeout;
    private Runnable pendingRetry;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { networkChanged(); }
                @Override public void onLost(Network network) { networkChanged(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    networkChanged();
                }
                private void networkChanged() {
                    handler.post(() -> {
                        if (destroyed || !started) return;
                        boolean available = hasNetwork();
                        boolean restored = available && !networkAvailable;
                        networkAvailable = available;
                        if (restored) {
                            cancelRetry();
                            retries.reset();
                            requestBanner();
                        }
                    });
                }
            };

    public BannerController(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("ad_privacy", Context.MODE_PRIVATE);
        connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        russian = "ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    public void attach(FrameLayout container) {
        if (destroyed || host != null) return;
        host = container;
        host.setContentDescription(text("Реклама", "Advertisement"));
        host.post(() -> {
            reserveSlot();
            if (preferences.getBoolean("choice_set", false)) initialize();
            else showChoice(true);
        });
    }

    public void onStart() {
        if (destroyed) return;
        started = true;
        retries.reset();
        networkAvailable = hasNetwork();
        if (connectivity != null && !networkRegistered) {
            try {
                connectivity.registerDefaultNetworkCallback(networkCallback);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
                // Ads can still load; failure to watch network state never affects local tasks.
            }
        }
        if (preferences.getBoolean("choice_set", false)) initialize();
        if (host != null) host.post(this::requestBanner);
    }

    public void onStop() {
        started = false;
        cancelRetry();
        cancelTimeout();
        releaseBanner();
        if (connectivity != null && networkRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
            networkRegistered = false;
        }
    }

    private void applyPrivacy() {
        // Pass the saved choice on every launch, before init and any ad request.
        YandexAds.setUserConsent(preferences.getBoolean("personalized", false));
        YandexAds.enableDebugErrorIndicator(BuildConfig.DEBUG);
        YandexAds.setLocationTracking(false);
        YandexAds.setAppAdAnalyticsReporting(false);
    }

    private void initialize() {
        if (destroyed || !preferences.getBoolean("choice_set", false)) return;
        applyPrivacy();
        if (initialized) { requestBanner(); return; }
        if (initializing) return;
        initializing = true;
        try {
            YandexAds.initialize(activity.getApplicationContext(), () -> handler.post(() -> {
                if (destroyed) return;
                initializing = false;
                initialized = true;
                requestBanner();
            }));
        } catch (RuntimeException ignored) {
            initializing = false;
            scheduleRetry();
        }
    }

    private BannerAdSize reserveSlot() {
        if (host == null) return null;
        int pixels = host.getWidth() > 0 ? host.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(1, Math.round(pixels / activity.getResources().getDisplayMetrics().density));
        BannerAdSize size = BannerAdSize.sticky(activity, width);
        // Keep the same reserved slot during loading/no-fill so cleaning content never jumps.
        host.setMinimumHeight(Math.max(host.getMinimumHeight(), size.getHeightInPixels(activity)));
        return size;
    }

    private void requestBanner() {
        if (destroyed || !started || activity.isFinishing() || activity.isDestroyed()
                || host == null || !initialized || loading || banner != null || pendingRetry != null) return;
        if (!hasNetwork()) { networkAvailable = false; return; }
        BannerAdSize size = reserveSlot();
        if (size == null || host.getWidth() <= 0) return;
        final BannerAdView view = new BannerAdView(activity);
        banner = view;
        loading = true;
        view.setAdSize(size);
        view.setBannerAdEventListener(new BannerAdEventListener() {
            @Override public void onAdLoaded() {
                handler.post(() -> {
                    if (banner != view || destroyed) return;
                    loading = false;
                    cancelTimeout();
                    retries.reset();
                });
            }
            @Override public void onAdFailedToLoad(AdRequestError error) {
                handler.post(() -> {
                    if (banner != view || destroyed) return;
                    cancelTimeout();
                    releaseBanner();
                    scheduleRetry();
                });
            }
            @Override public void onAdClicked() { }
            @Override public void onImpression(ImpressionData data) { }
        });
        host.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        timeout = () -> {
            if (banner != view || destroyed || !loading) return;
            releaseBanner();
            scheduleRetry();
        };
        handler.postDelayed(timeout, REQUEST_TIMEOUT_MS);
        try {
            // Debug uses the SDK demo block; release is guarded to contain its own real R-M block.
            view.loadAd(new AdRequest.Builder(BuildConfig.YANDEX_BANNER_ID).build());
        } catch (RuntimeException ignored) {
            cancelTimeout();
            releaseBanner();
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (destroyed || !started || pendingRetry != null) return;
        long delay = retries.failed();
        if (delay < 0) return;
        pendingRetry = () -> {
            pendingRetry = null;
            if (initialized) requestBanner(); else initialize();
        };
        handler.postDelayed(pendingRetry, delay);
    }

    private void cancelRetry() {
        if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
        pendingRetry = null;
    }

    private void cancelTimeout() {
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
    }

    private void releaseBanner() {
        loading = false;
        BannerAdView old = banner;
        banner = null;
        if (old != null) {
            old.setBannerAdEventListener(null);
            if (host != null) host.removeView(old);
            old.destroy();
        }
    }

    private boolean hasNetwork() {
        if (connectivity == null) return true;
        try {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException ignored) { return true; }
    }

    public void showPrivacyChoice() { showChoice(false); }

    private void showChoice(boolean firstLaunch) {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()
                || (privacyDialog != null && privacyDialog.isShowing())) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(8), padding, dp(8));
        TextView explanation = new TextView(activity);
        explanation.setTextSize(16);
        explanation.setText(text(
                "Рекламу предоставляет Яндекс. Комнаты, задачи и журнал уборки остаются на этом устройстве. Рекламные запросы могут передавать IP-адрес, сведения об устройстве и технические идентификаторы. Яндекс может определять приблизительный регион по IP-адресу.\n\nМожно разрешить Яндексу обработку данных для персонализации рекламы или выбрать контекстные объявления. Реклама загружается в обоих случаях. Отслеживание местоположения устройства и аналитические отчёты рекламного SDK отключены, разрешение AD_ID не запрашивается. Выбор можно изменить в «Приватность рекламы».\n\nAppMetrica передаёт Яндексу сведения об установках и сессиях, технические сведения об устройстве и приложении и IP-адрес для статистики использования. Содержимое ваших записей не передаётся. В AppMetrica отключены геолокация, рекламные идентификаторы и отчёты об ошибках. Эта статистика работает независимо от выбора персонализации рекламы.",
                "Ads are provided by Yandex. Rooms, tasks and the cleaning journal stay on this device. Ad requests may send an IP address, device information and technical identifiers. Yandex may infer an approximate region from your IP address.\n\nYou can allow Yandex to process data for personalized advertising or choose contextual ads. Ads load in both cases. Device location tracking and advertising SDK analytics reports are disabled; AD_ID permission is not requested. You can change your choice in Ad privacy.\n\nAppMetrica sends installation and session statistics, technical device and app information and an IP address to Yandex for usage analytics. The contents of your records are not sent. Location, advertising identifiers and crash reports are disabled in AppMetrica. These statistics operate independently of your advertising personalization choice."));
        content.addView(explanation);
        Button contextual = choiceButton(text("Контекстная реклама", "Contextual ads"));
        Button personalized = choiceButton(text("Разрешить персонализацию", "Allow personalization"));
        Button policy = choiceButton(text("Политика приватности", "Privacy policy"));
        content.addView(contextual);
        content.addView(personalized);
        content.addView(policy);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(text("Приватность рекламы", "Ad privacy"))
                .setView(scroller).setCancelable(!firstLaunch);
        if (!firstLaunch) builder.setNegativeButton(text("Закрыть", "Close"), null);
        privacyDialog = builder.create();
        contextual.setOnClickListener(v -> choose(false));
        personalized.setOnClickListener(v -> choose(true));
        policy.setOnClickListener(v -> openPrivacyPolicy());
        privacyDialog.setOnDismissListener(dialog -> privacyDialog = null);
        privacyDialog.show();
    }

    private Button choiceButton(String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinimumHeight(dp(48));
        button.setSingleLine(false);
        button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void choose(boolean personalized) {
        if (!preferences.edit().putBoolean("personalized", personalized).putBoolean("choice_set", true).commit()) {
            Toast.makeText(activity, text("Не удалось сохранить выбор. Повторите.",
                    "Could not save your choice. Please try again."), Toast.LENGTH_LONG).show();
            return;
        }
        if (privacyDialog != null) privacyDialog.dismiss();
        cancelRetry();
        cancelTimeout();
        releaseBanner();
        retries.reset();
        initialize();
    }

    public void openPrivacyPolicy() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(activity, text("Нет приложения для открытия ссылки.",
                    "No app is available to open this link."), Toast.LENGTH_LONG).show();
        }
    }

    public void destroy() {
        if (destroyed) return;
        onStop();
        destroyed = true;
        if (privacyDialog != null) privacyDialog.dismiss();
        privacyDialog = null;
        handler.removeCallbacksAndMessages(null);
        host = null;
    }

    private String text(String ru, String en) { return russian ? ru : en; }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
