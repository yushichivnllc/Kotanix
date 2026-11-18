// Compile-only stub of org.bouncycastle.jsse.BCSSLSocket.
// Upstream BCSSLSocket extends SSLSocket and adds BCSSLParameters get/setParameters();
// the Kotlin property here emits exactly those JVM signatures.
package org.bouncycastle.jsse

abstract class BCSSLSocket : javax.net.ssl.SSLSocket() {
  override fun getApplicationProtocol(): String = ""
  abstract var parameters: BCSSLParameters
}
