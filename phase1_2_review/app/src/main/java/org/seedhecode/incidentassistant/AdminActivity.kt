package org.seedhecode.incidentassistant

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import org.seedhecode.incidentassistant.data.*
import java.util.concurrent.Executors

/** Local admin sign-in, then the incidents each admin's role allows, as a list or on the zone map. */
class AdminActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var auth: AuthManager? = null
    private lateinit var status: TextView
    private lateinit var form: LinearLayout
    private lateinit var heading: TextView
    private lateinit var name: EditText
    private lateinit var password: EditText
    private lateinit var passwordAgain: EditText
    private lateinit var action: Button
    private lateinit var tabs: LinearLayout
    private lateinit var listTab: Button
    private lateinit var mapTab: Button
    private lateinit var list: LinearLayout
    private lateinit var mapPanel: LinearLayout
    private lateinit var floors: LinearLayout
    private lateinit var map: ZoneMapView
    private lateinit var mapNote: TextView
    private lateinit var details: LinearLayout
    private var settingUp = false

    private var session: AdminSession? = null
    private var data: MapData? = null
    private var showingMap = false
    private var floor: String? = null
    /** What the map's detail panel shows: one incident, one zone, or nothing yet. */
    private var selection: Selection? = null
    private sealed interface Selection {
        data class Incident(val incidentId: String) : Selection
        data class Zone(val zoneId: String) : Selection
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.background))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(value: String, size: Float, parent: LinearLayout = column) = TextView(this).apply {
            text = value; textSize = size; setTextColor(getColor(R.color.text_primary)); setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
        }
        fun field(hint: String, secret: Boolean, parent: LinearLayout) = EditText(this).apply {
            this.hint = hint; inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            parent.addView(this)
        }
        fun vertical(parent: LinearLayout = column) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this) }

        label("Admin", 28f)
        status = label("Opening local incident store…", 14f)
        form = vertical().apply { visibility = View.GONE }
        heading = label("", 18f, form)
        name = field("Admin name", false, form)
        password = field("Password", true, form)
        passwordAgain = field("Repeat password", true, form)
        action = Button(this).apply { isAllCaps = false; setOnClickListener { submitForm() }; form.addView(this) }

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; column.addView(this) }
        fun tab(text: String, map: Boolean) = Button(this).apply {
            this.text = text; isAllCaps = false; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { showingMap = map; render() }; tabs.addView(this)
        }
        listTab = tab("List", false)
        mapTab = tab("Map", true)
        list = vertical()
        mapPanel = vertical().apply { visibility = View.GONE }
        floors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapPanel.addView(HorizontalScrollView(this).apply { addView(floors) })
        map = ZoneMapView(this) { hit ->
            selection = when (hit) {
                is MapLayout.Hit.OnPin -> Selection.Incident(hit.incident.incidentId)
                is MapLayout.Hit.OnZone -> Selection.Zone(hit.zone.zoneId)
            }
            renderDetails()
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }; mapPanel.addView(this) }
        label("Zone colour = most urgent unresolved incident: red P1 · orange P2 · amber P3 · blue P4 · grey none. " +
            "Grey pins are resolved. Tap a pin or a zone for details.", 12f, mapPanel)
        mapNote = label("", 12f, mapPanel)
        details = vertical(mapPanel)

        background {
            val opened = AuthManager(IncidentStore.open(this))
            val needsSetup = !opened.hasAnyAdmin(TaxonomySeeder.ORG_ID)
            ui { auth = opened; showForm(needsSetup) }
        }
    }

    private fun showForm(setup: Boolean) {
        settingUp = setup
        form.visibility = View.VISIBLE
        heading.text = if (setup) "No admin exists on this device yet. Create the first one (organization-wide access)." else "Sign in"
        passwordAgain.visibility = if (setup) View.VISIBLE else View.GONE
        action.text = if (setup) "Create admin" else "Sign in"
        status.text = if (setup) "Passwords are stored as salted PBKDF2 hashes on this device only." else "Sign in with your admin name."
    }

    private fun submitForm() {
        val current = auth ?: return
        val user = name.text.toString().trim(); val secret = password.text.toString()
        if (settingUp && secret != passwordAgain.text.toString()) { status.text = "The passwords do not match."; return }
        action.isEnabled = false
        background {
            // PBKDF2 at 100k iterations is deliberately slow; keep it off the main thread.
            if (settingUp) current.createAdmin(TaxonomySeeder.ORG_ID, user, secret, "super_admin")
            val signedIn = current.loginAdmin(user, secret)
            val loaded = signedIn?.let(current::mapData)
            ui {
                action.isEnabled = true; password.setText(""); passwordAgain.setText("")
                if (signedIn == null || loaded == null) { status.text = "Name or password is incorrect."; return@ui }
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(form.windowToken, 0)
                form.visibility = View.GONE; tabs.visibility = View.VISIBLE
                session = signedIn; data = loaded; floor = MapLayout.defaultFloor(loaded)
                status.text = signedInText(signedIn, loaded)
                render()
            }
        }
    }

    private fun signedInText(admin: AdminSession, loaded: MapData) = "Signed in as ${admin.roleLevel.replace('_', ' ')}" +
        (admin.departmentId?.let { " for $it" } ?: ", all departments") + ". ${loaded.incidents.size} incident(s)."

    private fun render() {
        val admin = session ?: return
        val loaded = data ?: return
        listTab.isEnabled = showingMap; mapTab.isEnabled = !showingMap
        list.visibility = if (showingMap) View.GONE else View.VISIBLE
        mapPanel.visibility = if (showingMap) View.VISIBLE else View.GONE

        list.removeAllViews()
        if (loaded.incidents.isEmpty()) list.addView(note("No submitted incidents you can see yet."))
        loaded.incidents.forEach { list.addView(incidentCard(admin, it)) }

        floors.removeAllViews()
        for (option in MapLayout.floors(loaded)) floors.addView(Button(this).apply {
            text = option ?: "Other"; isAllCaps = false; isEnabled = option != floor
            setOnClickListener { floor = option; selection = null; render() }
        })
        map.show(loaded, floor)
        // Incidents without a resolved zone, or on a zone that cannot be drawn, are listed but get no pin.
        val drawable = loaded.zones.filter { it.drawable }.map { it.zoneId }.toSet()
        val unpinned = loaded.incidents.count { it.zoneId == null || it.zoneId !in drawable }
        mapNote.text = if (unpinned == 0) "" else "$unpinned incident(s) have no matched zone, so they are not on the map. See the List tab."
        mapNote.visibility = if (unpinned == 0) View.GONE else View.VISIBLE
        renderDetails()
    }

    private fun renderDetails() {
        val admin = session ?: return
        val loaded = data ?: return
        details.removeAllViews()
        when (val chosen = selection) {
            null -> details.addView(note("Tap a pin or a zone to see its incidents."))
            is Selection.Incident -> loaded.incidents.firstOrNull { it.incidentId == chosen.incidentId }
                ?.let { details.addView(incidentCard(admin, it)) } ?: details.addView(note("That incident is no longer visible."))
            is Selection.Zone -> {
                val zone = loaded.zones.firstOrNull { it.zoneId == chosen.zoneId } ?: return
                val here = loaded.incidents.filter { it.zoneId == zone.zoneId }
                details.addView(note(if (here.isEmpty()) "No incidents reported in ${zone.name}." else "${zone.name}: ${here.size} incident(s)"))
                here.forEach { details.addView(incidentCard(admin, it)) }
            }
        }
    }

    private fun note(value: String) = TextView(this).apply {
        text = value; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(8), 0, dp(8))
    }

    /** The same card in the list and in the map's detail panel. */
    private fun incidentCard(admin: AdminSession, incident: IncidentListing) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(12))
        setBackgroundResource(R.drawable.card_background)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        fun line(value: String, size: Float, secondary: Boolean = false) = addView(TextView(this@AdminActivity).apply {
            text = value; textSize = size; setPadding(0, 0, 0, dp(4))
            setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        })
        line("${incident.typeLabel} · ${incident.priority} · ${incident.status.wire}", 17f)
        line(incident.description, 15f)
        line("${incident.departmentId} · ${incident.zoneName ?: "Location not matched to a zone"} · ${incident.reportedAt}", 12f, true)
        if (incident.localSubmissionOnly) line("Saved on the reporter's device only; not delivered.", 12f, true)
        incident.status.next?.let { next ->
            addView(Button(this@AdminActivity).apply {
                text = "Mark ${next.wire}"; isAllCaps = false
                setOnClickListener { isEnabled = false; changeStatus(admin, incident, next) }
            })
        }
    }

    private fun changeStatus(admin: AdminSession, incident: IncidentListing, next: IncidentStatus) {
        val current = auth ?: return
        background {
            // A refused or stale change still reloads, so both views always show what is stored.
            val message = runCatching { current.updateIncidentStatus(admin, incident.incidentId, incident.status, next) }
                .fold({ "${it.typeLabel} is now ${it.status.wire}." }, { it.message ?: "Status change failed." })
            val reloaded = current.mapData(admin)
            ui { data = reloaded; render(); status.text = message }
        }
    }

    private fun background(task: () -> Unit) {
        io.execute { try { task() } catch (error: Exception) {
            ui { action.isEnabled = true; status.text = error.message ?: "Admin action failed." }
        } }
    }
    private fun ui(task: () -> Unit) { runOnUiThread { if (!isDestroyed && !isFinishing) task() } }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
