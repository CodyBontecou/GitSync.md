package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SshKeyGeneratorTest {
    @Test fun generatedKeyLoadsAndMatchesItsPublicKey() {
        val key = SshKeyGenerator.ed25519("cody@pixel")
        assertThat(key.privateKey).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        assertThat(key.publicKey).startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5")
        assertThat(key.publicKey).endsWith(" cody@pixel")
        // The same validation every saved SSH connection passes, including the public-key match.
        SshKeys.validate(key.credentials)
    }

    @Test fun fingerprintIsOpenSshSha256() {
        val key = SshKeyGenerator.ed25519("x")
        assertThat(key.fingerprint).matches("SHA256:[A-Za-z0-9+/]{43}")
        assertThat(SshKeyGenerator.shortFingerprint(key.fingerprint)).matches("SHA256:.{4}….{4}")
        assertThat(SshKeyGenerator.shortPublicKey(key.publicKey)).contains("…")
    }

    @Test fun keysAreUnique() {
        assertThat(SshKeyGenerator.ed25519("a").publicKey).isNotEqualTo(SshKeyGenerator.ed25519("a").publicKey)
    }

    @Test fun transferUpdateMapsPhases() {
        assertThat(TransferProgressUpdate("Receiving objects", 45, 100).fraction).isWithin(0.001f).of(0.45f)
        assertThat(TransferProgressUpdate("Resolving deltas", 1, 0).fraction).isNull()
        assertThat(TransferProgressUpdate("Writing objects", 1, 2).phase).isEqualTo(CloneTransferPhase.SENDING)
    }
}
