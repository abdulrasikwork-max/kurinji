package com.safesphere.app

import android.content.Context
import androidx.fragment.app.Fragment
import com.safesphere.app.core.device.AndroidBatteryMonitor
import com.safesphere.app.core.device.AndroidConnectivityMonitor
import com.safesphere.app.core.location.FusedLocationProvider
import com.safesphere.app.core.location.DestinationResolver
import com.safesphere.app.core.location.GeocoderDestinationResolver
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.account.data.DataStorePendingDeletionStore
import com.safesphere.app.feature.account.data.RemoteAccountRepository
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.account.domain.PendingDeletionStore
import com.safesphere.app.feature.account.session.AccountDataWiper
import kotlinx.coroutines.withContext
import com.safesphere.app.core.tracking.CompositeTrackingNoticeSource
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.safewalk.data.DataStoreActiveJourneyStore
import com.safesphere.app.feature.safewalk.data.RemoteJourneyRepository
import com.safesphere.app.feature.safewalk.data.remote.JourneyApi
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.session.AndroidSafeWalkAlerts
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import kotlinx.coroutines.flow.first
import com.safesphere.app.feature.auth.data.DataStoreSessionStorage
import com.safesphere.app.feature.auth.data.RemoteAuthRepository
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthInterceptor
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.SessionStorage
import com.safesphere.app.feature.emergency.data.DataStoreActiveEmergencyStore
import com.safesphere.app.feature.emergency.data.RemoteEmergencyLocationRepository
import com.safesphere.app.feature.emergency.data.RemoteEmergencyRepository
import com.safesphere.app.feature.emergency.data.remote.EmergencyApi
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
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
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertCoordinator
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactApi
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import android.os.SystemClock
import com.safesphere.app.core.crypto.AndroidKeystoreKeyWrapper
import com.safesphere.app.feature.evidence.capture.AndroidStorageSpace
import com.safesphere.app.feature.evidence.capture.EvidenceRecorder
import com.safesphere.app.feature.evidence.capture.MediaRecorderSegmentWriter
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.RemoteEvidenceRepository
import com.safesphere.app.feature.evidence.data.remote.EvidenceApi
import com.safesphere.app.feature.evidence.session.AndroidEvidenceUploadScheduler
import com.safesphere.app.feature.evidence.data.RecoveryKeyStore
import com.safesphere.app.feature.evidence.domain.PassphraseGenerator
import com.safesphere.app.feature.evidence.domain.PassphrasePolicy
import com.safesphere.app.feature.evidence.session.EvidenceRecovery
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.VaultLock
import com.safesphere.app.feature.community.data.DataStoreCommunityOutbox
import com.safesphere.app.feature.community.data.RemoteCommunityRepository
import com.safesphere.app.feature.community.data.remote.CommunityApi
import com.safesphere.app.feature.community.domain.CommunityOutbox
import com.safesphere.app.feature.community.domain.CommunityRepository
import com.safesphere.app.feature.moderation.data.RemoteModerationRepository
import com.safesphere.app.feature.moderation.data.remote.ModerationApi
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import com.safesphere.app.feature.intelligence.data.RemoteIntelligenceRepository
import com.safesphere.app.feature.intelligence.data.remote.IntelligenceApi
import com.safesphere.app.feature.intelligence.domain.IntelligenceRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    // No logging interceptor on purpose: request bodies contain passwords, so they must never be logged.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(sessionStorage))
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

    /**
     * Phase 10A: the ONE place that ends the session after a 401 (only if the rejected token is still the saved one)
     * and says why (password changed, account deleted).
     */
    val sessionEnder: SessionEnder by lazy { SessionEnder(sessionStorage) }

    /** Turns backend answers into results and clears the session on a 401. Used by the Phase 3 repositories. */
    private val apiCallHandler by lazy { ApiCallHandler(sessionEnder) }

    val authRepository: AuthRepository by lazy { RemoteAuthRepository(authApi, sessionStorage, sessionEnder) }

    // ---- Phase 10A: account security and deletion ------------------------------------------------

    private val accountApi: AccountApi by lazy { retrofit.create(AccountApi::class.java) }

    val accountRepository: AccountRepository by lazy {
        RemoteAccountRepository(accountApi, apiCallHandler, sessionStorage, sessionEnder)
    }

    /** A pending deletion this phone knows about (account id + date only); survives a sign-out. */
    val pendingDeletion: PendingDeletionStore by lazy { DataStorePendingDeletionStore(appContext) }

    /** Removes a DELETED account's evidence and recovery key from this phone (a sign-out clears the rest). */
    val accountDataWiper: AccountDataWiper by lazy {
        AccountDataWiper(
            parts = listOf(
                { userId -> evidenceVault.forgetAccount(userId) },
                { userId -> withContext(Dispatchers.IO) { recoveryKeyStore.remove(userId) } }
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

    /**
     * What the ONE tracking foreground service shows: an audio evidence recording first (the person must always see
     * it), then an active emergency, then a Safe Walk.
     */
    val trackingNoticeSource: TrackingNoticeSource by lazy {
        CompositeTrackingNoticeSource(listOf(evidenceRecorder, emergencyLocationSharing, safeWalk))
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
    val circleAlerts: CircleAlertController by lazy {
        CircleAlertCoordinator(
            scope = applicationScope,
            store = DataStoreCircleAlertStore(appContext),
            repository = circleAlertRepository,
            smsSender = smsSender,
            walkState = safeWalk.state,
            emergencyLocation = emergencyLocationSharing.state,
            session = sessionStorage.session
        )
    }

    // ---- Phase 5: Emergency Evidence Vault -------------------------------------------------------

    private val evidenceApi: EvidenceApi by lazy { retrofit.create(EvidenceApi::class.java) }

    val evidenceFiles: EvidenceFiles by lazy { EvidenceFiles(appContext.filesDir, appContext.cacheDir) }

    /** The one holder of the phone's encrypted evidence (sealing, recovery, upload, deletion, viewing). */
    val evidenceVault: EvidenceVault by lazy {
        EvidenceVault(
            scope = applicationScope,
            io = Dispatchers.IO,
            files = evidenceFiles,
            keyWrapper = AndroidKeystoreKeyWrapper(),
            repository = evidenceRepository,
            connectivityMonitor = AndroidConnectivityMonitor(appContext),
            session = sessionStorage.session,
            pinnedVaultKeyId = BuildConfig.EVIDENCE_VAULT_KEY_ID.takeIf { it.isNotBlank() },
            uploadScheduler = AndroidEvidenceUploadScheduler(appContext),
            recoveryKeys = recoveryKeyStore
        )
    }

    private val evidenceRepository by lazy { RemoteEvidenceRepository(evidenceApi, apiCallHandler) }

    /** Each account's recovery key on this phone (restore on a new phone, docs/PHASE5.md §11). */
    private val recoveryKeyStore: RecoveryKeyStore by lazy { RecoveryKeyStore(evidenceFiles, AndroidKeystoreKeyWrapper()) }

    /** Recovery passphrase set-up / change / restore. The passphrase never leaves the phone. */
    val evidenceRecovery: EvidenceRecovery by lazy {
        EvidenceRecovery(
            io = Dispatchers.IO,
            vault = evidenceVault,
            repository = evidenceRepository,
            store = recoveryKeyStore,
            keyWrapper = AndroidKeystoreKeyWrapper()
        )
    }

    /** The signed-in person (name and email are kept out of recovery passphrases). */
    val currentUser = sessionStorage.session.map { it?.user }

    /** Loaded from assets on first use (call off the main thread). */
    val passphrasePolicy: PassphrasePolicy by lazy {
        val common = appContext.assets.open("common_passwords_10k.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }
        PassphrasePolicy(common)
    }

    val passphraseGenerator: PassphraseGenerator by lazy {
        PassphraseGenerator(appContext.assets.open("eff_large_wordlist.txt").bufferedReader().useLines(PassphraseGenerator::parseEffList))
    }

    /** The vault screen's viewing lock (the phone's screen lock); any account change locks it. */
    val vaultLock: VaultLock by lazy { VaultLock(applicationScope, evidenceVault.currentUserId) }

    /** MediaRecorder must only be used from one thread. */
    private val recorderDispatcher by lazy { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }

    /** The one holder of audio evidence recording; also a notice source of the tracking service. */
    val evidenceRecorder: EvidenceRecorder by lazy {
        EvidenceRecorder(
            scope = applicationScope,
            recorderDispatcher = recorderDispatcher,
            vault = evidenceVault,
            writer = MediaRecorderSegmentWriter(appContext),
            storage = AndroidStorageSpace(appContext.filesDir),
            activeEmergencyId = emergencyLocationSharing.state.map { if (it.signedOut) null else it.emergencyId },
            currentUserId = evidenceVault.currentUserId,
            elapsedMillis = SystemClock::elapsedRealtime
        )
    }

    /** Set by SafetyTrackingService: true while it runs with the microphone service type. */
    val microphoneInForeground = MutableStateFlow(false)

    // ---- Phase 6: Community Safety Intelligence (place signals) ----------------------------------

    private val communityApi: CommunityApi by lazy { retrofit.create(CommunityApi::class.java) }

    val communityRepository: CommunityRepository by lazy { RemoteCommunityRepository(communityApi, apiCallHandler) }

    /** Reports made offline (cell + category only), cleared on logout. */
    val communityOutbox: CommunityOutbox by lazy { DataStoreCommunityOutbox(appContext) }

    /** The same location provider, for screens that need one fix while they are open (Community). */
    val screenLocationProvider: LocationProvider get() = locationProvider

    // ---- Phase 7: moderation (the server checks the role on every call) --------------------------

    private val moderationApi: ModerationApi by lazy { retrofit.create(ModerationApi::class.java) }

    val moderationRepository: ModerationRepository by lazy { RemoteModerationRepository(moderationApi, apiCallHandler) }

    // ---- Phase 8: Safety Intelligence (the area picture comes with the community area answer) -------------

    private val intelligenceApi: IntelligenceApi by lazy { retrofit.create(IntelligenceApi::class.java) }

    /** Region packs for "Check the way": only 5-character region cells are ever sent. */
    val intelligenceRepository: IntelligenceRepository by lazy { RemoteIntelligenceRepository(intelligenceApi, apiCallHandler) }

    init {
        evidenceVault // seal captures interrupted by a crash and resume uploads right away
        evidenceRecorder // watches the active emergency (stops recording when it ends)
        circleAlerts // start watching walks and emergencies right away (e.g. after a restart into a walk)
        // On logout (or an expired token) forget the active emergency / walk records kept on this phone.
        applicationScope.launch {
            sessionStorage.session
                .map { it == null }
                .distinctUntilChanged()
                .collect { signedOut ->
                    if (signedOut) {
                        activeEmergencyStore.clear()
                        activeJourneyStore.clear()
                        communityOutbox.clear() // unsent reports belong to the account that made them
                    }
                }
        }
        // Phase 10A.3: the server said the account is deleted: remove what a sign-out keeps (evidence, recovery key).
        applicationScope.launch {
            sessionEnder.ended.collect { end ->
                if (end.reason == SessionEndReason.ACCOUNT_DELETED) accountDataWiper.wipe(end.userId)
            }
        }
        // Remember a pending deletion apart from the session, so the Login screen can still offer the local clean-up.
        applicationScope.launch {
            sessionStorage.session.map { it?.user }.distinctUntilChanged().collect { user ->
                if (user == null) return@collect
                val due = user.deletionDueAt
                if (due != null) pendingDeletion.set(PendingDeletion(user.id, due)) else pendingDeletion.clear(user.id)
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
