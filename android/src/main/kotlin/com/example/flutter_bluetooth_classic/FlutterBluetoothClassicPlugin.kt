package com.example.flutter_bluetooth_classic

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FlutterBluetoothClassicPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
  private lateinit var channel: MethodChannel
  private lateinit var stateChannel: EventChannel
  private lateinit var connectionChannel: EventChannel
  private lateinit var dataChannel: EventChannel
  
  private lateinit var context: Context
  private var activity: Activity? = null
  
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var outgoingConnectionCreator: OutgoingConnectionCreator? = null
  private var connectionListener: IncomingConnectionListener? = null
  private var activeConnection: ActiveConnection? = null
  
  private var stateStreamHandler = BluetoothStateStreamHandler()
  private var connectionStreamHandler = BluetoothConnectionStreamHandler()
  private var dataStreamHandler = BluetoothDataStreamHandler()
  
  private val REQUEST_ENABLE_BT = 1
  private val REQUEST_PERMISSIONS = 2
  
  // SPP UUID for Bluetooth Classic communication
  private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.applicationContext
    
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.flutter_bluetooth_classic.plugin/flutter_bluetooth_classic")
    channel.setMethodCallHandler(this)
    
    stateChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.flutter_bluetooth_classic.plugin/flutter_bluetooth_classic_state")
    stateChannel.setStreamHandler(stateStreamHandler)
    
    connectionChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.flutter_bluetooth_classic.plugin/flutter_bluetooth_classic_connection")
    connectionChannel.setStreamHandler(connectionStreamHandler)
    
    dataChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.flutter_bluetooth_classic.plugin/flutter_bluetooth_classic_data")
    dataChannel.setStreamHandler(dataStreamHandler)
    
    // Initialize Bluetooth adapter
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    
    // Register for Bluetooth state changes
    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    context.registerReceiver(bluetoothStateReceiver, filter)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "isBluetoothSupported" -> {
        result.success(bluetoothAdapter != null)
      }
      "isBluetoothEnabled" -> {
        result.success(bluetoothAdapter?.isEnabled == true)
      }
      "enableBluetooth" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }
        
        if (bluetoothAdapter?.isEnabled == true) {
          result.success(true)
          return
        }
        
        if (activity == null) {
          result.error("ACTIVITY_UNAVAILABLE", "Activity is not available", null)
          return
        }
        
        checkPermissions { granted ->
          if (granted) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity?.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            result.success(true)
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      "getPairedDevices" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }
        
        checkPermissions { granted ->
          if (granted) {
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val devicesList = ArrayList<Map<String, Any>>()
            
            pairedDevices?.forEach { device ->
              val deviceMap = HashMap<String, Any>()
              deviceMap["name"] = device.name ?: "Unknown"
              deviceMap["address"] = device.address
              deviceMap["paired"] = true
              devicesList.add(deviceMap)
            }
            
            result.success(devicesList)
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      "startDiscovery" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }
        
        checkPermissions { granted ->
          if (granted) {
            // Register for device discovery
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(discoveryReceiver, filter)
            
            // Start discovery
            val started = bluetoothAdapter?.startDiscovery() ?: false
            result.success(started)
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      "stopDiscovery" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }
        
        checkPermissions { granted ->
          if (granted) {
            try {
              context.unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
              // Receiver not registered, ignore
            }
            
            val stopped = bluetoothAdapter?.cancelDiscovery() ?: false
            result.success(stopped)
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      "connect" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }
        
        val address: String? = call.argument<String?>("address")
        if (address == null) {
          result.error("INVALID_ARGUMENT", "Device address is required", null)
          return
        }
        Log.i("BtPlugin", "Received connect address: $address")
        
        checkPermissions { granted ->
          if (granted) {
            try {
              // Stop any ongoing discovery
              bluetoothAdapter?.cancelDiscovery()
              
              // Disconnect any existing connection
              activeConnection?.close()
              
              // Get the Bluetooth device
              val device = bluetoothAdapter?.getRemoteDevice(address)
              if (device == null) {
                result.error("DEVICE_NOT_FOUND", "Device with address $address not found", null)
                return@checkPermissions
              }
              
              // Connect to the device
              outgoingConnectionCreator = OutgoingConnectionCreator(device, SPP_UUID)
              outgoingConnectionCreator?.createConnection(
                onConnectionCreated = {socket ->
                  activeConnection =
                    ActiveConnection(
                      socket,
                      connectionStreamHandler,
                      dataStreamHandler,
                      cleanupFn = {
                        activeConnection = null
                      })
                  activeConnection?.startReceivingData()

                  val connectionMap = mapOf(
                    "isConnected" to true,
                    "deviceAddress" to socket.remoteDevice?.address,
                    "status" to "CONNECTED"
                  )
                  connectionStreamHandler.send(connectionMap)
                },
                onError = { e ->
                  val connectionMap = mapOf(
                    "isConnected" to false,
                    "deviceAddress" to (device.address ?: ""),
                    "status" to "ERROR: ${e.message}"
                  )
                  connectionStreamHandler.send(connectionMap)
                }
              )
              
              result.success(true)
            } catch (e: Exception) {
              result.error("CONNECTION_FAILED", "Failed to connect to device: ${e.message}", null)
            }
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      "disconnect" -> {
        activeConnection?.close()
        result.success(true)
      }
      "sendData" -> {
        val data: ByteArray? = call.argument<ByteArray?>("data")
        if (data == null) {
          result.error("INVALID_ARGUMENT", "Data is required", null)
          return
        }

        if (activeConnection == null || !(activeConnection!!.isConnected())) {
          result.error("NOT_CONNECTED", "Not connected to any device", null)
          return
        }
        
        try {
          activeConnection?.write(data)
          result.success(true)
        } catch (e: Exception) {
          result.error("SEND_FAILED", "Failed to send data: ${e.message}", null)
        }
      }
      "listen" -> {
        if (bluetoothAdapter == null) {
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth is not available on this device", null)
          return
        }

        checkPermissions { granted ->
          if (granted) {
            try {
              // Stop any ongoing discovery
              bluetoothAdapter?.cancelDiscovery()

              // Connect to the device
              val serverSocket =
                bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("flutterBluetoothClassicDemo", SPP_UUID)
              if (serverSocket == null) {
                result.error("LISTEN_FAILED", "Failed to listen for incoming connections", null)
                return@checkPermissions
              }

              connectionListener = IncomingConnectionListener(serverSocket)
              connectionListener?.listenForConnection(
                onAcceptConnection = {socket ->
                  if (activeConnection == null || !(activeConnection!!.isConnected())) {
                    activeConnection =
                      ActiveConnection(
                        socket,
                        connectionStreamHandler,
                        dataStreamHandler,
                        cleanupFn = {
                          Log.i("BtPlugin", "Cleanup fn called")
                          activeConnection = null
                        })
                    activeConnection?.startReceivingData()

                    val connectionMap = mapOf(
                      "isConnected" to true,
                      "deviceAddress" to socket.remoteDevice?.address,
                      "status" to "CONNECTED"
                    )
                    connectionStreamHandler.send(connectionMap)
                  } else {
                    throw IOException("Connection already active")
                  }
                },
                onError = {e ->
                  val connectionMap = mapOf(
                    "isConnected" to false,
                    "deviceAddress" to "unknown",
                    "status" to "ERROR: ${e.message}"
                  )
                  connectionStreamHandler.send(connectionMap)
                }
              )

              result.success(true)
            } catch (e: Exception) {
              result.error("CONNECTION_FAILED", "Failed to connect to device: ${e.message}", null)
            }
          } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null)
          }
        }
      }
      else -> {
        result.notImplemented()
      }
    }
  }
  
  private fun checkPermissions(callback: (Boolean) -> Unit) {
    val permissionsToRequest = mutableListOf<String>()
    
    // Bluetooth permissions based on Android version
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Android 12+ requires BLUETOOTH_CONNECT and BLUETOOTH_SCAN
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
      }
      
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
      }
    } else {
      // Older versions
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH)
      }
      
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
      }
      
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }
    
    if (permissionsToRequest.isEmpty()) {
      // All permissions already granted
      callback(true)
      return
    }
    
    if (activity == null) {
      callback(false)
      return
    }
    
    // Request permissions
    ActivityCompat.requestPermissions(
      activity!!,
      permissionsToRequest.toTypedArray(),
      REQUEST_PERMISSIONS
    )
    
    // This will be handled asynchronously in onRequestPermissionsResult
    // For now, we'll assume we don't have permissions
    callback(false)
  }
  
  // BroadcastReceiver for Bluetooth state changes
  private val bluetoothStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action
      if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        val isEnabled = state == BluetoothAdapter.STATE_ON
        val status = when (state) {
          BluetoothAdapter.STATE_OFF -> "OFF"
          BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
          BluetoothAdapter.STATE_ON -> "ON"
          BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
          else -> "UNKNOWN"
        }
        
        val stateMap = mapOf(
          "isEnabled" to isEnabled,
          "status" to status
        )
        
        stateStreamHandler.send(stateMap)
      }
    }
  }
  
  // BroadcastReceiver for device discovery
  private val discoveryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action
      if (BluetoothDevice.ACTION_FOUND == action) {
        // Discovery has found a device
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        
        if (device != null) {
          val deviceName = device.name ?: "Unknown"
          val deviceAddress = device.address ?: "Unknown"
          
          // We can notify via the state channel for simplicity
          val deviceMap = mapOf(
            "event" to "deviceFound",
            "device" to mapOf(
              "name" to deviceName,
              "address" to deviceAddress,
              "paired" to (device.bondState == BluetoothDevice.BOND_BONDED)
            )
          )
          
          stateStreamHandler.send(deviceMap)
        }
      }
    }
  }
  
  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    stateChannel.setStreamHandler(null)
    connectionChannel.setStreamHandler(null)
    dataChannel.setStreamHandler(null)
    
    try {
      context.unregisterReceiver(bluetoothStateReceiver)
      context.unregisterReceiver(discoveryReceiver)
    } catch (e: IllegalArgumentException) {
      // Receivers might not be registered, ignore
    }

    activeConnection?.close()
    activeConnection = null
  }
  
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addRequestPermissionsResultListener(this)
  }
  
  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }
  
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addRequestPermissionsResultListener(this)
  }
  
  override fun onDetachedFromActivity() {
    activity = null
  }
  
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
    if (requestCode == REQUEST_PERMISSIONS) {
      // Check if all permissions were granted
      val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
      
      val stateMap = mapOf(
        "event" to "permissionResult",
        "granted" to allGranted
      )
      
      stateStreamHandler.send(stateMap)
      return true
    }
    return false
  }
}

// Stream handlers for event channels
class BluetoothStateStreamHandler : EventChannel.StreamHandler {
  private var eventSink: EventChannel.EventSink? = null
  
  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }
  
  override fun onCancel(arguments: Any?) {
    eventSink = null
  }
  
  fun send(data: Any) {
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    mainHandler.post {
      eventSink?.success(data)
    }
  }
}

class BluetoothConnectionStreamHandler : EventChannel.StreamHandler {
  private var eventSink: EventChannel.EventSink? = null
  
  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }
  
  override fun onCancel(arguments: Any?) {
    eventSink = null
  }
  
  fun send(data: Any) {
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    mainHandler.post {
      eventSink?.success(data)
    }
  }
}

class BluetoothDataStreamHandler : EventChannel.StreamHandler {
  private var eventSink: EventChannel.EventSink? = null
  
  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }
  
  override fun onCancel(arguments: Any?) {
    eventSink = null
  }
  
  fun send(data: Any) {
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    mainHandler.post {
      eventSink?.success(data)
    }
  }
}

class IncomingConnectionListener(
  private val serverSocket: BluetoothServerSocket
) {
  fun listenForConnection(
    onAcceptConnection: (socket: BluetoothSocket) -> Unit,
    onError: (e: Exception) -> Unit
  ) {
    var listening = true
    CoroutineScope(Dispatchers.IO).launch {
      while (listening) {
        val accSocket = try {
          serverSocket.accept()
        } catch (e: IOException) {
          onError(e)
          listening = false
          break
        }
        accSocket?.also { btSocket ->
          try {
            onAcceptConnection(btSocket)
            serverSocket.close()
            listening = false
          } catch (e: Exception) {
            onError(e)
          }
        }
      }
    }
  }
}

class OutgoingConnectionCreator(
  private val device: BluetoothDevice?,
  private val uuid: UUID,
) {
  fun createConnection(onConnectionCreated: (socket: BluetoothSocket) -> Unit, onError: (Exception) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        // Create socket and connect
        val socket = device?.createRfcommSocketToServiceRecord(uuid)
        socket?.connect()

        onConnectionCreated(socket!!)
      } catch (e: IOException) {
        onError(e)
      }
    }
  }
}

class ActiveConnection(
  private val socket: BluetoothSocket,
  private val connectionStreamHandler: BluetoothConnectionStreamHandler,
  private val dataStreamHandler: BluetoothDataStreamHandler,
  private val cleanupFn: () -> Unit
) {
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var readingData = false
  private var connectedDevice: BluetoothDevice? = null

  fun startReceivingData() {
    readingData = true

    CoroutineScope(Dispatchers.IO).launch {
      try {
        // Get streams
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        connectedDevice = socket.remoteDevice

        // Start reading data
        readData()
      } catch (e: IOException) {
        // Send connection failure
        val connectionMap = mapOf(
          "isConnected" to false,
          "deviceAddress" to (connectedDevice?.address ?: ""),
          "status" to "ERROR: ${e.message}"
        )
        connectionStreamHandler.send(connectionMap)

        // Close and cleanup
        close()
      }
    }

  }

  private suspend fun readData() {
    val buffer = ByteArray(1024)
    var bytes: Int

    while (readingData) {
      try {
        // Read data
        bytes = inputStream?.read(buffer) ?: -1

        if (bytes > 0) {
          val data = buffer.sliceArray(0 until bytes)

          Log.i("BtPlugin", "Received read data: $data")

          // Convert to List<Int> for Flutter
          val dataList = data.map { it.toInt() and 0xFF }

          // Send data to Flutter
          val dataMap = mapOf(
            "deviceAddress" to connectedDevice?.address,
            "data" to dataList
          )

          withContext(Dispatchers.Main) {
            dataStreamHandler.send(dataMap)
          }
        }
      } catch (e: IOException) {
        Log.i("BtPlugin", "Exception in reading data: ${e.message}")

        // If there's an error, send disconnection event
        if (readingData) {
          val connectionMap = mapOf(
            "isConnected" to false,
            "deviceAddress" to (connectedDevice?.address ?: "Unknown"),
            "status" to "DISCONNECTED: ${e.message}"
          )

          withContext(Dispatchers.Main) {
            connectionStreamHandler.send(connectionMap)
          }

          // Break the loop
          readingData = false
          close()
        }
      }
    }
  }

  fun write(data: ByteArray) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        Log.i("BtPlugin", "Received write data: $data")
        outputStream?.write(data)
      } catch (e: IOException) {
        // Handle write error
        val connectionMap = mapOf(
          "isConnected" to false,
          "deviceAddress" to connectedDevice?.address,
          "status" to "WRITE_ERROR: ${e.message}"
        )

        connectionStreamHandler.send(connectionMap)

        // If write fails, cancel the connection
        close()
      }
    }
  }

  fun isConnected(): Boolean {
    return socket.isConnected == true
  }

  fun close() {
    try {
      inputStream?.close()
      outputStream?.close()
      socket.close()
    } catch (e: IOException) {
      // Ignore close errors
    } finally {
      inputStream = null
      outputStream = null
      cleanupFn()
    }
  }
}
