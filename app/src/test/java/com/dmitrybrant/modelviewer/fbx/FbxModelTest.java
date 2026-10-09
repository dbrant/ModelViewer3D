package com.dmitrybrant.modelviewer.fbx;

import com.dmitrybrant.modelviewer.Material;
import com.dmitrybrant.modelviewer.MeshPart;
import com.dmitrybrant.modelviewer.ModelResources;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.Assert.*;

public class FbxModelTest {
    private static final float TOLERANCE = 1e-5f;
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4, 5};

    // A quad in the XY plane, facing +Z, in a file whose Z axis is up and whose front is -Y (as from 3ds Max).
    private static final String ASCII_QUAD = """
            ; FBX 7.4.0 project file
            ; ----------------------------------------------------
            FBXHeaderExtension:  {
            	FBXHeaderVersion: 1003
            	FBXVersion: 7400
            }
            GlobalSettings:  {
            	Version: 1000
            	Properties70:  {
            		P: "UpAxis", "int", "Integer", "",2
            		P: "UpAxisSign", "int", "Integer", "",1
            		P: "FrontAxis", "int", "Integer", "",1
            		P: "FrontAxisSign", "int", "Integer", "",-1
            		P: "CoordAxis", "int", "Integer", "",0
            		P: "CoordAxisSign", "int", "Integer", "",1
            	}
            }

            ; Object properties
            ;------------------------------------------------------------------
            Objects:  {
            	Geometry: 100, "Geometry::Quad", "Mesh" {
            		Vertices: *12 {
            			a: 0,0,0,1.5e0,0,0,
            			1.5,1,0,0,1,0
            		}
            		PolygonVertexIndex: *4 {
            			a: 0,1,2,-4
            		}
            		GeometryVersion: 124
            	}
            	Model: 200, "Model::Quad", "Mesh" {
            		Version: 232
            		Properties70:  {
            			P: "Lcl Translation", "Lcl Translation", "", "A",10,0,0
            		}
            		Shading: T
            		Culling: "CullingOff"
            	}
            	Material: 300, "Material::Red", "" {
            		ShadingModel: "phong"
            		Properties70:  {
            			P: "DiffuseColor", "Color", "", "A",1,0,0
            			P: "DiffuseFactor", "Number", "", "A",0.5
            			P: "SpecularColor", "Color", "", "A",0.25,0.25,0.25
            			P: "ShininessExponent", "Number", "", "A",20
            		}
            	}
            	AnimationStack: 400, "AnimStack::Take 001", "" {
            		Properties70:  {
            			P: "LocalStop", "KTime", "Time", "",46186158000
            		}
            	}
            }

            ; Object connections
            ;------------------------------------------------------------------
            Connections:  {
            	;Model::Quad, Model::RootNode
            	C: "OO",200,0
            	C: "OO",100,200
            	C: "OO",300,200
            	C: "OO",400,0
            }
            """;

    @Test
    public void testAscii() throws Exception {
        FbxModel model = load(ASCII_QUAD, null);
        assertEquals(6, model.getVertexCount());
        // The quad is moved to X = 10, and then turned so that the Z axis is up (Y, in the app).
        assertPositions(model, 0, new float[] {10, 0, 0, 11.5f, 0, 0, 11.5f, 0, -1});
        assertPositions(model, 3, new float[] {10, 0, 0, 11.5f, 0, -1, 10, 0, -1});
        for (int v = 0; v < 6; v++) {
            assertNormal(model, v, 0, 1, 0);
        }
        assertNull(model.getTexCoordBuffer());
        assertEquals(1, model.getParts().size());
        Material material = model.getParts().get(0).getMaterial();
        assertArrayEquals(new float[] {0.5f, 0, 0}, material.getDiffuseColor(), TOLERANCE);
        assertArrayEquals(new float[] {0.25f, 0.25f, 0.25f}, material.getSpecularColor(), TOLERANCE);
        assertEquals(20, material.getShininess(), TOLERANCE);
    }

    @Test
    public void testBinary() throws Exception {
        for (int version : new int[] {7400, 7500}) {
            // A quad that's split into two triangles, with a different material for each, of which one has a texture.
            Node objects = node("Objects").add(
                    node("Geometry", 100L, "Quad\0\1Geometry", "Mesh").add(
                            node("Vertices", new double[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}),
                            node("PolygonVertexIndex", new int[] {0, 1, -3, 0, 2, -4}),
                            node("LayerElementUV", 0).add(
                                    node("MappingInformationType", "ByPolygonVertex"),
                                    node("ReferenceInformationType", "IndexToDirect"),
                                    node("UV", new double[] {0, 0, 1, 0, 1, 1, 0, 1}),
                                    node("UVIndex", new int[] {0, 1, 2, 0, 2, 3})),
                            node("LayerElementMaterial", 0).add(
                                    node("MappingInformationType", "ByPolygon"),
                                    node("ReferenceInformationType", "IndexToDirect"),
                                    node("Materials", new int[] {1, 0}))),
                    node("Model", 200L, "Quad\0\1Model", "Mesh").add(node("Version", 232)),
                    node("Material", 300L, "Red\0\1Material", "").add(properties(
                            node("P", "DiffuseColor", "Color", "", "A", 1.0, 0.0, 0.0))),
                    node("Material", 301L, "Textured\0\1Material", "").add(properties(
                            node("P", "DiffuseColor", "Color", "", "A", 0.5, 0.5, 0.5))),
                    node("Texture", 400L, "Image\0\1Texture", "").add(node("RelativeFilename", "missing.png")),
                    node("Video", 500L, "Image\0\1Video", "Clip").add(node("Content", (Object) IMAGE)),
                    node("AnimationCurve", 600L, "\0\1AnimCurve", "").add(node("KeyValueFloat", new double[] {1, 2})));
            Node connections = node("Connections").add(
                    node("C", "OO", 200L, 0L),
                    node("C", "OO", 100L, 200L),
                    node("C", "OO", 300L, 200L),
                    node("C", "OO", 301L, 200L),
                    node("C", "OP", 400L, 301L, "DiffuseColor"),
                    node("C", "OO", 500L, 400L));
            byte[] data = new BinaryWriter(version).write(node("FBXHeaderExtension").add(node("FBXVersion", version)),
                    node("GlobalSettings").add(properties(node("P", "UpAxis", "int", "Integer", "", 1))), objects, connections);
            ModelResources resources = path -> {
                throw new AssertionError("The texture is embedded.");
            };
            FbxModel model = new FbxModel(new ByteArrayInputStream(data), resources);

            assertEquals(6, model.getVertexCount());
            assertPositions(model, 0, new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0});
            for (int v = 0; v < 6; v++) {
                assertNormal(model, v, 0, 0, 1);
            }
            List<MeshPart> parts = model.getParts();
            assertEquals(2, parts.size());
            // The textured material comes first, since the first triangle has it.
            assertPart(parts.get(0), 0, 3);
            assertArrayEquals(IMAGE, parts.get(0).getMaterial().getDiffuseTexture());
            assertArrayEquals(new float[] {1, 1, 1}, parts.get(0).getMaterial().getDiffuseColor(), TOLERANCE);
            assertPart(parts.get(1), 3, 3);
            assertNull(parts.get(1).getMaterial().getDiffuseTexture());
            assertArrayEquals(new float[] {1, 0, 0}, parts.get(1).getMaterial().getDiffuseColor(), TOLERANCE);
            assertTexCoords(model, new float[] {0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1});
        }
    }

    @Test
    public void testVersion6() throws Exception {
        // In version 6, meshes are in their models, and objects are connected by name.
        String fbx = """
                ; FBX 6.1.0 project file
                FBXHeaderExtension:  {
                	FBXHeaderVersion: 1003
                	FBXVersion: 6100
                }
                Objects:  {
                	Model: "Model::Tri", "Mesh" {
                		Version: 232
                		Properties60:  {
                			Property: "QuaternionInterpolate", "bool", "",0
                			Property: "Lcl Translation", "Lcl Translation", "A+",0.000000,5.000000,0.000000
                			Property: "Lcl Scaling", "Lcl Scaling", "A+",2.000000,2.000000,2.000000
                		}
                		Vertices: 0.000000,0.000000,0.000000,1.000000,0.000000,0.000000
                		,0.000000,1.000000,0.000000
                		PolygonVertexIndex: 0,1,-3
                		LayerElementNormal: 0 {
                			Version: 101
                			Name: ""
                			MappingInformationType: "ByVertice"
                			ReferenceInformationType: "Direct"
                			Normals: 0.000000,0.000000,1.000000,0.000000,0.000000,1.000000,
                			         0.000000,0.000000,1.000000
                		}
                		LayerElementUV: 0 {
                			MappingInformationType: "ByPolygonVertex"
                			ReferenceInformationType: "IndexToDirect"
                			UV: 0,0,1,0,0,1
                			UVIndex: 0,1,2
                		}
                		LayerElementTexture: 0 {
                			MappingInformationType: "ByPolygon"
                			ReferenceInformationType: "IndexToDirect"
                			BlendMode: "Translucent"
                			TextureAlpha: 1
                			TextureId: 0
                		}
                		LayerElementMaterial: 0 {
                			MappingInformationType: "AllSame"
                			ReferenceInformationType: "IndexToDirect"
                			Materials: 0
                		}
                	}
                	Material: "Material::Blue", "" {
                		Properties60:  {
                			Property: "ShadingModel", "KString", "", "Phong"
                			Property: "DiffuseColor", "ColorRGB", "",0.0000,0.0000,1.0000
                		}
                	}
                	Texture: "Texture::Image", "TextureVideoClip" {
                		FileName: "C:\\Users\\Someone\\textures\\image.png"
                		RelativeFilename: "textures\\image.png"
                	}
                	GlobalSettings:  {
                		Version: 1000
                		Properties60:  {
                			Property: "UpAxis", "int", "",1
                		}
                	}
                }
                Connections:  {
                	Connect: "OO", "Model::Tri", "Model::Scene"
                	Connect: "OO", "Material::Blue", "Model::Tri"
                	Connect: "OO", "Texture::Image", "Model::Tri"
                }
                Takes:  {
                	Current: ""
                }
                """;
        List<String> requested = new ArrayList<>();
        FbxModel model = load(fbx, path -> {
            requested.add(path);
            return path.equals("textures\\image.png") ? new ByteArrayInputStream(IMAGE) : null;
        });
        assertEquals(List.of("textures\\image.png"), requested);
        assertEquals(3, model.getVertexCount());
        assertPositions(model, 0, new float[] {0, 5, 0, 2, 5, 0, 0, 7, 0});
        for (int v = 0; v < 3; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
        assertTexCoords(model, new float[] {0, 0, 1, 0, 0, 1});
        assertEquals(1, model.getParts().size());
        assertPart(model.getParts().get(0), 0, 3);
        Material material = model.getParts().get(0).getMaterial();
        assertArrayEquals(IMAGE, material.getDiffuseTexture());
        assertArrayEquals(new float[] {1, 1, 1}, material.getDiffuseColor(), TOLERANCE);

        // Without the texture, the model has the color of its material.
        model = load(fbx, path -> null);
        assertNull(model.getTexCoordBuffer());
        material = model.getParts().get(0).getMaterial();
        assertNull(material.getDiffuseTexture());
        assertArrayEquals(new float[] {0, 0, 1}, material.getDiffuseColor(), TOLERANCE);
    }

    @Test
    public void testTransforms() throws Exception {
        // A triangle in a model that's rotated (in the given order) in its parent, which has a
        // pre-rotation and a translation; and the same triangle in a model that's rotated about a pivot.
        for (int order : new int[] {0, 3}) {
            String fbx = asciiScene("""
                    	Model: 10, "Model::Parent", "Null" {
                    		Properties70:  {
                    			P: "PreRotation", "Vector3D", "Vector", "",0,0,90
                    			P: "Lcl Translation", "Lcl Translation", "", "A",0,0,5
                    		}
                    	}
                    	Model: 20, "Model::Child", "Mesh" {
                    		Properties70:  {
                    			P: "RotationOrder", "enum", "", "",%d
                    			P: "Lcl Rotation", "Lcl Rotation", "", "A",90,90,0
                    		}
                    	}
                    	Model: 30, "Model::Pivoted", "Mesh" {
                    		Properties70:  {
                    			P: "RotationPivot", "Vector3D", "Vector", "",1,0,0
                    			P: "Lcl Rotation", "Lcl Rotation", "", "A",0,0,90
                    			P: "GeometricTranslation", "Vector3D", "Vector", "",1,0,0
                    		}
                    	}
                    """.formatted(order) + triangleGeometry(40) + triangleGeometry(41), """
                    	C: "OO",10,0
                    	C: "OO",20,10
                    	C: "OO",30,0
                    	C: "OO",40,20
                    	C: "OO",41,30
                    """);
            FbxModel model = load(fbx, null);
            assertEquals(6, model.getVertexCount());
            if (order == 0) {
                // X, then Y: (1, 0, 0) becomes (0, 0, -1), and (0, 1, 0) becomes (1, 0, 0), before the parent's transform.
                assertPositions(model, 0, new float[] {0, 0, 5, 0, 0, 4, 0, 1, 5});
            } else {
                // Y, then X: (1, 0, 0) becomes (0, 1, 0), and (0, 1, 0) becomes (0, 0, 1).
                assertPositions(model, 0, new float[] {0, 0, 5, -1, 0, 5, 0, 0, 6});
            }
            // The geometry is moved to the pivot, about which it's rotated.
            assertPositions(model, 3, new float[] {1, 0, 0, 1, 1, 0, 0, 0, 0});
        }
    }

    @Test
    public void testMirroredTransform() throws Exception {
        String fbx = asciiScene("""
                	Model: 10, "Model::Mirrored", "Mesh" {
                		Properties70:  {
                			P: "Lcl Scaling", "Lcl Scaling", "", "A",-1,1,1
                		}
                	}
                	Geometry: 20, "Geometry::Triangle", "Mesh" {
                		Vertices: *9 {
                			a: 0,0,0,1,0,0,0,1,0
                		}
                		PolygonVertexIndex: *3 {
                			a: 0,1,-3
                		}
                		LayerElementNormal: 0 {
                			MappingInformationType: "ByPolygonVertex"
                			ReferenceInformationType: "Direct"
                			Normals: *9 {
                				a: 0,0,1,0,0,1,0,0,1
                			}
                		}
                	}
                """, """
                	C: "OO",10,0
                	C: "OO",20,10
                """);
        FbxModel model = load(fbx, null);
        // The mirrored triangle still faces +Z, so its corners are reversed, to keep it from facing the other way.
        assertPositions(model, 0, new float[] {0, 0, 0, 0, 1, 0, -1, 0, 0});
        for (int v = 0; v < 3; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
    }

    @Test
    public void testSkinning() throws Exception {
        // A triangle in a mesh at X = 5, whose first two vertices are moved by a bone that was at the
        // mesh's origin when they were bound, and which is now rotated, and moved to Y = 10.
        for (boolean withTransform : new boolean[] {true, false}) {
            String fbx = asciiScene("""
                    	Model: 10, "Model::Skinned", "Mesh" {
                    		Properties70:  {
                    			P: "Lcl Translation", "Lcl Translation", "", "A",5,0,0
                    		}
                    	}
                    	Geometry: 20, "Geometry::Triangle", "Mesh" {
                    		Vertices: *9 {
                    			a: 0,0,0,1,0,0,0,1,0
                    		}
                    		PolygonVertexIndex: *3 {
                    			a: 0,1,-3
                    		}
                    		LayerElementNormal: 0 {
                    			MappingInformationType: "ByPolygonVertex"
                    			ReferenceInformationType: "Direct"
                    			Normals: *9 {
                    				a: 0,0,1,0,0,1,0,0,1
                    			}
                    		}
                    	}
                    	Deformer: 30, "Deformer::Skin", "Skin" {
                    		Version: 101
                    		Link_DeformAcuracy: 50
                    	}
                    	Deformer: 40, "SubDeformer::Bone", "Cluster" {
                    		Version: 100
                    		UserData: "", ""
                    		Indexes: *2 {
                    			a: 0,1
                    		}
                    		Weights: *2 {
                    			a: 1,0.5
                    		}
                    """ + (withTransform ? """
                    		Transform: *16 {
                    			a: 1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1
                    		}
                    """ : "") + """
                    		TransformLink: *16 {
                    			a: 1,0,0,0,0,1,0,0,0,0,1,0,5,0,0,1
                    		}
                    	}
                    	Model: 50, "Model::Bone", "LimbNode" {
                    		Properties70:  {
                    			P: "Lcl Translation", "Lcl Translation", "", "A",0,10,0
                    			P: "Lcl Rotation", "Lcl Rotation", "", "A",0,0,90
                    		}
                    	}
                    """, """
                    	C: "OO",10,0
                    	C: "OO",50,0
                    	C: "OO",20,10
                    	C: "OO",30,20
                    	C: "OO",40,30
                    	C: "OO",50,40
                    """);
            FbxModel model = load(fbx, null);
            // The first vertex moves with the bone, and so does the second (whose only weight is
            // for the bone, so it counts fully), and the third, without a bone, stays with the mesh.
            assertPositions(model, 0, new float[] {0, 10, 0, 0, 11, 0, 5, 1, 0});
            for (int v = 0; v < 3; v++) {
                assertNormal(model, v, 0, 0, 1);
            }
        }
    }

    @Test
    public void testEmbeddedTextureInAscii() throws Exception {
        // Embedded files are encoded in Base64, possibly in several strings.
        String encoded = Base64.getEncoder().encodeToString(IMAGE);
        String fbx = asciiScene("""
                	Model: 10, "Model::Triangle", "Mesh" {
                	}
                	Model: 11, "Model::Hidden", "Mesh" {
                		Properties70:  {
                			P: "Visibility", "Visibility", "", "A",0
                		}
                	}
                	Model: 12, "Model::Control", "Line" {
                	}
                	Geometry: 13, "Geometry::Control", "Line" {
                		Points: *6 {
                			a: 0,0,0,1,1,1
                		}
                	}
                	Material: 30, "Material::Textured", "" {
                	}
                	Texture: 40, "Texture::Image", "" {
                		RelativeFilename: "image.png"
                	}
                	Video: 50, "Video::Image", "Clip" {
                		Content: ,
                		"%s",
                		"%s"
                	}
                """.formatted(encoded.substring(0, 5), encoded.substring(5)) + triangleGeometry(20), """
                	C: "OO",10,0
                	C: "OO",11,0
                	C: "OO",12,0
                	C: "OO",20,10
                	C: "OO",20,11
                	C: "OO",13,12
                	C: "OO",30,10
                	C: "OP",40,30, "DiffuseColor"
                	C: "OO",50,40
                """);
        FbxModel model = load(fbx, path -> {
            throw new AssertionError("The texture is embedded.");
        });
        // The hidden model and the line aren't shown.
        assertEquals(3, model.getVertexCount());
        assertArrayEquals(IMAGE, model.getParts().get(0).getMaterial().getDiffuseTexture());
        assertEquals("Image", model.getParts().get(0).getMaterial().getDiffuseTextureName());
    }

    @Test
    public void testInvalidFiles() {
        // Files without meshes, such as those with only animations.
        assertThrows(IOException.class, () -> load(asciiScene("""
                	Model: 10, "Model::Root", "Null" {
                	}
                """, "	C: \"OO\",10,0\n"), null));
        assertThrows(IOException.class, () -> load("solid cube\nendsolid\n", null));
        assertThrows(IOException.class, () -> load("Objects:  {\n\tModel: 10, \"Model::Unfinished\"", null));
        assertThrows(IOException.class, () -> load(asciiScene("""
                	Model: 10, "Model::Triangle", "Mesh" {
                	}
                	Geometry: 20, "Geometry::Triangle", "Mesh" {
                		Vertices: *3 {
                			a: 0,0,0
                		}
                		PolygonVertexIndex: *3 {
                			a: 0,1,-3
                		}
                	}
                """, "\tC: \"OO\",10,0\n\tC: \"OO\",20,10\n"), null));

        byte[] data = new BinaryWriter(7400).write(node("Objects").add(node("Geometry", 100L, "\0\1Geometry", "Mesh")
                .add(node("Vertices", new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0}))));
        for (int length : new int[] {20, 40, data.length / 2, data.length - 160}) {
            assertThrows(IOException.class, () -> new FbxModel(new ByteArrayInputStream(Arrays.copyOf(data, length)), null));
        }
    }

    private static String triangleGeometry(int id) {
        return """
                	Geometry: %d, "Geometry::Triangle", "Mesh" {
                		Vertices: *9 {
                			a: 0,0,0,1,0,0,0,1,0
                		}
                		PolygonVertexIndex: *3 {
                			a: 0,1,-3
                		}
                	}
                """.formatted(id);
    }

    private static String asciiScene(String objects, String connections) {
        return "; FBX 7.4.0 project file\nFBXHeaderExtension:  {\n\tFBXVersion: 7400\n}\n"
                + "Objects:  {\n" + objects + "}\nConnections:  {\n" + connections + "}\n";
    }

    private static FbxModel load(String fbx, ModelResources resources) throws IOException {
        return new FbxModel(new ByteArrayInputStream(fbx.getBytes(StandardCharsets.UTF_8)), resources);
    }

    private static void assertPositions(FbxModel model, int firstVertex, float[] expected) {
        FloatBuffer vertices = model.getVertexBuffer();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], vertices.get(firstVertex * 3 + i), TOLERANCE);
        }
    }

    private static void assertNormal(FbxModel model, int vertex, float x, float y, float z) {
        FloatBuffer normals = model.getNormalBuffer();
        assertEquals(x, normals.get(vertex * 3), TOLERANCE);
        assertEquals(y, normals.get(vertex * 3 + 1), TOLERANCE);
        assertEquals(z, normals.get(vertex * 3 + 2), TOLERANCE);
    }

    private static void assertTexCoords(FbxModel model, float[] expected) {
        FloatBuffer texCoords = model.getTexCoordBuffer();
        assertNotNull(texCoords);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], texCoords.get(i), TOLERANCE);
        }
    }

    private static void assertPart(MeshPart part, int first, int count) {
        assertEquals(first, part.getFirst());
        assertEquals(count, part.getCount());
    }

    private static Node node(String name, Object... properties) {
        return new Node(name, properties);
    }

    private static Node properties(Node... properties) {
        return node("Properties70").add(properties);
    }

    private static final class Node {
        final String name;
        final Object[] properties;
        final List<Node> children = new ArrayList<>();

        Node(String name, Object[] properties) {
            this.name = name;
            this.properties = properties;
        }

        Node add(Node... nodes) {
            children.addAll(Arrays.asList(nodes));
            return this;
        }
    }

    /** Writes nodes in the binary format, with offsets of 32 bits, or 64 bits from version 7.5. */
    private static final class BinaryWriter {
        private final ByteBuffer out = ByteBuffer.allocate(0x10000).order(ByteOrder.LITTLE_ENDIAN);
        private final boolean wide;

        BinaryWriter(int version) {
            wide = version >= 7500;
            out.put("Kaydara FBX Binary  \0".getBytes(StandardCharsets.ISO_8859_1)).put((byte) 0x1a).put((byte) 0).putInt(version);
        }

        byte[] write(Node... nodes) {
            for (Node node : nodes) {
                writeNode(node);
            }
            out.put(new byte[wide ? 25 : 13]);
            // The footer, which isn't read.
            out.put(new byte[16]).putInt(0).putInt(7400).put(new byte[120]);
            return Arrays.copyOf(out.array(), out.position());
        }

        private void writeNode(Node node) {
            int start = out.position();
            byte[] name = node.name.getBytes(StandardCharsets.ISO_8859_1);
            putOffset(0);
            putOffset(node.properties.length);
            putOffset(0);
            out.put((byte) name.length).put(name);
            int propertiesStart = out.position();
            for (Object property : node.properties) {
                writeProperty(property);
            }
            int propertiesLength = out.position() - propertiesStart;
            if (!node.children.isEmpty()) {
                for (Node child : node.children) {
                    writeNode(child);
                }
                out.put(new byte[wide ? 25 : 13]);
            }
            if (wide) {
                out.putLong(start, out.position()).putLong(start + 16, propertiesLength);
            } else {
                out.putInt(start, out.position()).putInt(start + 8, propertiesLength);
            }
        }

        private void putOffset(long value) {
            if (wide) {
                out.putLong(value);
            } else {
                out.putInt((int) value);
            }
        }

        private void writeProperty(Object property) {
            if (property instanceof Integer value) {
                out.put((byte) 'I').putInt(value);
            } else if (property instanceof Long value) {
                out.put((byte) 'L').putLong(value);
            } else if (property instanceof Double value) {
                out.put((byte) 'D').putDouble(value);
            } else if (property instanceof String value) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                out.put((byte) 'S').putInt(bytes.length).put(bytes);
            } else if (property instanceof byte[] value) {
                out.put((byte) 'R').putInt(value.length).put(value);
            } else if (property instanceof double[] values) {
                // Arrays of doubles are compressed, and arrays of ints aren't.
                ByteBuffer raw = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (double value : values) {
                    raw.putDouble(value);
                }
                Deflater deflater = new Deflater();
                deflater.setInput(raw.array());
                deflater.finish();
                byte[] compressed = new byte[raw.capacity() + 64];
                int length = deflater.deflate(compressed);
                deflater.end();
                out.put((byte) 'd').putInt(values.length).putInt(1).putInt(length).put(compressed, 0, length);
            } else if (property instanceof int[] values) {
                out.put((byte) 'i').putInt(values.length).putInt(0).putInt(values.length * 4);
                for (int value : values) {
                    out.putInt(value);
                }
            } else {
                throw new IllegalArgumentException("Unsupported property: " + property);
            }
        }
    }
}
