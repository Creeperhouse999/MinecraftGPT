package com.example.coppergolem.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanStepParseTest {

    private static final String SAMPLE_JSON =
        "{\"plan\":[" +
        "{\"kind\":\"mine\",\"args\":{\"w\":\"3\",\"h\":\"3\",\"length\":\"16\",\"dir\":\"north\"},\"label\":\"Mine 3x3 tunnel north\"}," +
        "{\"kind\":\"deposit\",\"args\":{},\"label\":\"Deposit cobble\"}" +
        "]}";

    @Test
    void parseSampleJson_returns2Steps() {
        List<PlanStep> steps = AgentPlanner.parse(SAMPLE_JSON);
        assertEquals(2, steps.size());
    }

    @Test
    void parseSampleJson_kindsCorrect() {
        List<PlanStep> steps = AgentPlanner.parse(SAMPLE_JSON);
        assertEquals("mine", steps.get(0).kind());
        assertEquals("deposit", steps.get(1).kind());
    }

    @Test
    void parseSampleJson_labelsCorrect() {
        List<PlanStep> steps = AgentPlanner.parse(SAMPLE_JSON);
        assertEquals("Mine 3x3 tunnel north", steps.get(0).label());
        assertEquals("Deposit cobble", steps.get(1).label());
    }

    @Test
    void parseSampleJson_argsCorrect() {
        List<PlanStep> steps = AgentPlanner.parse(SAMPLE_JSON);
        var args = steps.get(0).args();
        assertEquals("3", args.get("w"));
        assertEquals("3", args.get("h"));
        assertEquals("16", args.get("length"));
        assertEquals("north", args.get("dir"));
        assertTrue(steps.get(1).args().isEmpty());
    }

    @Test
    void parseMalformedJson_returnsEmptyList() {
        List<PlanStep> steps = AgentPlanner.parse("not json");
        assertEquals(0, steps.size());
    }

    @Test
    void parseEmptyPlan_returnsEmptyList() {
        List<PlanStep> steps = AgentPlanner.parse("{\"plan\":[]}");
        assertEquals(0, steps.size());
    }
}
