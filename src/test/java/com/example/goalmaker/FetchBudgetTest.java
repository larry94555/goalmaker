package com.example.goalmaker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FetchBudgetTest {
    @Test
    void appliesOneHttpRequestBudgetAcrossTheWholeFetch() {
        FetchBudget budget = new FetchBudget(Duration.ofSeconds(5), 2);
        budget.consumeHttpRequest();
        budget.consumeHttpRequest();

        IllegalStateException error = assertThrows(IllegalStateException.class, budget::consumeHttpRequest);

        assertEquals("web fetch HTTP request budget exceeded", error.getMessage());
    }

    @Test
    void boundsIndividualTimeoutsByTheRemainingTotalTime() {
        FetchBudget budget = new FetchBudget(Duration.ofMillis(100), 2);
        long bounded = budget.boundedMillis(Duration.ofSeconds(10));

        org.junit.jupiter.api.Assertions.assertTrue(bounded > 0 && bounded <= 100);
    }
}
