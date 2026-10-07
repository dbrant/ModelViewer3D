package com.dmitrybrant.modelviewer.vdb

import com.dmitrybrant.modelviewer.Model
import java.io.InputStream

/*
* Loads an OpenVDB file as the kind of model that suits it: fog volumes (e.g. smoke or clouds) are
* rendered as volumes, and level sets are shown as their surface.
*
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
object VdbLoader {
    fun load(inputStream: InputStream): Model {
        // What's displayed is prepared from the grids before the model is created, so that the
        // voxel data can be released before then.
        val createModel = prepare(VdbReader(inputStream).read())
        return createModel()
    }

    private fun prepare(grids: VdbReader.Grids): () -> Model {
        val grid = grids.surface
        if (!grid.isLevelSet) {
            // A volume that's too large to resample is shown as a surface instead.
            VolumeTexture.build(grid, grids.color, VolumeTexture.MAX_BYTES)?.let { volume ->
                val gridName = grid.name
                val colorGridName = grids.color?.name
                return { VdbVolumeModel(volume, gridName, colorGridName) }
            }
        }
        val surface = VdbModel.extractSurface(grids)
        return { VdbModel(surface) }
    }
}
