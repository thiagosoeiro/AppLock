package dev.pranav.applock.features.applist.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

object AppIconCache {
    private const val MAX_CACHE_SIZE = 200

    /** The largest size an icon is drawn at: 32 dp on the main screen, 28 dp in the "+" sheet. */
    private const val ICON_SIZE_DP = 32

    private val iconCache = LruCache<String, ImageBitmap>(MAX_CACHE_SIZE)

    fun getIcon(context: Context, appInfo: ApplicationInfo): ImageBitmap? {
        val cached = iconCache.get(appInfo.packageName)
        if (cached != null) return cached

        // Draw the icon at the size it is shown. Left to itself an adaptive icon rasterizes at its
        // own size, a few hundred pixels square, costing time on every row and memory in the cache.
        val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(1)
        val icon = appInfo.loadIcon(context.packageManager)
            ?.toBitmap(sizePx, sizePx)
            ?.asImageBitmap()
        icon?.let { iconCache.put(appInfo.packageName, it) }
        return icon
    }

    fun clear() {
        iconCache.evictAll()
    }
}

