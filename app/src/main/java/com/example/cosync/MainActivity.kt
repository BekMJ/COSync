package com.example.cosync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cosync.ui.theme.COSyNCTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var viewModel: SensorViewModel

    // Bluetooth permissions for Android 12 and above
    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("MainActivity", "Bluetooth has been enabled by the user.")
            if (hasBluetoothPermissions()) startScanning()
        } else {
            Log.d("MainActivity", "The user denied enabling Bluetooth or an error occurred.")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            Log.d("MainActivity", "Bluetooth permissions granted")
            if (bluetoothAdapter.isEnabled) startScanning()
        } else {
            Log.d("MainActivity", "Bluetooth permissions denied")
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
        } else if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
        } else {
            startScanning()
        }

        setContent {
            COSyNCTheme {
                // Pass the MainActivity context to the Composable
                SensorDataScreen(viewModel, this@MainActivity) // Pass context
            }
        }
    }

    private fun requestBluetoothPermissions() {
        requestPermissionLauncher.launch(bluetoothPermissions)
    }

    private fun hasBluetoothPermissions() =
        bluetoothPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner.startScan(null, settings, scanCallback) // No filter applied
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            Log.d("MainActivity", "Device found: ${device.address}")
            if (device.name == "Univ. Oklahoma NPL") {
                viewModel.updateDeviceList(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            for (result in results) {
                val device = result.device
                Log.d("MainActivity", "Batch device found: ${device.address}")
                if (device.name == "Univ. Oklahoma NPL") {
                    viewModel.updateDeviceList(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("MainActivity", "Scan failed with error: $errorCode")
        }
    }

    // Properly handle GATT connection and disconnection
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            val ENVIRONMENTAL_SENSING_UUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
            val CO_CONCENTRATION_UUID = UUID.fromString("00002BD0-0000-1000-8000-00805f9b34fb")

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("MainActivity", "Connected to GATT server.")
                    viewModel.updateConnectionStatus("Connected")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("MainActivity", "Disconnected from GATT server.")
                    viewModel.updateConnectionStatus("Disconnected")
                    gatt.close()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val environmentalSensingService = gatt.getService(ENVIRONMENTAL_SENSING_UUID)
                    environmentalSensingService?.let { service ->
                        val coConcentrationCharacteristic = service.getCharacteristic(CO_CONCENTRATION_UUID)
                        coConcentrationCharacteristic?.let { characteristic ->
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } ?: Log.e("MainActivity", "CO Concentration Characteristic not found")
                    } ?: Log.e("MainActivity", "Environmental Sensing Service not found")
                } else {
                    Log.e("MainActivity", "Service discovery failed with status: $status")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == CO_CONCENTRATION_UUID) {
                    val coData = characteristic.value
                    val hexString = coData.joinToString(separator = "-") { eachByte -> String.format("%02X", eachByte) }
                    val formattedString = "Received from ${characteristic.uuid}, value: $hexString"
                    Log.d("MainActivity", "CO Sensor Data: $formattedString")
                    viewModel.updateSensorData(formattedString)
                }
            }
        })
        viewModel.updateGattConnection(bluetoothGatt)
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDataScreen(viewModel: SensorViewModel, context: Context) {
    val sensorState = viewModel.sensorState.collectAsState().value

    Scaffold(
        topBar = { TopAppBar(title = { Text("Xhale - CO sensor") }) },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Connection Status: ${sensorState.connectionStatus}", style = MaterialTheme.typography.titleLarge)
                Text("Sensor Data: ${sensorState.sensorData}", style = MaterialTheme.typography.bodyLarge)

                // Display the list of devices
                LazyColumn {
                    items(sensorState.devices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Device: ${device.name ?: "Unknown"} - ${device.address}")
                            Button(onClick = { viewModel.connectToDevice(device, context) }) {
                                Text("Connect")
                            }
                        }
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

                Spacer(Modifier.height(10.dp))

                if (sensorState.connectionStatus == "Connected") {
                    Button(
                        onClick = { viewModel.startMonitoring() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Monitoring CO Data")
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Display the CO sensor data
                LazyColumn {
                    items(sensorState.coData) { data ->
                        Text(data, style = MaterialTheme.typography.bodyLarge)
                    }
                }

            }
        }
    )
}

class SensorViewModel : ViewModel() {
    private val _sensorState = MutableStateFlow(SensorState("Disconnected", "No data", emptyList(), emptyList(), displayData = false))
    val sensorState: StateFlow<SensorState> = _sensorState
    private var bluetoothGatt: BluetoothGatt? = null

    fun toggleDisplayData() {
        val currentState = _sensorState.value
        _sensorState.value = currentState.copy(displayData = !currentState.displayData)
    }

    fun updateDeviceList(device: BluetoothDevice) {
        val updatedList = _sensorState.value.devices.toMutableList()
        if (!updatedList.any { it.address == device.address }) {
            updatedList.add(device)
            _sensorState.value = _sensorState.value.copy(devices = updatedList)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            // Trigger the connection logic in MainActivity
            (context as MainActivity).connectToDevice(device)
        } else {
            Log.e("SensorViewModel", "Bluetooth connect permission not granted")
        }
    }

    fun scanForDevices() {
        _sensorState.value = _sensorState.value.copy(connectionStatus = "Scanning...", sensorData = "No data")
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        _sensorState.value = _sensorState.value.copy(connectionStatus = "Disconnected", sensorData = "No data", devices = emptyList(), coData = emptyList())
    }

    fun startMonitoring() {
        _sensorState.value = _sensorState.value.copy(sensorData = "Monitoring...")
    }

    fun updateSensorData(data: String) {
        val updatedData = _sensorState.value.coData.toMutableList()
        updatedData.add(data)
        _sensorState.value = _sensorState.value.copy(sensorData = data, coData = updatedData)
    }

    fun updateConnectionStatus(status: String) {
        _sensorState.value = _sensorState.value.copy(connectionStatus = status)
    }

    fun updateGattConnection(gatt: BluetoothGatt) {
        bluetoothGatt = gatt
    }

    data class SensorState(
        val connectionStatus: String,
        val sensorData: String,
        val devices: List<BluetoothDevice>,
        val coData: List<String>,
        val displayData: Boolean
    )
}
