package com.example.pie_usb_host

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.ParseException
import android.app.PendingIntent
import android.content.*
import android.os.Environment
import com.example.pie_usb_host.R
import android.hardware.usb.UsbDeviceConnection
import android.net.Uri
import android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
import android.widget.Button
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files.isReadable

import java.nio.file.Files.isWritable
import android.provider.DocumentsContract

import android.content.ContentResolver
import android.content.Intent.*
import android.database.Cursor
import java.security.MessageDigest
import java.util.*


const val FOLDER_URI = "folder_uri"
const val TAG = "UsbEnumerator"
private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
private const val FLASH_DRIVE_URI =
    "content://com.android.externalstorage.documents/tree/612D-B6E0%3A"

class MainActivity : AppCompatActivity() {
    val LOGTAG = "MainActivity"
    val REQUEST_CODE = 12123
    val LENGTH = 32
    val DEVICE_COUNT = "Device_Count"
    private lateinit var activity: MainActivity
    private lateinit var usbManager: UsbManager

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var permissionIntent: PendingIntent
    private var data_to_hash = ByteArray(LENGTH)

    private var usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        ) == false
                    ) {
                        Log.v("_device_", "permission denied for device $device")
                    }
                }
            }
        }
    }

    fun hash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data_to_hash)
        return digest.fold("", { str, it -> str + "%02x".format(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        activity = this

        statusView = findViewById(R.id.text_status)
        resultView = findViewById(R.id.text_result)

        usbManager = getSystemService(UsbManager::class.java)

        permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        val usb_detached_filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        val usb_permission_filter = IntentFilter(ACTION_USB_PERMISSION)

        registerReceiver(usbReceiver, usb_detached_filter)
        registerReceiver(usbReceiver, usb_permission_filter)

        findViewById<Button>(R.id.button).setOnClickListener {
            val pref = this.getPreferences(Context.MODE_PRIVATE)
            val uriString = pref.getString(FOLDER_URI, "") ?: ""
            if (uriString != "")
                checkFolderExist(Uri.parse(uriString), 4999)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun openDocumentTree() {
        val pref = this.getPreferences(Context.MODE_PRIVATE)
        val uriString = pref.getString(FOLDER_URI, "") ?: ""
        when {
            uriString == "" -> {
                start_open_document_tree()
                Log.v("_device_", "No Pref!")
            }
            arePermissionsGranted(uriString) -> {
                Log.v("_device_", "Please choose root folder!")
                checkFolderExist(Uri.parse(uriString), 5000)
            }
            else -> {
                start_open_document_tree()
            }
        }
    }

    private fun start_open_document_tree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE) {
            if (data != null) {
                //this is the uri user has provided us
                val treeUri: Uri? = data.data
                if (treeUri != null) {
                    if (Uri.decode(treeUri.toString()).endsWith(":") == false) {
                        Toast.makeText(
                            applicationContext,
                            "Please choose root folder!",
                            Toast.LENGTH_SHORT
                        ).show()
                        openDocumentTree()
                        return
                    }
                    // here we ask the content resolver to persist the permission for us
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                    if ((checkFolderExist(treeUri, 2000) == false)) {
                        Log.v("_device_", "ERROR! This should be unreachable")
                        releasePermissions(treeUri)
                        openDocumentTree()
                        return
                    }
                    val device_hash = hash()

                    val pref = this.getPreferences(Context.MODE_PRIVATE)
                    val editor = pref.edit()
                    var device_count = pref.getInt(DEVICE_COUNT, -1)
                    if (device_count == -1) { // Redundant
                        editor.putInt(DEVICE_COUNT, 0)
                        device_count = 0
                        editor.apply()
                    }
                    val succeeded =
                        copy_identification_files_to_local_storage(treeUri, device_count)
                    if (succeeded) {
                        editor.putString("Index_$device_count", device_hash)
                        editor.putString(device_hash, treeUri.toString())
                        editor.putInt(DEVICE_COUNT, device_count + 1)
                        editor.apply()
                        Toast.makeText(applicationContext, "Done!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "Identification files could not be found!" +
                                    "Please choose a correct drive or " +
                                    "please insert correct device",
                            Toast.LENGTH_SHORT
                        ).show()
                        //openDocumentTree() // uncomment this
                    }

                }
            }
        }
    }

    private fun copy_identification_files_to_local_storage(treeUri: Uri, index: Int): Boolean {
        var dir_watch = DocumentFile.fromTreeUri(this, treeUri)
        var settings_file = dir_watch?.findFile(".settings.bin")
        var watch_id_file = dir_watch?.findFile(".watch.id")
        if (settings_file == null) {
            Log.v("_device_", "copy::Settings File could not be found!")
            return false;
        }
        if (watch_id_file == null) {
            Log.v("_device_", "copy::Watch ID File could not be found!")
            return false;
        }
        var identificationFolder = this.getExternalFilesDir("IdentificationsFolder")
        val docFil = identificationFolder?.let { DocumentFile.fromFile(it) }
        // Check if they exist ?????
        val copyied_settings_file = docFil?.createFile("*/bin", ".$index.settings.bin")

        if (copyied_settings_file != null && copyied_settings_file.canWrite()) {
            var inputStream = settings_file?.let { contentResolver.openInputStream(it.uri) }
            var outputStream =
                copyied_settings_file?.let { contentResolver.openOutputStream(it.uri) }
            val buf = ByteArray(1024)
            var len: Int = inputStream?.read(buf) ?: 0
            while (len > 0) {
                outputStream?.write(buf, 0, len)
                len = inputStream?.read(buf) ?: 0
            }
            outputStream?.close()
            inputStream?.close()
        } else {
            return false
        }
        val copyied_watch_id_file = docFil?.createFile("*/id", ".$index.watch.id")
        if (copyied_watch_id_file != null && copyied_watch_id_file.canWrite()) {
            var inputStream = watch_id_file?.let { contentResolver.openInputStream(it.uri) }
            var outputStream =
                copyied_watch_id_file?.let { contentResolver.openOutputStream(it.uri) }
            val buf = ByteArray(1024)
            var len: Int = inputStream?.read(buf) ?: 0
            while (len > 0) {
                outputStream?.write(buf, 0, len)
                len = inputStream?.read(buf) ?: 0
            }
            outputStream?.close()
            inputStream?.close()
        } else {
            return false
        }
        return true
    }

    private fun compare_identification_files_to_local_storage(treeUri: Uri, index: Int): Boolean {
        var dir_watch = DocumentFile.fromTreeUri(this, treeUri)
        var settings_file = dir_watch?.findFile(".settings.bin")
        var watch_id_file = dir_watch?.findFile(".watch.id")
        if (settings_file == null) {
            Log.v("_device_", "compare::Settings File could not be found!")
            Log.v("_device_", "uri::$treeUri")
            return false;
        }
        if (watch_id_file == null) {
            Log.v("_device_", "compare::Watch ID File could not be found!")
            Log.v("_device_", "uri::$treeUri")
            return false;
        }
        var identificationFolder = this.getExternalFilesDir("IdentificationsFolder")
        val docFil = identificationFolder?.let { DocumentFile.fromFile(it) }
        // Check if they exist ?????
        val saved_settings_file = docFil?.findFile(".$index.settings.bin")

        if (saved_settings_file != null && saved_settings_file.canWrite()) {
            var i_w = settings_file?.let { contentResolver.openInputStream(it.uri) }
            var i_s = saved_settings_file?.let { contentResolver.openInputStream(it.uri) }
            val buf_w = ByteArray(1024)
            val buf_s = ByteArray(1024)
            var len_w: Int = i_w?.read(buf_w) ?: 0
            var len_s: Int = i_s?.read(buf_s) ?: 0
            while (len_w > 0 || len_s > 0) {
                if (len_s != len_w) {
                    i_w?.close()
                    i_s?.close()
                    return false
                }
                for (i in 0 until len_w) {
                    if (buf_w[i] != buf_s[i]) {
                        i_w?.close()
                        i_s?.close()
                        return false
                    }
                }
                len_w = i_w?.read(buf_w) ?: 0
                len_s = i_s?.read(buf_s) ?: 0
            }
            i_w?.close()
            i_s?.close()
        } else {
            return false
        }
        val saved_watch_id_file = docFil?.findFile(".$index.watch.id")
        if (saved_watch_id_file != null && saved_watch_id_file.canWrite()) {
            var i_w = watch_id_file?.let { contentResolver.openInputStream(it.uri) }
            var i_s = saved_watch_id_file?.let { contentResolver.openInputStream(it.uri) }
            val buf_w = ByteArray(1024)
            val buf_s = ByteArray(1024)
            var len_w: Int = i_w?.read(buf_w) ?: 0
            var len_s: Int = i_s?.read(buf_s) ?: 0
            while (len_w > 0 || len_s > 0) {
                if (len_s != len_w) {
                    i_w?.close()
                    i_s?.close()
                    return false
                }
                for (i in 0 until len_w) {
                    if (buf_w[i] != buf_s[i]) {
                        i_w?.close()
                        i_s?.close()
                        return false
                    }
                }
                len_w = i_w?.read(buf_w) ?: 0
                len_s = i_s?.read(buf_s) ?: 0
            }
            i_w?.close()
            i_s?.close()
        } else {
            return false
        }
        return true
    }

    private fun checkFolderExist(dirUri: Uri, timeout: Long): Boolean {
        val date_start = Date().time
        var dir = DocumentFile.fromTreeUri(this, dirUri)
        var time_left = Date().time - date_start
        while ((time_left < timeout) && (dir == null || !dir.exists())) {
            dir = DocumentFile.fromTreeUri(this, dirUri)
            time_left = Date().time - date_start
        }
        if (Date().time - date_start > timeout) {
            Toast.makeText(applicationContext, "Timeout has accured", Toast.LENGTH_SHORT).show()
            releasePermissions(dirUri)
            return false
        }
        return true
    }

    private fun releasePermissions(uri: Uri) {
        val flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.releasePersistableUriPermission(uri, flags)

        val pref = this.getPreferences(Context.MODE_PRIVATE)
        val editor = pref.edit()
        editor.putString(FOLDER_URI, "")
        editor.apply()
    }

    private fun arePermissionsGranted(uriString: String): Boolean {
        // list of all persisted permissions for our app
        val list = contentResolver.persistedUriPermissions
        Log.v("_device_", "to compare: $uriString")
        for (i in list.indices) {
            val persistedUriString = list[i].uri.toString()
            Log.v("_device_", "compare with: $persistedUriString")

            if (persistedUriString == uriString && list[i].isWritePermission && list[i].isReadPermission) {
                return true
            }
        }
        return false
    }

    private fun handleIntent(intent: Intent) {

        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null) {
            usbManager.requestPermission(device, permissionIntent)
            var succeeded = readDeviceDetails(device)
            if (!succeeded) {
                Toast.makeText(
                    applicationContext,
                    "Could not read device details",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val pref = this.getPreferences(Context.MODE_PRIVATE)
            val editor = pref.edit()
            var device_count = pref.getInt(DEVICE_COUNT, -1)
            if (device_count == -1) {
                editor.putInt(DEVICE_COUNT, 0)
                device_count = 0
                editor.apply()
            }
            if (device_count == 0) {
                openDocumentTree()
            } else {
                val device_hash = hash()
                for (index in 0 until device_count) {
                    val saved_hash = pref.getString("Index_$index", "")
                    if (saved_hash == "") continue
                    if (saved_hash == device_hash) {
                        var confirmed = confirm_the_device(device_hash, index)
                        if (confirmed) {
                            Toast.makeText(applicationContext, "Confirmed", Toast.LENGTH_SHORT)
                                .show()
                            Log.v("_device_", "Confirmed")
                            return
                        }
                    }
                }
                openDocumentTree()
            }
        }
    }

    private fun confirm_the_device(device_hash: String, index: Int): Boolean {
        val pref = this.getPreferences(Context.MODE_PRIVATE)
        val folder_uri_string = pref.getString(device_hash, "")
        if (folder_uri_string == "") return false
        val folder_uri = Uri.parse(folder_uri_string)
        if (checkFolderExist(folder_uri, 10 * 1000)) {
            if (compare_identification_files_to_local_storage(folder_uri, index)) {
                Toast.makeText(applicationContext, "Watch is recognized", Toast.LENGTH_SHORT).show()
                return true
            }
            return false
        } else {
            return false
        }
    }

    private fun readDeviceDetails(device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device)
        var succeeded = true
        data_to_hash = try {
            //Parse the raw device descriptor
            connection.readDeviceDescriptor()
        } catch (e: IllegalArgumentException) {
            Log.v("_device_", "Invalid device descriptor", e)
            succeeded = false
            ByteArray(32)
        }

        val configDescriptor = try {
            //Parse the raw configuration descriptor
            connection.readConfigurationDescriptor()
        } catch (e: IllegalArgumentException) {
            Log.v("_device_", "Invalid config descriptor", e)
            succeeded = false
            null
        } catch (e: ParseException) {
            Log.v("_device_", "Unable to parse config descriptor", e)
            succeeded = false
            null
        }

        connection.close()
        return succeeded
    }

}