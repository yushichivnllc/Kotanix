// Compile-only stub of org.bouncycastle.jsse.provider.BouncyCastleJsseProvider.
package org.bouncycastle.jsse.provider

import java.security.Provider

class BouncyCastleJsseProvider : Provider(PROVIDER_NAME, 1.0, "BouncyCastle JSSE (compile-only stub)") {
  companion object {
    const val PROVIDER_NAME: String = "BC"
  }
}
