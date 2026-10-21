package com.mini.tvlauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

data class AppEntry(
    val defaultLabel: String,
    val label: String,
    val pkg: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
    val averageColor: Int,
    val launchCount: Int = 0,
    val hasCustomImage: Boolean = false,
    val appInfo: ApplicationInfo? = null
)

data class ToolTile(
    val id: String,
    val label: String,
    val iconRes: Int,
    val intent: Intent
)

data class CategoryRowItem(
    val title: String,
    val apps: List<AppEntry>
)

data class ShortcutCandidate(
    val id: String,
    val label: String,
    val iconRes: Int,
    val averageColor: Int,
    val candidateIntents: List<Intent>
)
