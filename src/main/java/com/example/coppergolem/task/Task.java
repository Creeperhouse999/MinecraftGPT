package com.example.coppergolem.task;

public sealed interface Task permits Task.Sort, Task.Mine, Task.Chop, Task.Unknown {
    record Sort(int radius) implements Task {}
    record Mine(int w, int h, int length, String dir, String filter) implements Task {}
    record Chop(int radius, boolean replant) implements Task {}
    record Unknown(String reason) implements Task {}
}
