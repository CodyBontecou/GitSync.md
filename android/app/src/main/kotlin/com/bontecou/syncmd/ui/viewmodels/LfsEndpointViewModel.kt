package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LfsEndpointViewModel @Inject constructor(val endpoints: LfsEndpointStore) : ViewModel() {
    fun approve(challenge: LfsEndpointStore.Challenge, authentication: LfsEndpointStore.Authentication, username: String, secret: String) {
        viewModelScope.launch(Dispatchers.IO) { endpoints.approve(challenge, authentication, username, secret) }
    }
    fun revoke(approval: LfsEndpointStore.Approval, operation: SshLfsOperation) {
        viewModelScope.launch(Dispatchers.IO) { endpoints.revoke(approval, operation) }
    }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) { endpoints.refresh() } }
    fun reset() { viewModelScope.launch(Dispatchers.IO) { endpoints.reset() } }
}
