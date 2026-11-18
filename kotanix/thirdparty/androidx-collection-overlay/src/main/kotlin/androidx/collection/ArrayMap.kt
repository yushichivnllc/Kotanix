/*
 * Copyright 2018 The Android Open Source Project
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
 * Kotlin port of androidx.collection.ArrayMap (JVM), derived from the AOSP source at
 * https://cs.android.com/androidx/platform/frameworks/support (collection/collection,
 * file src/jvmMain/java/androidx/collection/ArrayMap.java, androidx-main @ cfa6d5ad3e3333bc4f0a6bdf02a3fa577aea11e2).
 *
 * Why this exists: the Kotanix offline build has no JDK javac and no access to a Maven repository,
 * so the three Java sources of androidx-collection cannot be compiled as-is. This file is a
 * direct, semantics-preserving translation — inner classes, iterator behaviour and the
 * same-object Map.Entry semantics of the original are kept. No logic is added or removed.
 */

package androidx.collection

import java.util.NoSuchElementException

/**
 * ArrayMap is a generic key->value mapping data structure that is designed to be more memory
 * efficient than a traditional [java.util.HashMap]. If you don't need the standard Java container
 * APIs provided here, consider using [SimpleArrayMap] instead. This implementation is not intended
 * to be appropriate for data structures that may contain large numbers of items. Because this
 * container is intended to better balance memory use, unlike most other standard Java containers it
 * will shrink its array as items are removed from it. This structure is **NOT** thread-safe.
 */
@Suppress("UNCHECKED_CAST", "KotlinRedundantDiagnosticSuppress")
public open class ArrayMap<K, V> : SimpleArrayMap<K, V>, MutableMap<K, V> {

    public constructor() : super()
    public constructor(capacity: Int) : super(capacity)
    public constructor(map: SimpleArrayMap<out K, out V>?) : super(map)

    private var mEntrySet: EntrySet? = null
    private var mKeySet: KeySet? = null
    private var mValues: ValueCollection? = null

    override val size: Int
        get() = super.size()

    public fun containsAll(collection: Collection<*>): Boolean {
        for (o in collection) {
            if (!containsKey(o as K)) return false
        }
        return true
    }

    public override fun containsKey(key: K): Boolean = super.containsKey(key)

    public override fun containsValue(value: V): Boolean = super.containsValue(value)

    public override fun get(key: K): V? = super.get(key)

    override fun putIfAbsent(key: K, value: V): V? = super<SimpleArrayMap>.putIfAbsent(key, value)

    override fun remove(key: K): V? = super<SimpleArrayMap>.remove(key)

    override fun remove(key: K, value: V): Boolean = super<SimpleArrayMap>.remove(key, value)


    override fun replace(key: K, value: V): V? = super<SimpleArrayMap>.replace(key, value)

    override fun replace(key: K, oldValue: V, newValue: V): Boolean =
        super<SimpleArrayMap>.replace(key, oldValue, newValue)

    override fun putAll(from: Map<out K, V>) {
        ensureCapacity(size + from.size)
        for ((k, v) in from) {
            put(k, v)
        }
    }

    public fun removeAll(collection: Collection<*>): Boolean {
        val oldSize = size
        for (o in collection) {
            remove(o as K)
        }
        return oldSize != size
    }

    public fun retainAll(collection: Collection<*>): Boolean {
        val oldSize = size
        for (i in size - 1 downTo 0) {
            if (!collection.contains(keyAt(i))) {
                removeAt(i)
            }
        }
        return oldSize != size
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            var entrySet = mEntrySet
            if (entrySet == null) {
                entrySet = EntrySet()
                mEntrySet = entrySet
            }
            return entrySet
        }

    override val keys: MutableSet<K>
        get() {
            var keySet = mKeySet
            if (keySet == null) {
                keySet = KeySet()
                mKeySet = keySet
            }
            return keySet
        }

    override val values: MutableCollection<V>
        get() {
            var values = mValues
            if (values == null) {
                values = ValueCollection()
                mValues = values
            }
            return values
        }

    private inner class EntrySet : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {

        override val size: Int
            get() = this@ArrayMap.size

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = MapIterator()

        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val index = indexOfKey(element.key)
            return index >= 0 && valueAt(index) == element.value
        }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            throw UnsupportedOperationException()
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val index = indexOfKey(element.key)
            if (index >= 0 && valueAt(index) == element.value) {
                removeAt(index)
                return true
            }
            return false
        }

        override fun hashCode(): Int {
            var result = 0
            for (i in 0 until this.size) {
                result += (keyAt(i)?.hashCode() ?: 0) xor (valueAt(i)?.hashCode() ?: 0)
            }
            return result
        }

        override fun equals(other: Any?): Boolean =
            other === this || (other is Set<*> && other.size == size && containsAll(other as Collection<MutableMap.MutableEntry<K, V>>))
    }

    private inner class KeySet : AbstractMutableSet<K>() {

        override val size: Int
            get() = this@ArrayMap.size

        override fun iterator(): MutableIterator<K> = KeyIterator()

        override fun contains(element: K): Boolean = containsKey(element)

        override fun add(element: K): Boolean {
            throw UnsupportedOperationException()
        }

        override fun clear() {
            this@ArrayMap.clear()
        }

        override fun containsAll(elements: Collection<K>): Boolean = this@ArrayMap.containsAll(elements)

        override fun remove(element: K): Boolean {
            val index = indexOfKey(element)
            if (index >= 0) {
                removeAt(index)
                return true
            }
            return false
        }

        override fun removeAll(elements: Collection<K>): Boolean = this@ArrayMap.removeAll(elements)

        override fun retainAll(elements: Collection<K>): Boolean = this@ArrayMap.retainAll(elements)

        override fun hashCode(): Int {
            var result = 0
            for (i in this@ArrayMap.size - 1 downTo 0) {
                val obj = keyAt(i)
                result += obj?.hashCode() ?: 0
            }
            return result
        }

        override fun equals(other: Any?): Boolean =
            other === this || (other is Set<*> && other.size == size && containsAll(other))
    }

    private inner class ValueCollection : AbstractMutableCollection<V>() {

        override val size: Int
            get() = this@ArrayMap.size

        override fun iterator(): MutableIterator<V> = ValueIterator()

        override fun add(element: V): Boolean {
            throw UnsupportedOperationException()
        }

        override fun clear() {
            this@ArrayMap.clear()
        }

        override fun contains(element: V): Boolean = indexOfValue(element) >= 0

        override fun containsAll(elements: Collection<V>): Boolean {
            for (o in elements) {
                if (!contains(o)) return false
            }
            return true
        }

        override fun remove(element: V): Boolean {
            val index = indexOfValue(element)
            if (index >= 0) {
                removeAt(index)
                return true
            }
            return false
        }

        override fun removeAll(elements: Collection<V>): Boolean {
            var changed = false
            for (i in size - 1 downTo 0) {
                if (elements.contains(valueAt(i))) {
                    removeAt(i)
                    changed = true
                }
            }
            return changed
        }

        override fun retainAll(elements: Collection<V>): Boolean {
            var changed = false
            for (i in size - 1 downTo 0) {
                if (!elements.contains(valueAt(i))) {
                    removeAt(i)
                    changed = true
                }
            }
            return changed
        }
    }

    private inner class KeyIterator : IndexBasedArrayIterator<K>(size) {
        override fun elementAt(index: Int): K = keyAt(index)
        override fun removeAt(index: Int) {
            removeAt(index)
        }
    }

    private inner class ValueIterator : IndexBasedArrayIterator<V>(size) {
        override fun elementAt(index: Int): V = valueAt(index)
        override fun removeAt(index: Int) {
            removeAt(index)
        }
    }

    private inner class MapIterator : MutableIterator<MutableMap.MutableEntry<K, V>>,
        MutableMap.MutableEntry<K, V> {

        private var mEnd = size - 1
        private var mIndex = -1
        private var mEntryValid = false

        override fun hasNext(): Boolean = mIndex < mEnd

        override fun next(): MutableMap.MutableEntry<K, V> {
            if (!hasNext()) {
                throw NoSuchElementException()
            }
            mIndex++
            mEntryValid = true
            return this
        }

        override fun remove() {
            check(mEntryValid) { "Call next() before removing an element." }
            removeAt(mIndex)
            mIndex--
            mEnd--
            mEntryValid = false
        }

        override val key: K
            get() {
                check(mEntryValid) { "This container does not support retaining Map.Entry objects" }
                return keyAt(mIndex)
            }

        override val value: V
            get() {
                check(mEntryValid) { "This container does not support retaining Map.Entry objects" }
                return valueAt(mIndex)
            }

        override fun setValue(newValue: V): V {
            check(mEntryValid) { "This container does not support retaining Map.Entry objects" }
            return setValueAt(mIndex, newValue)
        }

        override fun equals(other: Any?): Boolean {
            check(mEntryValid) { "This container does not support retaining Map.Entry objects" }
            if (other !is MutableMap.MutableEntry<*, *>) {
                return false
            }
            return other.key == keyAt(mIndex) && other.value == valueAt(mIndex)
        }

        override fun hashCode(): Int {
            check(mEntryValid) { "This container does not support retaining Map.Entry objects" }
            val key = keyAt(mIndex)
            val value = valueAt(mIndex)
            return (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
        }

        override fun toString(): String = "$key=$value"
    }
}
