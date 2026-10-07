package com.dmitrybrant.modelviewer

import android.content.ContentResolver
import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/*
* Copyright 2026 Dmitry Brant. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

/**
 * Finds the files that a model refers to: among the files that the user selected along with the
 * model (matched by file name), or else next to the model itself, if it was opened from a web or
 * file URL. (Documents opened through the system file picker don't give access to their neighbors.)
 */
class UriModelResources(
    private val contentResolver: ContentResolver,
    private val modelUri: Uri,
    /** Other selected files, keyed by their lowercase file names. */
    private val selectedFiles: Map<String, Uri>,
    /** The URL from which the model was downloaded, if any. */
    private val modelUrl: HttpUrl?
) : ModelResources {
    /** File names that the model asked for, but which couldn't be found. */
    val missingFiles = mutableListOf<String>()

    private val client by lazy { OkHttpClient() }

    override fun open(path: String): InputStream? {
        val stream = try {
            openFile(path)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (stream == null) {
            missingFiles.add(ModelResources.fileName(path))
        }
        return stream
    }

    private fun openFile(path: String): InputStream? {
        val normalizedPath = ModelResources.normalizePath(path)
        val fileName = ModelResources.fileName(path)
        selectedFiles[fileName.lowercase()]?.let {
            return contentResolver.openInputStream(it)
        }
        // Try the path relative to the model, and then just the file name, next to the model.
        val candidates = listOf(normalizedPath, fileName).distinct()
        if (modelUrl != null) {
            for (candidate in candidates) {
                val url = modelUrl.resolve(candidate) ?: continue
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        return ByteArrayInputStream(response.body.bytes())
                    }
                }
            }
        } else if (modelUri.scheme == "file") {
            val dir = File(modelUri.path ?: return null).parentFile
            for (candidate in candidates) {
                val file = File(dir, candidate)
                if (file.isFile) {
                    return FileInputStream(file)
                }
            }
        }
        return null
    }
}
