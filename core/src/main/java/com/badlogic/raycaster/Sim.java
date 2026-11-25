package com.badlogic.raycaster;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.math.Vector2;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Sim implements ApplicationListener {
    private static final float WORLD_WIDTH = 1000f;
    private static final float WORLD_HEIGHT = 800f;
    private static final float PARTICLE_RADIUS = 4f;

    private static final float RESTITUTION = 0.5f;

    private final Vector2 mouse = new Vector2();
    private static final float ATTRACT = 4f;
    private static final float DAMPING = 1.5f;

    private ShapeRenderer shapeRenderer;
    private FitViewport viewport;
    private Array<Particle> particles;

    @Override
    public void create() {
        shapeRenderer = new ShapeRenderer();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        particles = new Array<>();

        // Temporary test layout: a 20x20 block of particles near the middle of the world.
        float spacing = 10f;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
                particles.add(new Particle(
                    WORLD_WIDTH / 2f + (i - 10) * spacing,
                    WORLD_HEIGHT / 2f + (j - 10) * spacing));
            }
        }
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
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f); // clamp to avoid huge steps

        mouse.set(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(mouse);

        float sign = Gdx.input.isTouched() ? -1f : 1f;

        for (Particle p : particles) {
            float dx = mouse.x - p.position[0];
            float dy = mouse.y - p.position[1];

            p.acceleration[0] += sign * ATTRACT * dx - DAMPING * p.velocity[0];
            p.acceleration[1] += sign * ATTRACT * dy - DAMPING * p.velocity[1];

            p.update(dt);
            p.collideWithBounds(0f, 0f, WORLD_WIDTH, WORLD_HEIGHT, PARTICLE_RADIUS, RESTITUTION);
        }

        ScreenUtils.clear(0.05f, 0.05f, 0.1f, 1f);
        viewport.apply();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.SKY);
        for (Particle p : particles) {
            shapeRenderer.circle(p.position[0], p.position[1], PARTICLE_RADIUS, 12);
        }
        shapeRenderer.end();
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