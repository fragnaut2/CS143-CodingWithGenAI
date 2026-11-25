package com.badlogic.raycaster;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * Draws particles as a smooth fluid surface in two passes:
 *
 * 1. FIELD PASS: every particle is drawn as a soft, fuzzy blob into an offscreen buffer.
 *    Blobs are added together, so where particles are close the values pile up.
 *    The buffer is effectively a "density field" of the fluid.
 *
 * 2. COMPOSITE PASS: the field is drawn to the screen through a shader that turns every
 *    pixel above a threshold into water color and everything below it into transparent.
 *    The surface of the fluid is wherever the field crosses that threshold.
 */
public class FluidRenderer implements Disposable {

    // ---- Tuning ---------------------------------------------------------------------
    /** Radius of each blob in world units. Bigger = thicker, more connected sheets. */
    private static final float BLOB_RADIUS = 12f;
    /** Field value (0..1) where the surface sits. Higher = fluid shrinks, more droplets. */
    private static final float THRESHOLD = 0.05f;
    /** Width of the soft edge around the threshold. Smaller = crisper outline. */
    private static final float EDGE_SOFTNESS = 0.1f;
    /** Color right at the surface and deep inside the fluid (r, g, b). */
    private static final float[] SURFACE_COLOR = {0.45f, 0.80f, 1.00f};
    private static final float[] DEEP_COLOR    = {0.10f, 0.40f, 0.90f};
    // ---------------------------------------------------------------------------------

    private static final String VERTEX_SHADER =
        "attribute vec4 a_position;\n" +
        "attribute vec4 a_color;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "uniform mat4 u_projTrans;\n" +
        "varying vec2 v_texCoords;\n" +
        "void main() {\n" +
        "    v_texCoords = a_texCoord0;\n" +
        "    gl_Position = u_projTrans * a_position;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#ifdef GL_ES\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "varying vec2 v_texCoords;\n" +
        "uniform sampler2D u_texture;\n" +
        "uniform float u_threshold;\n" +
        "uniform float u_softness;\n" +
        "uniform vec3 u_surfaceColor;\n" +
        "uniform vec3 u_deepColor;\n" +
        "void main() {\n" +
        "    float field = texture2D(u_texture, v_texCoords).r;\n" +
        "    float inside = smoothstep(u_threshold - u_softness, u_threshold + u_softness, field);\n" +
        "    float depth = smoothstep(u_threshold, 1.0, field);\n" +
        "    gl_FragColor = vec4(mix(u_surfaceColor, u_deepColor, depth), inside);\n" +
        "}\n";

    private final float worldWidth;
    private final float worldHeight;

    private final Texture blobTexture;          // the fuzzy circle drawn for each particle
    private final FrameBuffer fieldBuffer;      // offscreen buffer holding the summed blobs
    private final TextureRegion fieldRegion;    // the buffer's texture, flipped right-side up
    private final OrthographicCamera fieldCamera;

    private final SpriteBatch blobBatch;        // draws blobs with additive blending
    private final SpriteBatch compositeBatch;   // draws the field through the threshold shader
    private final ShaderProgram compositeShader;

    public FluidRenderer(float worldWidth, float worldHeight) {
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;

        blobTexture = createBlobTexture();

        // The buffer covers the whole world, one pixel per world unit. Because it is tied to
        // world size rather than window size, it never needs to be rebuilt on resize.
        fieldBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, (int) worldWidth, (int) worldHeight, false);
        fieldBuffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        fieldRegion = new TextureRegion(fieldBuffer.getColorBufferTexture());
        fieldRegion.flip(false, true); // framebuffer textures come out upside down

        fieldCamera = new OrthographicCamera();
        fieldCamera.setToOrtho(false, worldWidth, worldHeight);

        blobBatch = new SpriteBatch();
        blobBatch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE); // additive: overlapping blobs sum up

        ShaderProgram.pedantic = false; // don't throw if a uniform is optimized out
        compositeShader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!compositeShader.isCompiled()) {
            throw new IllegalStateException("Fluid shader failed to compile:\n" + compositeShader.getLog());
        }
        compositeBatch = new SpriteBatch(1, compositeShader);
    }

    /** Draws all particles as a fluid. Call after clearing the screen. */
    public void render(Array<Particle> particles, Viewport viewport) {
        renderField(particles);
        renderFieldToScreen(viewport);
    }

    /** Pass 1: sum up one soft blob per particle into the offscreen buffer. */
    private void renderField(Array<Particle> particles) {
        fieldBuffer.begin();
        ScreenUtils.clear(0f, 0f, 0f, 0f);

        blobBatch.setProjectionMatrix(fieldCamera.combined);
        blobBatch.begin();
        for (int i = 0; i < particles.size; i++) {
            Particle p = particles.get(i);
            blobBatch.draw(blobTexture,
                p.position[0] - BLOB_RADIUS, p.position[1] - BLOB_RADIUS,
                BLOB_RADIUS * 2f, BLOB_RADIUS * 2f);
        }
        blobBatch.end();

        fieldBuffer.end();
    }

    /** Pass 2: draw the field over the whole world, thresholded into a solid-looking surface. */
    private void renderFieldToScreen(Viewport viewport) {
        // FrameBuffer.end() resets the GL viewport to the full window, so restore the letterboxed one.
        viewport.apply();

        compositeBatch.setProjectionMatrix(viewport.getCamera().combined);
        compositeBatch.begin();
        compositeShader.setUniformf("u_threshold", THRESHOLD);
        compositeShader.setUniformf("u_softness", EDGE_SOFTNESS);
        compositeShader.setUniformf("u_surfaceColor", SURFACE_COLOR[0], SURFACE_COLOR[1], SURFACE_COLOR[2]);
        compositeShader.setUniformf("u_deepColor", DEEP_COLOR[0], DEEP_COLOR[1], DEEP_COLOR[2]);
        compositeBatch.draw(fieldRegion, 0f, 0f, worldWidth, worldHeight);
        compositeBatch.end();
    }

    /**
     * Builds the blob image: brightest (1.0) in the center, fading smoothly to 0 at the edge.
     * The brightness is stored in every channel so additive blending sums it into the red
     * channel, which is what the shader reads.
     */
    private Texture createBlobTexture() {
        int size = 64;
        float half = size / 2f;

        Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None); // write pixels exactly as given

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - half) / half;
                float dy = (y + 0.5f - half) / half;
                float distSq = dx * dx + dy * dy;

                float falloff = distSq >= 1f ? 0f : (1f - distSq) * (1f - distSq);
                int v = Math.round(falloff * 255f);
                pixmap.drawPixel(x, y, (v << 24) | (v << 16) | (v << 8) | v);
            }
        }

        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    @Override
    public void dispose() {
        blobTexture.dispose();
        fieldBuffer.dispose();
        blobBatch.dispose();
        compositeBatch.dispose();
        compositeShader.dispose();
    }
}
