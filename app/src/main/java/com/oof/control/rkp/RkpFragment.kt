package com.oof.control.rkp

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.oof.control.R
import com.oof.control.utils.ShellExecutor
import com.oof.control.utils.TouchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen dialog for RKP (Remote Key Provisioning).
 *
 * Flow:
 * 1. User enters or loads a 64-hex CDI_Leaf seed
 * 2. User fills device config (or loads from current device / dev.pif)
 * 3. Tap "Provision" → generates keybox.xml → saves to /sdcard/OOF_RKP/
 * 4. Shows result with "Use this keybox" button → copies to Keylogger.zsl and triggers install
 */
class RkpFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    companion object {
        private const val TAG = "RkpFragment"
        private lateinit var RKP_DIR: File
        private lateinit var SEED_FILE: File
    }

    // UI refs (inflated manually — no view binding needed for this standalone fragment)
    private lateinit var etSeed: TextInputEditText
    private lateinit var etBrand: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etDevice: TextInputEditText
    private lateinit var etProduct: TextInputEditText
    private lateinit var etManufacturer: TextInputEditText
    private lateinit var etFingerprint: TextInputEditText
    private lateinit var etOsVersion: TextInputEditText
    private lateinit var etSystemPatch: TextInputEditText
    private lateinit var etBootPatch: TextInputEditText
    private lateinit var etVendorPatch: TextInputEditText
    private lateinit var btnLoadFromPif: MaterialButton
    private lateinit var btnProvision: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLastKeybox: TextView
    private lateinit var btnUseKeybox: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollView: ScrollView

    private lateinit var seedSection: LinearLayout
    private lateinit var configSection: LinearLayout
    private lateinit var etDeviceFingerprint: TextInputEditText
    private var useDeviceMode = true
    private var lastKeyboxPath: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        RKP_DIR = File(Environment.getExternalStorageDirectory(), "OOF_RKP")
        SEED_FILE = File(requireContext().filesDir, "rkp_seed")

        scrollView = ScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            setPadding(dp(24), dp(32), dp(24), dp(96))
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(color(R.color.background))
        }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(root)

        // ---- Header ----
        root.addView(sectionHeader("REMOTE KEY PROVISIONING"))
        root.addView(pageTitle("Keybox Generator"))
        root.addView(subtitle("Generate fresh attestation certificates from Google's RKP server."))

        // ---- Mode selector ----
        root.addView(sectionHeader("MODE", dp(16)))

        val modeGroup = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val rbDevice = android.widget.RadioButton(requireContext()).apply {
            text = "Device TEE"
            id = android.view.View.generateViewId()
        }
        val rbSeed = android.widget.RadioButton(requireContext()).apply {
            text = "Seed (Experimental)"
            id = android.view.View.generateViewId()
        }
        modeGroup.addView(rbDevice)
        modeGroup.addView(rbSeed)
        rbDevice.isChecked = true
        useDeviceMode = true
        root.addView(modeGroup)

        // ---- Seed section (hidden by default in device mode) ----
        seedSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        seedSection.addView(sectionHeader("SEED"))
        etSeed = addTextInput(seedSection, "CDI_Leaf Seed (64 hex chars)", singleLine = true)

        val btnGenerateSeed = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Generate Random Seed"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        btnGenerateSeed.setOnClickListener {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            etSeed.setText(hex)
            saveSeed(hex)
            toast("Generated new random seed")
        }
        seedSection.addView(btnGenerateSeed)
        root.addView(seedSection)

        loadSavedSeed()

        // ---- Device config section (hidden in device mode) ----
        configSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            useDeviceMode = checkedId == rbDevice.id
            seedSection.visibility = if (useDeviceMode) View.GONE else View.VISIBLE
            configSection.visibility = if (useDeviceMode) View.GONE else View.VISIBLE
        }

        // ---- Device config ----
        configSection.addView(sectionHeader("DEVICE CONFIG", dp(24)))

        btnLoadFromPif = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Load from dev.pif"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        configSection.addView(btnLoadFromPif)

        etBrand = addTextInput(configSection, "Brand")
        etManufacturer = addTextInput(configSection, "Manufacturer")
        etModel = addTextInput(configSection, "Model")
        etDevice = addTextInput(configSection, "Device")
        etProduct = addTextInput(configSection, "Product")
        etFingerprint = addTextInput(configSection, "Build Fingerprint")
        etOsVersion = addTextInput(configSection, "OS Version (e.g. 17)")
        etSystemPatch = addTextInput(configSection, "System Patch Level (YYYYMM)")
        etBootPatch = addTextInput(configSection, "Boot Patch Level (YYYYMMDD)")
        etVendorPatch = addTextInput(configSection, "Vendor Patch Level (YYYYMMDD)")
        root.addView(configSection)

        // ---- Fingerprint for device mode ----
        root.addView(sectionHeader("FINGERPRINT", dp(16)))
        etDeviceFingerprint = addTextInput(root, "Build Fingerprint (for Google)", singleLine = true)

        // ---- Actions ----
        root.addView(sectionHeader("PROVISION", dp(32)))

        progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar)

        tvStatus = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(tvStatus)

        btnProvision = MaterialButton(requireContext()).apply {
            text = "Provision Keybox"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(btnProvision)

        // ---- Result ----
        tvLastKeybox = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(tvLastKeybox)

        btnUseKeybox = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Use this keybox"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(btnUseKeybox)

        setupListeners()
        checkExistingKeyboxes()

        return scrollView
    }

    // ---- Listeners ----

    private fun setupListeners() {
        btnLoadFromPif.setOnClickListener { loadFromPif() }

        btnProvision.setOnClickListener { doProvision() }

        btnUseKeybox.setOnClickListener {
            val path = lastKeyboxPath ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Install Keybox")
                .setMessage("This will copy the keybox to Keylogger.zsl and trigger the keybox updater.\n\nFile: ${File(path).name}")
                .setPositiveButton("Install") { _, _ -> installKeybox(path) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---- Load from dev.pif ----

    private fun loadFromPif() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pifJson = withContext(Dispatchers.IO) {
                    val result = ShellExecutor.execute("cat", "/data/local/temp/dev.pif")
                    if (result.isSuccess) result.output.joinToString("\n") else null
                } ?: run {
                    toast("dev.pif not found"); return@launch
                }

                // Simple JSON parse (no dependency needed)
                fun extract(key: String): String {
                    val regex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
                    return regex.find(pifJson)?.groupValues?.get(1) ?: ""
                }

                etBrand.setText(extract("BRAND"))
                etManufacturer.setText(extract("MANUFACTURER").ifEmpty { extract("BRAND") })
                etModel.setText(extract("MODEL"))
                etDevice.setText(extract("DEVICE"))
                etProduct.setText(extract("PRODUCT"))
                etFingerprint.setText(extract("FINGERPRINT"))
                etOsVersion.setText(extract("FIRST_API_LEVEL").ifEmpty { "17" })

                val secPatch = extract("SECURITY_PATCH") // e.g. "2026-11-05"
                if (secPatch.isNotEmpty()) {
                    val parts = secPatch.split("-")
                    if (parts.size >= 2) {
                        etSystemPatch.setText("${parts[0]}${parts[1]}")
                        etBootPatch.setText("${parts[0]}${parts[1]}05")
                        etVendorPatch.setText("${parts[0]}${parts[1]}05")
                    }
                }

                toast("Loaded from dev.pif")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dev.pif", e)
                toast("Error: ${e.message}")
            }
        }
    }

    // ---- Provisioning ----

    private fun doProvision() {
        val fp = etDeviceFingerprint.text?.toString()?.trim() ?: ""

        if (useDeviceMode) {
            if (fp.isEmpty()) {
                toast("Build fingerprint is required for device mode")
                return
            }
        } else {
            val seedHex = etSeed.text?.toString()?.trim() ?: ""
            if (seedHex.length != 64 || !seedHex.all { it in "0123456789abcdefABCDEF" }) {
                toast("Seed must be exactly 64 hex characters")
                return
            }
            val brand = etBrand.text?.toString()?.trim() ?: ""
            val model = etModel.text?.toString()?.trim() ?: ""
            if (brand.isEmpty() || model.isEmpty()) {
                toast("Brand and Model are required")
                return
            }
        }

        btnProvision.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = if (useDeviceMode) "Talking to device TEE..." else "Connecting to Google RKP server..."

        val seedHex = etSeed.text?.toString()?.trim() ?: ""
        val brand = etBrand.text?.toString()?.trim() ?: ""
        val model = etModel.text?.toString()?.trim() ?: ""
        val manufacturer = etManufacturer.text?.toString()?.trim() ?: brand
        val product = etProduct.text?.toString()?.trim() ?: model.lowercase()
        val device = etDevice.text?.toString()?.trim() ?: model.lowercase()
        val osVersion = etOsVersion.text?.toString()?.trim() ?: "17"
        val systemPatch = etSystemPatch.text?.toString()?.trim()?.toLongOrNull() ?: 202611L
        val bootPatch = etBootPatch.text?.toString()?.trim()?.toLongOrNull() ?: 20261105L
        val vendorPatch = etVendorPatch.text?.toString()?.trim()?.toLongOrNull() ?: 20261105L

        if (!useDeviceMode) saveSeed(seedHex)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (useDeviceMode) {
                        DeviceRkpProvisioner(requireContext(), fp).provision()
                    } else {
                        val seed = hexToBytes(seedHex)
                        val config = DeviceRkpConfig(
                            brand = brand,
                            manufacturer = manufacturer,
                            product = product,
                            model = model,
                            device = device,
                            fingerprint = fp,
                            osVersion = osVersion,
                            systemPatchLevel = systemPatch,
                            bootPatchLevel = bootPatch,
                            vendorPatchLevel = vendorPatch,
                        )
                        RkpProvisioner(seed, config, curve = "p256").provision()
                    }
                }

                // Save to file
                RKP_DIR.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outFile = File(RKP_DIR, "keybox_$ts.xml")
                outFile.writeText(result.keyboxXml)

                lastKeyboxPath = outFile.absolutePath
                tvStatus.text = "✅ Provisioned! ${result.certCount} certs, leaf expires ${result.leafNotAfter}"
                tvStatus.setTextColor(color(R.color.accent))
                tvLastKeybox.text = "Saved: ${outFile.absolutePath}"
                tvLastKeybox.visibility = View.VISIBLE
                btnUseKeybox.visibility = View.VISIBLE

                Log.i(TAG, "Keybox provisioned: ${outFile.absolutePath}")

            } catch (e: DeviceNotRegisteredError) {
                tvStatus.text = "❌ Device not registered — the seed's DICE chain is not recognized by Google"
                tvStatus.setTextColor(color(R.color.text_error))
                Log.e(TAG, "Device not registered", e)
            } catch (e: RkpException) {
                tvStatus.text = "❌ ${e.message}"
                tvStatus.setTextColor(color(R.color.text_error))
                Log.e(TAG, "RKP error", e)
            } catch (e: Exception) {
                tvStatus.text = "❌ ${e.javaClass.simpleName}: ${e.message}"
                tvStatus.setTextColor(color(R.color.text_error))
                Log.e(TAG, "Provision failed", e)
            } finally {
                btnProvision.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    // ---- Install keybox (user confirmed) ----

    private fun installKeybox(xmlPath: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val xmlContent = File(xmlPath).readText()
                val b64 = Base64.encodeToString(xmlContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                withContext(Dispatchers.IO) {
                    // Write to /sdcard/Download for the init service
                    File("/sdcard/Download/Keylogger.zsl").writeText(b64)
                    // Also write directly to /data/local/temp (app is priv-app)
                    try {
                        File("/data/local/temp/Keylogger.zsl").writeText(b64)
                    } catch (_: Exception) {}
                }

                // Trigger keybox updater (copies from Download to /data/local/temp)
                TouchController.setSystemProp("persist.custom_keybox.state", "true")
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    delay(5_000)
                    TouchController.setSystemProp("persist.custom_keybox.state", "false")
                    // Kill GMS unstable so it reloads the keybox
                    try {
                        val suPaths = listOf("/system/bin/su", "/sbin/su", "/su/bin/su")
                        val suBin = suPaths.firstOrNull { File(it).exists() }
                        if (suBin != null) {
                            Runtime.getRuntime().exec(arrayOf(
                                suBin, "-c",
                                "killall com.google.android.gms.unstable 2>/dev/null"
                            )).waitFor()
                        }
                    } catch (_: Exception) {}
                }

                toast("Keybox installed — GMS will reload it shortly")
                tvStatus.text = "✅ Keybox installed, GMS restarting to pick up new keybox"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install keybox", e)
                toast("Error: ${e.message}")
            }
        }
    }

    // ---- Seed persistence ----

    private fun saveSeed(hex: String) {
        try {
            SEED_FILE.parentFile?.mkdirs()
            SEED_FILE.writeText(hex)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save seed", e)
        }
    }

    private fun loadSavedSeed() {
        try {
            if (SEED_FILE.exists()) {
                val hex = SEED_FILE.readText().trim()
                if (hex.length == 64) etSeed.setText(hex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load saved seed", e)
        }
    }

    private fun checkExistingKeyboxes() {
        try {
            if (!RKP_DIR.exists()) return
            val latest = RKP_DIR.listFiles()
                ?.filter { it.name.startsWith("keybox_") && it.name.endsWith(".xml") }
                ?.maxByOrNull { it.lastModified() }
            if (latest != null) {
                lastKeyboxPath = latest.absolutePath
                tvLastKeybox.text = "Last: ${latest.name}"
                tvLastKeybox.visibility = View.VISIBLE
                btnUseKeybox.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check keyboxes", e)
        }
    }

    // ---- UI helpers ----

    private fun addTextInput(parent: LinearLayout, hint: String, singleLine: Boolean = true): TextInputEditText {
        val layout = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            this.hint = hint
        }
        val et = TextInputEditText(layout.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            this.isSingleLine = singleLine
            textSize = 14f
        }
        layout.addView(et)
        parent.addView(layout)
        return et
    }

    private fun sectionHeader(text: String, topMargin: Int = 0): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setTextColor(color(R.color.text_secondary))
        letterSpacing = 0.1f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin; bottomMargin = dp(4) }
    }

    private fun pageTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 24f
        setTextColor(color(R.color.text_primary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    private fun subtitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = requireContext().getColor(id)

    private fun toast(msg: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
