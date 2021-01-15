package com.dmitrybrant.modelviewer

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentResolverCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dmitrybrant.modelviewer.gvr.ModelGvrActivity
import com.dmitrybrant.modelviewer.obj.ObjModel
import com.dmitrybrant.modelviewer.ply.PlyModel
import com.dmitrybrant.modelviewer.stl.StlModel
import com.dmitrybrant.modelviewer.util.Util.closeSilently
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

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
class MainActivity : AppCompatActivity() {
    private var modelView: ModelSurfaceView? = null
    private var containerView: ViewGroup? = null
    private var progressBar: ProgressBar? = null
    private var vrButton: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        containerView = findViewById(R.id.container_view)
        progressBar = findViewById(R.id.model_progress_bar)
        progressBar!!.setVisibility(View.GONE)
        progressBar = findViewById(R.id.model_progress_bar)
        vrButton = findViewById(R.id.vr_fab)
        vrButton!!.setOnClickListener { startVrActivity() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container_view)) { v: View?, insets: WindowInsetsCompat ->
            val params = vrButton!!.layoutParams as FrameLayout.LayoutParams
            params.topMargin = insets.systemWindowInsetTop
            params.bottomMargin = insets.systemWindowInsetBottom
            params.leftMargin = insets.systemWindowInsetLeft
            params.rightMargin = insets.systemWindowInsetRight
            insets.consumeSystemWindowInsets()
        }

        if (intent.data != null && savedInstanceState == null) {
            beginLoadModel(intent.data!!)
        }
    }

    override fun onStart() {
        super.onStart()
        createNewModelView(ModelViewerApplication.instance!!.currentModel)
        if (ModelViewerApplication.instance!!.currentModel != null) {
            title = ModelViewerApplication.instance!!.currentModel!!.title
        }
    }

    override fun onPause() {
        super.onPause()
        if (modelView != null) {
            modelView!!.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (modelView != null) {
            modelView!!.onResume()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_open_model -> {
                checkReadPermissionThenOpen()
                true
            }
            R.id.menu_load_sample -> {
                loadSampleModel()
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            READ_PERMISSION_REQUEST -> if (grantResults.size > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginOpenModel()
            } else {
                Toast.makeText(this, R.string.read_permission_failed, Toast.LENGTH_SHORT).show()
            }
            else -> {
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == OPEN_DOCUMENT_REQUEST && resultCode == RESULT_OK && resultData!!.data != null) {
            val uri = resultData.data
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            beginLoadModel(uri!!)
        }
    }

    private fun checkReadPermissionThenOpen() {
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    READ_PERMISSION_REQUEST)
        } else {
            beginOpenModel()
        }
    }

    private fun beginOpenModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        startActivityForResult(intent, OPEN_DOCUMENT_REQUEST)
    }

    private fun beginLoadModel(uri: Uri) {
        progressBar!!.visibility = View.VISIBLE
        ModelLoadTask().execute(uri)
    }

    private fun createNewModelView(model: Model?) {
        if (modelView != null) {
            containerView!!.removeView(modelView)
        }
        ModelViewerApplication.instance!!.currentModel = model
        modelView = ModelSurfaceView(this, model)
        containerView!!.addView(modelView, 0)
    }

    private inner class ModelLoadTask : AsyncTask<Uri?, Int?, Model?>() {

        override fun doInBackground(vararg file: Uri?): Model? {
            var stream: InputStream? = null
            try {
                val uri = file[0]
                val cr = applicationContext.contentResolver
                val fileName = getFileName(cr, uri!!)
                stream = if ("http" == uri.scheme || "https" == uri.scheme) {
                    val client = OkHttpClient()
                    val request: Request = Request.Builder().url(uri.toString()).build()
                    val response = client.newCall(request).execute()

                    // TODO: figure out how to NOT need to read the whole file at once.
                    ByteArrayInputStream(response.body!!.bytes())
                } else {
                    cr.openInputStream(uri)
                }
                if (stream != null) {
                    val model: Model
                    if (!TextUtils.isEmpty(fileName)) {
                        model = if (fileName!!.toLowerCase(Locale.ROOT).endsWith(".stl")) {
                            StlModel(stream)
                        } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".obj")) {
                            ObjModel(stream)
                        } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".ply")) {
                            PlyModel(stream)
                        } else {
                            // assume it's STL.
                            StlModel(stream)
                        }
                        model.title = fileName
                    } else {
                        // assume it's STL.
                        // TODO: autodetect file type by reading contents?
                        model = StlModel(stream)
                    }
                    return model
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                closeSilently(stream)
            }
            return null
        }

        override fun onPostExecute(model: Model?) {
            if (isDestroyed) {
                return
            }
            if (model != null) {
                setCurrentModel(model)
            } else {
                Toast.makeText(applicationContext, R.string.open_model_error, Toast.LENGTH_SHORT).show()
                progressBar!!.visibility = View.GONE
            }
        }

        private fun getFileName(cr: ContentResolver, uri: Uri): String? {
            if ("content" == uri.scheme) {
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                val metaCursor = ContentResolverCompat.query(cr, uri, projection, null, null, null, null)
                if (metaCursor != null) {
                    try {
                        if (metaCursor.moveToFirst()) {
                            return metaCursor.getString(0)
                        }
                    } finally {
                        metaCursor.close()
                    }
                }
            }
            return uri.lastPathSegment
        }
    }

    private fun setCurrentModel(model: Model) {
        createNewModelView(model)
        Toast.makeText(applicationContext, R.string.open_model_success, Toast.LENGTH_SHORT).show()
        title = model.title
        progressBar!!.visibility = View.GONE
    }

    private fun startVrActivity() {
        if (ModelViewerApplication.instance!!.currentModel == null) {
            Toast.makeText(this, R.string.view_vr_not_loaded, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, ModelGvrActivity::class.java))
        }
    }

    private fun loadSampleModel() {
        try {
            val stream = applicationContext.assets
                    .open(SAMPLE_MODELS[sampleModelIndex++ % SAMPLE_MODELS.size])
            setCurrentModel(StlModel(stream))
            stream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    companion object {
        private const val READ_PERMISSION_REQUEST = 100
        private const val OPEN_DOCUMENT_REQUEST = 101
        private val SAMPLE_MODELS = arrayOf("bunny.stl", "dragon.stl", "lucy.stl")
        private var sampleModelIndex = 0
    }
}
