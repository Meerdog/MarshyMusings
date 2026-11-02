package com.vmm5.notes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import com.vmm5.notes.R

/** Tango, the orange cat the app is named after, in his nine poses. */
enum class Tango(val res: Int) {
    Lounge(R.drawable.tango_lounge), Sit(R.drawable.tango_sit), Loaf(R.drawable.tango_loaf),
    Sleep(R.drawable.tango_sleep), Face(R.drawable.tango_face), Stretch(R.drawable.tango_stretch),
    Groom(R.drawable.tango_groom), Walk(R.drawable.tango_walk), Yawn(R.drawable.tango_yawn),
}

/** A Tango sprite at the given height, pixels kept crisp (no smoothing); nothing in the Minimal look. */
@Composable
fun TangoImage(pose: Tango, height: Dp, modifier: Modifier = Modifier, description: String? = null) {
    if (LocalMinimal.current) return // the Minimal look has no cat
    val bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(pose.res)
    Image(bitmap, description, modifier = modifier.height(height), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
}
