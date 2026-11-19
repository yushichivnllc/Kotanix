// Compile-only stubs of GraalVM native-image annotations.
package com.oracle.svm.core.annotate

import kotlin.reflect.KClass

annotation class TargetClass(val value: KClass<*> = Any::class)
annotation class Delete
annotation class Substitute
