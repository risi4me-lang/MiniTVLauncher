package com.mini.tvlauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SearchPanelManager(
    private val activity: Activity,
    private val searchPillBtn: View,
    private val searchPanel: FrameLayout,
    private val searchInput: EditText,
    private val searchAppResults: LinearLayout,
    private val searchWebBtn: View,
    private val searchYoutubeBtn: View,
    private val closeSearchPanelBtn: View,
    private val getInstalledApps: () -> List<AppEntry>,
    private val getHiddenPkgs: () -> Set<String>,
    private val onAppClick: (AppEntry) -> Unit
) {

    init {
        searchPillBtn.setOnClickListener { open() }
        searchPillBtn.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        closeSearchPanelBtn.setOnClickListener { close() }
        searchWebBtn.setOnClickListener { executeWebSearch(searchInput.text.toString()) }
        searchYoutubeBtn.setOnClickListener { executeYoutubeSearch(searchInput.text.toString()) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSearchApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun isOpen(): Boolean = searchPanel.visibility == View.VISIBLE

    fun open() {
        searchPanel.visibility = View.VISIBLE
        searchInput.requestFocus()
        filterSearchApps(searchInput.text.toString())
    }

    fun close() {
        searchPanel.visibility = View.GONE
        searchInput.setText("")
        searchPillBtn.requestFocus()
    }

    fun handleLiveTypeFromRemote(text: String, action: String) {
        activity.runOnUiThread {
            if (action == "clear") {
                searchInput.setText("")
            } else {
                searchInput.setText(text)
                if (text.isNotEmpty()) searchInput.setSelection(text.length)
            }

            if (action == "enter") {
                val query = searchInput.text.toString().trim()
                val matches = getInstalledApps().filter { it.label.contains(query, ignoreCase = true) }
                if (matches.isNotEmpty()) {
                    onAppClick(matches[0])
                    close()
                } else {
                    executeWebSearch(query)
                }
            }
        }
    }

    private fun filterSearchApps(query: String) {
        searchAppResults.removeAllViews()
        val q = query.trim().lowercase(Locale.US)
        val hiddenPkgs = getHiddenPkgs()
        val matches = if (q.isEmpty()) emptyList() else getInstalledApps().filter {
            !hiddenPkgs.contains(it.pkg) && it.label.lowercase(Locale.US).contains(q)
        }.take(5)

        val inflater = LayoutInflater.from(activity)
        for (app in matches) {
            val item = inflater.inflate(R.layout.item_hidden_app, searchAppResults, false)
            item.findViewById<TextView>(R.id.appName).text = app.label
            val btn = item.findViewById<TextView>(R.id.unhideBtn)
            btn.text = "Open"
            btn.setOnClickListener {
                onAppClick(app)
                close()
            }
            btn.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
            searchAppResults.addView(item)
        }
    }

    private fun executeWebSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            Toast.makeText(activity, "Type search query first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val url = "https://www.google.com/search?q=" + Uri.encode(q)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            close()
        } catch (_: Exception) {
            Toast.makeText(activity, "No browser or web search app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeYoutubeSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            Toast.makeText(activity, "Type search query first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube.tv")
                putExtra("query", q)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            close()
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                close()
            } catch (_: Exception) {
                Toast.makeText(activity, "No YouTube app or browser available", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
