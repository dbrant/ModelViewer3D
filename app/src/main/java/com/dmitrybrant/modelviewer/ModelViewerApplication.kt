package com.dmitrybrant.modelviewer

import android.app.Application

/*
* Copyright 2017 Dmitry Brant. All rights reserved.
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
class ModelViewerApplication : Application() {
    // Store the current model globally, so that we don't have to re-decode it upon
    // relaunching the main or VR activities.
    // TODO: handle this a bit better.
    var currentModel: Model? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        var instance: ModelViewerApplication? = null
            private set
    }
}