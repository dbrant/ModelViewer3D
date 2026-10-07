# ModelViewer3D

3D model viewer app for Android! Supports STL files (ASCII and binary), and has limited support for OBJ (Wavefront) and PLY (Stanford) files.

Also supports OpenVDB (.vdb) volumes: level sets are displayed as their surface, and fog volumes (such as smoke or clouds) are displayed as the surface where their density reaches 10% of its maximum. The first float or double grid in the file is shown.

Sample models from the Stanford [3D Scanning Repository](https://graphics.stanford.edu/data/3Dscanrep/), decimated using Blender.

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
