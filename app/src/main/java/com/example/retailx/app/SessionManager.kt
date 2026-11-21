package com.example.retailx.app

import com.example.retailx.core.model.AuthenticatedSession
import com.example.retailx.core.model.PermissionCode
import com.example.retailx.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager {
    private val _session = MutableStateFlow<AuthenticatedSession?>(null)
    val session: StateFlow<AuthenticatedSession?> = _session.asStateFlow()

    fun setSession(user: User, viaLan: Boolean = false) {
        val now = System.currentTimeMillis()
        _session.value = AuthenticatedSession(
            user = user,
            loginAt = now,
            lastActiveAt = now,
            viaLan = viaLan
        )
    }

    fun touchActivity() {
        val cur = _session.value ?: return
        _session.value = cur.copy(lastActiveAt = System.currentTimeMillis())
    }

    fun checkTimeout(timeoutMinutes: Int): Boolean {
        val cur = _session.value ?: return false
        val elapsed = System.currentTimeMillis() - cur.lastActiveAt
        if (elapsed > timeoutMinutes * 60_000L) {
            _session.value = null
            return true
        }
        return false
    }

    fun hasPermission(permission: PermissionCode): Boolean =
        _session.value?.hasPermission(permission) == true

    fun logout() {
        _session.value = null
    }
}
