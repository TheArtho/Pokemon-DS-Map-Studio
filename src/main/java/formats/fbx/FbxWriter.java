package formats.fbx;

import editor.game.Game;
import editor.grid.MapGrid;
import tileset.Face;
import tileset.Tile;
import tileset.Tileset;
import utils.Utils;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

import static editor.grid.MapGrid.cols;
import static editor.grid.MapGrid.gridTileSize;
import static editor.grid.MapGrid.rows;

/**
 * Binary FBX map exporter.
 */
public class FbxWriter {

    private static final int MAX_TILEABLE_SIZE_BW = 16;
    private static final int MAX_TILEABLE_SIZE_DPHGSS = 8;

    private static final long DOCUMENT_ID = 1000L;
    private static final long MODEL_ID = 1001L;
    private static final long GEOMETRY_ID = 1002L;
    private static final long MATERIAL_ID_START = 2000L;
    private static final long TEXTURE_ID_START = 3000L;
    private static final long VIDEO_ID_START = 4000L;
    private static final int FBX_VERSION = 7400;
    private static final int FBX_NODE_HEADER_SIZE = 13;
    private static final int FBX_NULL_RECORD_SIZE = 13;
    private static final byte[] FBX_BINARY_HEADER = new byte[] {
            'K', 'a', 'y', 'd', 'a', 'r', 'a', ' ', 'F', 'B', 'X', ' ', 'B', 'i', 'n', 'a', 'r', 'y', ' ',
            ' ', 0x00, 0x1A, 0x00
    };
    private static final double FBX_UNIT_SCALE_FACTOR = 100.0;

    private final Tileset tset;
    private final HashMap<Point, MapGrid> maps;
    private String folderPath;
    private String savePathFbx;
    private final boolean saveTextures;
    private final boolean saveVertexColors;
    private final float tileUpscale;

    private ArrayList<Tile> outTiles = new ArrayList<>();
    private ArrayList<Integer> textureUsage = new ArrayList<>();

    private int maxTileableSize = MAX_TILEABLE_SIZE_DPHGSS;

    public FbxWriter(Tileset tset, HashMap<Point, MapGrid> maps, String savePath, int game,
                     boolean saveTextures, boolean saveVertexColors, float tileUpscale) {
        this.tset = tset;
        this.maps = maps;
        this.savePathFbx = savePath;
        this.saveTextures = saveTextures;
        this.saveVertexColors = saveVertexColors;
        this.tileUpscale = tileUpscale;

        if (game == Game.BLACK || game == Game.WHITE || game == Game.BLACK2 || game == Game.WHITE2) {
            maxTileableSize = MAX_TILEABLE_SIZE_BW;
        }
    }

    public FbxWriter(Tileset tset, MapGrid grid, String savePath, int game,
                     boolean saveTextures, boolean saveVertexColors, float tileUpscale) {
        this(tset, new HashMap<Point, MapGrid>(1) {
            {
                put(new Point(0, 0), grid);
            }
        }, savePath, game, saveTextures, saveVertexColors, tileUpscale);
    }

    public void writeMapFbx() throws FileNotFoundException {
        if (!savePathFbx.endsWith(".fbx")) {
            savePathFbx = savePathFbx.concat(".fbx");
        }

        folderPath = new File(savePathFbx).getParent();
        collectMapTiles();

        FbxMesh mesh = buildMesh();
        writeBinaryFbx(mesh);

        if (saveTextures) {
            writeTextures();
        }
    }

    private void writeBinaryFbx(FbxMesh mesh) throws FileNotFoundException {
        try (FileOutputStream outFbx = new FileOutputStream(savePathFbx)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeFbxBinaryHeader(bytes);

            ArrayList<FbxNode> nodes = buildFbxNodeTree(mesh);
            int currentOffset = bytes.size();
            for (FbxNode node : nodes) {
                node.write(bytes, currentOffset);
                currentOffset += node.byteSize();
            }
            writeZeroBytes(bytes, FBX_NULL_RECORD_SIZE);

            outFbx.write(bytes.toByteArray());
        } catch (FileNotFoundException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new UncheckedIOException("Can't write FBX file.", ex);
        }
    }

    private void collectMapTiles() {
        outTiles = new ArrayList<>();

        long time = System.currentTimeMillis();
        for (HashMap.Entry<Point, MapGrid> mapEntry : maps.entrySet()) {
            for (int k = 0; k < mapEntry.getValue().numLayers; k++) {
                boolean[][] writtenGrid = new boolean[cols][rows];
                for (int i = 0; i < cols; i++) {
                    for (int j = 0; j < rows; j++) {
                        evaluateTile(mapEntry.getValue(), mapEntry.getKey(), k, i, j, writtenGrid, tileUpscale);
                    }
                }
            }
        }
        System.out.println("Elapsed time: " + (System.currentTimeMillis() - time) + " ms");
    }

    private void evaluateTile(MapGrid grid, Point mapCoords, int layer, int c, int r,
                              boolean[][] writtenGrid, float scale) {
        try {
            if ((!writtenGrid[c][r]) && (grid.tileLayers[layer][c][r] != -1)) {
                Tile tile = tset.get(grid.tileLayers[layer][c][r]).cloneObjData();
                if ((!tile.isXtileable()) && (!tile.isYtileable())) {
                    stretchTile(tile, 1, 1, c, r);
                    writeTile(grid, mapCoords, tile, layer, c, r, scale);
                    updateWGridNoTileable(writtenGrid, tile, c, r);
                } else if (tile.isXtileable() && tile.isYtileable()) {
                    int xSize = getNumEqualTilesX(grid, layer, c, r, writtenGrid, tile.getWidth());
                    int ySize = getNumEqualTilesY(grid, layer, c, r, writtenGrid, tile.getHeight());
                    if (xSize == 1 && ySize == 1) {
                        stretchTile(tile, 1, 1, c, r);
                        writeTile(grid, mapCoords, tile, layer, c, r, scale);
                        updateGridTileable(writtenGrid, c, r, tile.getWidth(), tile.getHeight());
                    } else if (xSize > ySize) {
                        int yExp = getExpansionY(grid, layer, c, r, writtenGrid, tile.getWidth(), tile.getHeight(), xSize);
                        stretchTile(tile, xSize, yExp, c, r);
                        writeTile(grid, mapCoords, tile, layer, c, r, scale);
                        updateGridTileable(writtenGrid, c, r, xSize * tile.getWidth(), yExp * tile.getHeight());
                    } else {
                        int xExp = getExpansionX(grid, layer, c, r, writtenGrid, tile.getWidth(), tile.getHeight(), ySize);
                        stretchTile(tile, xExp, ySize, c, r);
                        writeTile(grid, mapCoords, tile, layer, c, r, scale);
                        updateGridTileable(writtenGrid, c, r, xExp * tile.getWidth(), ySize * tile.getHeight());
                    }
                } else if (tile.isXtileable()) {
                    int xSize = getNumEqualTilesX(grid, layer, c, r, writtenGrid, tile.getWidth());
                    stretchTile(tile, xSize, 1, c, r);
                    writeTile(grid, mapCoords, tile, layer, c, r, scale);
                    updateGridTileable(writtenGrid, c, r, xSize * tile.getWidth(), tile.getHeight());
                } else {
                    int ySize = getNumEqualTilesY(grid, layer, c, r, writtenGrid, tile.getHeight());
                    stretchTile(tile, 1, ySize, c, r);
                    writeTile(grid, mapCoords, tile, layer, c, r, scale);
                    updateGridTileable(writtenGrid, c, r, tile.getWidth(), ySize * tile.getHeight());
                }
                moveTile(tile);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void updateGridTileable(boolean[][] writtenGrid, int c, int r, int xSize, int ySize) {
        int xLimit = Math.min(xSize, cols - c);
        int yLimit = Math.min(ySize, rows - r);
        for (int i = 0; i < xLimit; i++) {
            for (int j = 0; j < yLimit; j++) {
                writtenGrid[c + i][r + j] = true;
            }
        }
    }

    private void updateWGridNoTileable(boolean[][] writtenGrid, Tile tile, int c, int r) {
        updateGridTileable(writtenGrid, c, r, tile.getWidth(), tile.getHeight());
    }

    private void stretchTile(Tile tile, int xMult, int yMult, int c, int r) {
        ArrayList<Float> vertexCoords = tile.getVertexCoordsObj();
        int numVertex = vertexCoords.size() / 3;
        if (!(xMult == 1 && yMult == 1)) {
            for (int i = 0; i < numVertex; i++) {
                vertexCoords.set(i * 3, vertexCoords.get(i * 3) * xMult);
                vertexCoords.set(i * 3 + 1, vertexCoords.get(i * 3 + 1) * yMult);
            }
        }

        if (!tile.useGlobalTextureMapping()) {
            if (tile.isXtileable() && tile.isYtileable()) {
                // Keep both UV axes as stretched.
            } else if (tile.isXtileable()) {
                if (tile.isVtileable()) {
                    yMult = xMult;
                    xMult = 1;
                }
            } else if (tile.isYtileable()) {
                if (tile.isUtileable()) {
                    xMult = yMult;
                    yMult = 1;
                }
            }

            if (!tile.isUtileable() && !tile.isVtileable()) {
                xMult = 1;
                yMult = 1;
            }

            ArrayList<Float> textureCoords = tile.getTextureCoordsObj();
            int numTextCoords = textureCoords.size() / 2;
            for (int i = 0; i < numTextCoords; i++) {
                textureCoords.set(i * 2, textureCoords.get(i * 2) * xMult);
                textureCoords.set(i * 2 + 1, textureCoords.get(i * 2 + 1) * yMult);
            }
        } else {
            formats.obj.GlobalTextureMapper.applyGlobalTextureMapping(tile, c, r);
        }
    }

    private void moveTile(Tile tile) {
        ArrayList<Float> vertexCoords = tile.getVertexCoordsObj();
        int numVertex = vertexCoords.size() / 3;
        if (tile.getXOffset() != 0.0f || tile.getYOffset() != 0.0f || tile.getZOffset() != 0.0f) {
            for (int i = 0; i < numVertex; i++) {
                vertexCoords.set(i * 3, vertexCoords.get(i * 3) + tile.getXOffset());
                vertexCoords.set(i * 3 + 1, vertexCoords.get(i * 3 + 1) + tile.getYOffset());
                vertexCoords.set(i * 3 + 2, vertexCoords.get(i * 3 + 2) + tile.getZOffset());
            }
        }
    }

    private void displaceTile(Tile tile, int c, int r, int height) {
        ArrayList<Float> vertexCoords = tile.getVertexCoordsObj();
        int numVertex = vertexCoords.size() / 3;
        for (int i = 0; i < numVertex; i++) {
            vertexCoords.set(i * 3, vertexCoords.get(i * 3) + c * gridTileSize);
            vertexCoords.set(i * 3 + 1, vertexCoords.get(i * 3 + 1) + r * gridTileSize);
            vertexCoords.set(i * 3 + 2, vertexCoords.get(i * 3 + 2) + height * gridTileSize);
        }
    }

    private float[] getTileCenter(Tile tile) {
        ArrayList<Float> vertexCoords = tile.getVertexCoordsObj();
        float[] mean = new float[3];
        final int coordsPerVertex = 3;
        final int numVertex = vertexCoords.size() / coordsPerVertex;
        for (int i = 0; i < numVertex; i++) {
            for (int j = 0; j < coordsPerVertex; j++) {
                mean[j] += vertexCoords.get(i * 3 + j);
            }
        }
        for (int i = 0; i < coordsPerVertex; i++) {
            mean[i] /= numVertex;
        }
        return mean;
    }

    private void scaleTile(Tile tile, float scale) {
        float[] center = getTileCenter(tile);
        ArrayList<Float> vertexCoords = tile.getVertexCoordsObj();
        final int coordsPerVertex = 3;
        final int numVertex = vertexCoords.size() / coordsPerVertex;
        for (int i = 0; i < numVertex; i++) {
            for (int j = 0; j < coordsPerVertex; j++) {
                int index = i * 3 + j;
                vertexCoords.set(index, (vertexCoords.get(index) - center[j]) * scale + center[j]);
            }
        }
    }

    private int getExpansionY(MapGrid grid, int layer, int c, int r,
                              boolean[][] writtenGrid, int width, int height, int xSize) {
        int n = 1;
        for (int i = height, limit = rows - r; i < limit && n < maxTileableSize; i += height) {
            for (int j = 0; j < xSize * width; j += width) {
                int nextC = c + j;
                int nextR = r + i;
                if (!(sameHeightAndType(grid, layer, c, r, nextC, nextR) && !writtenGrid[nextC][nextR])) {
                    return n;
                }
            }
            n++;
        }

        return n;
    }

    private int getExpansionX(MapGrid grid, int layer, int c, int r,
                              boolean[][] writtenGrid, int width, int height, int ySize) {
        int n = 1;
        for (int i = width, limit = cols - c; i < limit && n < maxTileableSize; i += width) {
            for (int j = 0; j < ySize * height; j += height) {
                int nextC = c + i;
                int nextR = r + j;
                if (!(sameHeightAndType(grid, layer, c, r, nextC, nextR) && !writtenGrid[nextC][nextR])) {
                    return n;
                }
            }
            n++;
        }
        return n;
    }

    private int getNumEqualTilesX(MapGrid grid, int layer, int c, int r, boolean[][] writtenGrid, int width) {
        int n = 1;
        for (int i = width, limit = cols - c; i < limit && n < maxTileableSize; i += width) {
            int nextC = c + i;
            int nextR = r;
            if (sameHeightAndType(grid, layer, c, r, nextC, nextR) && !writtenGrid[nextC][nextR]) {
                n++;
            } else {
                return n;
            }
        }
        return n;
    }

    private int getNumEqualTilesY(MapGrid grid, int layer, int c, int r, boolean[][] writtenGrid, int height) {
        int n = 1;
        for (int i = height, limit = rows - r; i < limit && n < maxTileableSize; i += height) {
            int nextC = c;
            int nextR = r + i;
            if (sameHeightAndType(grid, layer, c, r, nextC, nextR) && !writtenGrid[nextC][nextR]) {
                n++;
            } else {
                return n;
            }
        }
        return n;
    }

    private boolean sameHeightAndType(MapGrid grid, int layer, int c1, int r1, int c2, int r2) {
        return grid.tileLayers[layer][c1][r1] == grid.tileLayers[layer][c2][r2]
                && grid.heightLayers[layer][c1][r1] == grid.heightLayers[layer][c2][r2];
    }

    private void writeTile(MapGrid grid, Point mapCoords, Tile tile, int layer, int c, int r, float scale) {
        scaleTile(tile, scale);
        displaceTile(tile,
                c - cols / 2 + mapCoords.x * cols,
                r - rows / 2 - mapCoords.y * cols,
                grid.heightLayers[layer][c][r]);
        outTiles.add(tile);
    }

    private FbxMesh buildMesh() {
        FbxMesh mesh = new FbxMesh(tset.getMaterials().size());

        ArrayList<Integer> vertexCoordsOffsets = new ArrayList<>();
        ArrayList<Integer> textureCoordsOffsets = new ArrayList<>();
        ArrayList<Integer> normalCoordsOffsets = new ArrayList<>();
        ArrayList<Integer> colorsOffsets = new ArrayList<>();

        for (Tile tile : outTiles) {
            vertexCoordsOffsets.add(mesh.vertexCoords.size() / 3);
            textureCoordsOffsets.add(mesh.textureCoords.size() / 2);
            normalCoordsOffsets.add(mesh.normalCoords.size() / 3);
            colorsOffsets.add(mesh.colors.size() / 3);

            mesh.vertexCoords.addAll(tile.getVertexCoordsObj());
            mesh.textureCoords.addAll(tile.getTextureCoordsObj());
            mesh.normalCoords.addAll(tile.getNormalCoordsObj());
            mesh.colors.addAll(tile.getColorsObj());
        }

        for (int i = 0; i < outTiles.size(); i++) {
            Tile tile = outTiles.get(i);
            Face.incrementAllIndices(tile.getFIndQuadObj(),
                    vertexCoordsOffsets.get(i),
                    textureCoordsOffsets.get(i),
                    normalCoordsOffsets.get(i),
                    colorsOffsets.get(i));

            Face.incrementAllIndices(tile.getFIndTriObj(),
                    vertexCoordsOffsets.get(i),
                    textureCoordsOffsets.get(i),
                    normalCoordsOffsets.get(i),
                    colorsOffsets.get(i));
        }

        for (int tex = 0; tex < mesh.facesQuadByTexture.size(); tex++) {
            for (Tile tile : outTiles) {
                int index = tile.getTextureIDs().indexOf(tex);
                if (index != -1) {
                    mesh.facesQuadByTexture.get(tex).addAll(tile.getFaceIndQuadOfTex(index));
                    mesh.facesTriByTexture.get(tex).addAll(tile.getFaceIndTriOfTex(index));
                }
            }
        }

        applyMapExportRotation(mesh.vertexCoords);
        applyMapExportRotation(mesh.normalCoords);
        textureUsage = countTextureUsage();
        mesh.usedTextureIds = getUsedTextureIds();
        return mesh;
    }

    private ArrayList<FbxNode> buildFbxNodeTree(FbxMesh mesh) {
        PolygonData polygonData = buildPolygonData(mesh);
        ArrayList<FbxNode> nodes = new ArrayList<>();

        nodes.add(headerExtensionNode());
        nodes.add(globalSettingsNode());
        nodes.add(documentsNode());
        nodes.add(definitionsNode(mesh.usedTextureIds.size()));
        nodes.add(objectsNode(mesh, polygonData));
        nodes.add(connectionsNode(mesh.usedTextureIds));
        nodes.add(node("Takes").child(node("Current", string(""))));

        return nodes;
    }

    private FbxNode headerExtensionNode() {
        return node("FBXHeaderExtension")
                .child(node("FBXHeaderVersion", integer(1003)))
                .child(node("FBXVersion", integer(FBX_VERSION)))
                .child(node("EncryptionType", integer(0)))
                .child(node("CreationTimeStamp")
                        .child(node("Version", integer(1000)))
                        .child(node("Year", integer(2026)))
                        .child(node("Month", integer(1)))
                        .child(node("Day", integer(1)))
                        .child(node("Hour", integer(0)))
                        .child(node("Minute", integer(0)))
                        .child(node("Second", integer(0)))
                        .child(node("Millisecond", integer(0))))
                .child(node("Creator", string("Pokemon DS Map Studio")));
    }

    private FbxNode globalSettingsNode() {
        return node("GlobalSettings")
                .child(node("Version", integer(1000)))
                .child(node("Properties70")
                        .child(propertyNode("UpAxis", "int", "Integer", "", integer(1)))
                        .child(propertyNode("UpAxisSign", "int", "Integer", "", integer(1)))
                        .child(propertyNode("FrontAxis", "int", "Integer", "", integer(2)))
                        .child(propertyNode("FrontAxisSign", "int", "Integer", "", integer(1)))
                        .child(propertyNode("CoordAxis", "int", "Integer", "", integer(0)))
                        .child(propertyNode("CoordAxisSign", "int", "Integer", "", integer(1)))
                        .child(propertyNode("UnitScaleFactor", "double", "Number", "",
                                doubleValue(FBX_UNIT_SCALE_FACTOR)))
                        .child(propertyNode("OriginalUnitScaleFactor", "double", "Number", "",
                                doubleValue(FBX_UNIT_SCALE_FACTOR))));
    }

    private FbxNode documentsNode() {
        return node("Documents")
                .child(node("Count", integer(1)))
                .child(node("Document", longValue(DOCUMENT_ID), string(""), string("Scene"))
                        .child(node("Properties70")
                                .child(propertyNode("SourceObject", "object", "", "")))
                        .child(node("RootNode", longValue(0))));
    }

    private FbxNode definitionsNode(int usedTextureCount) {
        int objectCount = 3 + usedTextureCount * 3;
        return node("Definitions")
                .child(node("Version", integer(100)))
                .child(node("Count", integer(objectCount)))
                .child(objectTypeNode("GlobalSettings", 1))
                .child(objectTypeNode("Geometry", 1))
                .child(objectTypeNode("Model", 1))
                .child(objectTypeNode("Material", usedTextureCount))
                .child(objectTypeNode("Texture", usedTextureCount))
                .child(objectTypeNode("Video", usedTextureCount));
    }

    private FbxNode objectTypeNode(String type, int count) {
        return node("ObjectType", string(type)).child(node("Count", integer(count)));
    }

    private FbxNode objectsNode(FbxMesh mesh, PolygonData polygonData) {
        FbxNode objects = node("Objects")
                .child(geometryNode(mesh, polygonData))
                .child(modelNode());

        for (int i = 0; i < mesh.usedTextureIds.size(); i++) {
            int textureId = mesh.usedTextureIds.get(i);
            objects.child(materialNode(i, textureId));
        }
        for (int i = 0; i < mesh.usedTextureIds.size(); i++) {
            int textureId = mesh.usedTextureIds.get(i);
            objects.child(textureNode(i, textureId));
            objects.child(videoNode(i, textureId));
        }

        return objects;
    }

    private FbxNode geometryNode(FbxMesh mesh, PolygonData polygonData) {
        FbxNode geometry = node("Geometry", longValue(GEOMETRY_ID), string("Geometry::map"), string("Mesh"))
                .child(node("Properties70"))
                .child(node("Vertices", doubleArray(mesh.vertexCoords)))
                .child(node("PolygonVertexIndex", intArray(polygonData.polygonVertexIndices)))
                .child(node("GeometryVersion", integer(124)))
                .child(normalLayerNode(polygonData))
                .child(uvLayerNode(polygonData));

        if (saveVertexColors) {
            geometry.child(colorLayerNode(polygonData));
        }

        geometry.child(materialLayerNode(polygonData))
                .child(layerNode(saveVertexColors));

        return geometry;
    }

    private FbxNode normalLayerNode(PolygonData polygonData) {
        return node("LayerElementNormal", integer(0))
                .child(node("Version", integer(101)))
                .child(node("Name", string("")))
                .child(node("MappingInformationType", string("ByPolygonVertex")))
                .child(node("ReferenceInformationType", string("Direct")))
                .child(node("Normals", doubleArray(polygonData.normals)));
    }

    private FbxNode uvLayerNode(PolygonData polygonData) {
        return node("LayerElementUV", integer(0))
                .child(node("Version", integer(101)))
                .child(node("Name", string("UVChannel_1")))
                .child(node("MappingInformationType", string("ByPolygonVertex")))
                .child(node("ReferenceInformationType", string("Direct")))
                .child(node("UV", doubleArray(polygonData.uvs)));
    }

    private FbxNode colorLayerNode(PolygonData polygonData) {
        return node("LayerElementColor", integer(0))
                .child(node("Version", integer(101)))
                .child(node("Name", string("Col")))
                .child(node("MappingInformationType", string("ByPolygonVertex")))
                .child(node("ReferenceInformationType", string("Direct")))
                .child(node("Colors", doubleArray(polygonData.colors)));
    }

    private FbxNode materialLayerNode(PolygonData polygonData) {
        return node("LayerElementMaterial", integer(0))
                .child(node("Version", integer(101)))
                .child(node("Name", string("")))
                .child(node("MappingInformationType", string("ByPolygon")))
                .child(node("ReferenceInformationType", string("IndexToDirect")))
                .child(node("Materials", intArray(polygonData.materialIndices)));
    }

    private FbxNode layerNode(boolean includeColors) {
        FbxNode layer = node("Layer", integer(0))
                .child(node("Version", integer(100)))
                .child(layerElementNode("LayerElementNormal", 0))
                .child(layerElementNode("LayerElementUV", 0));

        if (includeColors) {
            layer.child(layerElementNode("LayerElementColor", 0));
        }

        return layer.child(layerElementNode("LayerElementMaterial", 0));
    }

    private FbxNode layerElementNode(String type, int typedIndex) {
        return node("LayerElement")
                .child(node("Type", string(type)))
                .child(node("TypedIndex", integer(typedIndex)));
    }

    private FbxNode modelNode() {
        return node("Model", longValue(MODEL_ID), string("Model::map"), string("Mesh"))
                .child(node("Version", integer(232)))
                .child(node("Properties70")
                        .child(propertyNode("Lcl Translation", "Lcl Translation", "", "A",
                                doubleValue(0.0), doubleValue(0.0), doubleValue(0.0)))
                        .child(propertyNode("Lcl Rotation", "Lcl Rotation", "", "A",
                                doubleValue(0.0), doubleValue(0.0), doubleValue(0.0)))
                        .child(propertyNode("Lcl Scaling", "Lcl Scaling", "", "A",
                                doubleValue(1.0), doubleValue(1.0), doubleValue(1.0)))
                        .child(propertyNode("InheritType", "enum", "", "", integer(1)))
                        .child(propertyNode("DefaultAttributeIndex", "int", "Integer", "", integer(0))))
                .child(node("Shading", bool(true)))
                .child(node("Culling", string("CullingOff")));
    }

    private FbxNode materialNode(int usedMaterialIndex, int textureId) {
        String matName = getMaterialName(textureId);
        return node("Material", longValue(materialId(usedMaterialIndex)),
                string("Material::" + matName), string(""))
                .child(node("Version", integer(102)))
                .child(node("ShadingModel", string("phong")))
                .child(node("MultiLayer", integer(0)))
                .child(node("Properties70")
                        .child(propertyNode("DiffuseColor", "Color", "", "A",
                                doubleValue(0.8), doubleValue(0.8), doubleValue(0.8)))
                        .child(propertyNode("AmbientColor", "Color", "", "A",
                                doubleValue(0.2), doubleValue(0.2), doubleValue(0.2))));
    }

    private FbxNode textureNode(int usedMaterialIndex, int textureId) {
        String textureName = getMaterialName(textureId);
        String imageName = tset.getImageName(textureId);
        String absolutePath = new File(folderPath, imageName).getPath();

        return node("Texture", longValue(textureId(usedMaterialIndex)),
                string("Texture::" + textureName), string(""))
                .child(node("Type", string("TextureVideoClip")))
                .child(node("Version", integer(202)))
                .child(node("TextureName", string("Texture::" + textureName)))
                .child(node("Properties70")
                        .child(propertyNode("UVSet", "KString", "", "", string("UVChannel_1")))
                        .child(propertyNode("UseMaterial", "bool", "", "", integer(1))))
                .child(node("Media", string("Video::" + textureName)))
                .child(node("FileName", string(absolutePath)))
                .child(node("RelativeFilename", string(imageName)));
    }

    private FbxNode videoNode(int usedMaterialIndex, int textureId) {
        String textureName = getMaterialName(textureId);
        String imageName = tset.getImageName(textureId);
        String absolutePath = new File(folderPath, imageName).getPath();

        return node("Video", longValue(videoId(usedMaterialIndex)),
                string("Video::" + textureName), string("Clip"))
                .child(node("Type", string("Clip")))
                .child(node("Properties70")
                        .child(propertyNode("Path", "KString", "XRefUrl", "", string(absolutePath))))
                .child(node("FileName", string(absolutePath)))
                .child(node("RelativeFilename", string(imageName)));
    }

    private FbxNode connectionsNode(ArrayList<Integer> usedTextureIds) {
        FbxNode connections = node("Connections")
                .child(node("C", string("OO"), longValue(MODEL_ID), longValue(0)))
                .child(node("C", string("OO"), longValue(GEOMETRY_ID), longValue(MODEL_ID)));

        for (int i = 0; i < usedTextureIds.size(); i++) {
            connections.child(node("C", string("OO"), longValue(materialId(i)), longValue(MODEL_ID)))
                    .child(node("C", string("OP"), longValue(textureId(i)), longValue(materialId(i)),
                            string("DiffuseColor")))
                    .child(node("C", string("OO"), longValue(videoId(i)), longValue(textureId(i))));
        }

        return connections;
    }

    private FbxNode propertyNode(String name, String type, String label, String flags, FbxProperty... values) {
        FbxNode node = node("P", string(name), string(type), string(label), string(flags));
        for (FbxProperty value : values) {
            node.properties.add(value);
        }
        return node;
    }

    private PolygonData buildPolygonData(FbxMesh mesh) {
        PolygonData data = new PolygonData();

        for (int materialIndex = 0; materialIndex < mesh.usedTextureIds.size(); materialIndex++) {
            int textureId = mesh.usedTextureIds.get(materialIndex);
            addFacesToPolygonData(mesh.facesQuadByTexture.get(textureId), 4, materialIndex, mesh, data);
            addFacesToPolygonData(mesh.facesTriByTexture.get(textureId), 3, materialIndex, mesh, data);
        }

        return data;
    }

    private void addFacesToPolygonData(ArrayList<Face> faces, int numVertices, int materialIndex,
                                       FbxMesh mesh, PolygonData data) {
        for (Face face : faces) {
            for (int i = 0; i < numVertices; i++) {
                int vertexIndex = face.vInd[i] - 1;
                if (i == numVertices - 1) {
                    vertexIndex = -vertexIndex - 1;
                }
                data.polygonVertexIndices.add(vertexIndex);
                addTriplet(data.normals, mesh.normalCoords, face.nInd[i], 0.0f, 0.0f, 1.0f);
                addPair(data.uvs, mesh.textureCoords, face.tInd[i], 0.0f, 0.0f);
                addColor(data.colors, mesh.colors, face.cInd[i], 1.0f, 1.0f, 1.0f, 1.0f);
            }
            data.materialIndices.add(materialIndex);
        }
    }

    private void addTriplet(ArrayList<Float> dst, ArrayList<Float> src, int index,
                            float defaultX, float defaultY, float defaultZ) {
        int offset = (index - 1) * 3;
        if (offset < 0 || offset + 2 >= src.size()) {
            dst.add(defaultX);
            dst.add(defaultY);
            dst.add(defaultZ);
            return;
        }
        dst.add(src.get(offset));
        dst.add(src.get(offset + 1));
        dst.add(src.get(offset + 2));
    }

    private void addPair(ArrayList<Float> dst, ArrayList<Float> src, int index,
                         float defaultX, float defaultY) {
        int offset = (index - 1) * 2;
        if (offset < 0 || offset + 1 >= src.size()) {
            dst.add(defaultX);
            dst.add(defaultY);
            return;
        }
        dst.add(src.get(offset));
        dst.add(src.get(offset + 1));
    }

    private void addColor(ArrayList<Float> dst, ArrayList<Float> src, int index,
                          float defaultR, float defaultG, float defaultB, float defaultA) {
        int offset = (index - 1) * 3;
        if (offset < 0 || offset + 2 >= src.size()) {
            dst.add(defaultR);
            dst.add(defaultG);
            dst.add(defaultB);
            dst.add(defaultA);
            return;
        }
        dst.add(src.get(offset));
        dst.add(src.get(offset + 1));
        dst.add(src.get(offset + 2));
        dst.add(defaultA);
    }

    private ArrayList<Integer> countTextureUsage() {
        ArrayList<Integer> count = new ArrayList<>();
        for (int i = 0; i < tset.getMaterials().size(); i++) {
            count.add(0);
        }

        for (Tile tile : outTiles) {
            for (Integer i : tile.getTextureIDs()) {
                count.set(i, count.get(i) + 1);
            }
        }
        return count;
    }

    private ArrayList<Integer> getUsedTextureIds() {
        ArrayList<Integer> usedTextureIds = new ArrayList<>();
        for (int i = 0; i < textureUsage.size(); i++) {
            if (textureUsage.get(i) > 0) {
                usedTextureIds.add(i);
            }
        }
        return usedTextureIds;
    }

    private String getMaterialName(int textureId) {
        return Utils.removeExtensionFromPath(tset.getImageName(textureId));
    }

    private long materialId(int usedMaterialIndex) {
        return MATERIAL_ID_START + usedMaterialIndex;
    }

    private long textureId(int usedMaterialIndex) {
        return TEXTURE_ID_START + usedMaterialIndex;
    }

    private long videoId(int usedMaterialIndex) {
        return VIDEO_ID_START + usedMaterialIndex;
    }

    private static void applyMapExportRotation(ArrayList<Float> coords) {
        for (int i = 0; i < coords.size(); i += 3) {
            float x = coords.get(i);
            float y = coords.get(i + 1);
            float z = coords.get(i + 2);
            coords.set(i, -x);
            coords.set(i + 1, z);
            coords.set(i + 2, y);
        }
    }

    private void writeTextures() {
        for (int i = 0; i < tset.getMaterials().size(); i++) {
            if (textureUsage.get(i) > 0) {
                String path = folderPath + File.separator + tset.getImageName(i);
                File outputfile = new File(path);
                try {
                    ImageIO.write(tset.getTextureImg(i), "png", outputfile);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private static FbxNode node(String name, FbxProperty... properties) {
        return new FbxNode(name, properties);
    }

    private static FbxProperty integer(int value) {
        return new FbxProperty('I', value);
    }

    private static FbxProperty longValue(long value) {
        return new FbxProperty('L', value);
    }

    private static FbxProperty doubleValue(double value) {
        return new FbxProperty('D', value);
    }

    private static FbxProperty bool(boolean value) {
        return new FbxProperty('C', value);
    }

    private static FbxProperty string(String value) {
        return new FbxProperty('S', value);
    }

    private static FbxProperty doubleArray(ArrayList<Float> values) {
        return new FbxProperty('d', values);
    }

    private static FbxProperty intArray(ArrayList<Integer> values) {
        return new FbxProperty('i', values);
    }

    private static void writeFbxBinaryHeader(ByteArrayOutputStream out) {
        out.write(FBX_BINARY_HEADER, 0, FBX_BINARY_HEADER.length);
        writeInt32(out, FBX_VERSION);
    }

    private static void writeByte(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeInt64(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xff));
        out.write((int) ((value >>> 8) & 0xff));
        out.write((int) ((value >>> 16) & 0xff));
        out.write((int) ((value >>> 24) & 0xff));
        out.write((int) ((value >>> 32) & 0xff));
        out.write((int) ((value >>> 40) & 0xff));
        out.write((int) ((value >>> 48) & 0xff));
        out.write((int) ((value >>> 56) & 0xff));
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        writeInt64(out, Double.doubleToLongBits(value));
    }

    private static void writeZeroBytes(ByteArrayOutputStream out, int count) {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }

    private static class FbxNode {

        private final String name;
        private final ArrayList<FbxProperty> properties = new ArrayList<>();
        private final ArrayList<FbxNode> children = new ArrayList<>();

        private FbxNode(String name, FbxProperty... properties) {
            this.name = name;
            for (FbxProperty property : properties) {
                this.properties.add(property);
            }
        }

        private FbxNode child(FbxNode child) {
            children.add(child);
            return this;
        }

        private int byteSize() {
            int size = FBX_NODE_HEADER_SIZE + nameBytes().length + propertyBytesLength();
            for (FbxNode child : children) {
                size += child.byteSize();
            }
            if (!children.isEmpty()) {
                size += FBX_NULL_RECORD_SIZE;
            }
            return size;
        }

        private int propertyBytesLength() {
            int length = 0;
            for (FbxProperty property : properties) {
                length += property.byteSize();
            }
            return length;
        }

        private void write(ByteArrayOutputStream out, int startOffset) {
            byte[] nameBytes = nameBytes();
            int propertyBytesLength = propertyBytesLength();

            writeInt32(out, startOffset + byteSize());
            writeInt32(out, properties.size());
            writeInt32(out, propertyBytesLength);
            writeByte(out, nameBytes.length);
            out.write(nameBytes, 0, nameBytes.length);

            for (FbxProperty property : properties) {
                property.write(out);
            }

            int childOffset = startOffset + FBX_NODE_HEADER_SIZE + nameBytes.length + propertyBytesLength;
            for (FbxNode child : children) {
                child.write(out, childOffset);
                childOffset += child.byteSize();
            }

            if (!children.isEmpty()) {
                writeZeroBytes(out, FBX_NULL_RECORD_SIZE);
            }
        }

        private byte[] nameBytes() {
            return name.getBytes(StandardCharsets.UTF_8);
        }

    }

    private static class FbxProperty {

        private final char type;
        private final Object value;

        private FbxProperty(char type, Object value) {
            this.type = type;
            this.value = value;
        }

        private int byteSize() {
            switch (type) {
                case 'C':
                    return 2;
                case 'I':
                    return 5;
                case 'L':
                case 'D':
                    return 9;
                case 'S':
                    return 5 + stringBytes().length;
                case 'd':
                    return 13 + floatArray().size() * 8;
                case 'i':
                    return 13 + intArray().size() * 4;
                default:
                    throw new IllegalStateException("Unsupported FBX property type: " + type);
            }
        }

        private void write(ByteArrayOutputStream out) {
            writeByte(out, type);
            switch (type) {
                case 'C':
                    writeByte(out, ((Boolean) value) ? 1 : 0);
                    break;
                case 'I':
                    writeInt32(out, (Integer) value);
                    break;
                case 'L':
                    writeInt64(out, (Long) value);
                    break;
                case 'D':
                    writeDouble(out, (Double) value);
                    break;
                case 'S':
                    writeString(out);
                    break;
                case 'd':
                    writeDoubleArray(out);
                    break;
                case 'i':
                    writeIntArray(out);
                    break;
                default:
                    throw new IllegalStateException("Unsupported FBX property type: " + type);
            }
        }

        private void writeString(ByteArrayOutputStream out) {
            byte[] bytes = stringBytes();
            writeInt32(out, bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        private void writeDoubleArray(ByteArrayOutputStream out) {
            ArrayList<Float> values = floatArray();
            writeInt32(out, values.size());
            writeInt32(out, 0);
            writeInt32(out, values.size() * 8);
            for (Float value : values) {
                writeDouble(out, value);
            }
        }

        private void writeIntArray(ByteArrayOutputStream out) {
            ArrayList<Integer> values = intArray();
            writeInt32(out, values.size());
            writeInt32(out, 0);
            writeInt32(out, values.size() * 4);
            for (Integer value : values) {
                writeInt32(out, value);
            }
        }

        private byte[] stringBytes() {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }

        @SuppressWarnings("unchecked")
        private ArrayList<Float> floatArray() {
            return (ArrayList<Float>) value;
        }

        @SuppressWarnings("unchecked")
        private ArrayList<Integer> intArray() {
            return (ArrayList<Integer>) value;
        }

    }

    private static class FbxMesh {

        private final ArrayList<Float> vertexCoords = new ArrayList<>();
        private final ArrayList<Float> textureCoords = new ArrayList<>();
        private final ArrayList<Float> normalCoords = new ArrayList<>();
        private final ArrayList<Float> colors = new ArrayList<>();
        private final ArrayList<ArrayList<Face>> facesQuadByTexture;
        private final ArrayList<ArrayList<Face>> facesTriByTexture;
        private ArrayList<Integer> usedTextureIds = new ArrayList<>();

        private FbxMesh(int numTextures) {
            facesQuadByTexture = new ArrayList<>(numTextures);
            facesTriByTexture = new ArrayList<>(numTextures);
            for (int i = 0; i < numTextures; i++) {
                facesQuadByTexture.add(new ArrayList<>());
                facesTriByTexture.add(new ArrayList<>());
            }
        }

    }

    private static class PolygonData {

        private final ArrayList<Integer> polygonVertexIndices = new ArrayList<>();
        private final ArrayList<Float> normals = new ArrayList<>();
        private final ArrayList<Float> uvs = new ArrayList<>();
        private final ArrayList<Float> colors = new ArrayList<>();
        private final ArrayList<Integer> materialIndices = new ArrayList<>();

    }

}
