package dev.dash.mula.engine

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import dev.dash.mula.Diag
import java.lang.reflect.Modifier

/**
 * Servo 的 ServoView 是 SurfaceView，自己在 onTouchEvent 里用 GestureDetector 处理滑动/缩放。
 * 如果它对 ACTION_DOWN 返回 false，或者父容器中途拦截，后续 MOVE 就到不了 ServoView，表现为「能显示但滑不动」。
 *
 * 这里接管分发：
 * - DOWN 时禁止父容器拦截；
 * - 事件始终手动交给 ServoView.onTouchEvent，并无条件消费，保证它能收到整串 MOVE/UP。
 * 若 ServoView 本来就正常消费，行为与原来一致。同时把触摸链路和 ServoView 的公开 API 写进 Diag 便于定位。
 */
internal object ServoTouchBridge {
    @SuppressLint("ClickableViewAccessibility")
    fun install(view: View) {
        var gestures = 0
        var moves = 0
        var startX = 0f
        var startY = 0f
        view.setOnTouchListener { v, e ->
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                gestures++
                moves = 0
                startX = e.x; startY = e.y
                v.parent?.requestDisallowInterceptTouchEvent(true)
            } else if (action == MotionEvent.ACTION_MOVE) {
                moves++
            }
            val handled = v.onTouchEvent(e)
            if (gestures <= 4) {
                when (action) {
                    MotionEvent.ACTION_DOWN ->
                        Diag.log("servo touch DOWN handledByView=$handled size=${v.width}x${v.height} enabled=${v.isEnabled}")
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        Diag.log("servo touch ${if (action == MotionEvent.ACTION_UP) "UP" else "CANCEL"} moves=$moves dx=${e.x - startX} dy=${e.y - startY}")
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            true
        }
    }

    /** 把 ServoView / Client / Navigator 的构造参数和公开方法写进日志（插件 API 随版本变化，排查用）。 */
    fun dumpApi(ctorParams: Array<Class<*>>, vararg classes: Class<*>) {
        runCatching {
            Diag.log("servo ctor params: " + ctorParams.joinToString { it.name })
            classes.forEach { c ->
                val methods = c.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
                    .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
                Diag.log("servo api ${c.name} extends ${c.superclass?.simpleName} implements ${c.interfaces.joinToString { it.simpleName }}: $methods")
            }
        }
    }
}
