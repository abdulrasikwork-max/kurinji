package com.safesphere.app

import android.content.Context
import androidx.fragment.app.Fragment
import com.safesphere.app.core.device.AndroidBatteryMonitor
import com.safesphere.app.core.device.AndroidConnectivityMonitor
import com.safesphere.app.core.location.FusedLocationProvider
import com.safesphere.app.core.location.DestinationResolver
import com.safesphere.app.core.location.GeocoderDestinationResolver
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.network.AgeGate
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UploadTimeoutInterceptor
import com.safesphere.app.feature.account.data.DataStorePendingDeletionStore
import com.safesphere.app.feature.account.data.RemoteAccountRepository
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.account.domain.PendingDeletionStore
import com.safesphere.app.feature.account.session.AccountDataWiper
import com.safesphere.app.feature.account.session.SessionRenewer
import kotlinx.coroutines.flow.combine
import com.safesphere.app.core.tracking.CompositeTrackingNoticeSource
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.safewalk.data.DataStoreActiveJourneyStore
import com.safesphere.app.feature.safewalk.data.RemoteJourneyRepository
import com.safesphere.app.feature.safewalk.data.remote.JourneyApi
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.session.AndroidSafeWalkAlerts
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import com.safesphere.app.core.textcheck.HashedLexicon
import com.safesphere.app.core.textcheck.TextCheck
import com.safesphere.app.feature.social.data.DataStoreViewerPreferences
import com.safesphere.app.feature.social.data.RemoteSocialRepository
import com.safesphere.app.feature.social.data.remote.SocialApi
import com.safesphere.app.feature.social.domain.SocialRepository
import com.safesphere.app.feature.social.domain.ViewerPreferences
import coil.ImageLoader
import coil.memory.MemoryCache
import com.safesphere.app.feature.social.data.EncryptedPlacesStore
import com.safesphere.app.feature.social.data.AndroidMediaSource
import com.safesphere.app.feature.social.data.MediaSource
import com.safesphere.app.feature.social.data.PhotoPreparer
import com.safesphere.app.feature.social.data.VideoPreparer
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.PlacesStore
import com.safesphere.app.feature.social.data.RemoteExperienceRepository
import com.safesphere.app.feature.social.data.RemoteDiscoverRepository
import com.safesphere.app.feature.social.data.remote.DiscoverApi
import com.safesphere.app.feature.social.data.remote.ExperienceApi
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.data.RemoteReportRepository
import com.safesphere.app.feature.social.data.remote.ReportApi
import com.safesphere.app.feature.social.domain.ReportRepository
import com.safesphere.app.feature.moderation.data.RemoteContentModerationRepository
import com.safesphere.app.feature.moderation.data.remote.ContentModerationApi
import com.safesphere.app.feature.moderation.domain.ContentModerationRepository
import kotlinx.coroutines.flow.first
import com.safesphere.app.feature.auth.data.DataStoreSessionStorage
import com.safesphere.app.feature.auth.data.RemoteAuthRepository
import com.safesphere.app.feature.auth.data.SessionRefresher
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthInterceptor
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.SessionStorage
import com.safesphere.app.feature.emergency.data.DataStoreActiveEmergencyStore
import com.safesphere.app.feature.emergency.data.RemoteEmergencyLocationRepository
import com.safesphere.app.feature.emergency.data.RemoteEmergencyRepository
import com.safesphere.app.feature.emergency.data.remote.EmergencyApi
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.session.SosRecorder
import com.safesphere.app.core.retired.RiseRetirement
import com.safesphere.app.core.retired.VaultRetirement
import com.safesphere.app.feature.emergency.entry.SosLaunchRequest
import com.safesphere.app.feature.emergency.data.DataStoreSosOptionsStore
import com.safesphere.app.feature.emergency.domain.SosOptionsStore
import com.safesphere.app.feature.emergency.trigger.AndroidShakeCountdownNotifier
import com.safesphere.app.feature.emergency.trigger.AndroidShakeSensor
import com.safesphere.app.feature.emergency.trigger.ShakeSensor
import com.safesphere.app.feature.emergency.trigger.ShakeSos
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.feature.emergency.domain.EmergencyLocationRepository
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.location.EmergencyLocationSharing
import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.onboarding.data.DataStoreOnboardingRepository
import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import com.safesphere.app.feature.safetycircle.data.RecipientSyncingSafetyCircleRepository
import com.safesphere.app.feature.safetycircle.data.RemoteSafetyCircleRepository
import com.safesphere.app.core.sms.AndroidSmsSender
import com.safesphere.app.feature.circlealert.data.DataStoreCircleAlertStore
import com.safesphere.app.feature.circlealert.data.RemoteCircleAlertRepository
import com.safesphere.app.feature.circlealert.data.remote.CircleAlertApi
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleAlertStore
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertCoordinator
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactApi
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.core.crypto.AndroidKeystoreKeyWrapper
import com.safesphere.app.feature.moderation.data.RemoteModerationRepository
import com.safesphere.app.feature.moderation.data.remote.ModerationApi
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.safesphere.app.core.network.BackendOrigin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The app's "composition root": the one place that creates long-lived objects and hands them to
 * ViewModels. It is a small manual alternative to a dependency-injection library.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val onboardingRepository: OnboardingRepository by lazy {
        DataStoreOnboardingRepository(appContext)
    }

    private val sessionStorage: SessionStorage by lazy { DataStoreSessionStorage(appContext) }

    // Phase 17J: the one origin that may receive the sign-in token (AuthInterceptor, videoHeaders).
    private val backendHttpUrl: HttpUrl by lazy { backendBaseUrl().toHttpUrl() }

    // No logging interceptor on purpose: request bodies contain passwords, so they must never be logged.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(sessionStorage, backendHttpUrl))
            // Only photo / video / poster uploads (marked on their calls) get 120 s read and write timeouts.
            .addInterceptor(UploadTimeoutInterceptor())
            .build()
    }

    // ONE Retrofit instance for the whole backend: every API below shares the same client and JWT interceptor.
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(backendBaseUrl())
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }
    private val safetyContactApi: SafetyContactApi by lazy { retrofit.create(SafetyContactApi::class.java) }
    private val emergencyApi: EmergencyApi by lazy { retrofit.create(EmergencyApi::class.java) }

    /** Phase 11 C1: new access tokens from the stored refresh secret, one refresh at a time. */
    private val sessionRefresher by lazy { SessionRefresher(sessionStorage, authApi) }

    /**
     * Phase 10A: the ONE place that decides what a 401 means (only if the rejected token is still the saved one).
     * Phase 11 C1: an expired token is refreshed; a refused refresh is "sign in to sync" (nothing deleted); only a
     * changed password, a deleted or a disabled account signs the phone out.
     */
    val sessionEnder: SessionEnder by lazy { SessionEnder(sessionStorage, sessionRefresher) }

    /** Phase 11 C1: the session as it is now (null = signed out), for decisions that cannot wait (the session dialog). */
    val currentSession: StateFlow<AuthSession?> by lazy {
        sessionStorage.session.stateIn(applicationScope, SharingStarted.Eagerly, null)
    }

    /** Phase 17A: the one age state of the signed-in account (fed by every social refusal and the age screen). */
    val ageGate = AgeGate()

    /** Phase 17B: the phone verification handed from sign-up / sign-in to the code screen (memory only). */
    val pendingVerification = PendingVerification()

    /** Turns backend answers into results; refreshes and retries once on an expired token. Used by the repositories. */
    private val apiCallHandler by lazy { ApiCallHandler(sessionEnder, ageGate) }

    val authRepository: AuthRepository by lazy {
        RemoteAuthRepository(authApi, sessionStorage, sessionEnder, onAccountSwitch = { clearAccountScopedData() })
    }

    /**
     * What a sign-out removes from this phone (the active SOS record incl. unsent endings, the Safe Walk record, the
     * Safety Circle copy for offline SMS). Phase 11 C1: also run, BEFORE the new session is
     * saved, when another account signs in on this phone, so the new account never sees the previous one's data.
     */
    private suspend fun clearAccountScopedData() {
        activeEmergencyStore.clearAll()
        activeJourneyStore.clear()
        circleAlertStore.clear()
        placeMemory.clearAll() // Phase 13B (Q9): private and recent places belong to the account that marked them
    }

    // ---- Phase 13A: profiles and connections (nothing stored on the phone) -----------------------------

    val socialRepository: SocialRepository by lazy {
        RemoteSocialRepository(retrofit.create(SocialApi::class.java), apiCallHandler)
    }

    // ---- Phase 13B: experiences (places, posts, photos, perspectives) --------------------------------------

    val experienceRepository: ExperienceRepository by lazy {
        RemoteExperienceRepository(retrofit.create(ExperienceApi::class.java), apiCallHandler, backendBaseUrl())
    }

    /** IRL redesign (R1): "my own posts changed" (memory only), so the feed reads again when it is shown next. */
    val feedFreshness = com.safesphere.app.feature.social.domain.FeedFreshness()

    // ---- Phase 13C: butterflies, the Cocoon and Discover (nothing stored on the phone) ---------------------

    val discoverRepository: DiscoverRepository by lazy {
        RemoteDiscoverRepository(retrofit.create(DiscoverApi::class.java), apiCallHandler)
    }

    // ---- Phase 17G-1: Explore (a structured query to the backend; nothing stored on the phone) ------------------

    val exploreRepository: com.safesphere.app.feature.social.domain.ExploreRepository by lazy {
        com.safesphere.app.feature.social.data.RemoteExploreRepository(
            retrofit.create(com.safesphere.app.feature.social.data.remote.ExploreApi::class.java), apiCallHandler)
    }

    /** Phase 17G-3: whether THIS account agreed to the optional smart understanding (off by default; per account). */
    val smartUnderstanding: com.safesphere.app.feature.social.domain.SmartUnderstandingChoice by lazy {
        com.safesphere.app.feature.social.domain.SmartUnderstandingChoice(viewerPreferences) { currentSession.value?.user?.id }
    }

    // ---- Phase 13D: reports, own standing and appeals; moderators' content queue (nothing stored) ---------------

    val reportRepository: ReportRepository by lazy {
        RemoteReportRepository(retrofit.create(ReportApi::class.java), apiCallHandler)
    }

    val contentModerationRepository: ContentModerationRepository by lazy {
        RemoteContentModerationRepository(retrofit.create(ContentModerationApi::class.java), apiCallHandler,
            backendBaseUrl())
    }

    // ---- Phase 13E: the private check before sharing (the draft never leaves the phone) ----------------------------

    /**
     * The hashed lexicon from the app's assets (no readable words) and the same evidence rules as the server. Null when
     * the asset cannot be read: then the app simply shares without a nudge (the server checks anyway).
     */
    val textCheck: TextCheck? by lazy {
        runCatching {
            appContext.assets.open(HashedLexicon.ASSET).bufferedReader(Charsets.UTF_8).use {
                TextCheck(HashedLexicon(it.readLines()))
            }
        }.getOrNull()
    }

    /** E20: "Collapse strong language", a choice about the viewer only, kept on this phone. */
    val viewerPreferences: ViewerPreferences by lazy { DataStoreViewerPreferences(appContext) }

    /** IRL redesign (R6): the signed-in account's own Home city, remembered on this phone. */
    val homeCityChoice: com.safesphere.app.feature.social.domain.HomeCityChoice by lazy {
        com.safesphere.app.feature.social.domain.HomeCityChoice(viewerPreferences) { currentSession.value?.user?.id }
    }

    /** Q9: private places, recently used places and the last audience, encrypted on this phone, never uploaded. */
    private val placesStore: PlacesStore by lazy {
        EncryptedPlacesStore(java.io.File(appContext.filesDir, "places"), AndroidKeystoreKeyWrapper())
    }

    val placeMemory: PlaceMemory by lazy {
        PlaceMemory(placesStore, { currentSession.value?.user?.id }, Dispatchers.IO)
    }

    /**
     * Photos are made smaller and re-encoded without metadata on the phone before upload (the server does it again).
     * Phase 17C: videos are re-packed without metadata in the app's cache ("video-upload"); work files a previous run
     * left behind are deleted the first time this is used (none of this run can exist yet).
     */
    val mediaSource: MediaSource by lazy {
        val workDir = java.io.File(appContext.cacheDir, "video-upload")
        VideoPreparer.sweep(workDir)
        AndroidMediaSource(PhotoPreparer(appContext.contentResolver), VideoPreparer(appContext, workDir), Dispatchers.IO)
    }

    /**
     * Phase 17C: the platform video player streams through its own connection (not the shared client), so it is given
     * the current sign-in header for one playback; nothing is stored. Empty when signed out.
     */
    fun videoHeaders(url: String): Map<String, String> =
        if (!BackendOrigin.isSame(backendHttpUrl, url)) emptyMap()   // Phase 17J: never to another origin
        else currentSession.value?.token?.takeIf { it.isNotEmpty() }
            ?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    /**
     * Q3: ONE image loader on the shared client (so AuthInterceptor attaches the JWT), memory cache only: nothing about
     * other people is written to this phone's storage.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient(httpClient)
            .memoryCache { MemoryCache.Builder(appContext).maxSizePercent(0.15).build() }
            .diskCache(null)
            .respectCacheHeaders(true)
            .build()
    }

    // ---- Phase 10A: account security and deletion ------------------------------------------------

    private val accountApi: AccountApi by lazy { retrofit.create(AccountApi::class.java) }

    val accountRepository: AccountRepository by lazy {
        RemoteAccountRepository(accountApi, apiCallHandler, sessionStorage, sessionEnder)
    }

    /** A pending deletion this phone knows about (account id + date only); survives a sign-out. */
    val pendingDeletion: PendingDeletionStore by lazy { DataStorePendingDeletionStore(appContext) }

    /** Removes what this phone kept for a DELETED account (its SOS options; a sign-out clears the rest). */
    val accountDataWiper: AccountDataWiper by lazy {
        AccountDataWiper(
            parts = listOf(
                // Phase 17F: the deleted account's shake choice
                { userId -> sosOptionsStore.forget(userId) }
            ),
            pendingDeletion = pendingDeletion
        )
    }

    /** Phase 4D: every successful load also refreshes the offline copy used for SMS alerts. */
    val safetyCircleRepository: SafetyCircleRepository by lazy {
        RecipientSyncingSafetyCircleRepository(RemoteSafetyCircleRepository(safetyContactApi, apiCallHandler)) { contacts ->
            circleAlerts.updateRecipients(contacts)
        }
    }

    val emergencyRepository: EmergencyRepository by lazy {
        RemoteEmergencyRepository(emergencyApi, apiCallHandler)
    }

    // ---- Phase 4A: location, device readers, emergency location sharing --------------------------

    /**
     * Lives as long as the process. Single-threaded (main) on purpose: EmergencyLocationSharing relies on it.
     * Work that must survive leaving a screen (location saving during an emergency) runs here.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val locationProvider: LocationProvider by lazy { FusedLocationProvider(appContext) }

    private val emergencyLocationRepository: EmergencyLocationRepository by lazy {
        RemoteEmergencyLocationRepository(emergencyApi, apiCallHandler)
    }

    private val emergencyLocationSharing: EmergencyLocationSharing by lazy {
        EmergencyLocationSharing(
            scope = applicationScope,
            locationProvider = locationProvider,
            repository = emergencyLocationRepository,
            batteryMonitor = AndroidBatteryMonitor(appContext),
            connectivityMonitor = AndroidConnectivityMonitor(appContext),
            session = sessionStorage.session
        )
    }

    /** The one holder shared by the emergency screen and the tracking foreground service. */
    val locationSharing: LocationSharingController get() = emergencyLocationSharing

    /** Set by SafetyTrackingService: true while it runs in the foreground. */
    val trackingServiceRunning = MutableStateFlow(false)

    val activeEmergencyStore: ActiveEmergencyStore by lazy { DataStoreActiveEmergencyStore(appContext) }

    /**
     * Phase 11: starts every SOS on the phone (it works without internet) and records it on the server when it can.
     * Created with the container, so an SOS that started or ended offline is recorded after a restart too.
     */
    val sosRecorder: SosRecorder by lazy {
        SosRecorder(
            scope = applicationScope,
            store = activeEmergencyStore,
            repository = emergencyRepository,
            isOnline = AndroidConnectivityMonitor(appContext).isOnline,
            session = sessionStorage.session,
            // Circle first: its memory must move before the location state changes and re-triggers it.
            rebinders = { listOf(circleAlertCoordinator, emergencyLocationSharing) },
            onDialPromptRecorded = { emergencyId -> circleAlerts.onDialPromptOpened(emergencyId) }
        )
    }

    // ---- Phase 4B: Safe Walk ---------------------------------------------------------------------

    private val journeyApi: JourneyApi by lazy { retrofit.create(JourneyApi::class.java) }

    private val activeJourneyStore: ActiveJourneyStore by lazy { DataStoreActiveJourneyStore(appContext) }

    /** The one Safe Walk holder (screen, Home, notification action, tracking service). */
    val safeWalk: SafeWalkSession by lazy {
        SafeWalkSession(
            scope = applicationScope,
            repository = RemoteJourneyRepository(journeyApi, apiCallHandler),
            store = activeJourneyStore,
            locationProvider = locationProvider,
            connectivityMonitor = AndroidConnectivityMonitor(appContext),
            alerts = AndroidSafeWalkAlerts(appContext),
            session = sessionStorage.session,
            currentUserId = { sessionStorage.session.first()?.user?.id },
            batteryMonitor = AndroidBatteryMonitor(appContext),
            destinationResolver = destinationResolver
        )
    }

    /** Destination text -> place (platform Geocoder). Shared by Safe Walk and "Check the way" (Phase 8D). */
    val destinationResolver: DestinationResolver by lazy { GeocoderDestinationResolver(appContext) }

    /** What the ONE tracking foreground service shows: an active emergency first, then a Safe Walk. */
    val trackingNoticeSource: TrackingNoticeSource by lazy {
        CompositeTrackingNoticeSource(listOf(emergencyLocationSharing, safeWalk))
    }

    /** True while an emergency or a walk wants the location service type (used by SafetyTrackingService). */
    fun locationTrackingNeeded(): Boolean =
        emergencyLocationSharing.currentNotice() != null || safeWalk.currentNotice() != null

    // ---- Phase 4D: Safety Circle communication ---------------------------------------------------

    /** Sends SMS only for automatic alerts the person opted into. Also reached by SmsResultReceiver. */
    val smsSender: AndroidSmsSender by lazy { AndroidSmsSender(appContext) }

    private val circleAlertApi: CircleAlertApi by lazy { retrofit.create(CircleAlertApi::class.java) }

    val circleAlertRepository: CircleAlertRepository by lazy { RemoteCircleAlertRepository(circleAlertApi, apiCallHandler) }

    /** The one holder for Safety Circle communication. Created with the container so it watches every walk. */
    val circleAlerts: CircleAlertController get() = circleAlertCoordinator

    /** Phase 4D store (opt-in, Circle copy for offline SMS, alert memory); cleared by a sign-out or an account switch. */
    private val circleAlertStore: CircleAlertStore by lazy { DataStoreCircleAlertStore(appContext) }

    private val circleAlertCoordinator: CircleAlertCoordinator by lazy {
        CircleAlertCoordinator(
            scope = applicationScope,
            store = circleAlertStore,
            repository = circleAlertRepository,
            smsSender = smsSender,
            walkState = safeWalk.state,
            emergencyLocation = emergencyLocationSharing.state,
            session = sessionStorage.session
        )
    }

    /** The signed-in person. */
    val currentUser = sessionStorage.session.map { it?.user }

    /** The same location provider, for screens that need one fix while they are open. */
    val screenLocationProvider: LocationProvider get() = locationProvider

    // ---- Phase 7: moderation (the server checks the role on every call) --------------------------

    private val moderationApi: ModerationApi by lazy { retrofit.create(ModerationApi::class.java) }

    val moderationRepository: ModerationRepository by lazy { RemoteModerationRepository(moderationApi, apiCallHandler) }

    // ---- Phase 13F (D9): Rise "Proof" is retired; its encrypted notes on this phone are deleted once, with a notice --

    val riseRetirement: RiseRetirement by lazy { RiseRetirement(appContext.filesDir) }

    // ---- Phase N1: the Evidence Vault is retired; what it kept on this phone is deleted once, with a notice ----------

    val vaultRetirement: VaultRetirement by lazy { VaultRetirement(appContext.filesDir, appContext.cacheDir) }

    /** Phase 13F (F11): set only by the non-exported SosEntryActivity (shortcut, tile); memory only. */
    val sosLaunchRequest: SosLaunchRequest = SosLaunchRequest()

    // ---- Phase 17F: SOS options (shake gesture) ---------------------------------------------------------------

    /** Whether any screen of IRL is on the display (the shake gesture opens the SOS screen itself only then). */
    val appVisibility: com.safesphere.app.core.lifecycle.AppVisibility
        get() = (appContext as com.safesphere.app.SafeSphereApp).appVisibility

    /** The person's own SOS choice (shake), per account, on this phone only. */
    val sosOptionsStore: SosOptionsStore by lazy { DataStoreSosOptionsStore(appContext) }

    /** The shake sensor, shared by the holder and by the options screen (which hides the switch without one). */
    val shakeSensor: ShakeSensor by lazy { AndroidShakeSensor(appContext) }

    /**
     * Phase 17F (D-4): the one holder of the shake gesture. A shake only ever opens the cancellable 5 s countdown;
     * with IRL off the screen (Safe Walk running) that countdown is a notification with a Cancel button, and at zero
     * the SOS starts on the phone exactly as the SOS screen would have started it.
     */
    val shakeSos: ShakeSos by lazy {
        ShakeSos(
            scope = applicationScope,
            sensor = shakeSensor,
            notifier = AndroidShakeCountdownNotifier(appContext),
            userId = sessionStorage.session.map { it?.user?.id }.distinctUntilChanged(),
            options = sosOptionsStore::options,
            sosRunning = sosRecorder.state.map { it.current != null }.distinctUntilChanged(),
            walkRunning = safeWalk.state.map { it.isActive }.distinctUntilChanged(),
            appVisible = appVisibility.visible,
            notificationsAllowed = { NotificationPermission.isGranted(appContext) },
            startSos = { trigger ->
                val userId = sessionStorage.session.first()?.user?.id
                if (userId == null) {
                    false
                } else {
                    // The same steps the SOS screen takes when its countdown ends (EmergencyViewModel.startEmergency).
                    val record = sosRecorder.startLocal(userId, trigger)
                    safeWalk.onEmergencyStarted()
                    emergencyLocationSharing.start(record.emergencyId, record.startedAt)
                    circleAlertCoordinator.onEmergencyActive(record.emergencyId, record.startedAt)
                    true
                }
            }
        )
    }

    init {
        applicationScope.launch(Dispatchers.IO) { riseRetirement.retire() }
        applicationScope.launch(Dispatchers.IO) { vaultRetirement.retire() }
        currentSession // Phase 11 C1: start following the session now, so the dialog never reads a stale "null"
        circleAlerts // start watching walks and emergencies right away (e.g. after a restart into a walk)
        sosRecorder // record an SOS that started or ended offline as soon as the server can be reached
        shakeSos // Phase 17F: starts listening for the shake gesture if (and only if) the person switched it on
        // On a real sign-out (the person's own, a changed password, a deleted or disabled account) forget the active
        // emergency / walk records kept on this phone. Phase 11 C1: an expired token is NOT a sign-out (the session stays,
        // marked "sign in to sync" if the refresh is refused), so this never runs for it.
        applicationScope.launch {
            sessionStorage.session
                .map { it == null }
                .distinctUntilChanged()
                .collect { signedOut ->
                    if (signedOut) {
                        activeEmergencyStore.clearAll() // incl. SOS endings not told to the server yet
                        activeJourneyStore.clear()
                        placeMemory.clearAll() // Phase 13B (Q9): private and recent places
                    }
                }
        }
        // Phase 10A.3: the server said the account is deleted: remove what a sign-out keeps (the SOS options).
        applicationScope.launch {
            sessionEnder.ended.collect { end ->
                if (end.reason == SessionEndReason.ACCOUNT_DELETED) accountDataWiper.wipe(end.userId)
            }
        }
        // Phase 10A.4 / 11 C1: keep the token usable (refresh at start after idle time, and before it runs out).
        SessionRenewer(
            scope = applicationScope,
            session = sessionStorage.session,
            safetyRunning = combine(
                emergencyLocationSharing.state.map { it.emergencyId != null && !it.signedOut },
                safeWalk.state.map { it.isActive }
            ) { sos, walk -> sos || walk },
            renew = accountRepository::renewToken,
            refresh = { token -> sessionEnder.refresh(token) }
        )
        // Remember a pending deletion apart from the session, so the Login screen can still offer the local clean-up.
        applicationScope.launch {
            sessionStorage.session.map { it?.user }.distinctUntilChanged().collect { user ->
                if (user == null) return@collect
                val due = user.deletionDueAt
                if (due != null) pendingDeletion.set(PendingDeletion(user.id, due)) else pendingDeletion.clear(user.id)
            }
        }
        // Phase 17A: the age state belongs to one account; a sign-out or another account starts it afresh, and a signed-in
        // account is asked once (offline: the first refused social call sets it instead).
        applicationScope.launch {
            sessionStorage.session.map { it?.user?.id }.distinctUntilChanged().collectLatest { userId ->
                ageGate.reset()
                if (userId == null) return@collectLatest
                val age = socialRepository.ageConfirmed()
                if (age is ApiResult.Success) if (age.data) ageGate.onConfirmed() else ageGate.onRefused()
            }
        }
    }

    /** Retrofit requires the base URL to end with "/". */
    private fun backendBaseUrl(): String {
        val url = BuildConfig.API_BASE_URL.trim()
        return if (url.endsWith("/")) url else "$url/"
    }

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 15L
    }
}

val Fragment.appContainer: AppContainer
    get() = (requireActivity().application as SafeSphereApp).container

val android.app.Activity.appContainer: AppContainer
    get() = (application as SafeSphereApp).container
