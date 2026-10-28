package com.mini.tvlauncher

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class CategoryRowsManager(
    private val prefs: SharedPreferences,
    private val rowsContainer: RecyclerView,
    private val sharedViewPool: RecyclerView.RecycledViewPool,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit,
    private val onAppFocused: (AppEntry?) -> Unit
) {

    fun refreshAppLists(allInstalledApps: List<AppEntry>, getFavoritePkgs: () -> List<String>, getHiddenPkgs: () -> Set<String>) {
        val hiddenPkgs = getHiddenPkgs()
        val visibleApps = allInstalledApps.filter { !hiddenPkgs.contains(it.pkg) }

        val favPkgs = getFavoritePkgs()
        val favApps = favPkgs.mapNotNull { pkg -> visibleApps.firstOrNull { it.pkg == pkg } }

        val groupByCategory = prefs.getBoolean("group_by_category", true)
        val rowItems = ArrayList<CategoryRowItem>()

        if (groupByCategory) {
            if (favApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem(AppUtils.CAT_FAVORITES, favApps))
            }

            val nonFavVisible = visibleApps.filter { !favPkgs.contains(it.pkg) }

            val entApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_ENTERTAINMENT }
            if (entApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_ENTERTAINMENT, entApps))

            val gameApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_GAMES }
            if (gameApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_GAMES, gameApps))

            val prodApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_PRODUCTIVITY }
            if (prodApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_PRODUCTIVITY, prodApps))

            val otherApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_OTHERS }
            if (otherApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_OTHERS, otherApps))
        } else {
            if (favApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem(AppUtils.CAT_FAVORITES, favApps))
            }
            val remainingApps = visibleApps.filter { !favPkgs.contains(it.pkg) }
            if (remainingApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem("Apps", remainingApps))
            }
        }

        rowsContainer.adapter = CategoryRowAdapter(rowItems, sharedViewPool, prefs, onAppClick, onAppLongClick, onAppFocused)
    }
}

class CategoryRowAdapter(
    private val rows: List<CategoryRowItem>,
    private val sharedViewPool: RecyclerView.RecycledViewPool,
    private val prefs: SharedPreferences,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit,
    private val onAppFocused: (AppEntry?) -> Unit
) : RecyclerView.Adapter<CategoryRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rowTitle: TextView = v.findViewById(R.id.rowTitle)
        val rowRecyclerView: RecyclerView = v.findViewById(R.id.rowRecyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category_row, parent, false)
        val h = VH(v)
        h.rowRecyclerView.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 2
        }
        h.rowRecyclerView.setRecycledViewPool(sharedViewPool)
        h.rowRecyclerView.setHasFixedSize(true)
        h.rowRecyclerView.isNestedScrollingEnabled = false
        return h
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.rowTitle.text = row.title.uppercase(Locale.US)
        val adapter = AppAdapter(holder.rowRecyclerView, prefs, onAppClick, onAppLongClick, onAppFocused)
        holder.rowRecyclerView.adapter = adapter
        adapter.submit(row.apps)
    }

    override fun getItemCount() = rows.size
}

class ToolsAdapter(
    private val tiles: List<ToolTile>,
    private val onClick: (ToolTile) -> Unit,
    private val onToolFocused: () -> Unit = {}
) : RecyclerView.Adapter<ToolsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardContainer: FrameLayout = v.findViewById(R.id.cardContainer)
        val toolIcon: ImageView = v.findViewById(R.id.toolIcon)
        val toolLabel: TextView = v.findViewById(R.id.toolLabel)
        val cardBorder: View = v.findViewById(R.id.cardBorder)

        init {
            cardContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            cardContainer.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_tool_tile, p, false)
        val h = VH(v)

        v.setOnFocusChangeListener { _, focused ->
            val duration = 160L
            val density = v.resources.displayMetrics.density
            val scale = if (focused) 1.1f else 1.0f
            val alpha = if (focused) 1f else 0f

            if (focused) onToolFocused()

            h.cardContainer.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (focused) 6f * density else 0f)
                .setDuration(duration)
                .start()

            h.cardBorder.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()
        }

        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = tiles[pos]
        h.toolLabel.text = item.label
        h.toolIcon.setImageResource(item.iconRes)
        h.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = tiles.size
}

class AppAdapter(
    private val recyclerView: RecyclerView,
    private val prefs: SharedPreferences,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit,
    private val onAppFocused: (AppEntry?) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppEntry> = emptyList()
    private var isFirstLoad = true

    fun submit(l: List<AppEntry>) {
        items = l
        notifyDataSetChanged()
        if (isFirstLoad && l.isNotEmpty()) {
            isFirstLoad = false
            recyclerView.alpha = 0f
            recyclerView.translationY = 60f * recyclerView.resources.displayMetrics.density
            recyclerView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    recyclerView.post {
                        val vh = recyclerView.findViewHolderForAdapterPosition(0)
                        vh?.itemView?.requestFocus()
                    }
                }
                .start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardContainer: FrameLayout = v.findViewById(R.id.cardContainer)
        val cardImage: ImageView = v.findViewById(R.id.cardImage)
        val iconLayout: FrameLayout = v.findViewById(R.id.iconLayout)
        val cardIcon: ImageView = v.findViewById(R.id.cardIcon)
        val cardBorder: View = v.findViewById(R.id.cardBorder)
        val label: TextView = v.findViewById(R.id.label)

        init {
            cardContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            cardContainer.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false)
        val h = VH(v)

        v.setOnFocusChangeListener { _, focused ->
            val duration = 160L
            val density = v.resources.displayMetrics.density
            val scale = if (focused) 1.1f else 1.0f
            val alpha = if (focused) 1f else 0f

            val pos = h.bindingAdapterPosition
            if (focused && pos != RecyclerView.NO_POSITION && pos < items.size) {
                onAppFocused(items[pos])
            }

            h.cardContainer.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (focused) 8f * density else 0f)
                .setDuration(duration)
                .start()

            h.cardBorder.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()

            h.label.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()
        }

        v.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                onAppLongClick(items[pos], v)
            }
            true
        }

        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.label.text = e.label

        val isBright = DisplayModeManager.getEffectiveMode(prefs) == "bright"
        if (isBright) {
            h.label.textSize = 15.5f
            h.label.setTextColor(Color.WHITE)
            h.label.setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
        } else {
            h.label.textSize = 14.0f
            h.label.setTextColor(0xFFE2E8F0.toInt())
            h.label.setShadowLayer(0f, 0f, 0f, 0)
        }

        if (e.banner != null) {
            h.cardImage.setImageDrawable(e.banner)
            h.cardImage.visibility = View.VISIBLE
            h.iconLayout.visibility = View.GONE
            h.cardContainer.setBackgroundResource(R.drawable.card_bg)
        } else {
            h.cardImage.visibility = View.GONE
            h.iconLayout.visibility = View.VISIBLE
            h.cardIcon.setImageDrawable(e.icon)
            val gradient = createIconGradient(e.averageColor, h.itemView.resources.displayMetrics.density)
            h.cardContainer.background = gradient
        }

        h.itemView.setOnClickListener { onAppClick(e) }
    }

    private fun createIconGradient(avgColor: Int, density: Float): GradientDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(avgColor, hsv)
        val topColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.75f).coerceIn(0f, 1f), (hsv[2] * 0.65f).coerceIn(0.2f, 0.85f)))
        val bottomColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.85f).coerceIn(0f, 1f), (hsv[2] * 0.25f).coerceIn(0.1f, 0.45f)))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            cornerRadius = 18f * density
        }
    }

    override fun getItemCount() = items.size
}
