package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class DeviceListActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        adapter
    }

    private lateinit var listView: ListView
    private lateinit var scanButton: Button
    private val devicesList = mutableListOf<BluetoothDevice>()
    private lateinit var adapter: ArrayAdapter<String>

    private val REQUEST_ENABLE_BT = 1
    private val REQUEST_PERMISSIONS = 2

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 10000 // 10 seconds scan

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (!devicesList.any { it.address == device.address }) {
                    devicesList.add(device)
                    adapter.add(device.name ?: "Unknown Device" + "\n" + device.address)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        listView = findViewById(R.id.devicesListView)
        scanButton = findViewById(R.id.scanButton)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        scanButton.setOnClickListener {
            checkPermissionsAndScan()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val device = devicesList[position]
            connectToDevice(device)
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth не поддерживается на этом устройстве", Toast.LENGTH_LONG).show()
            finish()
        } else {
            if (!bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            scanLeDevice(true)
        }
    }

    private fun scanLeDevice(enable: Boolean) {
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (enable) {
            devicesList.clear()
            adapter.clear()
            handler.postDelayed({
                scanning = false
                bluetoothLeScanner?.stopScan(scanCallback)
                scanButton.text = "Начать сканирование"
            }, SCAN_PERIOD)
            scanning = true
            scanButton.text = "Сканирование..."
            bluetoothLeScanner?.startScan(scanCallback)
        } else {
            scanning = false
            bluetoothLeScanner?.stopScan(scanCallback)
            scanButton.text = "Начать сканирование"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        scanLeDevice(false)
        Toast.makeText(this, "Подключение к ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, SpeedActivity::class.java)
        intent.putExtra("device_address", device.address)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                scanLeDevice(true)
            } else {
                Toast.makeText(this, "Необходимы разрешения для BLE", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
