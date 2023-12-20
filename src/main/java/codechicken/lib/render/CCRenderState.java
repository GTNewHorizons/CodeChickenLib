package codechicken.lib.render;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import codechicken.lib.colour.ColourRGBA;
import codechicken.lib.lighting.LC;
import codechicken.lib.lighting.LightMatrix;
import codechicken.lib.util.Copyable;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Transformation;
import codechicken.lib.vec.Vector3;

/**
 * The core of the CodeChickenLib render system. Rendering operations are written to avoid object allocations by reusing
 * static variables.
 */
public class CCRenderState {
    // TODO: Do a similar ASM to the tesselator redirector in Angelica
    // public static CCRenderPipeline pipeline = new CCRenderPipeline(instance());

    public final CCRenderPipeline pipelineLocal;

    private static final ThreadLocal<CCRenderState> instances = ThreadLocal.withInitial(CCRenderState::new);

    private CCRenderState() {
        pipelineLocal = new CCRenderPipeline(this);
    }

    public static CCRenderState instance() {
        return instances.get();
    }

    private static int nextOperationIndex;

    public static int registerOperation() {
        return nextOperationIndex++;
    }

    public static int operationCount() {
        return nextOperationIndex;
    }

    /**
     * Represents an operation to be run for each vertex that operates on and modifies the current state
     */
    public interface IVertexOperation {

        /**
         * Load any required references and add dependencies to the pipeline based on the current model (may be null)
         * Return false if this operation is redundant in the pipeline with the given model
         */
        // boolean load();
        boolean load(CCRenderState state);

        /**
         * Perform the operation on the current render state
         */
        // void operate();
        void operate(CCRenderState state);

        /**
         * Get the unique id representing this type of operation. Duplicate operation IDs within the pipeline may have
         * unexpected results. ID shoulld be obtained from CCRenderState.registerOperation() and stored in a static
         * variable
         */
        int operationID();
    }

    private static ArrayList<VertexAttribute<?>> vertexAttributes = new ArrayList<VertexAttribute<?>>();

    private static int registerVertexAttribute(VertexAttribute<?> attr) {
        vertexAttributes.add(attr);
        return vertexAttributes.size() - 1;
    }

    public static VertexAttribute<?> getAttribute(int index) {
        return vertexAttributes.get(index);
    }

    /**
     * Management class for a vertex attrute such as colour, normal etc This class should handle the loading of the
     * attrute from an array provided by IVertexSource.getAttributes or the computation of this attrute from others
     * 
     * @param <T> The array type for this attrute eg. int[], Vector3[]
     */
    public abstract static class VertexAttribute<T> implements IVertexOperation {

        public final int attributeIndex = registerVertexAttribute(this);
        private final int operationIndex = registerOperation();
        /**
         * Set to true when the attrute is part of the pipeline. Should only be managed by CCRenderState when
         * constructing the pipeline
         */
        public boolean active = false;

        /**
         * Construct a new array for storage of vertex attrutes in a model
         */
        public abstract T newArray(int length);

        @Override
        public int operationID() {
            return operationIndex;
        }
    }

    public static void arrayCopy(Object src, int srcPos, Object dst, int destPos, int length) {
        System.arraycopy(src, srcPos, dst, destPos, length);
        if (dst instanceof Copyable[]) {
            Object[] oa = (Object[]) dst;
            Copyable<Object>[] c = (Copyable[]) dst;
            for (int i = destPos; i < destPos + length; i++) if (c[i] != null) oa[i] = c[i].copy();
        }
    }

    public static <T> T copyOf(VertexAttribute<T> attr, T src, int length) {
        T dst = attr.newArray(length);
        arrayCopy(src, 0, dst, 0, ((Object[]) src).length);
        return dst;
    }

    public static interface IVertexSource {

        public Vertex5[] getVertices();

        /**
         * Gets an array of vertex attrutes
         * 
         * @param attr The vertex attrute to get
         * @param <T>  The attrute array type
         * @return An array, or null if not computed
         */
        public <T> T getAttributes(VertexAttribute<T> attr);

        /**
         * @return True if the specified attrute is provided by this model, either by returning an array from
         *         getAttributes or by setting the state in prepareVertex
         */
        public boolean hasAttribute(VertexAttribute<?> attr);

        /**
         * Callback to set CCRenderState for a vertex before the pipeline runs
         */
        // public void prepareVertex();
        public void prepareVertex(CCRenderState state);
    }

    public static VertexAttribute<Vector3[]> normalAttrib = new VertexAttribute<Vector3[]>() {

        private Vector3[] normalRef;

        @Override
        public Vector3[] newArray(int length) {
            return new Vector3[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            normalRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return normalRef != null;

            if (state.model.hasAttribute(sideAttrib)) {
                state.pipelineLocal.addDependency(sideAttrib);
                return true;
            }
            throw new IllegalStateException(
                    "Normals requested but neither normal or side attrutes are provided by the model");
        }

        @Override
        public void operate(CCRenderState state) {
            if (normalRef != null) state.setNormal(normalRef[state.vertexIndex]);
            else state.setNormal(Rotation.axes[state.side]);
        }
    };
    public static VertexAttribute<int[]> colourAttrib = new VertexAttribute<int[]>() {

        private int[] colourRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            colourRef = state.model.getAttributes(this);
            return colourRef != null || !state.model.hasAttribute(this);
        }

        @Override
        public void operate(CCRenderState state) {
            if (colourRef != null) state.setColour(ColourRGBA.multiply(state.baseColour, colourRef[state.vertexIndex]));
            else state.setColour(state.baseColour);
        }
    };
    public static VertexAttribute<int[]> lightingAttrib = new VertexAttribute<int[]>() {

        private int[] colourRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            if (!state.computeLighting || !state.useColour || !state.model.hasAttribute(this)) return false;

            colourRef = state.model.getAttributes(this);
            if (colourRef != null) {
                state.pipelineLocal.addDependency(colourAttrib);
                return true;
            }
            return false;
        }

        @Override
        public void operate(CCRenderState state) {
            state.setColour(ColourRGBA.multiply(state.colour, colourRef[state.vertexIndex]));
        }
    };
    public static VertexAttribute<int[]> sideAttrib = new VertexAttribute<int[]>() {

        private int[] sideRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            sideRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return sideRef != null;

            state.pipelineLocal.addDependency(normalAttrib);
            return true;
        }

        @Override
        public void operate(CCRenderState state) {
            if (sideRef != null) state.side = sideRef[state.vertexIndex];
            else state.side = CCModel.findSide(state.normal);
        }
    };
    /**
     * Uses the position of the lightmatrix to compute LC if not provided
     */
    public static VertexAttribute<LC[]> lightCoordAttrib = new VertexAttribute<LC[]>() {

        private LC[] lcRef;
        private final Vector3 vec = new Vector3(); // for computation
        private final Vector3 pos = new Vector3();

        @Override
        public LC[] newArray(int length) {
            return new LC[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            lcRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return lcRef != null;

            pos.set(state.lightMatrix.pos.x, state.lightMatrix.pos.y, state.lightMatrix.pos.z);
            state.pipelineLocal.addDependency(sideAttrib);
            state.pipelineLocal.addRequirement(Transformation.operationIndex);
            return true;
        }

        @Override
        public void operate(CCRenderState state) {
            if (lcRef != null) state.lc.set(lcRef[state.vertexIndex]);
            else state.lc.compute(vec.set(state.vert.vec).sub(pos), state.side);
        }
    };

    // pipeline state
    public IVertexSource model;
    public int firstVertexIndex;
    public int lastVertexIndex;
    public int vertexIndex;

    // context
    public int baseColour;
    public int alphaOverride;
    public boolean useNormals;
    public boolean computeLighting;
    public boolean useColour;
    public LightMatrix lightMatrix = new LightMatrix();

    // vertex outputs
    public Vertex5 vert = new Vertex5();
    public boolean hasNormal;
    public Vector3 normal = new Vector3();
    public boolean hasColour;
    public int colour;
    public boolean hasBrightness;
    public int brightness;

    // attrute storage
    public int side;
    public LC lc = new LC();

    public void reset() {
        model = null;
        pipelineLocal.reset();
        useNormals = hasNormal = hasBrightness = hasColour = false;
        useColour = computeLighting = true;
        baseColour = alphaOverride = -1;
    }

    public void setPipeline(IVertexOperation... ops) {
        pipelineLocal.setPipeline(ops);
    }

    public void setPipeline(IVertexSource model, int start, int end, IVertexOperation... ops) {
        pipelineLocal.reset();
        setModel(model, start, end);
        pipelineLocal.setPipeline(ops);
    }

    public void bindModel(IVertexSource model) {
        if (this.model != model) {
            this.model = model;
            pipelineLocal.rebuild();
        }
    }

    public void setModel(IVertexSource source) {
        setModel(source, 0, source.getVertices().length);
    }

    public void setModel(IVertexSource source, int start, int end) {
        bindModel(source);
        setVertexRange(start, end);
    }

    public void setVertexRange(int start, int end) {
        firstVertexIndex = start;
        lastVertexIndex = end;
    }

    public void render(IVertexOperation... ops) {
        setPipeline(ops);
        render();
    }

    public void render() {
        Vertex5[] verts = model.getVertices();
        for (vertexIndex = firstVertexIndex; vertexIndex < lastVertexIndex; vertexIndex++) {
            model.prepareVertex(this);
            vert.set(verts[vertexIndex]);
            runPipeline();
            writeVert();
        }
    }

    public void runPipeline() {
        pipelineLocal.operate();
    }

    public void writeVert() {
        if (hasNormal) Tessellator.instance.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        if (hasColour) Tessellator.instance.setColorRGBA(
                colour >>> 24,
                colour >> 16 & 0xFF,
                colour >> 8 & 0xFF,
                alphaOverride >= 0 ? alphaOverride : colour & 0xFF);
        if (hasBrightness) Tessellator.instance.setBrightness(brightness);
        Tessellator.instance.addVertexWithUV(vert.vec.x, vert.vec.y, vert.vec.z, vert.uv.u, vert.uv.v);
    }

    public void setNormal(double x, double y, double z) {
        hasNormal = true;
        normal.set(x, y, z);
    }

    public void setNormal(Vector3 n) {
        hasNormal = true;
        normal.set(n);
    }

    public void setColour(int c) {
        hasColour = true;
        colour = c;
    }

    public void setBrightness(int b) {
        hasBrightness = true;
        brightness = b;
    }

    public void setBrightness(IBlockAccess world, int x, int y, int z) {
        setBrightness(world.getBlock(x, y, z).getMixedBrightnessForBlock(world, x, y, z));
    }

    public void pullLightmap() {
        setBrightness((int) OpenGlHelper.lastBrightnessY << 16 | (int) OpenGlHelper.lastBrightnessX);
    }

    public void pushLightmap() {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightness & 0xFFFF, brightness >>> 16);
    }

    /**
     * Compact helper for setting dynamic rendering context. Uses normals and doesn't compute lighting
     */
    public void setDynamic() {
        useNormals = true;
        computeLighting = false;
    }

    public static void changeTexture(String texture) {
        changeTexture(new ResourceLocation(texture));
    }

    public static void changeTexture(ResourceLocation texture) {
        Minecraft.getMinecraft().renderEngine.bindTexture(texture);
    }

    public void startDrawing() {
        startDrawing(7);
    }

    public void startDrawing(int mode) {
        Tessellator.instance.startDrawing(mode);
        if (hasColour) Tessellator.instance.setColorRGBA(
                colour >>> 24,
                colour >> 16 & 0xFF,
                colour >> 8 & 0xFF,
                alphaOverride >= 0 ? alphaOverride : colour & 0xFF);
        if (hasBrightness) Tessellator.instance.setBrightness(brightness);
    }

    public void draw() {
        Tessellator.instance.draw();
    }
}
