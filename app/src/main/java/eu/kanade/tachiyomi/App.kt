package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.DomainModule
import eu.kanade.domain.SYDomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.coil.AnimeCoverFetcher
import eu.kanade.tachiyomi.data.coil.AnimeCoverKeyer
import eu.kanade.tachiyomi.data.coil.AnimeKeyer
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import org.conscrypt.Conscrypt
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.entries.anime.AnimeWidgetManager
import tachiyomi.presentation.widget.entries.manga.MangaWidgetManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.Security

class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {

    private val basePreferences: BasePreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()
        patchInjekt()

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Avoid potential crashes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        Injekt.importModule(PreferenceModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(DomainModule())
        // SY -->
        Injekt.importModule(SYDomainModule())
        // SY <--

        setupNotificationChannels()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Forzar la aplicación del idioma configurado
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            try {
                val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                if (!currentLocales.isEmpty) {
                    // Si ya hay un idioma configurado, asegurarse de que se aplique
                    val languageTag = currentLocales.get(0)?.toLanguageTag() ?: "es"
                    setLocale(languageTag)
                } else {
                    // Si no hay idioma configurado, usar español por defecto
                    setLocale("es")
                    // También actualizar AppCompatDelegate
                    val localeList = androidx.core.os.LocaleListCompat.forLanguageTags("es")
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error al configurar el idioma" }
            }
        }

        // Inicializar repositorios por defecto
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val animeRepo = Injekt.get<mihon.domain.extensionrepo.anime.repository.AnimeExtensionRepoRepository>()
            val mangaRepo = Injekt.get<mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository>()

            // Agregar repositorio de anime por defecto
            if (animeRepo.getAll().isEmpty()) {
                animeRepo.insertRepo(
                    baseUrl = "https://raw.githubusercontent.com/Kohi-den/extensions/main",
                    name = "Kohi-den Extensions",
                    shortName = "Kohi-den",
                    website = "https://github.com/Kohi-den/extensions",
                    signingKeyFingerprint = "NOFINGERPRINT-1"
                )
            }

            // Agregar repositorio de manga por defecto
            if (mangaRepo.getAll().isEmpty()) {
                mangaRepo.insertRepo(
                    baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
                    name = "Keiyoushi Extensions",
                    shortName = "Keiyoushi",
                    website = "https://github.com/keiyoushi/extensions",
                    signingKeyFingerprint = "NOFINGERPRINT-2"
                )
            }
        }

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode().changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_24dp)
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(ProcessLifecycleOwner.get().lifecycleScope)

        setAppCompatDelegateThemeMode(Injekt.get<UiPreferences>().themeMode().get())

        // Updates widget update
        with(MangaWidgetManager(Injekt.get(), Injekt.get())) {
            init(ProcessLifecycleOwner.get().lifecycleScope)
        }

        with(AnimeWidgetManager(Injekt.get(), Injekt.get())) {
            init(ProcessLifecycleOwner.get().lifecycleScope)
        }

        if (!LogcatLogger.isInstalled && networkPreferences.verboseLogging().get()) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }

        initializeMigrator()
    }

    private fun initializeMigrator() {
        val preferenceStore = Injekt.get<PreferenceStore>()
        val preference = preferenceStore.getInt(Preference.appStateKey("last_version_code"), 0)
        logcat { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            val callFactoryLazy = lazy { Injekt.get<NetworkHelper>().client }
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                add(AnimeCoverFetcher.AnimeFactory(callFactoryLazy))
                add(AnimeCoverFetcher.AnimeCoverFactory(callFactoryLazy))
                // Keyer
                add(AnimeKeyer())
                add(MangaKeyer())
                add(AnimeCoverKeyer())
                add(MangaCoverKeyer())
            }

            crossfade((300 * this@App.animatorDurationScale).toInt())
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            if (networkPreferences.verboseLogging().get()) logger(DebugLogger())

            // Coil spawns a new thread for every image load by default
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart()
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped()
    }

    override fun getPackageName(): String {
        // This causes freezes in Android 6/7 for some reason
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            val chromiumElement = stackTrace.find {
                it.className.equals(
                    "org.chromium.base.BuildInfo",
                    ignoreCase = true,
                )
            }
            if (chromiumElement?.methodName.equals("getAll", ignoreCase = true)) {
                return WebViewUtil.SPOOF_PACKAGE_NAME
            }
        } catch (_: Exception) {
        }
        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode().set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }

    /**
     * Establece el idioma de la aplicación de manera forzada
     */
    private fun setLocale(languageCode: String) {
        try {
            val locale = java.util.Locale(languageCode)
            java.util.Locale.setDefault(locale)
            
            val config = resources.configuration
            config.setLocale(locale)
            resources.updateConfiguration(config, resources.displayMetrics)
            
            createConfigurationContext(config)
            
            // También configurar las locales de AppCompatDelegate
            val localeList = androidx.core.os.LocaleListCompat.forLanguageTags(languageCode)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
            
            logcat { "Idioma configurado a: $languageCode" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al configurar el idioma: $languageCode" }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"
