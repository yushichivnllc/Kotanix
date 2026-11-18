// Compile-only stub of org.conscrypt.ConscryptHostnameVerifier (matches upstream
// common/src/main/java/org/conscrypt/ConscryptHostnameVerifier.java).
package org.conscrypt

import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

interface ConscryptHostnameVerifier {
  fun verify(certs: Array<out X509Certificate>?, hostname: String?, session: SSLSession?): Boolean
}
