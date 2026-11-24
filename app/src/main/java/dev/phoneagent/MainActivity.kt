package dev.phoneagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The app: Home (give a task), Runs (history), Usage, Settings. Plain views, built in code. */
class MainActivity : Activity() {
    private enum class Tab(val label: String, val icon: String) { HOME("Home", "home"), RUNS("Runs", "runs"), USAGE("Usage", "chart"), SETTINGS("Settings", "settings") }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = Tab.HOME
    private var detail: RunInfo? = null
    private var showLog = false
    private var runsFilter = "all"
    private var usageDays = 7
    private var homeScrollY = 0

    private var taskField: EditText? = null
    private var micButton: ImageView? = null
    private var logView: TextView? = null
    private var voice: Voice? = null
    private var listenAfterGrant = false
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(this)
        Prefs.init(this)
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION") run { window.statusBarColor = 0; window.navigationBarColor = 0 }
        window.decorView.setBackgroundColor(C.BG)
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(buildShell())
        render()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AgentBus.listener = { line -> main.post { logView?.let { it.append("\n$line") } } }
        AgentBus.statusListener = { main.post { if (!showLog) render() } }
        render()
        if (Auth.signedIn && Prefs.models().isEmpty()) fetchModels()
    }

    override fun onPause() {
        super.onPause()
        AgentBus.listener = null
        AgentBus.statusListener = null
        voice?.cancel()
    }

    // ---------------------------------------------------------------- shell & navigation

    private fun buildShell(): View {
        content = FrameLayout(this)
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF141419.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                nav.setPadding(0, dp(6), 0, bars.bottom + dp(6))
                content.setPadding(0, 0, 0, ime.bottom)
                nav.visibility = if (ime.bottom > 0) View.GONE else View.VISIBLE
                WindowInsets.CONSUMED
            }
        }
        return root
    }

    private fun buildNav() {
        nav.removeAllViews()
        for (t in Tab.entries) {
            val on = t == tab && detail == null && !showLog
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                val pillBg = FrameLayout(this@MainActivity).apply {
                    background = if (t == tab) round(C.ACCENT_SOFT, dp(16).toFloat()) else null
                    addView(icon(t.icon, if (t == tab) C.ACCENT else C.SUB, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }
                addView(pillBg, LinearLayout.LayoutParams(dp(60), dp(32)))
                addView(text(t.label, 12f, if (t == tab) C.TXT else C.SUB, if (t == tab) 650 else 500), lp(-2, -2, top = dp(3)))
                background = pressable(0x00000000, 16)
                contentDescription = t.label + if (on) " (selected)" else ""
                setOnClickListener { go(t) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun go(t: Tab) {
        if (t == tab && detail == null && !showLog) return
        tab = t
        detail = null
        showLog = false
        render()
    }

    private fun canGoBack() = detail != null || showLog || tab != Tab.HOME

    private fun goBack(): Boolean {
        when {
            detail != null -> detail = null
            showLog -> showLog = false
            tab != Tab.HOME -> tab = Tab.HOME
            else -> return false
        }
        render()
        return true
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val d = onBackInvokedDispatcher
        if (canGoBack()) {
            if (backCallback == null) {
                val cb = OnBackInvokedCallback { goBack() }
                d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
                backCallback = cb
            }
        } else backCallback?.let { d.unregisterOnBackInvokedCallback(it); backCallback = null }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!goBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun render() {
        if (tab == Tab.HOME && detail == null && !showLog) (content.getChildAt(0) as? ScrollView)?.let { homeScrollY = it.scrollY }
        voice?.cancel()
        taskField = null
        micButton = null
        logView = null
        content.removeAllViews()
        val v = when {
            showLog -> logScreen()
            detail != null -> detailScreen(detail!!)
            else -> when (tab) {
                Tab.HOME -> homeScreen()
                Tab.RUNS -> runsScreen()
                Tab.USAGE -> usageScreen()
                Tab.SETTINGS -> settingsScreen()
            }
        }
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (tab == Tab.HOME && detail == null && !showLog) (v as? ScrollView)?.let { s -> s.post { s.scrollTo(0, homeScrollY) } }
        buildNav()
        updateBack()
    }

    // ---------------------------------------------------------------- building blocks

    private fun page(build: LinearLayout.() -> Unit): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
            build()
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
        }
    }

    private fun LinearLayout.title(t: String, sub: String? = null) {
        addView(text(t, 28f, C.TXT, 700), lp(top = dp(10)))
        if (sub != null) addView(text(sub, 13.5f, C.SUB), lp(top = dp(2)))
    }

    private fun LinearLayout.topBar(t: String, action: View? = null) {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton("back", sizeDp = 42, iconDp = 22, bg = 0x00000000) { goBack() })
            addView(text(t, 18f, C.TXT, 650), lp(0, -2, 1f, left = dp(6)))
            if (action != null) addView(action)
        }, lp(top = dp(4), left = -dp(8)))
    }

    private fun LinearLayout.section(t: String) {
        addView(text(t, 13f, C.SUB, 600), lp(top = dp(22), bottom = dp(8), left = dp(4)))
    }

    private fun divider() = View(this).apply { setBackgroundColor(C.LINE) }

    /** A settings-style row: icon, title, subtitle, right-hand widget. */
    private fun row(iconName: String?, title: String, sub: String? = null, right: View? = null, titleColor: Int = C.TXT, onClick: (() -> Unit)? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(14), dp(13))
            minimumHeight = dp(56)
            if (iconName != null) addView(icon(iconName, C.SUB, 22).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, titleColor, 500))
                if (sub != null) addView(text(sub, 12.5f, C.SUB), lp(top = dp(2)))
            }, lp(0, -2, 1f))
            if (right != null) addView(right, lp(-2, -2, left = dp(10)))
            if (onClick != null) { background = pressable(0x00000000, 0); setOnClickListener { onClick() } }
        }

    private fun group(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(C.CARD, dp(22).toFloat(), dp(1), C.LINE)
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(divider(), lp(-1, dp(1), left = dp(16)))
            addView(r)
        }
    }

    private fun statusIcon(status: String) = FrameLayout(this).apply {
        val (name, col) = when (status) { "done" -> "check" to C.OK; "stopped" -> "stop" to C.MUTE; else -> "close" to C.BAD }
        background = round((col and 0x00FFFFFF) or 0x24000000, dp(15).toFloat())
        addView(icon(name, col, if (name == "stop") 13 else 16), FrameLayout.LayoutParams(dp(if (name == "stop") 13 else 16), dp(if (name == "stop") 13 else 16), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
    }

    private fun statusText(status: String) = when (status) { "done" -> "Done"; "stopped" -> "Stopped"; else -> "Didn't finish" }

    private fun timeText(t: Long) = DateFormat.getTimeFormat(this).format(Date(t))

    private fun dayText(t: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(d, today) -> "Today"
            same(d, y) -> "Yesterday"
            d.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH).format(Date(t))
            else -> SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).format(Date(t))
        }
    }

    private fun runRow(r: RunInfo, showDay: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(10), dp(12))
        background = pressable(0x00000000, 0)
        addView(statusIcon(r.status))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(r.task.ifBlank { "(no task)" }, 15f, C.TXT, 500, 2))
            val whenText = (if (showDay) dayText(r.startedAt) + ", " else "") + timeText(r.startedAt)
            addView(text("$whenText · ${r.steps} steps · ${duration(r.seconds)} · ${compact(r.tokens)} tokens", 12.5f, C.SUB, 400, 1), lp(top = dp(3)))
        }, lp(0, -2, 1f, left = dp(12)))
        addView(icon("right", C.MUTE, 18))
        setOnClickListener { detail = r; render() }
    }

    // ---------------------------------------------------------------- home

    private fun ready() = AgentService.instance != null && Auth.signedIn

    private fun homeScreen() = page {
        val running = AgentService.instance?.agent
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Phone Agent", 28f, C.TXT, 700), lp(0, -2, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(12), dp(5))
                background = pressable(0x12FFFFFF, 16)
                addView(FrameLayout(this@MainActivity).apply {
                    background = round(if (Auth.signedIn) 0xFF10A37F.toInt() else C.RAISED, dp(12).toFloat())
                    addView(icon("bolt", 0xFFFFFFFF.toInt(), 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(if (Auth.signedIn) "ChatGPT" else "Signed out", 13f, C.TXT, 600), lp(-2, -2, left = dp(7)))
                setOnClickListener { go(Tab.SETTINGS) }
            })
        }, lp(top = dp(10)))

        val status = when {
            running != null -> "Running: ${running.task}" to C.ACCENT
            ready() -> "Ready · accessibility on" to C.OK
            else -> {
                val left = listOf(AgentService.instance == null, !Auth.signedIn).count { it }
                (if (left == 1) "One step left before the first task" else "Two quick steps before the first task") to C.WARN
            }
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(StatusDot(this@MainActivity).apply { color = status.second; pulse = running != null }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(text(status.first, 13.5f, status.second, 500, 1), lp(-2, -2, left = dp(6)))
        }, lp(top = dp(4)))

        if (!ready()) addView(setupCard(), lp(top = dp(16)))
        addView(askCard(running != null), lp(top = dp(16)))

        if (running == null) {
            addView(text("Try", 13f, C.SUB, 600), lp(top = dp(18), bottom = dp(8), left = dp(4)))
            val tries = listOf("What Android version do I have?", "Set an alarm for 6:45 tomorrow", "Turn on dark mode", "Find the weather in my city")
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    tries.forEachIndexed { i, t ->
                        addView(chip(t) { taskField?.setText(t); taskField?.setSelection(t.length); Prefs.task = t }, lp(-2, -2, left = if (i > 0) dp(8) else 0))
                    }
                })
            }, lp(-1, -2))
        }

        val runs = Runs.list(this@MainActivity)
        val weekAgo = startOfDay(System.currentTimeMillis()) - 6 * DAY
        val week = runs.filter { it.startedAt >= weekAgo }
        section("Recent")
        if (runs.isEmpty()) {
            addView(group(row(null, "No runs yet", "Your finished tasks show up here, with every step the agent took.")))
        } else {
            val rows = runs.take(3).map { runRow(it, showDay = true) as View }.toMutableList()
            rows += row("chart", "This week: ${week.size} ${if (week.size == 1) "task" else "tasks"} · ${compact(week.sumOf { it.tokens })} tokens",
                null, icon("right", C.MUTE, 18)) { go(Tab.USAGE) }
            rows += row("runs", "See all ${runs.size} runs", null, icon("right", C.MUTE, 18), C.ACCENT) { go(Tab.RUNS) }
            addView(group(*rows.toTypedArray()))
        }
    }

    private fun setupCard() = card().apply {
        addView(text("Finish setup", 17f, C.TXT, 650))
        addView(text("Phone Agent needs one system switch and your ChatGPT account.", 13f, C.SUB), lp(top = dp(2), bottom = dp(4)))
        val a11y = AgentService.instance != null
        addView(setupStep(1, a11y, "Turn on accessibility", "Lets the agent see the screen and tap for you.", "Turn on") { openAccessibilitySettings() })
        if (!a11y) {
            addView(text("Find Phone Agent in the list (on some phones under \"Downloaded apps\" or \"Installed apps\") and switch it on." +
                if (Build.VERSION.SDK_INT >= 33) " Greyed out or \"Restricted setting\"? Open App info → ⋮ → \"Allow restricted settings\" first." else "",
                12.5f, C.MUTE), lp(top = dp(2), left = dp(40)))
            if (Build.VERSION.SDK_INT >= 33) addView(text("Open App info", 13f, C.ACCENT, 600).apply {
                setPadding(0, dp(8), 0, dp(4)); setOnClickListener { openAppInfo() }
            }, lp(-2, -2, left = dp(40)))
        }
        addView(setupStep(2, Auth.signedIn, "Sign in with ChatGPT", "Tasks run on your ChatGPT plan. No API key needed.", "Sign in") { startLogin(true) })
        if (!Voice.hasPermission(this@MainActivity)) {
            addView(setupStep(3, false, "Allow the microphone (optional)", "Talk to the agent instead of typing.", "Allow") { requestMic(false) })
        }
    }

    private fun setupStep(n: Int, done: Boolean, title: String, sub: String, button: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(FrameLayout(this@MainActivity).apply {
            background = round(if (done) 0x243FD68B else 0x14FFFFFF, dp(14).toFloat())
            if (done) addView(icon("check", C.OK, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            else addView(text("$n", 13f, C.TXT, 650).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, if (done) C.SUB else C.TXT, 600))
            addView(text(sub, 12.5f, C.SUB))
        }, lp(0, -2, 1f, left = dp(12)))
        if (!done) addView(secondaryButton(button, color = C.ACCENT) { onClick() }.apply { minimumHeight = dp(38); setPadding(dp(14), 0, dp(14), 0) })
    }

    private fun askCard(running: Boolean) = card().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("What should I do?", 20f, C.TXT, 650))
                addView(text("Speak or type a task. You can steer while it works.", 13f, C.SUB), lp(top = dp(2)))
            }, lp(0, -2, 1f))
        })
        val field = EditText(this@MainActivity).apply {
            setText(Prefs.task)
            hint = "e.g. Set an alarm for 6:45 tomorrow"
            textSize = 16f
            setTextColor(C.TXT)
            setHintTextColor(C.MUTE)
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(C.FIELD, dp(18).toFloat(), dp(1), C.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.task = s?.toString() ?: "" }
            })
        }
        taskField = field
        addView(field, lp(top = dp(14)))

        addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip(Prefs.modelName(Prefs.currentModel()), "spark") { pickModel() })
                addView(chip("Effort: ${Prefs.effort}") { pickEffort() }, lp(-2, -2, left = dp(8)))
                addView(chip(if (Prefs.askFirst) "Ask before sending" else "Doesn't ask first", "shield", Prefs.askFirst) {
                    Prefs.askFirst = !Prefs.askFirst; render()
                }, lp(-2, -2, left = dp(8)))
            })
        }, lp(top = dp(12)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val mic = iconButton("mic", C.TXT, 0x1AFFFFFF, sizeDp = 52, iconDp = 24) { toggleListening() }.apply { contentDescription = "Speak a task" }
            micButton = mic
            addView(mic)
            if (running) {
                addView(secondaryButton("Stop the agent", "stop", C.BAD) { AgentService.instance?.stopAgent() }.apply { minimumHeight = dp(52) },
                    lp(0, -2, 1f, left = dp(10)))
            } else {
                addView(primaryButton("Start", "play") { start() }, lp(0, -2, 1f, left = dp(10)))
            }
        }, lp(top = dp(14)))
    }

    private fun toggleListening() {
        val v = voice ?: Voice(this,
            onPartial = { t -> taskField?.setText(t); taskField?.setSelection(t.length) },
            onFinal = { t -> taskField?.setText(t); taskField?.setSelection(t.length); Prefs.task = t },
            onEnd = { err ->
                micButton?.let { it.setImageDrawable(Icon("mic", C.TXT)); it.background = pressable(0x1AFFFFFF, 26) }
                if (err != null && err != "cancelled") toast(err)
            },
        ).also { voice = it }
        if (v.active) { v.stop(); return }
        if (!Voice.hasPermission(this)) { requestMic(true); return }
        micButton?.let { it.setImageDrawable(Icon("mic", 0xFFFFFFFF.toInt())); it.background = pressable(C.BAD, 26) }
        taskField?.hint = "Listening…"
        v.start(Prefs.voiceLang)
    }

    private fun requestMic(thenListen: Boolean) {
        listenAfterGrant = thenListen
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        render()
        if (Voice.hasPermission(this)) { if (listenAfterGrant && tab == Tab.HOME) toggleListening() }
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            toast("Microphone is blocked. Allow it in App info → Permissions.")
            openAppInfo()
        }
        listenAfterGrant = false
    }

    // ---------------------------------------------------------------- runs

    private fun runsScreen() = page {
        val all = Runs.list(this@MainActivity)
        val done = all.count { it.done }
        title("Runs", if (all.isEmpty()) "Every finished task, step by step" else "${all.size} runs · $done done")
        val filters = listOf("all" to "All", "done" to "Done", "failed" to "Didn't finish", "stopped" to "Stopped")
        addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                filters.forEachIndexed { i, (k, l) ->
                    val n = if (k == "all") all.size else all.count { it.status == k }
                    addView(chip("$l  $n", selected = runsFilter == k) { runsFilter = k; render() }, lp(-2, -2, left = if (i > 0) dp(8) else 0))
                }
            })
        }, lp(top = dp(14)))
        val shown = if (runsFilter == "all") all else all.filter { it.status == runsFilter }
        if (shown.isEmpty()) {
            addView(text(if (all.isEmpty()) "No runs yet. Start a task on Home; it shows up here with every step." else "Nothing here.", 14f, C.SUB),
                lp(top = dp(28), left = dp(4)))
            return@page
        }
        for ((day, runs) in shown.groupBy { dayText(it.startedAt) }) {
            section(day)
            addView(group(*runs.map { runRow(it) as View }.toTypedArray()))
        }
    }

    private fun detailScreen(r: RunInfo) = page {
        topBar("Run", iconButton("trash", C.SUB, 0x00000000, sizeDp = 42, iconDp = 22) {
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete this run?")
                .setMessage("Its log and screenshots are removed from this phone.")
                .setPositiveButton("Delete") { _, _ -> Runs.delete(r); detail = null; render() }
                .setNegativeButton("Cancel", null).show()
        })
        addView(text(r.task, 22f, C.TXT, 650), lp(top = dp(6)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusIcon(r.status).apply { layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) })
            addView(text(statusText(r.status), 14f, C.TXT, 600), lp(-2, -2, left = dp(8)))
            addView(text("  ·  ${dayText(r.startedAt)}, ${timeText(r.startedAt)}", 13.5f, C.SUB, 400, 1))
        }, lp(top = dp(10)))

        if (r.answer.isNotBlank()) addView(card().apply {
            addView(text(if (r.done) "Answer" else "What happened", 13f, C.SUB, 600))
            addView(text(r.answer, 15.5f, C.TXT).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }, lp(top = dp(6)))
        }, lp(top = dp(16)))

        val stats = listOf(
            "Steps" to "${r.steps}",
            "Duration" to duration(r.seconds),
            "Tokens" to compact(r.tokens),
            "AI time" to duration(r.llmSeconds),
            "Model" to Prefs.modelName(r.model.ifEmpty { null }),
            "Screen reading" to (Mode.entries.firstOrNull { it.key == r.mode }?.key?.replaceFirstChar { it.uppercase() } ?: r.mode) + if (r.effort.isNotEmpty()) " · ${r.effort}" else "",
        )
        addView(card().apply {
            for (pair in stats.chunked(2)) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for ((k, v) in pair) addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(v, 17f, C.TXT, 650, 1))
                        addView(text(k, 12.5f, C.SUB))
                    }, lp(0, -2, 1f))
                }, lp(top = dp(6), bottom = dp(4)))
            }
        }, lp(top = dp(12)))

        val events = Runs.timeline(r)
        if (events.isNotEmpty()) {
            section("What it did")
            addView(card(14).apply {
                for (e in events) addView(timelineRow(e), lp(top = dp(6), bottom = dp(4)))
            })
        }

        Runs.lastScreen(r)?.let { f ->
            val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
            if (bmp != null) {
                section("Last screen")
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = round(C.CARD, dp(18).toFloat())
                    clipToOutline = true
                    contentDescription = "Screenshot of the last screen of this run"
                }, lp(dp(220), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
        }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("Copy answer") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("answer", r.answer))
                toast("Copied")
            }, lp(0, -2, 1f))
            addView(primaryButton("Run again", "replay") { Prefs.task = r.task; detail = null; tab = Tab.HOME; render(); start() }, lp(0, -2, 1f, left = dp(10)))
        }, lp(top = dp(22)))
    }

    private fun timelineRow(e: RunEvent) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val (ic, col) = when (e.kind) {
            "user" -> "user" to C.ACCENT
            "question" -> "info" to C.WARN
            "confirm" -> "shield" to (if (e.ok) C.OK else C.WARN)
            else -> (if (e.ok) "check" else "close") to (if (e.ok) C.OK else C.BAD)
        }
        addView(text(if (e.step > 0) "${e.step}" else "", 12f, C.MUTE, 600).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(18), -2).apply { topMargin = dp(2) })
        addView(icon(ic, col, 16).apply { (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(2) } })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            val main = when (e.kind) {
                "user" -> "You: “${e.text}”"
                "question" -> "Asked: ${e.text}"
                "confirm" -> "Asked for OK: ${e.text}"
                else -> e.text
            }
            addView(text(main, 14f, C.TXT))
            val sub = when (e.kind) {
                "user" -> e.sub
                "question" -> "You answered: ${e.sub}"
                "confirm" -> "You ${e.sub}"
                else -> e.sub
            }
            if (sub.isNotBlank()) addView(text(sub, 12.5f, C.SUB), lp(top = dp(1)))
        }, lp(0, -2, 1f))
    }

    // ---------------------------------------------------------------- usage

    private fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun usageScreen() = page {
        title("Usage", "Counted on this phone, from every run")
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = round(0x12FFFFFF, dp(20).toFloat())
            for ((d, l) in listOf(7 to "7 days", 30 to "30 days", 0 to "All time")) {
                val on = usageDays == d
                addView(text(l, 13.5f, if (on) C.TXT else C.SUB, if (on) 650 else 500).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(8))
                    background = if (on) round(C.RAISED, dp(17).toFloat()) else null
                    setOnClickListener { usageDays = d; render() }
                }, lp(0, -2, 1f))
            }
        }, lp(top = dp(16)))

        val all = Runs.list(this@MainActivity)
        val today = startOfDay(System.currentTimeMillis())
        val first = all.minOfOrNull { startOfDay(it.startedAt) } ?: today
        val days = if (usageDays > 0) usageDays else (((today - first) / DAY).toInt() + 1).coerceIn(7, 90)
        val from = today - (days - 1) * DAY
        val sel = if (usageDays > 0) all.filter { it.startedAt >= from } else all
        val tokens = sel.sumOf { it.tokens }
        val done = sel.count { it.done }

        addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((v, l) in listOf(
                    "${sel.size}" to "tasks · $done done",
                    compact(tokens) to "tokens",
                    duration(sel.sumOf { it.seconds }) to "agent time",
                )) addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(v, 24f, C.TXT, 700, 1))
                    addView(text(l, 12.5f, C.SUB, 400, 1))
                }, lp(0, -2, 1f))
            }, lp(top = dp(4)))

            addView(text("Tokens per day", 14f, C.TXT, 600), lp(top = dp(20)))
            addView(text(if (usageDays == 0 && days == 90 && all.isNotEmpty()) "Last 90 days · tap a column" else "Tap a column for its value", 12f, C.MUTE), lp(top = dp(1)))
            val perDay = LongArray(days)
            for (r in all) {
                val i = ((startOfDay(r.startedAt) - from) / DAY).toInt()
                if (i in 0 until days) perDay[i] += r.tokens
            }
            val fmt = SimpleDateFormat("MMM d", Locale.ENGLISH)
            val wd = SimpleDateFormat("EEEEE", Locale.ENGLISH)
            val names = (0 until days).map { fmt.format(Date(from + it * DAY)) }
            val ticks = if (days <= 7) (0 until days).associateWith { wd.format(Date(from + it * DAY)) }
            else mapOf(0 to names.first(), days / 2 to names[days / 2], days - 1 to "Today")
            addView(BarChart(this@MainActivity).apply { set(perDay.toList(), names, ticks, "tokens") }, lp(top = dp(10)))
        }, lp(top = dp(14)))

        if (sel.isNotEmpty()) {
            val n = sel.size.toDouble()
            section("Per task")
            addView(group(
                row(null, "Average tokens", "${compact((sel.sumOf { it.inTok } / n).toLong())} in · ${compact((sel.sumOf { it.outTok } / n).toLong())} out",
                    text(compact((tokens / n).toLong()), 15f, C.TXT, 650)),
                row(null, "Average time", "AI thinking: ${duration(sel.sumOf { it.llmSeconds } / n)}", text(duration(sel.sumOf { it.seconds } / n), 15f, C.TXT, 650)),
                row(null, "Average steps", null, text("%.1f".format(sel.sumOf { it.steps } / n), 15f, C.TXT, 650)),
                row(null, "Finished the task", "Done ${done} · didn't finish ${sel.count { it.status == "failed" }} · stopped ${sel.count { it.status == "stopped" }}",
                    text("${(done * 100 / sel.size)}%", 15f, C.TXT, 650)),
            ))

            section("By model")
            val byModel = sel.groupBy { it.model }.entries.sortedByDescending { e -> e.value.sumOf { it.tokens } }
            addView(group(*byModel.map { (m, rs) ->
                row(null, Prefs.modelName(m.ifEmpty { null }), "${rs.size} ${if (rs.size == 1) "task" else "tasks"}",
                    text(compact(rs.sumOf { it.tokens }) + " tokens", 14f, C.TXT, 600)) as View
            }.toTypedArray()))
        }

        section("Plan limits")
        addView(group(
            row("info", "Your ChatGPT plan sets the limits", "Requests count against your plan's usage, like chats in ChatGPT. OpenAI shows the remaining limits there."),
            row("open", "Open usage on chatgpt.com", null, null, C.ACCENT) { openUrl(Auth.USAGE_URL) },
        ))
    }

    // ---------------------------------------------------------------- settings

    private fun switch(on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = on
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun settingsScreen() = page {
        title("Settings")
        val svc = AgentService.instance

        section("Account")
        addView(group(
            row("user", if (Auth.signedIn) Auth.email ?: "Signed in" else "Not signed in",
                if (Auth.signedIn) (if (Auth.planEnabled) "Sign in with ChatGPT · runs on your plan" else "Signed in, but plan usage was not allowed")
                else "Sign in with ChatGPT to use your plan. No API key needed."),
            if (Auth.signedIn) row("open", "Usage limits on chatgpt.com", null, null) { openUrl(Auth.USAGE_URL) }
            else row("bolt", "Continue with ChatGPT", null, null, C.ACCENT) { startLogin(true) },
            *(if (Auth.signedIn) arrayOf<View>(row(null, "Sign out", null, null, C.BAD) { logout() }) else emptyArray()),
        ))

        section("Permissions")
        val rows = mutableListOf<View>(
            row("touch", "Accessibility", if (svc != null) "On · the agent can see and use the screen" else "Off · needed to see the screen and tap",
                text(if (svc != null) "On" else "Turn on", 14f, if (svc != null) C.OK else C.ACCENT, 600)) { openAccessibilitySettings() },
            row("mic", "Microphone", "To give tasks and corrections by voice",
                text(if (Voice.hasPermission(this@MainActivity)) "Allowed" else "Allow", 14f, if (Voice.hasPermission(this@MainActivity)) C.OK else C.ACCENT, 600)) {
                if (Voice.hasPermission(this@MainActivity)) openAppInfo() else requestMic(false)
            },
        )
        if (svc == null && Build.VERSION.SDK_INT >= 33) rows += row("info", "Switch greyed out?",
            "Apps installed outside the Play Store need App info → ⋮ → \"Allow restricted settings\" first.", icon("open", C.SUB, 18)) { openAppInfo() }
        addView(group(*rows.toTypedArray()))

        section("Agent")
        addView(group(
            row("spark", "Model", Prefs.modelName(Prefs.currentModel()), icon("right", C.MUTE, 18)) { pickModel() },
            row(null, "Reasoning effort", "${Prefs.effort.replaceFirstChar { it.uppercase() }} · higher is slower and uses more of your plan", icon("right", C.MUTE, 18)) { pickEffort() },
            row(null, "Screen reading", Prefs.mode.label, icon("right", C.MUTE, 18)) { pickMode() },
            row("shield", "Ask before sending, paying or deleting", "Shows exactly what will happen and waits for your OK",
                switch(Prefs.askFirst) { Prefs.askFirst = it }),
            row("touch", "Wait while I touch the screen", "Tap anything yourself; the agent pauses and then continues from there",
                switch(Prefs.touchWait) { Prefs.touchWait = it }),
            row("mic", "Voice language", Voice.LANGUAGES.firstOrNull { it.first == Prefs.voiceLang }?.second ?: Prefs.voiceLang, icon("right", C.MUTE, 18)) { pickVoice() },
        ))

        section("Data")
        val runs = Runs.list(this@MainActivity)
        val mb = Runs.sizeBytes(this@MainActivity) / 1_000_000.0
        addView(group(
            row("runs", "Run history", "${runs.size} runs · ${"%.1f".format(mb)} MB on this phone. Screenshots are kept for the last 20 runs."),
            row("trash", "Clear run history", null, null, C.BAD) {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Clear run history?")
                    .setMessage("All runs, logs and screenshots are deleted from this phone. Usage numbers start from zero.")
                    .setPositiveButton("Clear") { _, _ -> Runs.clear(this@MainActivity); render() }
                    .setNegativeButton("Cancel", null).show()
            },
            row(null, "Debug log", "What the agent and the app did, line by line", icon("right", C.MUTE, 18)) { showLog = true; render() },
        ))

        section("About")
        addView(group(
            row(null, "Version", BuildConfig.VERSION_NAME),
            row("open", "Source code", "github.com/ezzcodeezzlife/phone-agent", null) { openUrl("https://github.com/ezzcodeezzlife/phone-agent") },
        ))
    }

    private fun logScreen(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
            topBar("Debug log", iconButton("trash", C.SUB, 0x00000000, sizeDp = 42, iconDp = 22) { logView?.text = "" })
        }
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(C.SUB)
            setTextIsSelectable(true)
            text = AgentBus.all().takeLast(400).joinToString("\n")
        }
        logView = tv
        val scroll = ScrollView(this).apply { addView(tv); post { fullScroll(View.FOCUS_DOWN) } }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    // ---------------------------------------------------------------- pickers

    private fun pick(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { d, i -> d.dismiss(); onPick(i); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickModel() {
        val models = Prefs.models()
        if (models.isEmpty()) {
            if (Auth.signedIn) { toast("Loading models…"); fetchModels() } else toast("Sign in with ChatGPT first")
            return
        }
        pick("Model", models.map { "${it.name}  (${it.slug})" }, models.indexOfFirst { it.slug == Prefs.currentModel() }) { Prefs.model = models[it].slug }
    }

    private val efforts = listOf("low", "medium", "high")

    private fun pickEffort() = pick("Reasoning effort", efforts.map { it.replaceFirstChar { c -> c.uppercase() } }, efforts.indexOf(Prefs.effort)) { Prefs.effort = efforts[it] }

    private fun pickMode() = pick("Screen reading", Mode.entries.map { it.label }, Mode.entries.indexOf(Prefs.mode)) { Prefs.mode = Mode.entries[it] }

    private fun pickVoice() = pick("Voice language", Voice.LANGUAGES.map { it.second }, Voice.LANGUAGES.indexOfFirst { it.first == Prefs.voiceLang }) {
        Prefs.voiceLang = Voice.LANGUAGES[it].first
    }

    // ---------------------------------------------------------------- actions

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("No browser found") }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /**
     * Android has no public intent that opens one service's switch: ACCESSIBILITY_DETAILS_SETTINGS needs
     * a permission only system apps and app stores hold (checked through SDK 37). The next best thing is
     * the accessibility list with our entry as the highlight key; AOSP-based Settings scroll to it and flash it.
     */
    private fun openAccessibilitySettings() {
        val key = ComponentName(this, AgentService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        try { startActivity(intent) } catch (_: Exception) { toast("Accessibility settings not found") }
    }

    private fun fetchModels() {
        Thread {
            try {
                val list = OpenAI.listModels()
                Prefs.saveModels(list)
                AgentBus.log("Models: " + list.joinToString(", ") { it.slug })
                main.post { render() }
            } catch (e: Exception) {
                AgentBus.log("Loading models failed: ${e.message}")
            }
        }.start()
    }

    private fun startLogin(openBrowser: Boolean) {
        toast("Confirm the sign-in in your browser")
        SignInService.start(this)
        Auth.startLogin(
            onUrl = { url ->
                // Debug builds log the URL so the sign-in can also be done in a desktop browser (adb forward tcp:1455 tcp:1455).
                if (BuildConfig.DEBUG && !url.contains("id_token_hint")) Log.i("PhoneAgent", "AUTH_URL $url")
                if (openBrowser) main.post { openUrl(url) }
            },
            onDone = { err ->
                if (err != null) AgentBus.log("Sign-in failed: $err") else AgentBus.log("Signed in as ${Auth.email}")
                main.post {
                    SignInService.stop()
                    render()
                    if (err == null) fetchModels() else toast(err)
                }
            },
        )
    }

    private fun logout() {
        Auth.logout { main.post { AgentBus.log("Signed out"); render() } }
    }

    private fun start() {
        val svc = AgentService.instance ?: return toast("Turn on accessibility first")
        if (!Auth.signedIn) return toast("Sign in with ChatGPT first")
        val task = Prefs.task.trim()
        if (task.isEmpty()) return toast("Say or type a task first")
        val model = Prefs.currentModel() ?: return toast("No model loaded yet").also { fetchModels() }
        AgentBus.lastAnswer = null
        if (!svc.startAgent(task, Prefs.mode, model, Prefs.effort)) toast("The agent is already running")
        render()
    }

    /** Debug hooks so runs can be started from adb: am start -n dev.phoneagent/.MainActivity --es task "…" --es mode tree --ez run true */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("grant_mic", false)) { i.removeExtra("grant_mic"); requestMic(false) }
        if (!BuildConfig.DEBUG) return
        i.getStringExtra("task")?.let { Prefs.task = it }
        i.getStringExtra("mode")?.let { Prefs.mode = Mode.of(it) }
        i.getStringExtra("effort")?.let { if (it in efforts) Prefs.effort = it }
        i.getStringExtra("model")?.let { Prefs.model = it }
        if (i.hasExtra("ask_first")) Prefs.askFirst = i.getBooleanExtra("ask_first", true)
        i.getStringExtra("tab")?.let { t -> Tab.entries.firstOrNull { it.name.equals(t, true) }?.let { tab = it; detail = null; showLog = false } }
        if (i.getBooleanExtra("open_last_run", false)) detail = Runs.list(this).firstOrNull()
        render()
        if (i.getBooleanExtra("login", false)) startLogin(openBrowser = !i.getBooleanExtra("no_browser", false))
        if (i.getBooleanExtra("run", false)) main.postDelayed({ start() }, 800)
        i.removeExtra("run")
        i.removeExtra("login")
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
