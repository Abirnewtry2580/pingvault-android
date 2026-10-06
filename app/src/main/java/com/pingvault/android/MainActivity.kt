package com.pingvault.android

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: NotificationStore
    private lateinit var accessButton: Button
    private lateinit var search: EditText
    private lateinit var appSpinner: Spinner
    private lateinit var list: LinearLayout
    private var savedOnly = false
    private var selectedPackage: String? = null
    private var appChoices: List<Pair<String, String>> = emptyList()
    private var updatingAppSpinner = false
    private var pendingExportPath: String? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotificationStore.get(this)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
        refreshList()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ArchiveNotificationListener.ACTION_ARCHIVE_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(updateReceiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(updateReceiver) }
        super.onStop()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        root.addView(TextView(this).apply {
            text = "PingVault"
            textSize = 28f
            setTextColor(Color.rgb(20, 38, 60))
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Your notifications, kept on this device"
            textSize = 14f
            setTextColor(Color.rgb(85, 101, 120))
            setPadding(0, dp(3), 0, dp(14))
        })
        accessButton = Button(this).apply {
            textSize = 14f
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(accessButton, matchWrap())

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "All"
            setOnClickListener { savedOnly = false; refreshList() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(Button(this).apply {
            text = "Saved"
            setOnClickListener { savedOnly = true; refreshList() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)

        appSpinner = Spinner(this)
        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingAppSpinner) return
                selectedPackage = if (position == 0) null else appChoices.getOrNull(position - 1)?.second
                refreshList()
            }
        }
        root.addView(appSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            bottomMargin = dp(6)
        })

        root.addView(Button(this).apply {
            text = "Clear all"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear the whole archive?")
                    .setMessage("All archived notifications and copied attachments will be removed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear all") { _, _ ->
                        store.clearAll()
                        selectedPackage = null
                        savedOnly = false
                        refreshList()
                    }
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))

        search = EditText(this).apply {
            hint = "Search notifications"
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.WHITE, Color.rgb(220, 226, 234))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshList()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(8); bottomMargin = dp(10)
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshPermission()
    }

    private fun refreshPermission() {
        if (!::accessButton.isInitialized) return
        val enabled = isListenerEnabled()
        accessButton.text = if (enabled) "Notification access enabled" else "Enable notification access"
        accessButton.setBackgroundColor(if (enabled) Color.rgb(220, 241, 230) else Color.rgb(217, 235, 255))
        accessButton.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun isListenerEnabled(): Boolean {
        val flattened = ComponentName(this, ArchiveNotificationListener::class.java).flattenToString()
        val setting = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return setting.split(':').any { it.equals(flattened, ignoreCase = true) }
    }

    private fun refreshList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        refreshAppSpinner()
        val rows = store.list(search.text?.toString().orEmpty(), savedOnly, selectedPackage)
        if (rows.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (isListenerEnabled()) "No notifications saved yet." else "Enable notification access to start your archive."
                textSize = 15f
                setTextColor(Color.rgb(100, 112, 128))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(48))
            })
            return
        }
        rows.forEach { item -> list.addView(notificationCard(item)) }
    }

    private fun refreshAppSpinner() {
        if (!::appSpinner.isInitialized) return
        val current = store.appFilters()
        if (current == appChoices && appSpinner.adapter != null) return
        appChoices = current
        if (selectedPackage != null && appChoices.none { it.second == selectedPackage }) {
            selectedPackage = null
        }
        val labels = listOf("All apps") + appChoices.map { it.first }
        updatingAppSpinner = true
        appSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedIndex = selectedPackage?.let { pkg -> appChoices.indexOfFirst { it.second == pkg } + 1 } ?: 0
        appSpinner.setSelection(selectedIndex.coerceAtLeast(0), false)
        updatingAppSpinner = false
    }

    private fun notificationCard(item: ArchivedNotification): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(Color.WHITE, Color.rgb(230, 234, 240))
            elevation = dp(1).toFloat()
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = item.appName
            textSize = 12f
            setTextColor(Color.rgb(44, 100, 170))
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = if (item.saved) "★" else "☆"
            textSize = 22f
            setTextColor(Color.rgb(206, 151, 20))
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { store.toggleSaved(item.key); refreshList() }
        })
        card.addView(top)
        card.addView(TextView(this).apply {
            text = item.title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(27, 39, 54))
            setPadding(0, dp(4), 0, dp(2))
        })
        if (item.body.isNotBlank()) card.addView(TextView(this).apply {
            text = item.body
            textSize = 14f
            setTextColor(Color.rgb(73, 83, 96))
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.postedAt))
            textSize = 11f
            setTextColor(Color.rgb(125, 135, 147))
            setPadding(0, dp(7), 0, 0)
        })
        item.mediaPath?.let { path ->
            if (item.mediaMime?.startsWith("image/") == true) {
                BitmapFactory.decodeFile(path)?.let { bitmap ->
                    card.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        maxHeight = dp(220)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(8) })
                }
            } else card.addView(TextView(this).apply {
                text = "Attachment saved (" + (item.mediaMime ?: "media") + ")"
                textSize = 12f
                setTextColor(Color.rgb(44, 100, 170))
                setPadding(0, dp(8), 0, 0)
            })
        }
        card.setOnClickListener { showDetails(item) }
        card.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete notification?")
                .setMessage("This removes the archived item and its copied attachment.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> store.delete(item.key); refreshList() }
                .show()
            true
        }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    private fun showDetails(item: ArchivedNotification) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(TextView(this).apply {
            text = item.appName + " · " + DateFormat.getDateTimeInstance().format(Date(item.postedAt))
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        content.addView(TextView(this).apply {
            text = item.title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = item.body.ifBlank { "(No text in notification)" }
            textSize = 16f
            setTextColor(Color.DKGRAY)
        })
        item.mediaPath?.let { path ->
            if (item.mediaMime?.startsWith("image/") == true) {
                BitmapFactory.decodeFile(path)?.let { bitmap ->
                    content.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        maxHeight = dp(360)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    })
                }
            } else content.addView(TextView(this).apply {
                text = "Saved attachment: " + (item.mediaMime ?: "media")
                setPadding(0, dp(10), 0, 0)
            })
        }
        item.mediaPath?.let { path ->
            content.addView(Button(this).apply {
                text = "Export attachment"
                setOnClickListener { exportAttachment(path, item.mediaMime) }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this).setView(content)
            .setPositiveButton(if (item.saved) "Unsave" else "Save") { _, _ -> store.toggleSaved(item.key); refreshList() }
            .setNeutralButton("Delete") { _, _ -> store.delete(item.key); refreshList() }
            .setNegativeButton("Close", null).show()
    }

    private fun exportAttachment(path: String, mimeType: String?) {
        pendingExportPath = path
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, File(path).name)
        }
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
            .onFailure { Toast.makeText(this, "Could not open file picker.", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Handled for Android's document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val sourcePath = pendingExportPath ?: return
        val destination = data?.data ?: return
        val result = runCatching {
            val output = contentResolver.openOutputStream(destination) ?: error("Destination unavailable")
            File(sourcePath).inputStream().use { input ->
                output.use { sink -> input.copyTo(sink) }
            }
        }
        Toast.makeText(
            this,
            if (result.isSuccess) "Attachment exported." else "Could not export this attachment.",
            Toast.LENGTH_LONG
        ).show()
        pendingExportPath = null
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(14).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

    companion object {
        private const val REQUEST_EXPORT = 5201
    }
}
