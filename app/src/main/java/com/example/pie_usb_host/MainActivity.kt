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
import java.util.*


const val FOLDER_URI = "folder_uri"
const val TAG = "UsbEnumerator"
private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
private const val FLASH_DRIVE_URI =
    "content://com.android.externalstorage.documents/tree/612D-B6E0%3A"

class MainActivity : AppCompatActivity() {
    val LOGTAG = "MainActivity"
    val REQUEST_CODE = 12123

    private lateinit var usbManager: UsbManager

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var permissionIntent: PendingIntent

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
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            device?.let {
                printStatus(getString(R.string.status_removed))
                printDeviceDescription(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        // read uriString from shared preferences
        Log.v("_device_", "openDocumentTree")
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
        Log.v("_device_", "onActivityResult")
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE) {
            Log.v("_device_", "ok")
            if (data != null) {
                Log.v("_device_", "data ok")
                //this is the uri user has provided us
                val treeUri: Uri? = data.data
                Log.v("_device_", "data = " + treeUri.toString())
                if (treeUri != null) {
                    Log.v("_device_", "treeUri ok")
                    if (Uri.decode(treeUri.toString()).endsWith(":") == false) {
                        Log.v("_device_", "Please choose root folder!")
                        openDocumentTree()
                        return
                    }
                    // here we ask the content resolver to persist the permission for us
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                    Log.v("_device_", "perm given")

                    if (checkFolderExist(treeUri, 5000) == false) {
                        Log.v("_device_", "Please choose root folder of the watch!")
                        releasePermissions(treeUri)
                        openDocumentTree()
                        return
                    }
                    Log.v("_device_", "folder exist")

                    // we should store the string fo further use
                    val pref = this.getPreferences(Context.MODE_PRIVATE)
                    val editor = pref.edit()
                    editor.putString(FOLDER_URI, treeUri.toString())
                    editor.apply()

                    copy_identification_files_to_local_storage(treeUri)
                    Log.v("_device_", "Done")

                }
            }
        }
    }

    private fun copy_identification_files_to_local_storage(treeUri: Uri) {
        Log.v("_device_", "copy_identification_files_to_local_storage")
        var dir_watch = DocumentFile.fromTreeUri(this, treeUri)
        var sett = dir_watch?.findFile(".settings.bin")
        if(sett ==null) Log.v("_device_", "got problem")
        var file_app = this.getExternalFilesDir("CopyLocation")
        if (file_app != null) {
            val dest = DocumentFile.fromFile(file_app)
            val file = dest.createFile("*/txt","test.txt")
            if (file != null && file.canWrite()) {
                Log.v("_device_", "file.uri = ${file.uri.toString()}")
                var inputStream = sett?.let { contentResolver.openInputStream(it.uri) }
                var outputStream = file?.let { contentResolver.openOutputStream(it.uri) }
                val buf = ByteArray(1024)
                var len : Int = inputStream?.read(buf)?:0
                Log.v("_device_", "len: $len")
                while (len > 0) {
                    outputStream?.write(buf, 0, len)
                    len = inputStream?.read(buf)?:0
                    Log.v("_device_", "len: $len")
                }
                outputStream?.close()
                inputStream?.close()
            }
        }
    }

    private fun checkFolderExist(dirUri: Uri, timeout: Long): Boolean {

        val date_start = Date().time
        var dir = DocumentFile.fromTreeUri(this, dirUri)
        var time_left = Date().time - date_start
        while ((time_left < timeout) && (dir == null || !dir.exists())) {
            dir = DocumentFile.fromTreeUri(this, dirUri)
            time_left = Date().time - date_start
            Log.v("_device_", "$time_left - $dirUri")
        }
        if (Date().time - date_start > timeout) {
            Toast.makeText(applicationContext, "Timeout has accured", Toast.LENGTH_SHORT).show()
            releasePermissions(dirUri)
            return false
        }
        return true
        //dir.listFiles().forEach { Log.v("_device_", "" + it.name) }
    }

    private fun makeDoc(dirUri: Uri) {
        // content://com.android.externalstorage.documents/tree/612D-B6E0%3A
        val dir = DocumentFile.fromTreeUri(this, dirUri)
        dirUri.path?.let { Log.v("_device_", it) }
        if (dir == null || !dir.exists()) {
            //the folder was probably deleted
            Log.v("_device_", "no Dir")
            //according to Commonsware blog, the number of persisted uri permissions is limited
            //so we should release those we cannot use anymore
            //https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html
            releasePermissions(dirUri)
            //ask user to choose another folder
            Toast.makeText(this, "Folder deleted, please choose another!", Toast.LENGTH_SHORT)
                .show()
            openDocumentTree()
        } else {
            val file = dir.createFile("*/txt", "test.txt")

            if (file != null && file.canWrite()) {
                Log.v("_device_", "file.uri = ${file.uri.toString()}")
                alterDocument(file.uri)
            } else {
                Log.v("_device_", "no file or cannot write")
                //consider showing some more appropriate error message
                Toast.makeText(this, "Write error!", Toast.LENGTH_SHORT).show()

            }
        }
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

    private fun alterDocument(uri: Uri) {
        try {
            contentResolver.openFileDescriptor(uri, "w")?.use { parcelFileDescriptor ->
                FileOutputStream(parcelFileDescriptor.fileDescriptor).use {
                    it.write(
                        ("String written at ${System.currentTimeMillis()}\n")
                            .toByteArray()
                    )
                    Toast.makeText(this, "File Write OK!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
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
            printStatus(getString(R.string.status_added))
            printDeviceDetails(device)
            openDocumentTree()
        } else {
            // List all devices connected to USB host on startup
            printStatus(getString(R.string.status_list))
            printDeviceList()
        }
    }

    private fun printDeviceList() {
        val connectedDevices = usbManager.deviceList

        if (connectedDevices.isEmpty()) {
            printResult("No Devices Currently Connected")
        } else {
            val builder = buildString {
                append("Connected Device Count: ")
                append(connectedDevices.size)
                append("\n\n")
                for (device in connectedDevices.values) {
                    //Use the last device detected (if multiple) to open
                    append(device.getDescription())
                    append("\n\n")
                }
            }

            printResult(builder)
        }
    }

    private fun printDeviceDescription(device: UsbDevice) {
        val result = device.getDescription() + "\n\n"
        printResult(result)
    }

    private fun printDeviceDetails(device: UsbDevice) {
        return
        val connection = usbManager.openDevice(device)

        val deviceDescriptor = try {
            //Parse the raw device descriptor
            connection.readDeviceDescriptor()
        } catch (e: IllegalArgumentException) {
            Log.v("_device_", "Invalid device descriptor", e)
            null
        }

        val configDescriptor = try {
            //Parse the raw configuration descriptor
            connection.readConfigurationDescriptor()
        } catch (e: IllegalArgumentException) {
            Log.v("_device_", "Invalid config descriptor", e)
            null
        } catch (e: ParseException) {
            Log.v("_device_", "Unable to parse config descriptor", e)
            null
        }

        printResult("$deviceDescriptor\n\n$configDescriptor")
        connection.close()
    }

    private fun printStatus(status: String) {
        statusView.text = status
    }

    private fun printResult(result: String) {
        resultView.text = result
    }
}