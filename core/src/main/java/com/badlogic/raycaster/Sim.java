package com.badlogic.raycaster;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.IntArray;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Sim implements ApplicationListener {
    private static final int PARTICLE_COUNT = 2500;
    private static final float WORLD_WIDTH = 1000f;
    private static final float WORLD_HEIGHT = 800f;
    private static final float PARTICLE_RADIUS = 2f;

    private static final float CONNECT_RADIUS = 15f;

    private static final float RESTITUTION = 0.5f;

    private static final int SUBSTEPS = 4;

    private static final float H = 16f; //interaction radius
    private static final float STIFFNESS = 3000f; // repulsion strength
    private static final float VISCOSITY = 5f;

    private final Vector2 mouse = new Vector2();
    private static final float ATTRACT = 4f;
    private static final float DAMPING = 1.5f;

    private ShapeRenderer shapeRenderer;
    private FitViewport viewport;
    private Array<Particle> particles;

    private final IntArray nbrs = new IntArray();
    private boolean[] covered;

    private int rows, cols;
    private int[] cellHead;
    private int[] nextInCell;

    @Override
    public void create() {
        shapeRenderer = new ShapeRenderer();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        particles = new Array<>();

        

        // Temporary test layout: a sqrt(PARTICLE_COUNT) block of particles near the middle of the world.
        float spacing = 5f;
        for (int i = 0; i < (int)Math.sqrt(PARTICLE_COUNT); i++) {
            for (int j = 0; j < (int)Math.sqrt(PARTICLE_COUNT); j++) {
                particles.add(new Particle(
                    WORLD_WIDTH / 2f + (i - 10) * spacing,
                    WORLD_HEIGHT / 2f + (j - 10) * spacing));
            }
        }

        covered = new boolean[particles.size];

        cols = (int)Math.ceil(WORLD_WIDTH / H);
        rows = (int)Math.ceil(WORLD_HEIGHT / H);
        cellHead = new int[cols * rows];
        nextInCell = new int[particles.size];
    }

    @Override
    public void resize(int width, int height) {
        // If the window is minimized on a desktop (LWJGL3) platform, width and height are 0, which causes problems.
        // In that case, we don't resize anything, and wait for the window to be a normal size before updating.
        if(width <= 0 || height <= 0) return;

        viewport.update(width, height, true);
    }

    @Override
    public void render() {

        mouse.set(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(mouse);

        float sign = Gdx.input.isTouched() ? -1f : 1f;

        float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        float dt = frameDt / SUBSTEPS;
        for(int s = 0; s < SUBSTEPS; s++){
            step(dt);
        }

        ScreenUtils.clear(0.05f, 0.05f, 0.1f, 1f);
        viewport.apply();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        renderFluid();
    }

    private float dist2(Particle a, Particle b){
        float dx = b.position[0] - a.position[0];
        float dy = b.position[1] - a.position[1];
        return dx * dx + dy * dy;
    }

    private void renderFluid(){
        buildGrid();
        java.util.Arrays.fill(covered, false);
        float r2 = CONNECT_RADIUS * CONNECT_RADIUS;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.SKY);

        for(int i = 0; i < particles.size; i++){
            Particle a = particles.get(i);
            int cx = cellCoord(a.position[0], cols);
            int cy = cellCoord(a.position[1], rows);

            // Collect heighter indexed neighbors within range
            nbrs.clear();

            for (int gy = Math.max(cy - 1, 0); gy <= Math.min(cy + 1, rows - 1); gy++) {
                for (int gx = Math.max(cx - 1, 0); gx <= Math.min(cx + 1, cols - 1); gx++) {
                    for (int j = cellHead[gy * cols + gx]; j != -1; j = nextInCell[j]) {
                        if (j > i && dist2(a, particles.get(j)) < r2) nbrs.add(j);
                    }
                }
            }

            // Every pair of neighbors that are also close to each other forms a triangle
            for (int m = 0; m < nbrs.size; m++) {
                Particle b = particles.get(nbrs.get(m));
                for (int n = m + 1; n < nbrs.size; n++) {
                    Particle c = particles.get(nbrs.get(n));
                    if (dist2(b, c) < r2) {
                        shapeRenderer.triangle(a.position[0], a.position[1],
                                            b.position[0], b.position[1],
                                            c.position[0], c.position[1]);
                        covered[i] = covered[nbrs.get(m)] = covered[nbrs.get(n)] = true;
                    }
                }
            }
        }

        // Loose particles become droplets
        for (int i = 0; i < particles.size; i++) {
            if (!covered[i]) {
                Particle p = particles.get(i);
                shapeRenderer.circle(p.position[0], p.position[1], PARTICLE_RADIUS, 12);
            }
        }

        shapeRenderer.end();
    }

    private int cellCoord(float v, int max){
        int c = (int) (v / H);
        return c < 0 ? 0 : (c >= max ? max - 1 : c);
    }

    private void buildGrid(){
        java.util.Arrays.fill(cellHead, -1);
        for(int i = 0; i < particles.size; i++){
            Particle p = particles.get(i);
            int cell = cellCoord(p.position[1], rows) * cols + cellCoord(p.position[0], cols);
            nextInCell[i] = cellHead[cell];
            cellHead[cell] = i;
        }
    }

    private void step(float dt){
        // Accumulate forces
        for(Particle p : particles){
            p.acceleration[1] -= 500f;
        }

        applyParticleForces();

        for(Particle p : particles){
            p.update(dt);
            p.collideWithBounds(0f, 0f, WORLD_WIDTH, WORLD_HEIGHT, PARTICLE_RADIUS, RESTITUTION);
        }
    }

    private void applyParticleForces(){
        buildGrid();

        for(int i = 0; i < particles.size; i++){
            Particle a = particles.get(i);
            int cx = cellCoord(a.position[0], cols);
            int cy = cellCoord(a.position[1], rows);

            for(int ny = Math.max(cy - 1, 0); ny <= Math.min(cy + 1, rows - 1); ny++){
                for(int nx = Math.max(cx - 1, 0); nx <= Math.min(cx + 1, cols - 1); nx++){
                    for(int j = cellHead[ny * cols + nx]; j != -1; j = nextInCell[j]){
                        if(j > i){
                            interact(a, particles.get(j));
                        }
                    }
                }
            }
        }
    }

    private void interact(Particle a, Particle b){
        float h2 = H * H;
        

        float dx = b.position[0] - a.position[0];
        float dy = b.position[1] - a.position[1];

        float d2 = dx * dx + dy * dy;
        if(d2 >= h2) return;

        float d = (float)Math.sqrt(d2);
        float nx, ny;
        if(d < 1e-4f) {nx = 1f; ny = 0f;}
        else {nx = dx / d; ny = dy / d;}

        float w = 1f - d / H;

        // Repulsion along the line between the two particles
        float push = STIFFNESS * w;
        a.acceleration[0] -= nx * push;
        a.acceleration[1] -= ny * push;
        b.acceleration[0] += nx * push;
        b.acceleration[1] += ny * push;

        // Viscosity: pull relative velocities together
        float vx = VISCOSITY * w * (b.velocity[0] - a.velocity[0]);
        float vy = VISCOSITY * w * (b.velocity[1] - a.velocity[1]);
        a.acceleration[0] += vx;
        a.acceleration[1] += vy;
        b.acceleration[0] -= vx;
        b.acceleration[1] -= vy;
    }

    @Override
    public void pause() {
        // Invoked when your application is paused.
    }

    @Override
    public void resume() {
        // Invoked when your application is resumed after pause.
    }

    @Override
    public void dispose() {
        shapeRenderer.dispose();
    }
}