package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.SshHostKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class SshTrustViewModel @Inject constructor(val trust: SshHostKeyStore) : ViewModel() {
    fun approve(challenge: SshHostKeyStore.Challenge) { viewModelScope.launch(Dispatchers.IO) { trust.approve(challenge) } }
    fun forget(key: com.bontecou.syncmd.services.git.SshHostKey) { viewModelScope.launch(Dispatchers.IO) { trust.forget(key) } }
    fun reset() { viewModelScope.launch(Dispatchers.IO) { trust.reset() } }
}
