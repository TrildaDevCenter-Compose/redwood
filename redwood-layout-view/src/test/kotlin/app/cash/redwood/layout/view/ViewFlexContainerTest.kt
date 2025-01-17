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
package app.cash.redwood.layout.view

import android.view.View
import app.cash.burst.Burst
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.redwood.layout.AbstractFlexContainerTest
import app.cash.redwood.layout.TestFlexContainer
import app.cash.redwood.layout.api.Constraint
import app.cash.redwood.layout.api.Overflow
import app.cash.redwood.layout.widget.Column
import app.cash.redwood.layout.widget.Row
import app.cash.redwood.layout.widget.Spacer
import app.cash.redwood.snapshot.testing.ViewSnapshotter
import app.cash.redwood.snapshot.testing.ViewTestWidgetFactory
import app.cash.redwood.ui.Px
import app.cash.redwood.widget.ChangeListener
import app.cash.redwood.widget.ViewGroupChildren
import app.cash.redwood.yoga.FlexDirection
import com.android.resources.LayoutDirection
import org.junit.Rule

@Burst
class ViewFlexContainerTest(
  layoutDirection: LayoutDirection = LayoutDirection.LTR,
) : AbstractFlexContainerTest<View>() {

  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(layoutDirection = layoutDirection),
    theme = "android:Theme.Material.Light.NoActionBar",
    supportsRtl = true,
  )

  override val widgetFactory: ViewTestWidgetFactory
    get() = ViewTestWidgetFactory(paparazzi.context)

  override fun flexContainer(
    direction: FlexDirection,
    backgroundColor: Int,
  ): ViewTestFlexContainer {
    val delegate = ViewFlexContainer(paparazzi.context, direction).apply {
      value.setBackgroundColor(backgroundColor)
    }
    return ViewTestFlexContainer(delegate)
      .apply { (this as TestFlexContainer<*>).applyDefaults() }
  }

  override fun row(): Row<View> = ViewRow(paparazzi.context).apply {
    value.setBackgroundColor(defaultBackgroundColor)
    applyDefaults()
  }

  override fun column(): Column<View> = ViewColumn(paparazzi.context).apply {
    value.setBackgroundColor(defaultBackgroundColor)
    applyDefaults()
  }

  override fun spacer(backgroundColor: Int): Spacer<View> {
    return ViewSpacer(paparazzi.context)
      .apply {
        value.setBackgroundColor(backgroundColor)
      }
  }

  override fun snapshotter(widget: View) = ViewSnapshotter(paparazzi, widget)

  class ViewTestFlexContainer internal constructor(
    private val delegate: ViewFlexContainer,
  ) : TestFlexContainer<View>,
    YogaFlexContainer<View> by delegate,
    ChangeListener by delegate {
    override val value: View get() = delegate.value
    override var modifier by delegate::modifier

    override val children: ViewGroupChildren = delegate.children

    override fun width(width: Constraint) = delegate.width(width)
    override fun height(height: Constraint) = delegate.height(height)
    override fun overflow(overflow: Overflow) = delegate.overflow(overflow)
    override fun onScroll(onScroll: ((Px) -> Unit)?) = delegate.onScroll(onScroll)

    override fun scroll(offset: Px) {
      delegate.onScroll?.invoke(offset)
    }
  }
}
