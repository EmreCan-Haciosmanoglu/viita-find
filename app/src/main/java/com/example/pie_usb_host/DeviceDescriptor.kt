package com.example.pie_usb_host

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * Structured response for a USB device descriptor.
 * Defined in section 9.5 of the USB 2.0 spec: http://www.usb.org/developers/docs/usb20_docs/
 */
data class DeviceDescriptor(
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val maxPacketSize: Int,
    val vendorId: Int,
    val productId: Int
) {
    /**
     * Return a printable description of the connected device.
     */
    override fun toString(): String {
        return String.format(
            "Device Descriptor:\n"
                    + "Device Class: %s -> Subclass: 0x%02x -> Protocol: 0x%02x\n"
                    + "Max Packet Size: %d\n"
                    + "VID: 0x%04x\nPID: 0x%04x\n",
            UsbHelper.nameForClass(deviceClass),
            deviceSubclass,
            deviceProtocol,
            maxPacketSize,
            vendorId,
            productId
        )
    }
}

/**
 * Request the device descriptor through the USB device connection.
 */
fun UsbDeviceConnection.readDeviceDescriptor(): DeviceDescriptor {
    val buffer = ByteArray(LENGTH)

    controlTransfer(
        REQUEST_TYPE, REQUEST, REQ_VALUE, REQ_INDEX,
        buffer, LENGTH, TIMEOUT
    )

    return parseResponse(buffer)
}

// Type: Indicates whether this is a read or write
//  Matches USB_ENDPOINT_DIR_MASK for either IN or OUT
private const val REQUEST_TYPE = 0x80 //UsbConstants.USB_ENDPOINT_DIR_MASK

// Request: GET_DESCRIPTOR = 0x06
private const val REQUEST = 0x06

// Value: Descriptor Type (High) and Index (Low)
//  Device Descriptor = 0x1
//  Descriptor Index = 0x0
private const val REQ_VALUE = 0x100
private const val REQ_INDEX = 0x00
private const val LENGTH = 32
private const val TIMEOUT = 2000

private fun parseResponse(buffer: ByteArray): DeviceDescriptor {
    require(
        buffer[0] == UsbHelper.DESC_SIZE_DEVICE
                && buffer[1] == UsbHelper.DESC_TYPE_DEVICE
    ) {
        "Invalid device descriptor"
    }

    // Parse device descriptor header
    val deviceClass = buffer[4].toPositiveInt()
    val deviceSubclass = buffer[5].toPositiveInt()
    val deviceProtocol = buffer[6].toPositiveInt()
    // Maximum packet size for control transfers
    val maxPacketSize = buffer[7].toPositiveInt()
    // USB.org assigned vendor id
    var vendorId = buffer[9].toPositiveInt() shl 8
    vendorId += buffer[8].toPositiveInt()
    // Manufacturer assigned product id
    var productId = buffer[11].toPositiveInt() shl 8
    productId += buffer[10].toPositiveInt()

    return DeviceDescriptor(
        deviceClass, deviceSubclass, deviceProtocol,
        maxPacketSize, vendorId, productId
    )
}