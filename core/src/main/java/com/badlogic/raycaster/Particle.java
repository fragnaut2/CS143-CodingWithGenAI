package com.badlogic.raycaster;

/** A single fluid particle. Each vector is a two-element array: index 0 = x, index 1 = y. */
public class Particle {
    public final float[] position = new float[2];
    public final float[] velocity = new float[2];
    public final float[] acceleration = new float[2];

    public Particle(float x, float y) {
        position[0] = x;
        position[1] = y;
    }

    /** Simple semi-implicit Euler step; the forces/spatial grid will feed into acceleration later. */
    public void update(float dt) {
        velocity[0] += acceleration[0] * dt;
        velocity[1] += acceleration[1] * dt;
        position[0] += velocity[0] * dt;
        position[1] += velocity[1] * dt;

        // Clear so forces can be accumulated fresh each frame.
        acceleration[0] = 0f;
        acceleration[1] = 0f;
    }
}
