package com.safesphere.app.feature.safety.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 13F (F12): the Ready check's facts. The Safety Circle row comes from the phone's own copy (the one SMS uses
 * without internet), refreshed from the server when possible; offline it shows what the phone has. Permissions are
 * the answers the screen reads when it becomes visible. Nothing is stored.
 */
class ReadyCheckViewModel(
    private val circleRepository: SafetyCircleRepository,
    private val circleAlerts: CircleAlertController
) : ViewModel() {

    private data class Permissions(val location: Boolean, val notifications: Boolean?)

    private val permissions = MutableStateFlow<Permissions?>(null)

    val rows: StateFlow<List<ReadyRow>> = combine(circleAlerts.state, permissions) { circle, granted ->
        if (granted == null) emptyList() else ReadyCheck.rows(ReadyInputs(
            circleKnown = circle.loaded,
            circlePeople = circle.recipients.size,
            autoSms = circle.autoSms,
            sms = circle.availability,
            locationAllowed = granted.location,
            notificationsAllowed = granted.notifications
        ))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Called when the screen is visible: new permission answers, SMS availability, and a fresh Circle copy. */
    fun refresh(locationAllowed: Boolean, notificationsAllowed: Boolean?) {
        permissions.value = Permissions(locationAllowed, notificationsAllowed)
        circleAlerts.refreshAvailability()
        // A successful load also refreshes the offline copy the row reads (Phase 4D); a failure keeps the copy.
        viewModelScope.launch { circleRepository.getContacts() }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReadyCheckViewModel(container.safetyCircleRepository, container.circleAlerts) }
        }
    }
}
