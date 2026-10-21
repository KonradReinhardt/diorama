package dev.diorama;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TickBudgetTest {
	@Test void stopsAfterTimeBudgetButMakesProgressOnSlowTicks() {
		AtomicLong now = new AtomicLong(100);
		var budget = new TickBudget(24, 2_000_000, now::get);
		now.set(3_000_000);
		assertTrue(budget.available());
		budget.completed();
		assertFalse(budget.available());
	}
	@Test void fastJobsStillHaveACountLimit() {
		var budget = new TickBudget(2, 2_000_000, () -> 0);
		budget.completed(); assertTrue(budget.available());
		budget.completed(); assertFalse(budget.available());
	}
}
