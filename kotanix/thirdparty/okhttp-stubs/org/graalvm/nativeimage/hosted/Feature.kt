// Compile-only stub of org.graalvm.nativeimage.hosted.Feature.
package org.graalvm.nativeimage.hosted

interface Feature {
  interface BeforeAnalysisAccess
  fun beforeAnalysis(access: BeforeAnalysisAccess?) = Unit
}
