// SPDX-License-Identifier: GPL-3.0-only
// Cairn navigation host. Refraction uses the attributed KSU-derived liquid primitives.
package com.cairn.manager.ui

import android.content.Context
import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import android.widget.FrameLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.manager.ui.ksu.FloatingBottomBar
import com.cairn.manager.ui.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import com.cairn.manager.ui.liquid.lens
import com.cairn.manager.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.*

/** Java-callable adapter; business pages keep their existing callbacks. */
open class LiquidNavigation(context: Context) : FrameLayout(context) {
    fun interface SelectionListener { fun onSelected(index: Int) }
    private var labels by mutableStateOf(listOf<String>())
    private var selected by mutableIntStateOf(0)
    private var listener: SelectionListener? = null
    private var snapshot by mutableStateOf<Bitmap?>(null)
    private var capturing = false
    private val captureTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (width > 0 && height > 0 && isShown) {
                val host = rootView
                val here = IntArray(2); val origin = IntArray(2)
                getLocationInWindow(here); host.getLocationInWindow(origin)
                val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = AndroidCanvas(frame)
                canvas.translate((origin[0]-here[0]).toFloat(), (origin[1]-here[1]).toFloat())
                capturing = true
                try { host.draw(canvas); snapshot = frame } finally { capturing = false }
            }
            postDelayed(this, 100)
        }
    }
    override fun dispatchDraw(canvas: AndroidCanvas) { if (!capturing) super.dispatchDraw(canvas) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(captureTick) }
    override fun onDetachedFromWindow() { removeCallbacks(captureTick); snapshot=null; super.onDetachedFromWindow() }
    init {
        val compose = ComposeView(context)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent { Navigation(labels, selected, snapshot) { i -> selected=i; listener?.onSelected(i) } }
        addView(compose, LayoutParams(-1,-1))
    }
    fun setLabels(values: Array<String>) { labels=values.toList() }
    fun setSelectedIndex(value: Int) { if(value in labels.indices)selected=value }
    fun getSelectedIndex(): Int = selected
    fun setSelectionListener(value: SelectionListener?) { listener=value }
}

@Composable
private fun Navigation(labels: List<String>, selected: Int, snapshot: Bitmap?, onSelected: (Int)->Unit) {
    val backdrop=rememberLayerBackdrop()
    val capsule=remember { RoundedCornerShape(32.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().clip(capsule).border(1.dp,Color(0x26ffffff),capsule)) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            snapshot?.let { drawImage(it.asImageBitmap(), dstSize=IntSize(size.width.toInt(),size.height.toInt())) }
        }
        MiuixTheme(colors=darkColorScheme(primary=Color(0xff7fd1ff),onSurface=Color(0xffe6edf3),surfaceContainer=Color(0xff1a1f29))) {
            if(labels.isNotEmpty()) FloatingBottomBar(
                modifier=Modifier.fillMaxSize(),
                selectedIndex=selected,
                onSelected=onSelected,
                backdrop=backdrop,
                tabsCount=labels.size,
                isBlurEnabled=true,
            ) { activateTab ->
                labels.forEachIndexed { i,label ->
                    FloatingBottomBarItem(selected=i==selected,onClick={activateTab(i)}) {
                        BasicText(label,style=TextStyle(color=LocalContentColor.current,fontSize=12.sp))
                    }
                }
            }
        }
    }
}
