package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SpeedActivity : AppCompatActivity() {

    private lateinit var speedTextView: TextView
    private lateinit var lineChart: LineChart

    private var bluetoothGatt: BluetoothGatt? = null
    private val uartServiceUUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val txCharacteristicUUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private lateinit var fileWriter: BufferedWriter
    private val csvDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    // Изменён формат: yyyy.MM.dd_HH.mm.ss
    private val fileNameDateFormat = SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.getDefault())

    // Start time for chart X-axis
    private var startMillis: Long = 0L

    companion object {
        private const val REQUEST_BLE_PERMS = 1001
        private const val REQUEST_STORAGE_PERM = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speed)

        speedTextView = findViewById(R.id.speedTextView)
        lineChart = findViewById(R.id.lineChart)

        // Initialize start time
        startMillis = System.currentTimeMillis()
        val startLabel = fileNameDateFormat.format(Date(startMillis))

        // Initialize CSV writer with dynamic filename
        val dynamicName = "speed_data_$startLabel.csv"
        fileWriter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prepareCsvWriterViaMediaStore(dynamicName)
                ?: throw IllegalStateException("Не удалось создать CSV через MediaStore")
        } else {
            if (!checkStoragePermission()) {
                requestStoragePermission()
            }
            prepareCsvWriterLegacy(dynamicName)
        }
        fileWriter.write("timestamp,speed_kmh\n")

        // Initialize chart
        setupChart()

        // Connect via BLE
        val deviceAddress = intent.getStringExtra("device_address")
        when {
            deviceAddress.isNullOrEmpty() -> {
                Toast.makeText(this, "Нет адреса устройства", Toast.LENGTH_SHORT).show()
                finish()
            }
            checkBlePermissions() -> connectToDevice(deviceAddress)
            else -> requestBlePermissions()
        }
    }

    // MediaStore approach for Android Q+
    private fun prepareCsvWriterViaMediaStore(fileName: String): BufferedWriter? {
        val mimeType = "text/csv"
        val relPath = Environment.DIRECTORY_DOCUMENTS

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }

        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            values
        ) ?: return null

        return resolver.openOutputStream(uri)?.bufferedWriter()
    }

    // Legacy method for Android ≤ 9
    private fun prepareCsvWriterLegacy(fileName: String): BufferedWriter {
        val docs = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!docs.exists()) docs.mkdirs()
        val csvFile = File(docs, fileName)
        return csvFile.bufferedWriter()
    }

    private fun checkStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE_PERM
        )
    }

    private fun setupChart() {
        val dataSet = LineDataSet(mutableListOf(), "Скорость, км/ч").apply {
            lineWidth = 2f
            setDrawCircles(false)
            mode = LineDataSet.Mode.LINEAR
            setDrawValues(false)
        }
        lineChart.data = LineData(dataSet)

        val displayFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lineChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            axisMinimum = 0f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val actualMillis = startMillis + (value * 1000).toLong()
                    return displayFormat.format(Date(actualMillis))
                }
            }
        }
        lineChart.axisRight.isEnabled = false
        lineChart.description.isEnabled = false
        lineChart.invalidate()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt, status: Int, newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    Toast.makeText(this@SpeedActivity, "BLE подключено", Toast.LENGTH_SHORT).show()
                }
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val svc = gatt.getService(uartServiceUUID)
                val tx = svc?.getCharacteristic(txCharacteristicUUID)
                tx?.let {
                    gatt.setCharacteristicNotification(it, true)
                    val desc = it.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    desc?.apply {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(this)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == txCharacteristicUUID) {
                val speed = characteristic.value
                    .toString(Charsets.UTF_8)
                    .trim()
                    .toFloatOrNull() ?: return

                val now = System.currentTimeMillis()
                val xVal = (now - startMillis) / 1000f

                runOnUiThread {
                    speedTextView.text = "Скорость: %.1f км/ч".format(speed)
                    lineChart.data?.let { data ->
                        data.addEntry(Entry(xVal, speed), 0)
                        data.notifyDataChanged()
                        lineChart.notifyDataSetChanged()
                        lineChart.setVisibleXRangeMaximum(60f)
                        lineChart.moveViewToX(xVal)
                        lineChart.invalidate()
                    }
                }

                if (::fileWriter.isInitialized) {
                    fileWriter.apply {
                        write("${csvDateFormat.format(Date(now))},%.2f\n".format(speed))
                        flush()
                    }
                }
            }
        }
    }

    private fun connectToDevice(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothGatt = adapter.getRemoteDevice(address)
            .connectGatt(this, false, gattCallback)
    }

    private fun checkBlePermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_BLE_PERMS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_STORAGE_PERM -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Re-create writer for legacy path with same dynamic name
                    val label = fileNameDateFormat.format(Date(startMillis))
                    fileWriter = prepareCsvWriterLegacy("speed_data_$label.csv")
                } else {
                    Toast.makeText(this, "Нет доступа к Документам", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_BLE_PERMS -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    intent.getStringExtra("device_address")?.let { connectToDevice(it) }
                } else {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fileWriter.isInitialized) fileWriter.close()
        bluetoothGatt?.close()
    }
}
