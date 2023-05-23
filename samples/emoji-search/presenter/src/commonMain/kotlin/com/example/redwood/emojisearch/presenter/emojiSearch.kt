/*
 * Copyright (C) 2022 Square, Inc.
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
package com.example.redwood.emojisearch.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.redwood.Modifier
import app.cash.redwood.layout.api.Constraint
import app.cash.redwood.layout.api.CrossAxisAlignment
import app.cash.redwood.layout.compose.Column
import app.cash.redwood.layout.compose.Row
import app.cash.redwood.ui.Margin
import app.cash.redwood.ui.dp
import com.example.redwood.emojisearch.compose.Image
import com.example.redwood.emojisearch.compose.TextInput
import example.values.TextFieldState
import kotlinx.serialization.json.Json

private data class EmojiImage(
  val label: String,
  val url: String,
)

// TODO Switch to https://github.com/cashapp/zipline/issues/490 once available.
@Suppress("FUN_INTERFACE_WITH_SUSPEND_FUNCTION") // https://youtrack.jetbrains.com/issue/KTIJ-7642
fun interface HttpClient {
  suspend fun call(url: String, headers: Map<String, String>): String
}

/**
 * LazyColumn doesn't work in browsers. This indirection allows us to use LazyColumn with a mobile
 * host and regular column with a browser host.
 *
 * TODO LazyColumn should work outside of Treehouse https://github.com/cashapp/redwood/issues/605.
 */
interface ColumnProvider {
  @Composable
  fun <T> create(
    items: List<T>,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier,
    itemContent: @Composable (item: T) -> Unit,
  )
}

@Composable
fun EmojiSearch(
  httpClient: HttpClient,
) {
  val allEmojis = remember { mutableStateListOf<EmojiImage>() }

  // Simple counter that allows us to trigger refreshes by simple incrementing the value
  var refreshSignal by remember { mutableStateOf(0) }
  var refreshing by remember { mutableStateOf(false) }

  LaunchedEffect(refreshSignal) {
    try {
      refreshing = true
      val emojisJson = httpClient.call(
        url = "https://api.github.com/emojis",
        headers = mapOf("Accept" to "application/vnd.github.v3+json"),
      )
      val labelToUrl = Json.decodeFromString<Map<String, String>>(emojisJson)

      allEmojis.clear()
      allEmojis.addAll(labelToUrl.map { (key, value) -> EmojiImage(key, value) })
    } finally {
      refreshing = false
    }
  }

  var searchTerm by remember { mutableStateOf(TextFieldState()) }
  val filteredEmojis by derivedStateOf {
    val searchTerms = searchTerm.text.split(" ")
    allEmojis.filter { image ->
      searchTerms.all { image.label.contains(it, ignoreCase = true) }
    }
  }

  Column(
    width = Constraint.Fill,
    height = Constraint.Fill,
    horizontalAlignment = CrossAxisAlignment.Center,
    margin = Margin(top = 60.dp),
  ) {
    TextInput(
      state = searchTerm,
      hint = "Search",
      onChange = { searchTerm = it },
      modifier = Modifier
        .horizontalAlignment(CrossAxisAlignment.Stretch),
    )
    filteredEmojis.take(10).forEach { image ->
      Image(image.url)
    }
  }
}