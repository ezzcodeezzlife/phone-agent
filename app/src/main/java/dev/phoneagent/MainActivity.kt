package dev.phoneagent

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var a11yText: TextView
    private lateinit var a11yBtn: Button
    private lateinit var accountText: TextView
    private lateinit var loginBtn: Button
    private lateinit var usageLink: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var effortSpinner: Spinner
    private lateinit var modeGroup: RadioGroup
    private val modeButtons = LinkedHashMap<Mode, RadioButton>()
    private lateinit var taskEdit: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var answerText: TextView
    private lateinit var logText: TextView
    private var models: List<OpenAI.Model> = emptyList()
    private val efforts = listOf("low", "medium", "high")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContentView(buildUi())
        loadCachedModels()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AgentBus.listener = { line -> main.post { appendLog(line) } }
        AgentBus.statusListener = { main.post { refresh() } }
        logText.text = AgentBus.all().takeLast(200).joinToString("\n")
        refresh()
        if (Auth.signedIn && models.isEmpty()) fetchModels()
    }

    override fun onPause() {
        super.onPause()
        AgentBus.listener = null
        AgentBus.statusListener = null
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.rgb(32, 33, 36)) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun section(title: String, parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        box.addView(label(title, 13f, true, Color.rgb(95, 99, 104)))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return box
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        col.addView(label("Phone Agent", 24f, true))
        col.addView(label("An AI agent that operates your phone (prototype)", 13f, color = Color.rgb(95, 99, 104)))

        // 1) The one switch
        val s1 = section("1 · Accessibility", col)
        a11yText = label("")
        a11yBtn = button("Turn on accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        s1.addView(a11yText); s1.addView(a11yBtn)

        // 2) Sign in with ChatGPT
        val s2 = section("2 · ChatGPT account", col)
        accountText = label("")
        loginBtn = button("Continue with ChatGPT") { if (Auth.signedIn) logout() else startLogin(openBrowser = true) }
        usageLink = label("Using ChatGPT plan · Manage usage", 13f, color = Color.rgb(26, 115, 232)).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Auth.USAGE_URL))) }
        }
        s2.addView(accountText); s2.addView(loginBtn); s2.addView(usageLink)

        // 3) Model + mode
        val s3 = section("3 · Model & mode", col)
        modelSpinner = Spinner(this)
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                models.getOrNull(pos)?.let { prefs.edit().putString("model", it.slug).apply() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        effortSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, efforts.map { "Reasoning: $it" })
            setSelection(efforts.indexOf(prefs.getString("effort", "low")).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.edit().putString("effort", efforts[pos]).apply() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        modeGroup = RadioGroup(this)
        for (m in Mode.entries) {
            val rb = RadioButton(this).apply { text = m.label; id = View.generateViewId() }
            modeButtons[m] = rb
            modeGroup.addView(rb)
        }
        modeButtons[Mode.of(prefs.getString("mode", null))]?.isChecked = true
        modeGroup.setOnCheckedChangeListener { _, _ -> prefs.edit().putString("mode", currentMode().key).apply() }
        s3.addView(modelSpinner); s3.addView(effortSpinner); s3.addView(modeGroup)

        // 4) Task
        val s4 = section("4 · Task", col)
        taskEdit = EditText(this).apply {
            hint = "e.g. \"Add 'Dentist' to my calendar tomorrow at 3 pm\""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setText(prefs.getString("task", ""))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startBtn = button("▶ Start") { start() }
        stopBtn = button("■ Stop") { AgentService.instance?.stopAgent() }
        row.addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f))
        answerText = label("", 15f, true, Color.rgb(24, 128, 56))
        s4.addView(taskEdit); s4.addView(row); s4.addView(answerText)

        // Log
        val s5 = section("Log", col)
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 10.5f; setTextColor(Color.rgb(60, 64, 67)); setTextIsSelectable(true)
        }
        s5.addView(logText)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(241, 243, 244))
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        return scroll
    }

    private fun appendLog(line: String) {
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        if (logText.lineCount > 400) logText.text = AgentBus.all().takeLast(200).joinToString("\n")
    }

    private fun refresh() {
        val svc = AgentService.instance
        a11yText.text = if (svc != null) "✓ On: the agent can read and operate the screen." else "Off. Switch on \"Phone Agent\" once under Accessibility."
        a11yBtn.visibility = if (svc != null) View.GONE else View.VISIBLE
        if (Auth.signedIn) {
            accountText.text = "✓ Signed in as ${Auth.email ?: "?"}" + if (Auth.planEnabled) "" else " (plan usage not allowed)"
            loginBtn.text = "Sign out"
            usageLink.visibility = View.VISIBLE
        } else {
            accountText.text = "Sign in with your ChatGPT account. Requests then run on your ChatGPT plan (Plus/Pro); no API key needed."
            loginBtn.text = "Continue with ChatGPT"
            usageLink.visibility = View.GONE
        }
        val running = svc?.agent != null
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
        answerText.text = AgentBus.lastAnswer?.let { "Result: $it" } ?: ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun currentMode(): Mode = modeButtons.entries.firstOrNull { it.value.isChecked }?.key ?: Mode.AUTO

    // ---------------------------------------------------------------- models

    private fun loadCachedModels() {
        val cached = prefs.getString("models", null) ?: return
        try {
            val arr = JSONArray(cached)
            setModels((0 until arr.length()).map { arr.getJSONObject(it).let { o -> OpenAI.Model(o.getString("slug"), o.getString("name")) } })
        } catch (_: Exception) {}
    }

    private fun fetchModels() {
        Thread {
            try {
                val list = OpenAI.listModels()
                val arr = JSONArray()
                list.forEach { arr.put(JSONObject().put("slug", it.slug).put("name", it.name)) }
                prefs.edit().putString("models", arr.toString()).apply()
                AgentBus.log("Models: " + list.joinToString(", ") { it.slug })
                main.post { setModels(list) }
            } catch (e: Exception) {
                AgentBus.log("Loading models failed: ${e.message}")
            }
        }.start()
    }

    private fun setModels(list: List<OpenAI.Model>) {
        models = list
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { "${it.name}  (${it.slug})" })
        val saved = prefs.getString("model", null)
        val idx = list.indexOfFirst { it.slug == saved }
        if (idx >= 0) modelSpinner.setSelection(idx)
    }

    private fun currentModel(): String? = prefs.getString("model", null) ?: models.getOrNull(modelSpinner.selectedItemPosition)?.slug

    // ---------------------------------------------------------------- actions

    private fun startLogin(openBrowser: Boolean) {
        accountText.text = "Signing in: please confirm in the browser…"
        Auth.startLogin(
            onUrl = { url ->
                // Debug builds log the URL so the sign-in can also be done in a desktop browser (adb forward tcp:1455 tcp:1455).
                if (BuildConfig.DEBUG && !url.contains("id_token_hint")) Log.i("PhoneAgent", "AUTH_URL $url")
                if (openBrowser) main.post {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("No browser found") }
                }
            },
            onDone = { err ->
                if (err != null) AgentBus.log("Sign-in failed: $err") else AgentBus.log("Signed in as ${Auth.email}")
                main.post {
                    refresh()
                    if (err == null) fetchModels() else toast(err)
                }
            },
        )
    }

    private fun logout() {
        Auth.logout { main.post { AgentBus.log("Signed out"); refresh() } }
    }

    private fun start() {
        val svc = AgentService.instance ?: return toast("Turn on accessibility first")
        if (!Auth.signedIn) return toast("Sign in with ChatGPT first")
        val task = taskEdit.text.toString().trim()
        if (task.isEmpty()) return toast("Please enter a task")
        val model = currentModel() ?: return toast("No model loaded")
        prefs.edit().putString("task", task).putString("mode", currentMode().key).apply()
        AgentBus.lastAnswer = null
        if (!svc.startAgent(task, currentMode(), model, efforts[effortSpinner.selectedItemPosition])) toast("Agent is already running")
        refresh()
    }

    /** Debug hooks so runs can be started from adb: am start -n dev.phoneagent/.MainActivity --es task "…" --es mode tree --ez run true */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("show_answer", false) || i.data?.scheme == "phoneagent") refresh()
        if (!BuildConfig.DEBUG) return
        i.getStringExtra("task")?.let { taskEdit.setText(it) }
        i.getStringExtra("mode")?.let { modeButtons[Mode.of(it)]?.isChecked = true }
        i.getStringExtra("effort")?.let { e -> efforts.indexOf(e).takeIf { it >= 0 }?.let { effortSpinner.setSelection(it); prefs.edit().putString("effort", e).apply() } }
        i.getStringExtra("model")?.let { m -> prefs.edit().putString("model", m).apply(); models.indexOfFirst { it.slug == m }.takeIf { it >= 0 }?.let { modelSpinner.setSelection(it) } }
        if (i.getBooleanExtra("login", false)) startLogin(openBrowser = !i.getBooleanExtra("no_browser", false))
        if (i.getBooleanExtra("run", false)) main.postDelayed({ start() }, 800)
        i.removeExtra("run")
        i.removeExtra("login")
    }
}
