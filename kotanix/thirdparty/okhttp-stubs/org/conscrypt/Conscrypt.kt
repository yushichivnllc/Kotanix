// Compile-only stub of org.conscrypt.Conscrypt (provided by conscrypt-openjdk-uber at runtime).
package org.conscrypt

import java.security.Provider
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object Conscrypt {
  fun newProvider(): Provider = error("stub")
  fun setHostnameVerifier(trustManager: X509TrustManager, verifier: ConscryptHostnameVerifier) = Unit
  fun isConscrypt(socket: SSLSocket): Boolean = false
  fun setUseSessionTickets(socket: SSLSocket, useSessionTickets: Boolean) = Unit
  fun setApplicationProtocols(socket: SSLSocket, protocols: Array<String>) = Unit
  fun getApplicationProtocol(socket: SSLSocket): String? = null
  fun isAvailable(): Boolean = false
  fun version(): Version? = null

  class Version {
    fun major(): Int = 0
    fun minor(): Int = 0
    fun patch(): Int = 0
  }
}
