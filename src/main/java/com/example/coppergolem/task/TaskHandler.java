package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;

public interface TaskHandler {
    /** @return true when task complete. */
    boolean tick(GolemPrimitives g);
    String status();
    void pause();
    void resume();
}
