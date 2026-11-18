/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Kotlin port of androidx.collection.ArraySetJvmUtil (JVM), derived from the AOSP source at
 * https://cs.android.com/androidx/platform/frameworks/support
 * (collection/collection, file src/jvmMain/java/androidx/collection/ArraySetJvmUtil.java,
 * androidx-main @ cfa6d5ad3e3333bc4f0a6bdf02a3fa577aea11e2).
 */

package androidx.collection


internal object ArraySetJvmUtil {
	@Suppress("UNCHECKED_CAST")
	@JvmStatic
	fun <T> resizeForToArray(destination: Array<T>, size: Int): Array<T> {
		return if (destination.size < size) {
			java.lang.reflect.Array.newInstance(destination::class.java.componentType!!, size) as Array<T>
		} else {
			if (destination.size > size) {
				@Suppress("UNCHECKED_CAST")
				(destination as Array<Any?>)[size] = null
			}
			destination
		}
	}
}
