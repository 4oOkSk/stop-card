package io.github.user4ooksk.stopcard.policy

import org.junit.Assert.*
import org.junit.Test

class InputProvenanceTest {
    @Test fun `input binds the UID package and Activity to one destination`() {
        val input = InputProvenance()
        input.record(10001, "shop", "page", 100)
        assertFalse(input.claim(10002, "shop", "page", "pay", 101))
        assertFalse(input.claim(10001, "other", "page", "pay", 101))
        assertFalse(input.claim(10001, "shop", "other-page", "pay", 101))
        assertFalse(input.claim(10001, "shop", "page", "shop", 101))
        assertTrue(input.claim(10001, "shop", "page", "pay", 101))
        assertTrue("the same operation can bind its service", input.claim(10001, "shop", null, "pay", 102))
        assertFalse("another package cannot inherit the click", input.claim(10001, "shop", "page", "bank", 102))
    }

    @Test fun `input expires and a backwards clock cannot authorize a call`() {
        val input = InputProvenance()
        input.record(10001, "shop", "page", 100)
        assertFalse(input.claim(10001, "shop", "page", "pay", 99))
        assertTrue(input.claim(10001, "shop", "page", "pay", 1100))
        assertFalse(input.claim(10001, "shop", "page", "pay", 1101))
        input.record(10001, "shop", "page", 1200)
        assertTrue(input.claim(10001, "shop", "page", "bank", 1201))
    }

    @Test fun `another page in a shared UID replaces the old input identity`() {
        val input = InputProvenance()
        input.record(10001, "shop", "page", 100)
        input.record(10001, "sibling", "other-page", 101)
        assertFalse(input.claim(10001, "shop", "page", "pay", 102))
        assertTrue(input.claim(10001, "sibling", "other-page", "pay", 102))
    }
}
