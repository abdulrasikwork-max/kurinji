package com.safesphere.app

import android.content.Context
import androidx.fragment.app.Fragment
import com.safesphere.app.core.device.AndroidBatteryMonitor
import com.safesphere.app.core.device.AndroidConnectivityMonitor
import com.safesphere.app.core.location.FusedLocationProvider
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
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.safewalk.data.DataStoreActiveJourneyStore
import com.safesphere.app.feature.safewalk.data.RemoteJourneyRepository
import com.safesphere.app.feature.safewalk.data.remote.JourneyApi
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.session.AndroidSafeWalkAlerts
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import com.safesphere.app.core.textcheck.HashedLexicon
import com.safesphere.app.core.textcheck.TextCheck
import com.safesphere.app.feature.social.data.RemoteSocialRepository
import com.safesphere.app.feature.social.data.remote.SocialApi
import com.safesphere.app.feature.social.domain.SocialRepository
import coil.ImageLoader
import coil.memory.MemoryCache
import com.safesphere.app.feature.social.data.AndroidMediaSource
import com.safesphere.app.feature.social.data.MediaSource
import com.safesphere.app.feature.social.data.PhotoPreparer
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
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.feature.emergency.domain.EmergencyLocationRepository
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.location.EmergencyLocationSharing
import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.onboarding.data.DataStoreOnboardingRepository
import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import com.safesphere.app.feature.safetycircle.data.RecipientSyncingSafetyCircleRepository
import com.safesphere.app.feature.safetycircle.data.RemoteSafetyCircleRepository
import com.safesphere.app.feature.circlealert.data.DataStoreCircleAlertStore
import com.safesphere.app.feature.circlealert.data.RemoteCircleAlertRepository
import com.safesphere.app.feature.circlealert.data.RemoteSafetyAlertRepository
import com.safesphere.app.feature.circlealert.data.remote.SafetyAlertApi
import com.safesphere.app.feature.circlealert.domain.SafetyAlertRepository
import com.safesphere.app.feature.circlealert.data.remote.CircleAlertApi
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleAlertStore
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertCoordinator
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactApi
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.core.crypto.AndroidKeystoreKeyWrapper
import com.safesphere.app.core.retired.OldSocialRetirement
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // Phase 17J: the one origin that may receive the sign-in token (AuthInterceptor).
    private val backendHttpUrl: HttpUrl by lazy { backendBaseUrl().toHttpUrl() }

    // No logging interceptor on purpose: request bodies contain sign-in codes, tickets and refresh secrets, so they must never be logged.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(sessionStorage, backendHttpUrl))
            // Only photo uploads (marked on their calls) get 120 s read and write timeouts.
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
     * "Sign out all other phones" from another phone (N2), a deleted or a disabled account signs the phone out.
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
    }

    // ---- Phase 13A: profiles and connections (nothing stored on the phone) -----------------------------

    val socialRepository: SocialRepository by lazy {
        RemoteSocialRepository(retrofit.create(SocialApi::class.java), apiCallHandler)
    }

    // ---- Phase N4: moments (Today, a moment, the person's own; nothing stored on the phone) -----------------------

    val momentRepository: com.safesphere.app.feature.moments.domain.MomentRepository by lazy {
        com.safesphere.app.feature.moments.data.RemoteMomentRepository(
            retrofit.create(com.safesphere.app.feature.moments.data.remote.MomentApi::class.java), apiCallHandler,
            backendBaseUrl())
    }

    /** Phase N5: Same, Say more, the follow-up, I'm in and the author's people. */
    val responseRepository: com.safesphere.app.feature.moments.domain.ResponseRepository by lazy {
        com.safesphere.app.feature.moments.data.RemoteResponseRepository(
            retrofit.create(com.safesphere.app.feature.moments.data.remote.ResponseApi::class.java), apiCallHandler)
    }

    /** Phase N5 (A2): Replies, plans, private replies and the Private replies setting. */
    val repliesRepository: com.safesphere.app.feature.replies.domain.RepliesRepository by lazy {
        com.safesphere.app.feature.replies.data.RemoteRepliesRepository(
            retrofit.create(com.safesphere.app.feature.replies.data.RepliesApi::class.java), apiCallHandler)
    }

    /** Phase N5 (D19): the dot on the Replies tab, one holder in application scope (no number, no push). */
    val repliesMarker: com.safesphere.app.feature.replies.ui.RepliesMarker by lazy {
        com.safesphere.app.feature.replies.ui.RepliesMarker(repliesRepository)
    }

    /** A2: the camera app's target for a moment's photo, its preparation and deletion. */
    val momentPhotoFiles: com.safesphere.app.feature.moments.data.MomentPhotoFiles by lazy {
        com.safesphere.app.feature.moments.data.MomentPhotoFiles(appContext)
    }

    /** A2 (D5): the last audience chosen on this phone, per account. */
    val audienceMemory: com.safesphere.app.feature.moments.data.DataStoreAudienceMemory by lazy {
        com.safesphere.app.feature.moments.data.DataStoreAudienceMemory(appContext) { sessionStorage.session.first()?.user?.id }
    }

    /**
     * A2: what Create needs from the membership (social never imports membership: the mapping lives here). The end of
     * the new-member period is the approval time + 7 days (M2); the server computes its own and decides.
     */
    suspend fun sharingStatus(): ApiResult<com.safesphere.app.feature.moments.domain.SharingStatus> =
        when (val status = membershipRepository.status()) {
            is ApiResult.Success -> ApiResult.Success(com.safesphere.app.feature.moments.domain.SharingStatus(
                member = status.data.state == com.safesphere.app.feature.membership.domain.MembershipState.MEMBER,
                newMemberUntil = com.safesphere.app.feature.moments.domain.DraftRules.newMemberUntil(
                    status.data.membership?.verifiedAt, java.time.Instant.now())))
            is ApiResult.Failure -> status
        }

    /** A3: admins schedule the daily question (the server checks the role on every call). */
    val questionAdminRepository: com.safesphere.app.feature.moments.domain.QuestionAdminRepository by lazy {
        com.safesphere.app.feature.moments.data.RemoteQuestionAdminRepository(
            retrofit.create(com.safesphere.app.feature.moments.data.QuestionAdminApi::class.java), apiCallHandler)
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

    /** Photos are made smaller and re-encoded without metadata on the phone before upload (the server does it again). */
    val mediaSource: MediaSource by lazy { AndroidMediaSource(PhotoPreparer(appContext.contentResolver), Dispatchers.IO) }

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

    // ---- Phase N3: the MIET community membership (applicant side) -----------------------------------

    val membershipRepository: com.safesphere.app.feature.membership.domain.MembershipRepository by lazy {
        com.safesphere.app.feature.membership.data.RemoteMembershipRepository(
            retrofit.create(com.safesphere.app.feature.membership.data.remote.MembershipApi::class.java), apiCallHandler)
    }

    /** The ID photo's file handling (camera-app target in the cache, re-encoding, deletion). */
    val idPhotoFiles: com.safesphere.app.feature.membership.data.IdPhotoFiles by lazy {
        com.safesphere.app.feature.membership.data.IdPhotoFiles(appContext)
    }
    val idPhotoSource: com.safesphere.app.feature.membership.data.IdPhotoSource get() = idPhotoFiles

    /** N3 (A2): verifiers and admins; the server checks the role on every call. */
    val staffRepository: com.safesphere.app.feature.membership.domain.StaffRepository by lazy {
        com.safesphere.app.feature.membership.data.RemoteStaffRepository(
            retrofit.create(com.safesphere.app.feature.membership.data.remote.StaffApi::class.java), apiCallHandler)
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
                // N4 (A2): the deleted account's last chosen audience
                { userId -> audienceMemory.forget(userId) }
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

    /** The same process-long scope, for small work a screen starts but must not cut off when it closes (A2: deleting an unused upload). */
    val backgroundScope: CoroutineScope get() = applicationScope

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

    /**
     * The one "On my way" holder (N6, D22: the screen, the banners, the reminder alarm and its notification buttons).
     * A reminder, not tracking: no location provider, no battery reader, no tracking service.
     */
    val safeWalk: SafeWalkSession by lazy {
        SafeWalkSession(
            scope = applicationScope,
            repository = RemoteJourneyRepository(journeyApi, apiCallHandler),
            store = activeJourneyStore,
            connectivityMonitor = AndroidConnectivityMonitor(appContext),
            alerts = AndroidSafeWalkAlerts(appContext),
            reminders = com.safesphere.app.feature.safewalk.session.AndroidReminderScheduler(appContext),
            session = sessionStorage.session,
            currentUserId = { sessionStorage.session.first()?.user?.id }
        )
    }

    /** What the ONE tracking foreground service shows: only an active emergency since N6 (On my way tracks nothing). */
    val trackingNoticeSource: TrackingNoticeSource get() = emergencyLocationSharing

    /** True while an emergency wants the location service type (used by SafetyTrackingService). */
    fun locationTrackingNeeded(): Boolean = emergencyLocationSharing.currentNotice() != null

    // ---- Phase 4D: Safety Circle communication ---------------------------------------------------

    private val circleAlertApi: CircleAlertApi by lazy { retrofit.create(CircleAlertApi::class.java) }

    val circleAlertRepository: CircleAlertRepository by lazy { RemoteCircleAlertRepository(circleAlertApi, apiCallHandler) }

    /** N6 (D20): the server's automatic Safety Circle texts: the choice, the preview, the test, the state in an SOS. */
    val safetyAlertRepository: SafetyAlertRepository by lazy {
        RemoteSafetyAlertRepository(retrofit.create(SafetyAlertApi::class.java), apiCallHandler)
    }

    /** The one holder for what the phone does for the Safety Circle (texting by hand, reports, Call 112). */
    val circleAlerts: CircleAlertController get() = circleAlertCoordinator

    /** Phase 4D store (Circle copy for SMS by hand, reports waiting for the server); cleared by a sign-out or an account switch. */
    private val circleAlertStore: CircleAlertStore by lazy { DataStoreCircleAlertStore(appContext) }

    private val circleAlertCoordinator: CircleAlertCoordinator by lazy {
        CircleAlertCoordinator(
            scope = applicationScope,
            store = circleAlertStore,
            repository = circleAlertRepository,
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

    // ---- Phase N4: places, cities and video are retired; what they kept on this phone is deleted once (no notice:
    //      private places only served the old composer) ---------------------------------------------------------

    // ---- Phase N6: shake SOS and the SOS options are retired; their stored switch is deleted once (no notice) ----------

    val safetyTrimRetirement: com.safesphere.app.core.retired.SafetyTrimRetirement by lazy {
        com.safesphere.app.core.retired.SafetyTrimRetirement(appContext.filesDir)
    }

    val oldSocialRetirement: OldSocialRetirement by lazy {
        OldSocialRetirement(appContext.filesDir, appContext.cacheDir, AndroidKeystoreKeyWrapper::forgetRetiredKey)
    }

    /** Phase 13F (F11): set only by the non-exported SosEntryActivity (shortcut, tile); memory only. */
    val sosLaunchRequest: SosLaunchRequest = SosLaunchRequest()

    // ---- N7 (D26 to D34): push notifications (pointers; the phone renders from IRL's own API) ----------------------

    val pushOpenRequest = com.safesphere.app.core.push.PushOpenRequest()
    val notificationAsk by lazy {
        com.safesphere.app.core.push.NotificationAsk(applicationScope, pushPreferences,
            configured = { pushTokens.configured },
            runtimePermission = { com.safesphere.app.core.notifications.NotificationPermission.isRuntimePermission() },
            granted = { com.safesphere.app.core.notifications.NotificationPermission.isGranted(appContext) })
    }
    val pushPreferences by lazy { com.safesphere.app.core.push.PushPreferences(appContext) }
    val pushTray: com.safesphere.app.core.push.PushTray by lazy { com.safesphere.app.core.push.AndroidPushTray(appContext) }
    private val pushTokens: com.safesphere.app.core.push.PushTokenSource by lazy {
        com.safesphere.app.core.push.FirebaseTokenSource(appContext)
    }
    val pushConfigured: Boolean get() = pushTokens.configured
    val pushRepository by lazy {
        com.safesphere.app.core.push.PushRepository(retrofit.create(com.safesphere.app.core.push.PushApi::class.java),
            apiCallHandler)
    }
    val pushRegistration by lazy {
        com.safesphere.app.core.push.PushRegistration(pushTokens, pushRepository::register, pushTray,
            allowed = { androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled() },
            appVersion = BuildConfig.VERSION_NAME)
    }

    /** N7 (D36): the Safety Circle in the app, from the member's side (requests, circles you're in, an SOS). */
    val circleInAppRepository by lazy {
        com.safesphere.app.feature.safetycircle.data.CircleInAppRepository(
            retrofit.create(com.safesphere.app.feature.safetycircle.data.CircleInAppApi::class.java), apiCallHandler)
    }

    /** The renderers by kind; each feature registers its own (core/push imports no feature). */
    private val pushRenderers: Map<com.safesphere.app.core.push.PushKind, com.safesphere.app.core.push.PushRenderer> by lazy {
        com.safesphere.app.feature.replies.push.RepliesPushRenderers(appContext, momentRepository, responseRepository,
            repliesRepository, showContent = { pushPreferences.showContent.first() }).all() +
            com.safesphere.app.core.push.AccountPushRenderers(appContext).all() +
            com.safesphere.app.feature.safetycircle.push.CirclePushRenderers(appContext, circleInAppRepository).all() +
            mapOf(
                com.safesphere.app.core.push.PushKind.OMW_REMINDER to
                    com.safesphere.app.feature.safewalk.push.OnMyWayPushRenderer(safeWalk).renderer,
                com.safesphere.app.core.push.PushKind.PEOPLE_REQUEST to
                    com.safesphere.app.feature.social.push.PeoplePushRenderer(appContext, socialRepository).renderer,
                com.safesphere.app.core.push.PushKind.MEMBERSHIP_DECIDED to
                    com.safesphere.app.feature.membership.push.MembershipPushRenderer(appContext, membershipRepository).renderer
            )
    }

    val pushRouter by lazy {
        com.safesphere.app.core.push.PushRouter(
            renderers = pushRenderers,
            tray = pushTray,
            appVisible = { (appContext as? SafeSphereApp)?.appVisibility?.visible?.value == true },
            quietAtNight = { pushPreferences.quietAtNight.first() }
        )
    }

    /**
     * D31: a channel the person blocked in Android settings switches the matching server switch off (never on), so IRL
     * stops sending what cannot be shown. Read when IRL comes to the front.
     */
    private suspend fun syncBlockedChannels() {
        if (!pushTokens.configured || sessionStorage.session.first() == null) return
        val manager = appContext.getSystemService(android.app.NotificationManager::class.java) ?: return
        fun blocked(id: String) = manager.getNotificationChannel(id)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        val current = (pushRepository.switches() as? ApiResult.Success)?.data ?: return
        val wanted = current.copy(
            replies = current.replies && !blocked(com.safesphere.app.core.notifications.NotificationChannels.REPLIES),
            plans = current.plans && !blocked(com.safesphere.app.core.notifications.NotificationChannels.PLANS),
            planMessages = current.planMessages && !blocked(com.safesphere.app.core.notifications.NotificationChannels.PLANS),
            people = current.people && !blocked(com.safesphere.app.core.notifications.NotificationChannels.PEOPLE),
            staff = current.staff && !blocked(com.safesphere.app.core.notifications.NotificationChannels.STAFF)
        )
        if (wanted != current) pushRepository.change(wanted)
    }

    init {
        applicationScope.launch(Dispatchers.IO) { riseRetirement.retire() }
        applicationScope.launch(Dispatchers.IO) { vaultRetirement.retire() }
        applicationScope.launch(Dispatchers.IO) { oldSocialRetirement.retire() }
        applicationScope.launch(Dispatchers.IO) { safetyTrimRetirement.retire() }
        currentSession // Phase 11 C1: start following the session now, so the dialog never reads a stale "null"
        circleAlerts // start watching walks and emergencies right away (e.g. after a restart into a walk)
        sosRecorder // record an SOS that started or ended offline as soon as the server can be reached
        // On a real sign-out (the person's own, the other phones signed out from elsewhere, a deleted or disabled account) forget the active
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
                    }
                }
        }
        // N7 (D28): the push token follows the sign-in ("sign in to sync" keeps what it has: nothing is deleted then).
        pushRegistration.follow(applicationScope, sessionStorage.session
            .filter { it == null || !it.needsSignIn }
            .map { s -> s?.let { it.user.id to com.safesphere.app.core.push.PushRules.sessionIdOf(it.token) } })
        // N7 (D34): every pointer refreshes the Replies dot; IRL coming to the front re-checks the token and the channels.
        applicationScope.launch {
            pushRouter.events.collect { if (it.kind.social) repliesMarker.check(force = true) }
        }
        (appContext as? SafeSphereApp)?.appVisibility?.let { visibility ->
            applicationScope.launch {
                visibility.visible.filter { it }.collect {
                    runCatching { pushRegistration.sync() }
                    runCatching { syncBlockedChannels() }
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
