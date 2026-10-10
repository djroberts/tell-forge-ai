package dev.tellforgeai.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingEvidenceTest {
    @Test
    fun `matches exact model id and common display formatting`() {
        assertTrue(ModelPricingEvidence.matchesModel("gpt-6-luna", "GPT-6 Luna API pricing"))
        assertTrue(ModelPricingEvidence.matchesModel("gpt-6-luna", "https://openrouter.ai/openai/gpt-6-luna"))
        assertTrue(ModelPricingEvidence.matchesModel("gpt-6-luna", "Model: gpt_6_luna"))
    }

    @Test
    fun `namespaced model IDs match evidence for the model after slash`() {
        assertTrue(ModelPricingEvidence.matchesModel("openai/gpt-6-luna", "GPT-6 Luna API pricing"))
        assertTrue(ModelPricingEvidence.matchesModel("openrouter/openai/gpt-6-luna", "Model id: openai/gpt-6-luna"))
        assertTrue(ModelPricingEvidence.sameModelId("openai/gpt-6-luna", "gpt-6-luna"))
        assertTrue(ModelPricingEvidence.sameModelId("gpt-6-luna", "openai/gpt-6-luna"))
        assertFalse(ModelPricingEvidence.sameModelId("openai/gpt-6-luna", "openai/gpt-5.6-luna"))
    }

    @Test
    fun `does not match a different version or model family`() {
        assertFalse(ModelPricingEvidence.matchesModel("gpt-6-luna", "GPT-5.6 Luna: $0.20 input, $1.20 output"))
        assertFalse(ModelPricingEvidence.matchesModel("gpt-6-luna", "GPT-6 Sol API pricing"))
        assertFalse(ModelPricingEvidence.matchesModel("gpt-6-luna", "GPT-6-Luna-preview API pricing"))
    }

    @Test
    fun `does not accept evidence mention from separate source`() {
        // Production checks the title, URL, and content of the selected source as one evidence record.
        assertFalse(ModelPricingEvidence.matchesModel("gpt-6-luna", "GPT-5.6 Luna pricing only"))
    }
}
