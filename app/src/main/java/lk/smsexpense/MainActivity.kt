package lk.smsexpense

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TreeSet

class MainActivity : Activity() {

    private val primary = Color.parseColor("#00695C")
    private val red = Color.parseColor("#C62828")
    private val green = Color.parseColor("#2E7D32")
    private val grey = Color.parseColor("#6B7280")
    private val bg = Color.parseColor("#F3F5F7")

    private val all = mutableListOf<Txn>()
    private val shown = mutableListOf<Txn>()
    private val months = mutableListOf<String>() // "yyyy-MM", newest first
    private var monthIdx = 0
    private var filter = 0 // 0 = all, 1 = spent, 2 = received

    private val money = DecimalFormat("#,##0.00")
    private val monthKey = SimpleDateFormat("yyyy-MM", Locale.US)
    private val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var monthLabel: TextView
    private lateinit var spentTv: TextView
    private lateinit var recvTv: TextView
    private lateinit var countTv: TextView
    private lateinit var topBox: LinearLayout
    private lateinit var tabs: List<TextView>
    private lateinit var status: TextView
    private lateinit var grantBtn: Button
    private lateinit var resetHidden: TextView
    private lateinit var list: ListView
    private lateinit var adapter: TxnAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = primary
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) load() else showNeedPermission()
    }

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermission()) load() else showNeedPermission(denied = true)
    }

    // ---------------------------------------------------------------- data

    private fun load() {
        grantBtn.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = "Reading your SMS…"
        Thread {
            val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
            val found = mutableListOf<Txn>()
            try {
                contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"), null, null, "date DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val addr = c.getString(0) ?: continue
                        if (addr in hidden) continue
                        val body = c.getString(1) ?: continue
                        SmsParser.parse(addr, body, c.getLong(2))?.let { found.add(it) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't read SMS: ${e.message}" }
                return@Thread
            }
            runOnUiThread {
                all.clear()
                all.addAll(found)
                rebuildMonths()
                render()
            }
        }.start()
    }

    private fun rebuildMonths() {
        val current = months.getOrNull(monthIdx)
        val set = TreeSet<String>(Collections.reverseOrder())
        set.add(monthKey.format(Date()))
        all.forEach { set.add(monthKey.format(Date(it.date))) }
        months.clear()
        months.addAll(set)
        monthIdx = current?.let { months.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    }

    private fun render() {
        if (months.isEmpty()) return
        val key = months[monthIdx]
        monthLabel.text = monthName.format(monthKey.parse(key)!!)

        val inMonth = all.filter { monthKey.format(Date(it.date)) == key }
        val spent = inMonth.filter { it.isDebit }
        val received = inMonth.filter { !it.isDebit }
        spentTv.text = totals(spent)
        recvTv.text = totals(received)
        countTv.text = "${inMonth.size} transactions this month"

        // Top places money went
        topBox.removeAllViews()
        val top = spent.groupBy { it.place to it.currency }
            .map { (k, v) -> Triple(k.first, k.second, v.sumOf { it.amount }) to v.size }
            .sortedByDescending { it.first.third }
            .take(5)
        if (top.isNotEmpty()) {
            topBox.addView(text("Top spending", 13f, grey, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
            val max = top.first().first.third
            for ((t, n) in top) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(text("${t.first}  ·  $n×", 14f, Color.BLACK), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("${t.second} ${money.format(t.third)}", 14f, Color.BLACK, bold = true))
                topBox.addView(row)
                val bar = View(this).apply { background = rounded(red and 0x55FFFFFF.toInt(), 3) }
                val w = (t.third / max).toFloat().coerceIn(0.03f, 1f)
                val barRow = LinearLayout(this).apply { setPadding(0, dp(3), 0, dp(10)) }
                barRow.addView(bar, LinearLayout.LayoutParams(0, dp(5), w))
                barRow.addView(View(this), LinearLayout.LayoutParams(0, dp(5), 1f - w + 0.0001f))
                topBox.addView(barRow)
            }
        }

        shown.clear()
        shown.addAll(
            when (filter) {
                1 -> spent
                2 -> received
                else -> inMonth
            }
        )
        adapter.notifyDataSetChanged()
        tabs.forEachIndexed { i, tv ->
            val on = i == filter
            tv.setTextColor(if (on) Color.WHITE else primary)
            tv.background = rounded(if (on) primary else Color.WHITE, 18, if (on) 0 else primary)
        }

        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        resetHidden.visibility = if (hidden.isEmpty()) View.GONE else View.VISIBLE
        resetHidden.text = "Hidden senders: ${hidden.joinToString(", ")}  —  tap to show again"

        status.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (all.isEmpty())
            "No bank transaction SMS found yet.\nMessages like \"LKR 1,250.00 debited from A/C…\" will show up here."
        else "Nothing here for this month."
    }

    private fun totals(list: List<Txn>): String {
        if (list.isEmpty()) return "LKR 0.00"
        return list.groupBy { it.currency }
            .toSortedMap(compareBy<String> { if (it == "LKR") "" else it })
            .map { (cur, v) -> "$cur ${money.format(v.sumOf { it.amount })}" }
            .joinToString("\n")
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // App bar with month switcher
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primary)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val prev = text("‹", 30f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnClickListener { if (monthIdx < months.size - 1) { monthIdx++; render() } }
        }
        val next = text("›", 30f, Color.WHITE, bold = true).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnClickListener { if (monthIdx > 0) { monthIdx--; render() } }
        }
        monthLabel = text("", 19f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        bar.addView(prev)
        bar.addView(monthLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(next)
        root.addView(bar)

        // Header that scrolls with the list
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(4))
        }
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spentTv = text("", 19f, red, bold = true)
        recvTv = text("", 19f, green, bold = true)
        cards.addView(card("Spent", spentTv), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        cards.addView(card("Received", recvTv), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        header.addView(cards)

        countTv = text("", 13f, grey).apply { setPadding(dp(4), dp(10), 0, dp(10)) }
        header.addView(countTv)

        topBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(4))
        }
        header.addView(topBox)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(6))
        }
        tabs = listOf("All", "Spent", "Received").mapIndexed { i, label ->
            text(label, 14f, primary, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(7), dp(16), dp(7))
                setOnClickListener { filter = i; render() }
                tabRow.addView(this, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        header.addView(tabRow)

        resetHidden = text("", 12f, grey).apply {
            setPadding(dp(4), dp(4), 0, dp(6))
            visibility = View.GONE
            setOnClickListener {
                prefs.edit().remove("hidden").apply()
                load()
            }
        }
        header.addView(resetHidden)

        grantBtn = Button(this).apply {
            text = "Allow SMS access"
            visibility = View.GONE
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_SMS), 1) }
        }
        header.addView(grantBtn)

        status = text("", 14f, grey).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
        }
        header.addView(status)

        adapter = TxnAdapter()
        list = ListView(this).apply {
            divider = null
            addHeaderView(header, null, false)
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { parent, _, pos, _ ->
                (parent.getItemAtPosition(pos) as? Txn)?.let { showSms(it) }
            }
            setOnItemLongClickListener { parent, _, pos, _ ->
                (parent.getItemAtPosition(pos) as? Txn)?.let { askHide(it) }
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        tabs.forEachIndexed { i, tv -> tv.background = rounded(if (i == 0) primary else Color.WHITE, 18) }
        monthLabel.text = monthName.format(Date())
    }

    private fun showNeedPermission(denied: Boolean = false) {
        status.visibility = View.VISIBLE
        grantBtn.visibility = View.VISIBLE
        status.text = if (!denied)
            "This app reads your bank SMS on this phone to total your spending.\nNothing leaves your phone — the app has no internet permission."
        else
            "SMS access is off.\n\nIf the Allow button does nothing: open App info → ⋮ (top right) → " +
                "\"Allow restricted settings\", then Permissions → SMS → Allow."
        if (denied) {
            grantBtn.text = "Open App info"
            grantBtn.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun showSms(t: Txn) {
        AlertDialog.Builder(this)
            .setTitle("${t.sender} · ${dayFmt.format(Date(t.date))}")
            .setMessage(t.body)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun askHide(t: Txn) {
        AlertDialog.Builder(this)
            .setTitle("Hide \"${t.sender}\"?")
            .setMessage("Ignore every SMS from this sender (use this for promo senders that get picked up by mistake). You can undo it later.")
            .setPositiveButton("Hide") { _, _ ->
                val set = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
                set.add(t.sender)
                prefs.edit().putStringSet("hidden", set).apply()
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class TxnAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = shown[position]
            val wrap = LinearLayout(this@MainActivity).apply { setPadding(dp(14), dp(4), dp(14), dp(4)) }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.WHITE, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val left = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            left.addView(text(t.place, 15f, Color.BLACK, bold = true).apply { maxLines = 1 })
            left.addView(text("${dayFmt.format(Date(t.date))} · ${t.sender}", 12f, grey).apply { maxLines = 1 })
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            val sign = if (t.isDebit) "−" else "+"
            row.addView(
                text("$sign ${t.currency} ${money.format(t.amount)}", 15f, if (t.isDebit) red else green, bold = true)
                    .apply { setPadding(dp(10), 0, 0, 0) }
            )
            wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
            return wrap
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun card(label: String, value: TextView): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(text(label, 13f, grey))
            addView(value)
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(1), strokeColor)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
