package com.example.wifilock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wifilock.databinding.ActivityMainBinding

/**
 * تطبيق لمسح نقاط اتصال Wi-Fi القريبة (BSSID) ومحاولة تثبيت الاتصال
 * على نقطة محددة عند تشابه أسماء الشبكات (SSID) بين عدة Access Points.
 *
 * ملاحظة صادقة: بدون صلاحيات Root، لا يمكن لأي تطبيق أندرويد عادي إجبار
 * النظام بشكل مطلق ودائم على البقاء متصلاً بنقطة اتصال واحدة تحديداً.
 * الحل هنا يستخدم WifiNetworkSpecifier (متاح من أندرويد 10 فما فوق)
 * الذي يسمح بطلب اتصال صريح بـ BSSID معين طالما أن التطبيق يعمل
 * (على الأقل بالخلفية القريبة/foreground). قد يعتبره النظام أحياناً
 * شبكة "محلية فقط" حسب إصدار أندرويد والجهاز.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var adapter: WifiListAdapter

    private val scanResultsList = mutableListOf<ScanResult>()
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var lockedBssid: String? = null

    // مستقبل بث نتائج المسح
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                updateScanResults()
            } else {
                // نتائج قديمة مخزّنة مسبقاً، نعرضها رغم ذلك
                updateScanResults()
            }
        }
    }

    // طلب الأذونات المتعددة دفعة واحدة
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            Toast.makeText(
                this,
                "التطبيق يحتاج كل الأذونات لعرض نقاط الويفاي القريبة",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        adapter = WifiListAdapter(scanResultsList) { scanResult ->
            confirmAndLock(scanResult)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnScan.setOnClickListener { requestPermissionsAndScan() }
        binding.btnUnlock.setOnClickListener { releaseLock() }

        registerReceiver(
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        requestPermissionsAndScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
    }

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScan() {
        binding.txtStatus.text = "جاري المسح..."
        val started = wifiManager.startScan()
        if (!started) {
            // بعض الأجهزة ترفض المسح المتكرر بسرعة، نعرض آخر نتائج معروفة
            updateScanResults()
            Toast.makeText(this, "تعذّر بدء مسح جديد، عرض آخر النتائج", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScanResults() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val results = wifiManager.scanResults
            .sortedByDescending { it.level } // الأقوى إشارة أولاً
        scanResultsList.clear()
        scanResultsList.addAll(results)
        adapter.notifyDataSetChanged()
        binding.txtStatus.text = "تم العثور على ${results.size} نقطة اتصال"
    }

    /**
     * يعرض تأكيداً قبل تثبيت الاتصال على BSSID محدد
     */
    private fun confirmAndLock(scanResult: ScanResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(
                this,
                "تثبيت BSSID يتطلب أندرويد 10 فما فوق",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        lockToBssid(scanResult)
    }

    /**
     * يطلب من النظام الاتصال بشبكة Wi-Fi محددة عبر BSSID دقيق
     * باستخدام WifiNetworkSpecifier + NetworkRequest.
     * ملاحظة: يتطلب من المستخدم إدخال كلمة مرور الشبكة هنا (نفس كلمة مرور SSID العادية).
     */
    private fun lockToBssid(scanResult: ScanResult) {
        releaseLock() // أزل أي قفل سابق أولاً

        val ssid = scanResult.SSID
        val bssid = scanResult.BSSID

        val passwordInput = androidx.appcompat.widget.AppCompatEditText(this).apply {
            hint = "كلمة مرور الشبكة (اتركها فارغة إن كانت مفتوحة)"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تثبيت الاتصال بـ $bssid")
            .setMessage("SSID: $ssid\nستتم محاولة إجبار الاتصال بهذه النقطة تحديداً.")
            .setView(passwordInput)
            .setPositiveButton("تثبيت") { _, _ ->
                val password = passwordInput.text?.toString().orEmpty()
                performLock(ssid, bssid, password)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performLock(ssid: String, bssid: String, password: String) {
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid.trim('"')) // إزالة علامات الاقتباس التي تضيفها بعض الأجهزة
            .setBssid(android.net.MacAddress.fromString(bssid))

        if (password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    binding.txtStatus.text = "متصل الآن بـ $bssid"
                    Toast.makeText(this@MainActivity, "تم الاتصال بنجاح", Toast.LENGTH_SHORT)
                        .show()
                }
                // لتوجيه حركة بيانات هذا التطبيق فعلياً عبر هذه الشبكة:
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onUnavailable() {
                runOnUiThread {
                    binding.txtStatus.text = "فشل الاتصال بـ $bssid"
                    Toast.makeText(
                        this@MainActivity,
                        "تعذّر الاتصال (تحقق من كلمة المرور أو قوة الإشارة)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    binding.txtStatus.text = "انقطع الاتصال بـ $bssid"
                }
            }
        }

        activeNetworkCallback = callback
        lockedBssid = bssid
        connectivityManager.requestNetwork(request, callback)
        binding.txtStatus.text = "جاري محاولة التثبيت على $bssid ..."
        binding.btnUnlock.isEnabled = true
    }

    private fun releaseLock() {
        activeNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // لم يكن مسجلاً أصلاً، تجاهل
            }
        }
        connectivityManager.bindProcessToNetwork(null)
        activeNetworkCallback = null
        lockedBssid = null
        binding.btnUnlock.isEnabled = false
        binding.txtStatus.text = "تم إلغاء التثبيت"
    }
}

/**
 * محول عرض قائمة نقاط الاتصال في RecyclerView
 */
class WifiListAdapter(
    private val items: List<ScanResult>,
    private val onLockClick: (ScanResult) -> Unit
) : RecyclerView.Adapter<WifiListAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val txtSsid: android.widget.TextView = view.findViewById(R.id.txtSsid)
        val txtBssid: android.widget.TextView = view.findViewById(R.id.txtBssid)
        val txtSignal: android.widget.TextView = view.findViewById(R.id.txtSignal)
        val btnLock: android.widget.Button = view.findViewById(R.id.btnLock)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = items[position]
        holder.txtSsid.text = if (result.SSID.isNullOrEmpty()) "(بدون اسم)" else result.SSID
        holder.txtBssid.text = "BSSID: ${result.BSSID}"
        holder.txtSignal.text = "الإشارة: ${result.level} dBm"
        holder.btnLock.setOnClickListener { onLockClick(result) }
    }

    override fun getItemCount(): Int = items.size
}
