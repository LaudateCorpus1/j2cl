/*
 * Copyright 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

// TODO(b/204366308): Remove when the corresponding function in Kotlin stdlib is standarized.
internal fun <K, V> buildMap(fn: MutableMap<K, V>.() -> Unit): Map<K, V> =
  mutableMapOf<K, V>().apply(fn).toMap()

// TODO(b/204366308): Remove when the corresponding function in Kotlin stdlib is standarized.
internal fun <V> buildSet(fn: MutableSet<V>.() -> Unit): Set<V> =
  mutableSetOf<V>().apply(fn).toSet()
