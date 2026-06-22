package com.example.coppergolem.agent;

import java.util.Map;

/**
 * One step in an AI-generated plan.
 *
 * @param kind  verb from the task vocabulary (sort, mine, chop, deposit, acquire_tool, craft, torch)
 * @param args  key→value parameters (coords, sizes, filters, etc.)
 * @param label human-readable description shown in the UI
 */
public record PlanStep(String kind, Map<String, String> args, String label) {}
