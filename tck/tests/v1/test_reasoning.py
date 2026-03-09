"""Silver Tier — Reasoning Memory Behavioral Tests.

These tests verify the behavioral contracts for reasoning memory,
including trace management, step recording, and tool call tracking.
"""

import pytest

from tck.adapters.base_adapter import (
    TCKReasoningStep,
    TCKReasoningTrace,
    TCKToolCall,
    ToolCallStatus,
)
from tck.fixtures.data import SESSION_A, SESSION_B, TRACE_TASK


@pytest.mark.silver
class TestStartTrace:
    """Tests for starting reasoning traces."""

    async def test_start_trace_basic(self, adapter):
        """SPEC-4.1.1: start_trace MUST return a TCKReasoningTrace with valid fields."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        assert isinstance(trace, TCKReasoningTrace)
        assert trace.id is not None
        assert trace.session_id == SESSION_A
        assert trace.task == TRACE_TASK
        assert trace.started_at is not None
        assert trace.completed_at is None
        assert trace.outcome is None
        assert trace.success is None

    async def test_start_trace_unique_ids(self, adapter):
        """SPEC-4.1.2: Each trace MUST have a unique ID."""
        trace1 = await adapter.start_trace(SESSION_A, "Task 1")
        trace2 = await adapter.start_trace(SESSION_A, "Task 2")
        assert trace1.id != trace2.id


@pytest.mark.silver
class TestAddStep:
    """Tests for adding reasoning steps."""

    async def test_add_step_basic(self, adapter):
        """SPEC-4.2.1: add_step MUST return a TCKReasoningStep linked to the trace."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(
            trace.id,
            thought="I need to search for information.",
            action="search",
            observation="Found relevant data.",
        )
        assert isinstance(step, TCKReasoningStep)
        assert step.id is not None
        assert step.trace_id == trace.id
        assert step.thought == "I need to search for information."
        assert step.action == "search"
        assert step.observation == "Found relevant data."

    async def test_add_step_sequential_numbering(self, adapter):
        """SPEC-4.2.2: Steps MUST have monotonically increasing step_number values."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step1 = await adapter.add_step(trace.id, thought="Step 1")
        step2 = await adapter.add_step(trace.id, thought="Step 2")
        step3 = await adapter.add_step(trace.id, thought="Step 3")

        assert step1.step_number < step2.step_number
        assert step2.step_number < step3.step_number

    async def test_add_step_partial_fields(self, adapter):
        """SPEC-4.2.3: add_step MUST accept partial fields (thought only, action only, etc.)."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)

        step_thought = await adapter.add_step(trace.id, thought="Just thinking")
        assert step_thought.thought == "Just thinking"
        assert step_thought.action is None

        step_action = await adapter.add_step(trace.id, action="Just acting")
        assert step_action.action == "Just acting"
        assert step_action.thought is None


@pytest.mark.silver
class TestRecordToolCall:
    """Tests for recording tool calls."""

    async def test_record_tool_call_basic(self, adapter):
        """SPEC-4.3.1: record_tool_call MUST return a TCKToolCall with valid fields."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="search_entities")
        tc = await adapter.record_tool_call(
            step.id,
            "search_entities",
            {"query": "Alice", "limit": 5},
        )
        assert isinstance(tc, TCKToolCall)
        assert tc.id is not None
        assert tc.tool_name == "search_entities"
        assert tc.arguments == {"query": "Alice", "limit": 5}
        assert tc.status == ToolCallStatus.SUCCESS

    async def test_record_tool_call_with_result(self, adapter):
        """SPEC-4.3.2: record_tool_call MUST store result and duration when provided."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="lookup")
        tc = await adapter.record_tool_call(
            step.id,
            "entity_lookup",
            {"name": "Alice"},
            result={"found": True, "entity": "Alice Johnson"},
            duration_ms=150,
        )
        assert tc.result is not None
        assert tc.duration_ms == 150

    async def test_record_tool_call_failure_status(self, adapter):
        """SPEC-4.3.3: record_tool_call MUST support failure statuses."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="failing_tool")

        tc_failure = await adapter.record_tool_call(
            step.id,
            "failing_tool",
            {},
            status=ToolCallStatus.FAILURE,
            error="Connection refused",
        )
        assert tc_failure.status == ToolCallStatus.FAILURE
        assert tc_failure.error == "Connection refused"

    async def test_record_tool_call_timeout_status(self, adapter):
        """SPEC-4.3.4: record_tool_call MUST support timeout status."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="slow_tool")
        tc = await adapter.record_tool_call(
            step.id,
            "slow_tool",
            {},
            status=ToolCallStatus.TIMEOUT,
            error="Request timed out after 30s",
        )
        assert tc.status == ToolCallStatus.TIMEOUT


@pytest.mark.silver
class TestCompleteTrace:
    """Tests for completing reasoning traces."""

    async def test_complete_trace_with_outcome(self, adapter):
        """SPEC-4.4.1: complete_trace MUST set outcome, success, and completed_at."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        await adapter.add_step(trace.id, thought="Analyzed the data")

        completed = await adapter.complete_trace(
            trace.id,
            outcome="Found Alice Johnson at Acme Corp",
            success=True,
        )
        assert completed.outcome == "Found Alice Johnson at Acme Corp"
        assert completed.success is True
        assert completed.completed_at is not None

    async def test_complete_trace_failure(self, adapter):
        """SPEC-4.4.2: complete_trace MUST support failure outcomes."""
        trace = await adapter.start_trace(SESSION_A, "Impossible task")
        completed = await adapter.complete_trace(
            trace.id,
            outcome="Could not complete the task",
            success=False,
        )
        assert completed.success is False


@pytest.mark.silver
class TestGetTraceWithSteps:
    """Tests for retrieving full traces."""

    async def test_get_trace_with_steps(self, adapter):
        """SPEC-4.5.1: get_trace_with_steps MUST return the full trace with all steps."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        await adapter.add_step(trace.id, thought="Step 1", action="search")
        await adapter.add_step(trace.id, thought="Step 2", action="analyze")
        await adapter.complete_trace(trace.id, outcome="Done", success=True)

        full_trace = await adapter.get_trace_with_steps(trace.id)
        assert full_trace is not None
        assert full_trace.id == trace.id
        assert len(full_trace.steps) == 2
        assert full_trace.outcome == "Done"

    async def test_get_trace_includes_tool_calls(self, adapter):
        """SPEC-4.5.2: get_trace_with_steps MUST include tool calls in each step."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="search")
        await adapter.record_tool_call(
            step.id, "search_entities", {"query": "Alice"}
        )

        full_trace = await adapter.get_trace_with_steps(trace.id)
        assert len(full_trace.steps) == 1
        assert len(full_trace.steps[0].tool_calls) == 1
        assert full_trace.steps[0].tool_calls[0].tool_name == "search_entities"

    async def test_get_trace_nonexistent(self, adapter):
        """SPEC-4.5.3: get_trace_with_steps for nonexistent ID MUST return None."""
        from uuid import uuid4

        result = await adapter.get_trace_with_steps(uuid4())
        assert result is None


@pytest.mark.silver
class TestListTraces:
    """Tests for listing reasoning traces."""

    async def test_list_traces_all(self, adapter):
        """SPEC-4.6.1: list_traces MUST return all traces."""
        await adapter.start_trace(SESSION_A, "Task 1")
        await adapter.start_trace(SESSION_A, "Task 2")
        await adapter.start_trace(SESSION_B, "Task 3")

        traces = await adapter.list_traces()
        assert len(traces) == 3

    async def test_list_traces_by_session(self, adapter):
        """SPEC-4.6.2: list_traces with session_id MUST filter by session."""
        await adapter.start_trace(SESSION_A, "Task A1")
        await adapter.start_trace(SESSION_A, "Task A2")
        await adapter.start_trace(SESSION_B, "Task B1")

        traces_a = await adapter.list_traces(session_id=SESSION_A)
        assert len(traces_a) == 2
        for t in traces_a:
            assert t.session_id == SESSION_A

    async def test_list_traces_respects_limit(self, adapter):
        """SPEC-4.6.3: list_traces MUST respect the limit parameter."""
        for i in range(5):
            await adapter.start_trace(SESSION_A, f"Task {i}")

        traces = await adapter.list_traces(limit=2)
        assert len(traces) <= 2


@pytest.mark.silver
class TestGetToolStats:
    """Tests for aggregated tool statistics."""

    async def test_get_tool_stats_after_calls(self, adapter):
        """SPEC-4.7.1: get_tool_stats MUST return accurate aggregated statistics."""
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, action="use tools")

        await adapter.record_tool_call(
            step.id, "search_entities", {"q": "a"},
            status=ToolCallStatus.SUCCESS, duration_ms=100,
        )
        await adapter.record_tool_call(
            step.id, "search_entities", {"q": "b"},
            status=ToolCallStatus.SUCCESS, duration_ms=200,
        )
        await adapter.record_tool_call(
            step.id, "search_entities", {"q": "c"},
            status=ToolCallStatus.FAILURE, error="not found",
        )

        stats = await adapter.get_tool_stats(tool_name="search_entities")
        assert len(stats) >= 1
        tool_stat = stats[0]
        assert tool_stat.name == "search_entities"
        assert tool_stat.total_calls == 3
        assert tool_stat.successful_calls == 2
        assert tool_stat.failed_calls == 1
