package com.safesphere.app.feature.membership.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.auth.domain.PhonePolicy
import com.safesphere.app.feature.auth.domain.RealNamePolicy
import com.safesphere.app.feature.membership.data.RemoteStaffRepository
import com.safesphere.app.feature.membership.domain.BanReason
import com.safesphere.app.feature.membership.domain.BanRow
import com.safesphere.app.feature.membership.domain.LogRow
import com.safesphere.app.feature.membership.domain.RequestDetail
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.StaffRepository
import com.safesphere.app.feature.membership.domain.VerifierRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What an admin action did, for one message. */
enum class StaffDone { VERIFIER_ADDED, VERIFIER_REMOVED, BANNED, LIFTED, ENDED, RELINKED, NAME_CORRECTED }

/** The input that was refused on the phone (shown on that field). */
enum class StaffField { PHONE, ROLL, NAME, CODE, TARGET }

data class StaffAdminUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val verifiers: List<VerifierRow>? = null,
    val bans: List<BanRow>? = null,
    val queue: List<RequestDetail>? = null,
    val log: List<LogRow> = emptyList(),
    val logPage: Int = -1,
    val logEnded: Boolean = false,
    val problems: Set<StaffField> = emptySet(),
    val done: StaffDone? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase N3 (A2): the admin lists and actions (verifiers, bans, the ID-photo queue, ending a membership, moving an
 * account to a new number, correcting a checked name, the log). Inputs are checked on the phone first; the server
 * decides and logs every action. Nothing is stored on the phone.
 */
class StaffAdminViewModel(private val repository: StaffRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(StaffAdminUiState())
    val uiState: StateFlow<StaffAdminUiState> = _uiState.asStateFlow()

    fun loadVerifiers() = load { repository.verifiers().also { r -> if (r is ApiResult.Success) set { it.copy(verifiers = r.data) } } }

    fun loadBans() = load { repository.bans().also { r -> if (r is ApiResult.Success) set { it.copy(bans = r.data) } } }

    fun loadQueue() = load { repository.photoQueue().also { r -> if (r is ApiResult.Success) set { it.copy(queue = r.data) } } }

    /** The log, 50 lines a page; [more] adds the next page under the ones shown. */
    fun loadLog(more: Boolean = false) {
        val state = _uiState.value
        if (more && state.logEnded) return
        val page = if (more) state.logPage + 1 else 0
        load {
            repository.log(page).also { r ->
                if (r is ApiResult.Success) set {
                    it.copy(log = if (page == 0) r.data else it.log + r.data, logPage = page,
                        logEnded = r.data.size < LOG_PAGE)
                }
            }
        }
    }

    fun setVerifier(phone: String, verifier: Boolean) {
        if (!check(phoneOk = PhonePolicy.isValid(phone))) return
        act(if (verifier) StaffDone.VERIFIER_ADDED else StaffDone.VERIFIER_REMOVED, reload = ::loadVerifiers) {
            repository.setVerifier(phone, verifier)
        }
    }

    fun ban(phone: String, roll: String, reason: BanReason, note: String) {
        val hasPhone = phone.isNotBlank()
        val hasRoll = roll.isNotBlank()
        val problems = buildSet {
            if (!hasPhone && !hasRoll) add(StaffField.TARGET)
            if (hasPhone && !PhonePolicy.isValid(phone)) add(StaffField.PHONE)
            if (hasRoll && !RollNumberPolicy.accepts(roll)) add(StaffField.ROLL)
        }
        if (problems.isNotEmpty()) {
            set { it.copy(problems = problems) }
            return
        }
        act(StaffDone.BANNED, reload = ::loadBans) {
            repository.ban(phone.takeIf { hasPhone }, roll.takeIf { hasRoll }, reason, note)
        }
    }

    fun lift(id: Long) = act(StaffDone.LIFTED, reload = ::loadBans) { repository.lift(id) }

    fun endMembership(phone: String) {
        if (!check(phoneOk = PhonePolicy.isValid(phone))) return
        act(StaffDone.ENDED) { repository.endMembership(phone) }
    }

    fun relink(code: String, roll: String) {
        val problems = buildSet {
            if (RemoteStaffRepository.cleanCode(code) == null) add(StaffField.CODE)
            if (!RollNumberPolicy.accepts(roll)) add(StaffField.ROLL)
        }
        if (problems.isNotEmpty()) {
            set { it.copy(problems = problems) }
            return
        }
        act(StaffDone.RELINKED) { repository.relink(code, roll) }
    }

    fun correctName(phone: String, name: String) {
        val cleaned = RealNamePolicy.clean(name)
        val problems = buildSet {
            if (!PhonePolicy.isValid(phone)) add(StaffField.PHONE)
            if (RealNamePolicy.check(cleaned) != RealNamePolicy.Result.OK) add(StaffField.NAME)
        }
        if (problems.isNotEmpty()) {
            set { it.copy(problems = problems) }
            return
        }
        act(StaffDone.NAME_CORRECTED) { repository.correctName(phone, cleaned) }
    }

    fun onDoneShown() = set { it.copy(done = null) }
    fun onFailureShown() = set { it.copy(failure = null) }
    fun onSessionExpiredHandled() = set { it.copy(sessionExpired = false) }
    fun onFieldEdited(field: StaffField) = set { it.copy(problems = it.problems - field - StaffField.TARGET) }

    private fun check(phoneOk: Boolean): Boolean {
        if (!phoneOk) set { it.copy(problems = setOf(StaffField.PHONE)) }
        return phoneOk
    }

    private fun <T> load(call: suspend () -> ApiResult<T>) {
        if (_uiState.value.loading) return
        set { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = call()
            set { it.copy(loading = false) }
            if (result is ApiResult.Failure) fail(result)
        }
    }

    private fun act(done: StaffDone, reload: (() -> Unit)? = null, call: suspend () -> ApiResult<Unit>) {
        if (_uiState.value.busy) return
        set { it.copy(busy = true, problems = emptySet(), failure = null) }
        viewModelScope.launch {
            val result = call()
            set { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    set { it.copy(done = done) }
                    reload?.invoke()
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) =
        set { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) }

    private inline fun set(change: (StaffAdminUiState) -> StaffAdminUiState) = _uiState.update(change)

    companion object {
        const val LOG_PAGE = 50

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StaffAdminViewModel(container.staffRepository) }
        }
    }
}
