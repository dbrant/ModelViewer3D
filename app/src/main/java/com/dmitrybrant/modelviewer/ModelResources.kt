package com.dmitrybrant.modelviewer

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
 * Provides the other files that a model refers to, such as an OBJ file's material libraries and textures.
 */
fun interface ModelResources {
    /**
     * Opens the file with the given path, as written in the model (usually relative to the
     * model's own location), or returns null if the file isn't available.
     */
    fun open(path: String): InputStream?

    companion object {
        /** Returns just the file name of a path as written in a model file, which may use either kind of slash. */
        fun fileName(path: String) = normalizePath(path).substringAfterLast('/')

        fun normalizePath(path: String) = path.trim().removeSurrounding("\"").replace('\\', '/')
    }
}
