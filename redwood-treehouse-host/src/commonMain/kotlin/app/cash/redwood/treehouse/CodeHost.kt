/*
 * Copyright (C) 2023 Square, Inc.
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
package app.cash.redwood.treehouse

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/** Manages loading and hot-reloading a series of code sessions. */
internal interface CodeHost<A : AppService> {
  val stateStore: StateStore

  /** Only accessed on [TreehouseDispatchers.ui]. */
  val session: CodeSession<A>?

  fun newServiceScope(): ServiceScope<A>

  /** Cancels the current code and propagates [exception] to all listeners. */
  fun handleUncaughtException(exception: Throwable)

  fun addListener(listener: Listener<A>)

  fun removeListener(listener: Listener<A>)

  interface Listener<A : AppService> {
    fun codeSessionChanged(next: CodeSession<A>)
    fun uncaughtException(exception: Throwable)
  }

  /**
   * Tracks all of the services created to produce a UI, and offers a single mechanism to close
   * them all. Note that closing this does not close the app services it was applied to.
   */
  interface ServiceScope<A : AppService> {
    /**
     * Returns a new instance that forwards calls to [appService] and keeps track of returned
     * instances so they may be closed.
     */
    fun apply(appService: A): A
    fun close()
  }
}

internal fun CodeHost<*>.asExceptionHandler() = object : CoroutineExceptionHandler {
  override val key: CoroutineContext.Key<*>
    get() = CoroutineExceptionHandler.Key

  override fun handleException(context: CoroutineContext, exception: Throwable) {
    handleUncaughtException(exception)
  }
}
