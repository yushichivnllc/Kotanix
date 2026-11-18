// Compile-only stub of org.openjsse.javax.net.ssl.SSLSocket.
package org.openjsse.javax.net.ssl

import javax.net.ssl.SSLSocket as JdkSSLSocket

abstract class SSLSocket : JdkSSLSocket() {
  abstract var sslParameters: SSLParameters
  override abstract fun getApplicationProtocol(): String?
}
