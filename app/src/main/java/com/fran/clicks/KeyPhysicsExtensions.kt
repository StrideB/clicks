package com.fran.clicks

import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

object KeyPhysicsRegistry {
    val activeSprings = WeakHashMap<Any, SpringAnimation>()
}

fun android.view.View.animateSpringReturn(startingDepth: Float = 8f) {
    KeyPhysicsRegistry.activeSprings[this]?.cancel()
    translationY = startingDepth

    val anim = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
        spring = SpringForce(0f).apply {
            stiffness = SpringForce.STIFFNESS_HIGH
            dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
        }
    }

    KeyPhysicsRegistry.activeSprings[this] = anim
    anim.start()
}
