package com.example.cosync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cosync.ui.theme.COSyNCTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.ViewModelInitializer
import androidx.compose.ui.graphics.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider


class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var viewModel: SensorViewModel

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("MainActivity", "Bluetooth has been enabled by the user.")
            if (hasLocationPermission()) startScanning()
        } else {
            Log.d("MainActivity", "The user denied enabling Bluetooth or an error occurred.")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Location permission granted")
            if (bluetoothAdapter.isEnabled) startScanning()
        } else {
            Log.d("MainActivity", "Location permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager: BluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[SensorViewModel::class.java]

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else if (!hasLocationPermission()) {
            requestLocationPermission()
        } else {
            startScanning()
        }

        setContent {
            COSyNCTheme {
                SensorDataScreen(viewModel) // Pass viewModel to Composable
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDeviceByAddress() {
        val deviceAddress = "D7:63:92:33:13:6D"
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (device != null) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
            Log.d("MainActivity", "Trying to connect to: $deviceAddress")
        } else {
            Log.d("MainActivity", "Device not found with address: $deviceAddress")
        }
    }

    private fun requestLocationPermission() {
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0x1800")).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d("MainActivity", "Device found: ${result.device.address}")
            viewModel.updateDeviceList(result.device)  // Correctly access ViewModel here
        }
    }


    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("MainActivity", "Connected to GATT server.")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("MainActivity", "Disconnected from GATT server.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (service in gatt.services) {
                    Log.d("MainActivity", "Service discovered: ${service.uuid}")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDataScreen(viewModel: SensorViewModel = SensorViewModel()) {
    val sensorState = viewModel.sensorState.collectAsState().value

    Scaffold(
        topBar = { TopAppBar(title = { Text("Xhale - CO sensor") }) },
        content = { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Connection Status: ${sensorState.connectionStatus}", style = MaterialTheme.typography.titleLarge)
                Text("Sensor Data: ${sensorState.sensorData}", style = MaterialTheme.typography.bodyLarge)

                // Display the list of devices
                LazyColumn {
                    items(sensorState.devices) { device ->
                        Text("Device: ${device.name ?: "Unknown"} - ${device.address}")
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.scanForDevices() },
                    enabled = sensorState.connectionStatus != "Connected",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan for Devices")
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = { viewModel.disconnectDevice() },
                    enabled = sensorState.connectionStatus == "Connected",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }
        }
    )
}



class SensorViewModel : ViewModel() {
    private val _sensorState = MutableStateFlow(SensorState("Disconnected", "", emptyList()))
    val sensorState: StateFlow<SensorState> = _sensorState

    fun updateDeviceList(device: BluetoothDevice) {
        val updatedList = _sensorState.value.devices.toMutableList()
        if (!updatedList.any { it.address == device.address }) {
            updatedList.add(device)
            _sensorState.value = _sensorState.value.copy(devices = updatedList)
        }
    }

    fun scanForDevices() {
        _sensorState.value = SensorState("Scanning...", "No data", _sensorState.value.devices)
    }

    fun disconnectDevice() {
        _sensorState.value = SensorState("Disconnected", "No data", emptyList())
    }


    data class SensorState(val connectionStatus: String, val sensorData: String, val devices: List<BluetoothDevice>)
}

