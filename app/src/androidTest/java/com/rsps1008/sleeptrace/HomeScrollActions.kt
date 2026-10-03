package com.rsps1008.sleeptrace

import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA

/** Keep the target within the padded viewport, including the edge-to-edge system bar insets. */
fun revealHomeView() = object : ViewAction {
    override fun getConstraints() = isDescendantOfA(isAssignableFrom(ScrollView::class.java))
    override fun getDescription() = "scroll target into the safe viewport"
    override fun perform(uiController: UiController, view: View) {
        var ancestor = view.parent
        while (ancestor !is ScrollView) ancestor = ancestor.parent
        val scroll = ancestor
        val content = scroll.getChildAt(0) as android.view.ViewGroup
        val bounds = Rect(0, 0, view.width, view.height)
        content.offsetDescendantRectToMyCoords(view, bounds)
        scroll.scrollTo(0, bounds.top)
        uiController.loopMainThreadUntilIdle()
    }
}
