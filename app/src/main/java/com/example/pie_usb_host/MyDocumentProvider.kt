package com.example.pie_usb_host

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.StringBuilder
import java.util.HashSet


class MyDocumentProvider : DocumentsProvider() {

    private val DEFAULT_ROOT_PROJECTION: Array<String?>? = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    )
    private val DEFAULT_DOCUMENT_PROJECTION: Array<String?>? = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    public var mBaseDir: File? = null
    private val MAX_SEARCH_RESULTS = 20
    private val MAX_LAST_MODIFIED = 5

    private val ROOT = "root"

    override fun onCreate(): Boolean {
        mBaseDir = context!!.filesDir
        return true
    }

    override fun queryRoots(projection: Array<String?>?): Cursor {
        // Use a MatrixCursor to build a cursor
        // with either the requested fields, or the default
        // projection if "projection" is null.
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        // It's possible to have multiple roots (e.g. for multiple accounts in the
        // same app) -- just add multiple cursor rows.
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT)

            // You can provide an optional summary, which helps distinguish roots
            // with the same title. You can also use this field for displaying an
            // user account name.
            add(DocumentsContract.Root.COLUMN_SUMMARY, context?.getString(R.string.root_summary))

            // FLAG_SUPPORTS_CREATE means at least one directory under the root supports
            // creating documents. FLAG_SUPPORTS_RECENTS means your application's most
            // recently used documents will show up in the "Recents" category.
            // FLAG_SUPPORTS_SEARCH allows users to search all documents the application
            // shares.
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
                        DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
            )

            // COLUMN_TITLE is the root title (e.g. Gallery, Drive).
            add(DocumentsContract.Root.COLUMN_TITLE, context?.getString(R.string.title))

            // This document id cannot change after it's shared.
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, mBaseDir?.let { getDocIdForFile(it) })

            // The child MIME types are used to filter the roots and only present to the
            // user those roots that contain the desired type somewhere in their file hierarchy.
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, mBaseDir?.let { getChildMimeTypes(it) })
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, mBaseDir?.freeSpace)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<String?>?): Cursor {
        // Create a cursor with the requested projection, or the default projection.
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            includeFile(this, documentId, null)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<String?>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            val parent: File? = parentDocumentId?.let { getFileForDocId(it) }
            parent?.listFiles()
                ?.forEach { file ->
                    includeFile(this, null, file)
                }
        }
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.v(TAG, "openDocument, mode: $mode")
        // It's OK to do network operations in this method to download the document,
        // as long as you periodically check the CancellationSignal. If you have an
        // extremely large file to transfer from the network, a better solution may
        // be pipes or sockets (see ParcelFileDescriptor for helper methods).

        val file: File? = documentId?.let { getFileForDocId(it) }
        val accessMode: Int = ParcelFileDescriptor.parseMode(mode)

        val isWrite: Boolean = mode?.contains("w") ?: false
        return if (isWrite) {
            val handler = context?.mainLooper?.let { Handler(it) }
            // Attach a close listener if the document is opened in write mode.
            try {
                ParcelFileDescriptor.open(file, accessMode, handler) {
                    // Update the file with the cloud server. The client is done writing.
                    Log.i(
                        TAG,
                        "A file with id $documentId has been closed! Time to update the server."
                    )
                }
            } catch (e: IOException) {
                throw FileNotFoundException(
                    "Failed to open document with id $documentId and mode $mode"
                )
            }
        } else {
            ParcelFileDescriptor.open(file, accessMode)
        }
    }

    override fun createDocument(
        documentId: String?,
        mimeType: String?,
        displayName: String?
    ): String? {
        val parent: File? = documentId?.let { getFileForDocId(it) }
        val file: File = try {
            File(parent?.path, displayName ?: "Default File Name").apply {
                createNewFile()
                setWritable(true)
                setReadable(true)
            }
        } catch (e: IOException) {
            throw FileNotFoundException(
                "Failed to create document with name $displayName and documentId $documentId"
            )
        }

        return getDocIdForFile(file)
    }


    private fun getTypeForFile(file: File): String? {
        return if (file.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getTypeForName(file.name)
        }
    }

    private fun getTypeForName(name: String): String? {
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) {
                return mime
            }
        }
        return "application/octet-stream"
    }

    private fun getChildMimeTypes(parent: File): String? {
        val mimeTypes: MutableSet<String> = HashSet()
        mimeTypes.add("image/*")
        mimeTypes.add("text/*")
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document")

        // Flatten the list into a string and insert newlines between the MIME type strings.
        val mimeTypesString = StringBuilder()
        for (mimeType in mimeTypes) {
            mimeTypesString.append(mimeType).append("\n")
        }
        return mimeTypesString.toString()
    }

    private fun getDocIdForFile(file: File): String? {
        var path = file.absolutePath

        // Start at first char of path under root
        val rootPath: String = mBaseDir?.getPath() ?: ""
        path = if (rootPath == path) {
            ""
        } else if (rootPath.endsWith("/")) {
            path.substring(rootPath.length)
        } else {
            path.substring(rootPath.length + 1)
        }
        return "root:$path"
    }

    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, docIdp: String?, filep: File?) {
        var docId: String? = docIdp
        var file = filep
        if (docId == null) {
            docId = file?.let { getDocIdForFile(it) }
        } else {
            file = getFileForDocId(docId)
        }
        var flags = 0
        if (file != null) {
            if (file.isDirectory) {
                // Request the folder to lay out as a grid rather than a list. This also allows a larger
                // thumbnail to be displayed for each image.
                //            flags |= Document.FLAG_DIR_PREFERS_GRID;

                // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
                if (file.isDirectory && file.canWrite()) {
                    flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                }
            } else if (file.canWrite()) {
                // If the file is writable set FLAG_SUPPORTS_WRITE and
                // FLAG_SUPPORTS_DELETE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            }
        }
        val displayName = file?.name
        val mimeType: String? = file?.let { getTypeForFile(it) }
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) {
                // Allow the image to be represented by a thumbnail rather than an icon
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
            }
        }
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
        if (file != null) {
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        }
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        if (file != null) {
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)

        // Add a custom icon
        row.add(DocumentsContract.Document.COLUMN_ICON, R.drawable.ic_launcher_foreground)
    }

    @Throws(FileNotFoundException::class)
    private fun getFileForDocId(docId: String): File? {
        var target: File? = mBaseDir
        if (docId == ROOT) {
            return target
        }
        val splitIndex = docId.indexOf(':', 1)
        return if (splitIndex < 0) {
            throw FileNotFoundException("Missing root for $docId")
        } else {
            val path = docId.substring(splitIndex + 1)
            target = File(target, path)
            if (!target.exists()) {
                throw FileNotFoundException("Missing file for $docId at $target")
            }
            target
        }
    }

    private fun writeDummyFilesToStorage() {
        if (mBaseDir?.list()?.size!! > 0) {
            return
        }
        val imageResIds: IntArray? = getResourceIdArray(R.array.image_res_ids)
        for (resId in imageResIds!!) {
            writeFileToInternalStorage(resId, ".jpeg")
        }
        val textResIds: IntArray? = getResourceIdArray(R.array.text_res_ids)
        for (resId in textResIds!!) {
            writeFileToInternalStorage(resId, ".txt")
        }
        val docxResIds: IntArray? = getResourceIdArray(R.array.docx_res_ids)
        for (resId in docxResIds!!) {
            writeFileToInternalStorage(resId, ".docx")
        }
    }

    private fun writeFileToInternalStorage(resId: Int, extension: String) {
        val ins = context!!.resources.openRawResource(resId)
        val outputStream = ByteArrayOutputStream()
        var size: Int
        var buffer = ByteArray(1024)
        try {
            while (ins.read(buffer, 0, 1024).also { size = it } >= 0) {
                outputStream.write(buffer, 0, size)
            }
            ins.close()
            buffer = outputStream.toByteArray()
            val filename = context!!.resources.getResourceEntryName(resId) + extension
            val fos = context!!.openFileOutput(filename, Context.MODE_PRIVATE)
            fos.write(buffer)
            fos.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getResourceIdArray(arrayResId: Int): IntArray? {
        val ar = context!!.resources.obtainTypedArray(arrayResId)
        val len = ar.length()
        val resIds = IntArray(len)
        for (i in 0 until len) {
            resIds[i] = ar.getResourceId(i, 0)
        }
        ar.recycle()
        return resIds
    }
}