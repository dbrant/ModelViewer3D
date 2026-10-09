# ModelViewer3D

3D model viewer app for Android! Supports STL files (ASCII and binary), and has limited support for OBJ (Wavefront) and PLY (Stanford) files.

Also supports OpenVDB (.vdb) volumes: level sets are displayed as their surface, and fog volumes (such as smoke or clouds) are rendered as translucent volumes, by ray marching through their density on the GPU, with light from above that the volume shadows itself. The first float or double grid in the file is shown. If the volume also has a temperature grid (named "temperature") or a grid of flames (named "flames" or "flame", as from EmberGen or Blender), its hot parts glow like fire, with the colors of a blackbody.

Requires a device that supports OpenGL ES 3.0.

Models are shown with their colors and materials, when the file has them:
* OBJ: materials (colors, shininess, transparency) and diffuse textures, from the material (.mtl) files that the model refers to. Since Android only gives access to the files that you pick, select the .mtl and texture files together with the .obj file when opening it (long-press to select multiple files). Models opened from a web link find their files automatically. OBJ files with vertex colors are also supported.
* PLY: vertex colors.
* STL: per-facet colors in binary STL files (as written by VisCAM/SolidView or Materialise Magics).
* VDB: a color grid (named "Cd" or "color") alongside the volume.

Sample models from the Stanford [3D Scanning Repository](https://graphics.stanford.edu/data/3Dscanrep/), decimated using Blender.

## Building

VR mode uses the [Google Cardboard SDK](https://github.com/googlevr/cardboard), which is built from source from the `third_party/cardboard` submodule. Clone the repository with its submodules:

```
git clone --recurse-submodules https://github.com/dbrant/ModelViewer3D.git
```

or, in an existing clone, run `git submodule update --init`. Building the SDK requires the Android NDK and CMake, which Android Studio installs from the SDK Manager.

## License

Copyright 2017+ Dmitry Brant

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
