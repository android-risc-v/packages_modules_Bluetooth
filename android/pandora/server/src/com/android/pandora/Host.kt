/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.pandora

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothUuid
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.os.ParcelUuid
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.nio.ByteBuffer
import java.io.Closeable
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pandora.HostGrpc.HostImplBase
import pandora.HostProto.*

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Host(
  private val context: Context,
  private val security: Security,
  private val server: Server
) : HostImplBase(), Closeable {
  private val TAG = "PandoraHost"

  private val scope: CoroutineScope
  private val flow: Flow<Intent>

  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
  private val bluetoothAdapter = bluetoothManager.adapter

  private var connectability = ConnectabilityMode.NOT_CONNECTABLE
  private var discoverability = DiscoverabilityMode.NOT_DISCOVERABLE

  private val advertisers = mutableMapOf<UUID, AdvertiseCallback>()

  init {
    scope = CoroutineScope(Dispatchers.Default)

    // Add all intent actions to be listened.
    val intentFilter = IntentFilter()
    intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
    intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
    intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
    intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    intentFilter.addAction(BluetoothDevice.ACTION_FOUND)

    // Creates a shared flow of intents that can be used in all methods in the coroutine scope.
    // This flow is started eagerly to make sure that the broadcast receiver is registered before
    // any function call. This flow is only cancelled when the corresponding scope is cancelled.
    flow = intentFlow(context, intentFilter).shareIn(scope, SharingStarted.Eagerly)
  }

  override fun close() {
    scope.cancel()
  }

  private suspend fun rebootBluetooth() {
    Log.i(TAG, "rebootBluetooth")

    val stateFlow =
      flow
        .filter { it.getAction() == BluetoothAdapter.ACTION_STATE_CHANGED }
        .map { it.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) }

    if (bluetoothAdapter.isEnabled) {
      bluetoothAdapter.disable()
      stateFlow.filter { it == BluetoothAdapter.STATE_OFF }.first()
    }

    // TODO: b/234892968
    delay(3000L)

    bluetoothAdapter.enable()
    stateFlow.filter { it == BluetoothAdapter.STATE_ON }.first()
  }

  override fun factoryReset(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver, 30) {
      Log.i(TAG, "factoryReset")

      val stateFlow =
        flow
          .filter { it.getAction() == BluetoothAdapter.ACTION_STATE_CHANGED }
          .map { it.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) }

      initiatedConnection.clear()
      waitedAclConnection.clear()

      bluetoothAdapter.clearBluetooth()

      stateFlow.filter { it == BluetoothAdapter.STATE_ON }.first()
      // Delay to initialize the Bluetooth completely and to fix flakiness: b/266611263
      delay(1000L)
      Log.i(TAG, "Shutdown the gRPC Server")
      server.shutdown()

      // The last expression is the return value.
      Empty.getDefaultInstance()
    }
  }

  override fun reset(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      Log.i(TAG, "reset")
      initiatedConnection.clear()
      waitedAclConnection.clear()
      rebootBluetooth()

      Empty.getDefaultInstance()
    }
  }

  override fun readLocalAddress(
    request: Empty,
    responseObserver: StreamObserver<ReadLocalAddressResponse>
  ) {
    grpcUnary<ReadLocalAddressResponse>(scope, responseObserver) {
      Log.i(TAG, "readLocalAddress")
      val localMacAddress = MacAddress.fromString(bluetoothAdapter.getAddress())
      ReadLocalAddressResponse.newBuilder()
        .setAddress(ByteString.copyFrom(localMacAddress.toByteArray()))
        .build()
    }
  }

  private suspend fun waitPairingRequestIntent(bluetoothDevice: BluetoothDevice) {
    Log.i(TAG, "waitPairingRequestIntent: device=$bluetoothDevice")
    var pairingVariant =
      flow
        .filter { it.getAction() == BluetoothDevice.ACTION_PAIRING_REQUEST }
        .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
        .first()
        .getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)

    val confirmationCases =
      intArrayOf(
        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION,
        BluetoothDevice.PAIRING_VARIANT_CONSENT,
        BluetoothDevice.PAIRING_VARIANT_PIN,
      )

    if (pairingVariant in confirmationCases) {
      bluetoothDevice.setPairingConfirmation(true)
    }
  }

  private suspend fun waitConnectionIntent(bluetoothDevice: BluetoothDevice) {
    Log.i(TAG, "waitConnectionIntent: device=$bluetoothDevice")
    flow
      .filter { it.action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED }
      .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
      .map { it.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR) }
      .filter { it == BluetoothAdapter.STATE_CONNECTED }
      .first()
  }

  suspend fun waitBondIntent(bluetoothDevice: BluetoothDevice) {
    // We only wait for bonding to be completed since we only need the ACL connection to be
    // established with the peer device (on Android state connected is sent when all profiles
    // have been connected).
    Log.i(TAG, "waitBondIntent: device=$bluetoothDevice")
    flow
      .filter { it.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
      .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
      .map { it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) }
      .filter { it == BOND_BONDED }
      .first()
  }

  suspend fun waitIncomingAclConnectedIntent(address: String?, transport: Int): Intent {
    return flow
      .filter { it.action == BluetoothDevice.ACTION_ACL_CONNECTED }
      .filter { address == null || it.getBluetoothDeviceExtra().address == address }
      .filter { !initiatedConnection.contains(it.getBluetoothDeviceExtra()) }
      .filter {
        it.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.ERROR) == transport
      }
      .first()
  }

  private suspend fun acceptPairingAndAwaitBonded(bluetoothDevice: BluetoothDevice) {
    val acceptPairingJob = scope.launch { waitPairingRequestIntent(bluetoothDevice) }
    waitBondIntent(bluetoothDevice)
    if (acceptPairingJob.isActive) {
      acceptPairingJob.cancel()
    }
  }

  override fun waitConnection(
    request: WaitConnectionRequest,
    responseObserver: StreamObserver<WaitConnectionResponse>
  ) {
    grpcUnary(scope, responseObserver) {
      if (request.address.isEmpty()) throw Status.UNKNOWN.asException()
      var bluetoothDevice = request.address.toBluetoothDevice(bluetoothAdapter)

      Log.i(TAG, "waitConnection: device=$bluetoothDevice")

      if (!bluetoothAdapter.isEnabled) {
        Log.e(TAG, "Bluetooth is not enabled, cannot waitConnection")
        throw Status.UNKNOWN.asException()
      }

      if (!bluetoothDevice.isConnected() || waitedAclConnection.contains(bluetoothDevice)) {
        bluetoothDevice =
          waitIncomingAclConnectedIntent(bluetoothDevice.address, TRANSPORT_BREDR)
            .getBluetoothDeviceExtra()
      }

      waitedAclConnection.add(bluetoothDevice)

      WaitConnectionResponse.newBuilder()
        .setConnection(bluetoothDevice.toConnection(TRANSPORT_BREDR))
        .build()
    }
  }

  override fun waitDisconnection(
    request: WaitDisconnectionRequest,
    responseObserver: StreamObserver<Empty>
  ) {
    grpcUnary(scope, responseObserver) {
      val bluetoothDevice = request.connection.toBluetoothDevice(bluetoothAdapter)
      Log.i(TAG, "waitDisconnection: device=$bluetoothDevice")
      if (!bluetoothAdapter.isEnabled) {
        Log.e(TAG, "Bluetooth is not enabled, cannot waitDisconnection")
        throw Status.UNKNOWN.asException()
      }
      if (bluetoothDevice.bondState != BluetoothDevice.BOND_NONE) {
        flow
          .filter { it.action == BluetoothDevice.ACTION_ACL_DISCONNECTED }
          .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
          .first()
      }
      Empty.getDefaultInstance()
    }
  }

  override fun connect(request: ConnectRequest, responseObserver: StreamObserver<ConnectResponse>) {
    grpcUnary(scope, responseObserver) {
      val bluetoothDevice = request.address.toBluetoothDevice(bluetoothAdapter)

      Log.i(TAG, "connect: address=$bluetoothDevice")

      initiatedConnection.add(bluetoothDevice)
      bluetoothAdapter.cancelDiscovery()

      if (!bluetoothDevice.isConnected()) {
        if (bluetoothDevice.bondState == BOND_BONDED) {
          // already bonded, just reconnect
          bluetoothDevice.connect()
          waitConnectionIntent(bluetoothDevice)
        } else {
          // need to bond
          bluetoothDevice.createBond()
          if (!security.manuallyConfirm) {
            acceptPairingAndAwaitBonded(bluetoothDevice)
          }
        }
      }

      ConnectResponse.newBuilder()
        .setConnection(bluetoothDevice.toConnection(TRANSPORT_BREDR))
        .build()
    }
  }

  override fun disconnect(request: DisconnectRequest, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      val bluetoothDevice = request.connection.toBluetoothDevice(bluetoothAdapter)
      Log.i(TAG, "disconnect: device=$bluetoothDevice")

      if (!bluetoothDevice.isConnected()) {
        Log.e(TAG, "Device is not connected, cannot disconnect")
        throw Status.UNKNOWN.asException()
      }

      when (request.connection.transport) {
        TRANSPORT_BREDR -> {
          Log.i(TAG, "disconnect BR_EDR")
          bluetoothDevice.disconnect()
          flow
            .filter { it.action == BluetoothDevice.ACTION_ACL_DISCONNECTED }
            .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
            .first()
        }
        TRANSPORT_LE -> {
          Log.i(TAG, "disconnect LE")
          val gattInstance =
            try {
              GattInstance.get(bluetoothDevice.address)
            } catch (e: Exception) {
              Log.w(TAG, "Gatt instance doesn't exist. Android might be peripheral")
              val instance = GattInstance(bluetoothDevice, TRANSPORT_LE, context)
              instance.waitForState(BluetoothProfile.STATE_CONNECTED)
              instance
            }
          if (gattInstance.isDisconnected()) {
            Log.e(TAG, "Device is not connected, cannot disconnect")
            throw Status.UNKNOWN.asException()
          }

          gattInstance.disconnectInstance()
          gattInstance.waitForState(BluetoothProfile.STATE_DISCONNECTED)
        }
        else -> {
          Log.e(TAG, "Device type UNKNOWN")
          throw Status.UNKNOWN.asException()
        }
      }

      Empty.getDefaultInstance()
    }
  }

  override fun connectLE(
    request: ConnectLERequest,
    responseObserver: StreamObserver<ConnectLEResponse>
  ) {
    grpcUnary<ConnectLEResponse>(scope, responseObserver) {
      val ownAddressType = request.ownAddressType
      if (
        ownAddressType != OwnAddressType.RANDOM &&
          ownAddressType != OwnAddressType.RESOLVABLE_OR_RANDOM
      ) {
        Log.e(TAG, "connectLE: Unsupported OwnAddressType: $ownAddressType")
        throw Status.UNKNOWN.asException()
      }
      val (address, type) =
        when (request.getAddressCase()!!) {
          ConnectLERequest.AddressCase.PUBLIC ->
            Pair(request.public, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
          ConnectLERequest.AddressCase.RANDOM ->
            Pair(request.random, BluetoothDevice.ADDRESS_TYPE_RANDOM)
          ConnectLERequest.AddressCase.PUBLIC_IDENTITY ->
            Pair(request.publicIdentity, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
          ConnectLERequest.AddressCase.RANDOM_STATIC_IDENTITY ->
            Pair(request.randomStaticIdentity, BluetoothDevice.ADDRESS_TYPE_RANDOM)
          ConnectLERequest.AddressCase.ADDRESS_NOT_SET -> throw Status.UNKNOWN.asException()
        }
      Log.i(TAG, "connectLE: $address")
      val bluetoothDevice = scanLeDevice(address.decodeAsMacAddressToString(), type)!!
      initiatedConnection.add(bluetoothDevice)
      GattInstance(bluetoothDevice, TRANSPORT_LE, context)
        .waitForState(BluetoothProfile.STATE_CONNECTED)
      ConnectLEResponse.newBuilder()
        .setConnection(bluetoothDevice.toConnection(TRANSPORT_LE))
        .build()
    }
  }

  private fun scanLeDevice(address: String, addressType: Int): BluetoothDevice? {
    Log.d(TAG, "scanLeDevice")
    var bluetoothDevice: BluetoothDevice? = null
    runBlocking {
      val flow = callbackFlow {
        val leScanCallback =
          object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
              super.onScanFailed(errorCode)
              Log.d(TAG, "onScanFailed: errorCode: $errorCode")
              trySendBlocking(null)
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) {
              super.onScanResult(callbackType, result)
              val deviceAddress = result.device.address
              val deviceAddressType = result.device.addressType
              if (deviceAddress == address && deviceAddressType == addressType) {
                Log.d(TAG, "found device address: $deviceAddress")
                trySendBlocking(result.device)
              }
            }
          }
        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bluetoothLeScanner?.startScan(leScanCallback) ?: run { trySendBlocking(null) }
        awaitClose { bluetoothLeScanner?.stopScan(leScanCallback) }
      }
      bluetoothDevice = flow.first()
    }
    return bluetoothDevice
  }

  override fun advertise(
    request: AdvertiseRequest,
    responseObserver: StreamObserver<AdvertiseResponse>
  ) {
    Log.d(TAG, "advertise")
    grpcServerStream(scope, responseObserver) {
      callbackFlow {
        val callback =
          object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
              Log.d(TAG, "advertising started")
            }
            override fun onStartFailure(errorCode: Int) {
              error("failed to start advertising: $errorCode")
            }
          }
        val advertisingDataBuilder = AdvertiseData.Builder()
        val dataTypesRequest = request.data

        if (
          !dataTypesRequest.getIncompleteServiceClassUuids16List().isEmpty() or
            !dataTypesRequest.getIncompleteServiceClassUuids32List().isEmpty() or
            !dataTypesRequest.getIncompleteServiceClassUuids128List().isEmpty()
        ) {
          Log.e(TAG, "Incomplete Service Class Uuids not supported")
          throw Status.UNKNOWN.asException()
        }

        for (service_uuid in dataTypesRequest.getCompleteServiceClassUuids16List()) {
          val uuid16 = "0000${service_uuid}-0000-1000-8000-00805F9B34FB"
          advertisingDataBuilder.addServiceUuid(ParcelUuid.fromString(uuid16))
        }
        for (service_uuid in dataTypesRequest.getCompleteServiceClassUuids32List()) {
          val uuid32 = "${service_uuid}-0000-1000-8000-00805F9B34FB"
          advertisingDataBuilder.addServiceUuid(ParcelUuid.fromString(service_uuid))
        }
        for (service_uuid in dataTypesRequest.getCompleteServiceClassUuids128List()) {
          advertisingDataBuilder.addServiceUuid(ParcelUuid.fromString(service_uuid))
        }

        advertisingDataBuilder
          .setIncludeDeviceName(
            dataTypesRequest.includeCompleteLocalName || dataTypesRequest.includeShortenedLocalName
          )
          .setIncludeTxPowerLevel(dataTypesRequest.includeTxPowerLevel)
          .addManufacturerData(
            BluetoothAssignedNumbers.GOOGLE,
            dataTypesRequest.manufacturerSpecificData.toByteArray()
          )
        val advertisingData = advertisingDataBuilder.build()

        val ownAddressType =
          when (request.ownAddressType) {
            OwnAddressType.RESOLVABLE_OR_PUBLIC,
            OwnAddressType.PUBLIC -> AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC
            OwnAddressType.RESOLVABLE_OR_RANDOM,
            OwnAddressType.RANDOM -> AdvertisingSetParameters.ADDRESS_TYPE_RANDOM
            else -> AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT
          }
        val advertiseSettings =
          AdvertiseSettings.Builder()
            .setConnectable(request.connectable)
            .setOwnAddressType(ownAddressType)
            .build()

        bluetoothAdapter.bluetoothLeAdvertiser.startAdvertising(
          advertiseSettings,
          advertisingData,
          callback,
        )

        if (request.connectable) {
          while (true) {
            Log.d(TAG, "Waiting for incoming connection")
            val connection =
              waitIncomingAclConnectedIntent(null, TRANSPORT_LE)
                .getBluetoothDeviceExtra()
                .toConnection(TRANSPORT_LE)
            Log.d(TAG, "Receive connection")
            trySendBlocking(AdvertiseResponse.newBuilder().setConnection(connection).build())
          }
        }

        awaitClose { bluetoothAdapter.bluetoothLeAdvertiser.stopAdvertising(callback) }
      }
    }
  }

  // TODO: Handle request parameters
  override fun scan(request: ScanRequest, responseObserver: StreamObserver<ScanningResponse>) {
    Log.d(TAG, "scan")
    grpcServerStream(scope, responseObserver) {
      callbackFlow {
        val callback =
          object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
              val bluetoothDevice = result.device
              val scanRecord = result.scanRecord
              val scanData = scanRecord.getAdvertisingDataMap()
              val serviceData = scanRecord?.serviceData!!

              var dataTypesBuilder =
                DataTypes.newBuilder().setTxPowerLevel(scanRecord.getTxPowerLevel())
              scanData[ScanRecord.DATA_TYPE_LOCAL_NAME_SHORT]?.let {
                dataTypesBuilder.setShortenedLocalName(it.decodeToString())
              }
                ?: run { dataTypesBuilder.setIncludeShortenedLocalName(false) }
              scanData[ScanRecord.DATA_TYPE_LOCAL_NAME_COMPLETE]?.let {
                dataTypesBuilder.setCompleteLocalName(it.decodeToString())
              }
                ?: run { dataTypesBuilder.setIncludeCompleteLocalName(false) }

              for (serviceDataEntry in serviceData) {
                val parcelUuid = serviceDataEntry.key
                Log.d(TAG, parcelUuid.uuid.toString())

                // use upper case uuid as the key
                if (BluetoothUuid.is16BitUuid(parcelUuid)) {
                  val uuid16 = parcelUuid.uuid.toString().substring(4, 8).uppercase()
                  dataTypesBuilder.addIncompleteServiceClassUuids16(uuid16)
                  dataTypesBuilder.putServiceDataUuid16(
                    uuid16,
                    ByteString.copyFrom(serviceDataEntry.value)
                  )
                } else if (BluetoothUuid.is32BitUuid(parcelUuid)) {
                  val uuid32 = parcelUuid.uuid.toString().substring(0, 8).uppercase()
                  dataTypesBuilder.addIncompleteServiceClassUuids32(uuid32)
                  dataTypesBuilder.putServiceDataUuid32(
                    uuid32,
                    ByteString.copyFrom(serviceDataEntry.value)
                  )
                } else {
                  val uuid128 = parcelUuid.uuid.toString().uppercase()
                  dataTypesBuilder.addIncompleteServiceClassUuids128(uuid128)
                  dataTypesBuilder.putServiceDataUuid128(
                    uuid128,
                    ByteString.copyFrom(serviceDataEntry.value)
                  )
                }
              }
              // Flags DataTypes CSSv10 1.3 Flags
              val mode: DiscoverabilityMode =
                when (result.scanRecord.advertiseFlags and 0b11) {
                  0b01 -> DiscoverabilityMode.DISCOVERABLE_LIMITED
                  0b10 -> DiscoverabilityMode.DISCOVERABLE_GENERAL
                  else -> DiscoverabilityMode.NOT_DISCOVERABLE
                }
              dataTypesBuilder.setLeDiscoverabilityMode(mode)
              var manufacturerData = ByteBuffer.allocate(32)
              val manufacteurSpecificDatas = scanRecord.getManufacturerSpecificData()
              for (i in 0..manufacteurSpecificDatas.size() - 1) {
                val id = manufacteurSpecificDatas.keyAt(i)
                manufacturerData
                  .put(id.toByte())
                  .put(id.shr(8).toByte())
                  .put(manufacteurSpecificDatas.get(id))
              }
              dataTypesBuilder.setManufacturerSpecificData(
                ByteString.copyFrom(manufacturerData.array())
              )
              val primaryPhy =
                when (result.getPrimaryPhy()) {
                  BluetoothDevice.PHY_LE_1M -> PrimaryPhy.PRIMARY_1M
                  BluetoothDevice.PHY_LE_CODED -> PrimaryPhy.PRIMARY_CODED
                  else -> PrimaryPhy.UNRECOGNIZED
                }
              var scanningResponseBuilder =
                ScanningResponse.newBuilder()
                  .setLegacy(result.isLegacy())
                  .setConnectable(result.isConnectable())
                  .setSid(result.getPeriodicAdvertisingInterval())
                  .setPrimaryPhy(primaryPhy)
                  .setTxPower(result.getTxPower())
                  .setRssi(result.getRssi())
                  .setPeriodicAdvertisingInterval(result.getPeriodicAdvertisingInterval().toFloat())
                  .setData(dataTypesBuilder.build())
              when (bluetoothDevice.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC ->
                  scanningResponseBuilder.setPublic(bluetoothDevice.toByteString())
                BluetoothDevice.ADDRESS_TYPE_RANDOM ->
                  scanningResponseBuilder.setRandom(bluetoothDevice.toByteString())
                else ->
                  Log.w(TAG, "Address type UNKNOWN: ${bluetoothDevice.type} addr: $bluetoothDevice")
              }
              // TODO: Complete the missing field as needed, all the examples are here
              trySendBlocking(scanningResponseBuilder.build())
            }

            override fun onScanFailed(errorCode: Int) {
              error("scan failed")
            }
          }
        bluetoothAdapter.bluetoothLeScanner.startScan(callback)

        awaitClose { bluetoothAdapter.bluetoothLeScanner.stopScan(callback) }
      }
    }
  }

  override fun inquiry(request: Empty, responseObserver: StreamObserver<InquiryResponse>) {
    Log.d(TAG, "Inquiry")
    grpcServerStream(scope, responseObserver) {
      launch {
        try {
          bluetoothAdapter.startDiscovery()
          awaitCancellation()
        } finally {
          bluetoothAdapter.cancelDiscovery()
        }
      }
      flow
        .filter { it.action == BluetoothDevice.ACTION_FOUND }
        .map {
          val bluetoothDevice = it.getBluetoothDeviceExtra()
          Log.i(TAG, "Device found: $bluetoothDevice")
          InquiryResponse.newBuilder().setAddress(bluetoothDevice.toByteString()).build()
        }
    }
  }

  override fun setDiscoverabilityMode(
    request: SetDiscoverabilityModeRequest,
    responseObserver: StreamObserver<Empty>
  ) {
    Log.d(TAG, "setDiscoverabilityMode")
    grpcUnary(scope, responseObserver) {
      discoverability = request.mode!!

      val scanMode =
        when (discoverability) {
          DiscoverabilityMode.UNRECOGNIZED -> null
          DiscoverabilityMode.NOT_DISCOVERABLE ->
            if (connectability == ConnectabilityMode.CONNECTABLE) {
              BluetoothAdapter.SCAN_MODE_CONNECTABLE
            } else {
              BluetoothAdapter.SCAN_MODE_NONE
            }
          DiscoverabilityMode.DISCOVERABLE_LIMITED,
          DiscoverabilityMode.DISCOVERABLE_GENERAL ->
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        }

      if (scanMode != null) {
        bluetoothAdapter.setScanMode(scanMode)
      }

      if (discoverability == DiscoverabilityMode.DISCOVERABLE_LIMITED) {
        bluetoothAdapter.setDiscoverableTimeout(
          Duration.ofSeconds(120)
        ) // limited discoverability needs a timeout, 120s is Android default
      }
      Empty.getDefaultInstance()
    }
  }

  override fun setConnectabilityMode(
    request: SetConnectabilityModeRequest,
    responseObserver: StreamObserver<Empty>
  ) {
    grpcUnary(scope, responseObserver) {
      Log.d(TAG, "setConnectabilityMode")
      connectability = request.mode!!

      val scanMode =
        when (connectability) {
          ConnectabilityMode.UNRECOGNIZED -> null
          ConnectabilityMode.NOT_CONNECTABLE -> {
            BluetoothAdapter.SCAN_MODE_NONE
          }
          ConnectabilityMode.CONNECTABLE -> {
            if (
              discoverability == DiscoverabilityMode.DISCOVERABLE_LIMITED ||
                discoverability == DiscoverabilityMode.DISCOVERABLE_GENERAL
            ) {
              BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
            } else {
              BluetoothAdapter.SCAN_MODE_CONNECTABLE
            }
          }
        }
      if (scanMode != null) {
        bluetoothAdapter.setScanMode(scanMode)
      }
      Empty.getDefaultInstance()
    }
  }
}
