package com.jarves.mh.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * The document tree the user picked, read as a [FolderSource].
 *
 * Built on the platform's own `DocumentsContract` instead of a helper library, so the import needs no
 * extra dependency: listing a directory is one projection query, and opening a file is one stream on the
 * document uri. Android's tree access is read-only here — nothing is written back into the picked folder.
 */
class SafFolderSource(private val context: Context, private val treeUri: Uri) : FolderSource {

    private val resolver = context.contentResolver

    /** Relative path ("" is the picked folder) to the document id the provider gave it. */
    private val documentIds = mutableMapOf("" to DocumentsContract.getTreeDocumentId(treeUri))

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    override val rootName: String by lazy {
        val id = documentIds.getValue("")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "imported"
    }

    override fun children(relativePath: String): List<FolderSource.Node> {
        val parentId = documentIds[relativePath] ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = mutableListOf<FolderSource.Node>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(projection[0])
            val nameIndex = cursor.getColumnIndex(projection[1])
            val mimeIndex = cursor.getColumnIndex(projection[2])
            val sizeIndex = cursor.getColumnIndex(projection[3])
            if (idIndex < 0 || nameIndex < 0) return@use
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val path = if (relativePath.isEmpty()) name else "$relativePath/$name"
                documentIds[path] = id
                result += FolderSource.Node(
                    name = name,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = size,
                )
            }
        }
        return result
    }

    override fun open(relativePath: String): InputStream {
        val id = documentIds[relativePath] ?: error("document is not part of the picked folder")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        return resolver.openInputStream(uri) ?: error("document could not be opened")
    }
}
