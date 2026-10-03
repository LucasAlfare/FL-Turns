package com.lucasalfare.flturns

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TurnsTest {

  // ---------------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------------

  @Test
  fun `execution starts in running state`() {
    val execution = newExecution()

    assertEquals(ExecutionState.RUNNING, execution.state)
    assertNull(execution.result)
    assertNull(execution.failure)
    assertTrue(execution.children.isEmpty())
    assertEquals(0, execution.depth)
    assertNull(execution.parent)
  }

  @Test
  fun `execution exposes context values`() {
    val execution = newExecution(
      id = 42,
      turnId = 7,
      actor = "player",
      depth = 3
    )

    assertEquals(ExecutionId(42), execution.id)
    assertEquals(TurnActor("player"), execution.turnActor)
    assertEquals(TurnId(7), execution.turnId)
    assertEquals(3, execution.depth)
    assertNull(execution.parent)
  }

  @Test
  fun `execution completes with result`() {
    val execution = newExecution()

    execution.complete("result")

    assertEquals(ExecutionState.COMPLETED, execution.state)
    assertEquals("result", execution.result)
    assertNull(execution.failure)
  }

  @Test
  fun `execution can complete with null result`() {
    val execution = newExecution()

    execution.complete(null)

    assertEquals(ExecutionState.COMPLETED, execution.state)
    assertNull(execution.result)
  }

  @Test
  fun `completed execution cannot be changed`() {
    val execution = newExecution()
    val failure = IllegalStateException("failure")

    execution.complete("result")
    execution.complete("other")
    execution.fail(failure)
    execution.cancel()
    execution.suspendForChild()

    assertEquals(ExecutionState.COMPLETED, execution.state)
    assertEquals("result", execution.result)
    assertNull(execution.failure)
  }

  @Test
  fun `execution fails with exception`() {
    val execution = newExecution()
    val failure = IllegalStateException("failure")

    execution.fail(failure)

    assertEquals(ExecutionState.FAILED, execution.state)
    assertSame(failure, execution.failure)
    assertNull(execution.result)
  }

  @Test
  fun `failed execution cannot be changed`() {
    val execution = newExecution()
    val failure = IllegalStateException("failure")

    execution.fail(failure)
    execution.complete("result")
    execution.fail(IllegalArgumentException())
    execution.cancel()
    execution.suspendForChild()

    assertEquals(ExecutionState.FAILED, execution.state)
    assertSame(failure, execution.failure)
    assertNull(execution.result)
  }

  @Test
  fun `execution can be cancelled`() {
    val execution = newExecution()

    execution.cancel()

    assertEquals(ExecutionState.CANCELLED, execution.state)
    assertNull(execution.result)
    assertNull(execution.failure)
  }

  @Test
  fun `cancelled execution cannot be changed`() {
    val execution = newExecution()
    val failure = IllegalStateException("failure")

    execution.cancel()
    execution.complete("result")
    execution.fail(failure)
    execution.suspendForChild()

    assertEquals(ExecutionState.CANCELLED, execution.state)
    assertNull(execution.result)
    assertNull(execution.failure)
  }

  @Test
  fun `execution can be suspended and resumed`() {
    val execution = newExecution()

    execution.suspendForChild()

    assertEquals(ExecutionState.SUSPENDED, execution.state)

    execution.resumeAfterChild()

    assertEquals(ExecutionState.RUNNING, execution.state)
  }

  @Test
  fun `suspend only affects running execution`() {
    val completed = newExecution()
    completed.complete("result")
    completed.suspendForChild()

    val failed = newExecution()
    failed.fail(IllegalStateException())
    failed.suspendForChild()

    val cancelled = newExecution()
    cancelled.cancel()
    cancelled.suspendForChild()

    assertEquals(ExecutionState.COMPLETED, completed.state)
    assertEquals(ExecutionState.FAILED, failed.state)
    assertEquals(ExecutionState.CANCELLED, cancelled.state)
  }

  @Test
  fun `resume only affects suspended execution`() {
    val running = newExecution()
    running.resumeAfterChild()

    val failed = newExecution()
    failed.fail(IllegalStateException())
    failed.resumeAfterChild()

    val cancelled = newExecution()
    cancelled.cancel()
    cancelled.resumeAfterChild()

    assertEquals(ExecutionState.RUNNING, running.state)
    assertEquals(ExecutionState.FAILED, failed.state)
    assertEquals(ExecutionState.CANCELLED, cancelled.state)
  }

  @Test
  fun `attach child adds child`() {
    val parent = newExecution()
    val child = newExecution(
      id = 1,
      depth = 1,
      parent = parent
    )

    parent.attachChild(child)

    assertEquals(listOf(child), parent.children)
  }

  @Test
  fun `detach child removes child`() {
    val parent = newExecution()
    val child1 = newExecution(id = 1, depth = 1, parent = parent)
    val child2 = newExecution(id = 2, depth = 1, parent = parent)

    parent.attachChild(child1)
    parent.attachChild(child2)
    parent.detachChild(child1)

    assertEquals(listOf(child2), parent.children)
  }

  @Test
  fun `detaching unknown child has no effect`() {
    val parent = newExecution()
    val child = newExecution(id = 1, depth = 1, parent = parent)

    parent.detachChild(child)

    assertTrue(parent.children.isEmpty())
  }

  @Test
  fun `children preserve insertion order`() {
    val parent = newExecution()
    val child1 = newExecution(id = 1, depth = 1, parent = parent)
    val child2 = newExecution(id = 2, depth = 1, parent = parent)
    val child3 = newExecution(id = 3, depth = 1, parent = parent)

    parent.attachChild(child1)
    parent.attachChild(child2)
    parent.attachChild(child3)

    assertEquals(
      listOf(child1, child2, child3),
      parent.children
    )
  }

  @Test
  fun `cancelling execution cancels all descendants`() {
    val root = newExecution()
    val child = newExecution(
      id = 1,
      depth = 1,
      parent = root
    )
    val grandchild = newExecution(
      id = 2,
      depth = 2,
      parent = child
    )

    root.attachChild(child)
    child.attachChild(grandchild)

    root.cancel()

    assertEquals(ExecutionState.CANCELLED, root.state)
    assertEquals(ExecutionState.CANCELLED, child.state)
    assertEquals(ExecutionState.CANCELLED, grandchild.state)
  }

  @Test
  fun `failing execution fails all descendants`() {
    val root = newExecution()
    val child = newExecution(
      id = 1,
      depth = 1,
      parent = root
    )
    val grandchild = newExecution(
      id = 2,
      depth = 2,
      parent = child
    )
    val failure = IllegalStateException("boom")

    root.attachChild(child)
    child.attachChild(grandchild)

    root.fail(failure)

    assertEquals(ExecutionState.FAILED, root.state)
    assertEquals(ExecutionState.FAILED, child.state)
    assertEquals(ExecutionState.FAILED, grandchild.state)

    assertSame(failure, root.failure)
    assertSame(failure, child.failure)
    assertSame(failure, grandchild.failure)
  }

  @Test
  fun `execution equality depends only on execution id`() {
    val first = newExecution(
      id = 10,
      actor = "A",
      depth = 0
    )
    val second = newExecution(
      id = 10,
      actor = "B",
      depth = 3
    )
    val third = newExecution(
      id = 11,
      actor = "A",
      depth = 0
    )

    assertEquals(first, second)
    assertFalse(first == third)
    assertEquals(first.hashCode(), second.hashCode())
  }

  @Test
  fun `execution toString contains execution id`() {
    val execution = newExecution(id = 123)

    assertEquals("Execution(123)", execution.toString())
  }

  // ---------------------------------------------------------------------------
  // ExecutionScope
  // ---------------------------------------------------------------------------

  @Test
  fun `execution scope cannot execute before being attached`(): Unit = runBlocking {
    val scope = ExecutionScope(testEngine())

    assertFailsWith<IllegalArgumentException> {
      scope.execute(TurnActor("B"))
    }
  }

  @Test
  fun `execution scope can only be attached once`() {
    val execution = newExecution()

    assertFailsWith<IllegalStateException> {
      execution.scope.attach(execution)
    }
  }

  // ---------------------------------------------------------------------------
  // RoundRobinTurnFlow
  // ---------------------------------------------------------------------------

  @Test
  fun `round robin rejects empty actor list`() {
    assertFailsWith<IllegalArgumentException> {
      RoundRobinTurnFlow(emptyList())
    }
  }

  @Test
  fun `round robin generates actors in order`() {
    val flow = roundRobin("A", "B", "C")

    assertEquals(TurnActor("A"), flow.next().turnActor)
    assertEquals(TurnActor("B"), flow.next().turnActor)
    assertEquals(TurnActor("C"), flow.next().turnActor)
    assertEquals(TurnActor("A"), flow.next().turnActor)
    assertEquals(TurnActor("B"), flow.next().turnActor)
    assertEquals(TurnActor("C"), flow.next().turnActor)
  }

  @Test
  fun `round robin generates unique turn ids`() {
    val flow = roundRobin("A", "B")

    val turns = listOf(
      flow.next(),
      flow.next(),
      flow.next(),
      flow.next()
    )

    assertEquals(
      turns.size,
      turns.map(Turn::id).toSet().size
    )
  }

  @Test
  fun `round robin skips ineligible actors`() {
    val flow = roundRobin("A", "B", "C")

    val turn = flow.next {
      it.turnActor != TurnActor("A")
    }

    assertEquals(TurnActor("B"), turn.turnActor)
  }

  @Test
  fun `round robin checks at most one complete pass`() {
    val flow = roundRobin("A", "B", "C")
    var attempts = 0

    assertFailsWith<NoExecutableTurnException> {
      flow.next {
        attempts++
        false
      }
    }

    assertEquals(3, attempts)
  }

  @Test
  fun `round robin throws when no actor is executable`() {
    val flow = roundRobin("A", "B", "C")

    val exception = assertFailsWith<NoExecutableTurnException> {
      flow.next { false }
    }

    assertTrue(
      exception.message!!.contains("3")
    )
  }

  @Test
  fun `round robin preserves progression after skipped actors`() {
    val flow = roundRobin("A", "B", "C")

    val first = flow.next {
      it.turnActor != TurnActor("A")
    }
    val second = flow.next()
    val third = flow.next {
      it.turnActor != TurnActor("A")
    }

    assertEquals(TurnActor("B"), first.turnActor)
    assertEquals(TurnActor("C"), second.turnActor)
    assertEquals(TurnActor("B"), third.turnActor)
  }

  @Test
  fun `continue keeps normal progression`() {
    val flow = roundRobin("A", "B", "C")

    assertEquals(TurnActor("A"), flow.next().turnActor)

    flow.apply(FlowDecision.Continue)

    assertEquals(TurnActor("B"), flow.next().turnActor)
  }

  @Test
  fun `repeat repeats current actor`() {
    val flow = roundRobin("A", "B", "C")

    assertEquals(TurnActor("A"), flow.next().turnActor)

    flow.apply(FlowDecision.Repeat)

    assertEquals(TurnActor("A"), flow.next().turnActor)
    assertEquals(TurnActor("B"), flow.next().turnActor)
  }

  @Test
  fun `repeat can be applied multiple times`() {
    val flow = roundRobin("A", "B")

    flow.next()

    flow.apply(FlowDecision.Repeat)
    assertEquals(TurnActor("A"), flow.next().turnActor)

    flow.apply(FlowDecision.Repeat)
    assertEquals(TurnActor("A"), flow.next().turnActor)

    assertEquals(TurnActor("B"), flow.next().turnActor)
  }

  @Test
  fun `insert adds actor at current position`() {
    val flow = roundRobin("A", "B", "C")

    flow.next()
    flow.apply(FlowDecision.Insert(TurnActor("X")))

    assertEquals(TurnActor("X"), flow.next().turnActor)
    assertEquals(TurnActor("B"), flow.next().turnActor)
    assertEquals(TurnActor("C"), flow.next().turnActor)
    assertEquals(TurnActor("A"), flow.next().turnActor)
  }

  @Test
  fun `inserted actor becomes part of subsequent flow`() {
    val flow = roundRobin("A", "B")

    flow.next()
    flow.apply(FlowDecision.Insert(TurnActor("X")))

    assertEquals(TurnActor("X"), flow.next().turnActor)
    assertEquals(TurnActor("B"), flow.next().turnActor)
    assertEquals(TurnActor("A"), flow.next().turnActor)
    assertEquals(TurnActor("X"), flow.next().turnActor)
  }

  @Test
  fun `skip advances past next actor`() {
    val flow = roundRobin("A", "B", "C")

    flow.next()
    flow.apply(FlowDecision.Skip)

    assertEquals(TurnActor("C"), flow.next().turnActor)
  }

  @Test
  fun `skip wraps around the flow`() {
    val flow = roundRobin("A", "B", "C")

    flow.next()
    flow.next()
    flow.next()

    flow.apply(FlowDecision.Skip)

    assertEquals(TurnActor("B"), flow.next().turnActor)
  }

  @Test
  fun `jump to moves flow to target actor`() {
    val flow = roundRobin("A", "B", "C")

    flow.next()
    flow.apply(FlowDecision.JumpTo(TurnActor("C")))

    assertEquals(TurnActor("C"), flow.next().turnActor)
    assertEquals(TurnActor("A"), flow.next().turnActor)
  }

  @Test
  fun `jump to rejects unknown actor`() {
    val flow = roundRobin("A", "B", "C")

    assertFailsWith<IllegalArgumentException> {
      flow.apply(FlowDecision.JumpTo(TurnActor("X")))
    }
  }

  @Test
  fun `end marks flow as ended`() {
    val flow = roundRobin("A", "B")

    assertFalse(flow.isEnded())

    flow.apply(FlowDecision.End)

    assertTrue(flow.isEnded())
  }

  @Test
  fun `ended flow cannot generate another turn`() {
    val flow = roundRobin("A", "B")

    flow.apply(FlowDecision.End)

    assertFailsWith<IllegalStateException> {
      flow.next()
    }
  }

  @Test
  fun `end is idempotent`() {
    val flow = roundRobin("A", "B")

    flow.apply(FlowDecision.End)
    flow.apply(FlowDecision.End)

    assertTrue(flow.isEnded())
  }

  // ---------------------------------------------------------------------------
  // TurnsEngine
  // ---------------------------------------------------------------------------

  @Test
  fun `engine validates maximum execution depth`() {
    assertFailsWith<IllegalArgumentException> {
      testEngine(maximumExecutionDepth = -1)
    }
  }

  @Test
  fun `engine snapshot is empty before execution`() {
    val engine = testEngine()

    val snapshot = engine.snapshot()

    assertNull(snapshot.currentTurn)
    assertNull(snapshot.currentTurnActor)
    assertNull(snapshot.currentExecutionId)
    assertEquals(0, snapshot.depth)
    assertTrue(snapshot.activeExecutions.isEmpty())
    assertFalse(snapshot.flowEnded)
  }

  @Test
  fun `engine executes next turn`() = runBlocking {
    var receivedContext: TurnContext? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        receivedContext = context
        "result"
      }
    )

    val execution = engine.executeNextTurn()

    assertEquals(ExecutionState.COMPLETED, execution.state)
    assertEquals("result", execution.result)

    assertNotNull(receivedContext)
    assertEquals(TurnActor("A"), receivedContext.turnActor)
    assertEquals(TurnId(0), receivedContext.turnId)
    assertEquals(ExecutionId(0), receivedContext.executionId)
    assertEquals(0, receivedContext.depth)
    assertNull(receivedContext.parent)
  }

  @Test
  fun `engine accepts null handler result`() = runBlocking {
    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { null }
    )

    val execution = engine.executeNextTurn()

    assertEquals(ExecutionState.COMPLETED, execution.state)
    assertNull(execution.result)
  }

  @Test
  fun `engine does not apply non flow result`() = runBlocking {
    val flow = RecordingTurnFlow()
    val engine = TurnsEngine(
      flow = flow,
      handler = { "result" }
    )

    engine.executeNextTurn()

    assertNull(flow.appliedDecision)
  }

  @Test
  fun `engine applies every flow decision returned by root execution`() = runBlocking {
    val decisions = listOf(
      FlowDecision.Continue,
      FlowDecision.Repeat,
      FlowDecision.Insert(TurnActor("X")),
      FlowDecision.Skip,
      FlowDecision.JumpTo(TurnActor("A")),
      FlowDecision.End
    )

    decisions.forEach { decision ->
      val flow = RecordingTurnFlow()
      val engine = TurnsEngine(
        flow = flow,
        handler = { decision }
      )

      engine.executeNextTurn()

      assertEquals(decision, flow.appliedDecision)
    }
  }

  @Test
  fun `engine exposes flow ended status`() {
    val flow = roundRobin("A")
    val engine = testEngine(flow = flow)

    assertFalse(engine.isFlowEnded())

    flow.apply(FlowDecision.End)

    assertTrue(engine.isFlowEnded())
  }

  @Test
  fun `engine emits events in order for successful execution`() = runBlocking {
    val events = mutableListOf<TurnEvent>()

    val engine = testEngine(
      events = TurnEventSink { event ->
        events += event
      }
    )

    val execution = engine.executeNextTurn()

    assertEquals(3, events.size)

    val startedTurn = assertIs<TurnEvent.TurnStarted>(events[0])
    assertEquals(execution.turnId, startedTurn.turn.id)
    assertEquals(execution.turnActor, startedTurn.turn.turnActor)

    val startedExecution = assertIs<TurnEvent.ExecutionStarted>(events[1])
    assertEquals(execution.id, startedExecution.executionId)
    assertEquals(execution.turnId, startedExecution.turnId)
    assertEquals(execution.turnActor, startedExecution.turnActor)
    assertEquals(0, startedExecution.depth)
    assertNull(startedExecution.parentId)

    val completed = assertIs<TurnEvent.ExecutionCompleted>(events[2])
    assertEquals(execution.id, completed.executionId)
    assertEquals(execution.turnId, completed.turnId)
  }

  @Test
  fun `engine emits flow decision event`() = runBlocking {
    val events = mutableListOf<TurnEvent>()
    val flow = roundRobin("A")

    val engine = TurnsEngine(
      flow = flow,
      handler = { FlowDecision.Repeat },
      events = TurnEventSink { event ->
        events += event
      }
    )

    val execution = engine.executeNextTurn()

    assertEquals(4, events.size)
    assertIs<TurnEvent.TurnStarted>(events[0])
    assertIs<TurnEvent.ExecutionStarted>(events[1])
    assertIs<TurnEvent.ExecutionCompleted>(events[2])

    val decisionEvent = assertIs<TurnEvent.FlowDecisionApplied>(events[3])
    assertEquals(execution.turnId, decisionEvent.turnId)
    assertEquals(FlowDecision.Repeat, decisionEvent.decision)
  }

  @Test
  fun `engine emits turn flow ended event`() = runBlocking {
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { FlowDecision.End },
      events = TurnEventSink { event ->
        events += event
      }
    )

    val execution = engine.executeNextTurn()

    assertEquals(5, events.size)
    assertIs<TurnEvent.TurnStarted>(events[0])
    assertIs<TurnEvent.ExecutionStarted>(events[1])
    assertIs<TurnEvent.ExecutionCompleted>(events[2])
    assertIs<TurnEvent.FlowDecisionApplied>(events[3])

    val ended = assertIs<TurnEvent.TurnFlowEnded>(events[4])
    assertEquals(execution.turnId, ended.turnId)
  }

  @Test
  fun `engine fails execution when handler throws`() = runBlocking {
    val failure = IllegalStateException("boom")
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { throw failure },
      events = TurnEventSink { event ->
        events += event
      }
    )

    val thrown = assertFailsWith<IllegalStateException> {
      engine.executeNextTurn()
    }

    assertSame(failure, thrown)

    val snapshot = engine.snapshot()
    assertTrue(snapshot.activeExecutions.isEmpty())

    assertEquals(3, events.size)
    assertIs<TurnEvent.TurnStarted>(events[0])
    assertIs<TurnEvent.ExecutionStarted>(events[1])

    val failed = assertIs<TurnEvent.ExecutionFailed>(events[2])
    assertSame(failure, failed.failure)
  }

  @Test
  fun `engine cancels execution when handler throws cancellation`() = runBlocking {
    val cancellation = CancellationException("cancelled")
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { throw cancellation },
      events = TurnEventSink { event ->
        events += event
      }
    )

    val thrown = assertFailsWith<CancellationException> {
      engine.executeNextTurn()
    }

    assertSame(cancellation, thrown)

    val snapshot = engine.snapshot()
    assertTrue(snapshot.activeExecutions.isEmpty())

    assertEquals(3, events.size)
    assertIs<TurnEvent.TurnStarted>(events[0])
    assertIs<TurnEvent.ExecutionStarted>(events[1])

    val cancelled = assertIs<TurnEvent.ExecutionCancelled>(events[2])
    assertEquals(ExecutionId(0), cancelled.executionId)
  }

  @Test
  fun `engine clears active executions after successful execution`() = runBlocking {
    val engine = testEngine()

    engine.executeNextTurn()

    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `engine clears active executions after failed execution`() = runBlocking {
    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { throw IllegalStateException("boom") }
    )

    assertFailsWith<IllegalStateException> {
      engine.executeNextTurn()
    }

    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `engine clears active executions after cancelled execution`() = runBlocking {
    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        throw CancellationException("cancelled")
      }
    )

    assertFailsWith<CancellationException> {
      engine.executeNextTurn()
    }

    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `engine snapshot reflects active root execution`() = runBlocking {
    lateinit var engine: TurnsEngine
    var rootSnapshot: TurnsSnapshot? = null

    engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        rootSnapshot = engine.snapshot()
        "root"
      }
    )

    val execution = engine.executeNextTurn()

    val snapshot = assertNotNull(rootSnapshot)

    assertEquals(execution.context.parent, null)
    assertEquals(execution.id, snapshot.currentExecutionId)
    assertEquals(execution.turnActor, snapshot.currentTurnActor)
    assertEquals(execution.turnId, snapshot.currentTurn!!.id)
    assertEquals(0, snapshot.depth)
    assertEquals(listOf(execution.id), snapshot.activeExecutions)
    assertFalse(snapshot.flowEnded)
  }

  @Test
  fun `engine creates nested execution`() = runBlocking {
    var child: Execution? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          child = context.scope.execute(TurnActor("B"))
          "root"
        } else {
          "child"
        }
      }
    )

    val root = engine.executeNextTurn()
    val nested = assertNotNull(child)

    assertEquals(ExecutionState.COMPLETED, root.state)
    assertEquals(ExecutionState.COMPLETED, nested.state)

    assertEquals(TurnActor("B"), nested.turnActor)
    assertEquals(root.turnId, nested.turnId)
    assertEquals(root.depth + 1, nested.depth)
    assertEquals(root, nested.parent)
    assertTrue(root.id != nested.id)
    assertEquals("child", nested.result)
  }

  @Test
  fun `parent is suspended while child executes`() = runBlocking {
    var childSnapshot: TurnsSnapshot? = null
    var parentStateWhileChildRuns: ExecutionState? = null
    var rootAfterChildSnapshot: TurnsSnapshot? = null

    lateinit var engine: TurnsEngine

    engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
          rootAfterChildSnapshot = engine.snapshot()
        } else {
          parentStateWhileChildRuns = context.parent?.state
          childSnapshot = engine.snapshot()
        }

        "result"
      }
    )

    val root = engine.executeNextTurn()

    assertEquals(ExecutionState.SUSPENDED, parentStateWhileChildRuns)

    val duringChild = assertNotNull(childSnapshot)
    assertEquals(1, duringChild.depth)
    assertEquals(TurnActor("B"), duringChild.currentTurnActor)
    assertNotNull(duringChild.currentExecutionId)
    assertEquals(2, duringChild.activeExecutions.size)

    val afterChild = assertNotNull(rootAfterChildSnapshot)
    assertEquals(ExecutionId(0), afterChild.currentExecutionId)
    assertEquals(TurnActor("A"), afterChild.currentTurnActor)
    assertEquals(0, afterChild.depth)
    assertEquals(listOf(root.id), afterChild.activeExecutions)

    assertEquals(ExecutionState.COMPLETED, root.state)
  }

  @Test
  fun `round robin handles combined flow decisions`() {
    val flow = roundRobin("A", "B", "C")

    assertEquals(TurnActor("A"), flow.next().turnActor)

    flow.apply(FlowDecision.Insert(TurnActor("X")))
    assertEquals(TurnActor("X"), flow.next().turnActor)

    flow.apply(FlowDecision.Repeat)
    assertEquals(TurnActor("X"), flow.next().turnActor)

    flow.apply(FlowDecision.Skip)
    assertEquals(TurnActor("C"), flow.next().turnActor)

    flow.apply(FlowDecision.JumpTo(TurnActor("C")))
    assertEquals(TurnActor("C"), flow.next().turnActor)
  }

  @Test
  fun `nested execution uses same turn id`() = runBlocking {
    var child: Execution? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          child = context.scope.execute(TurnActor("B"))
        }

        Unit
      }
    )

    val root = engine.executeNextTurn()
    val nested = assertNotNull(child)

    assertEquals(root.turnId, nested.turnId)
  }

  @Test
  fun `nested executions increase depth`() = runBlocking {
    val depths = mutableListOf<Int>()

    val engine: TurnsEngine = TurnsEngine(
      flow = roundRobin("A"),
      maximumExecutionDepth = 3,
      handler = { context ->
        depths += context.depth

        if (context.depth < 3) {
          context.scope.execute(TurnActor("N${context.depth + 1}"))
        }

        Unit
      }
    )

    val root = engine.executeNextTurn()

    assertEquals(
      listOf(0, 1, 2, 3),
      depths
    )

    assertEquals(ExecutionState.COMPLETED, root.state)
  }

  @Test
  fun `maximum depth zero allows root but rejects child`() = runBlocking {
    var failure: Throwable? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      maximumExecutionDepth = 0,
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        }

        Unit
      }
    )

    try {
      engine.executeNextTurn()
    } catch (t: Throwable) {
      failure = t
    }

    assertIs<MaximumExecutionDepthExceededException>(failure)

    val snapshot = engine.snapshot()

    assertTrue(snapshot.activeExecutions.isEmpty())
  }

  @Test
  fun `maximum depth one allows one child but rejects grandchild`() = runBlocking {
    val engine = TurnsEngine(
      flow = roundRobin("A"),
      maximumExecutionDepth = 1,
      handler = { context ->
        if (context.depth < 2) {
          context.scope.execute(TurnActor("N"))
        }

        Unit
      }
    )

    assertFailsWith<MaximumExecutionDepthExceededException> {
      engine.executeNextTurn()
    }

    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `maximum depth exception contains configured and attempted depth`() = runBlocking {
    val engine = TurnsEngine(
      flow = roundRobin("A"),
      maximumExecutionDepth = 0,
      handler = { context ->
        context.scope.execute(TurnActor("B"))
        Unit
      }
    )

    val exception = assertFailsWith<MaximumExecutionDepthExceededException> {
      engine.executeNextTurn()
    }

    assertTrue(exception.message!!.contains("maximum=0"))
    assertTrue(exception.message!!.contains("attempted=1"))
  }

  @Test
  fun `child failure propagates to parent`() = runBlocking {
    val failure = IllegalStateException("child failure")
    var rootExecution: Execution? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          try {
            context.scope.execute(TurnActor("B"))
          } finally {
            rootExecution = null
          }
        } else {
          throw failure
        }

        Unit
      }
    )

    val thrown = assertFailsWith<IllegalStateException> {
      try {
        rootExecution = engine.executeNextTurn()
      } catch (e: IllegalStateException) {
        throw e
      }
    }

    assertSame(failure, thrown)
    assertNull(rootExecution)
    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `child cancellation propagates to parent`() = runBlocking {
    val cancellation = CancellationException("child cancelled")

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        } else {
          throw cancellation
        }

        Unit
      }
    )

    val thrown = assertFailsWith<CancellationException> {
      engine.executeNextTurn()
    }

    assertSame(cancellation, thrown)
    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `child is detached after successful execution`() = runBlocking {
    var rootExecution: Execution?

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        }

        Unit
      }
    )

    rootExecution = engine.executeNextTurn()

    assertNotNull(rootExecution)
    assertTrue(rootExecution.children.isEmpty())
  }

  @Test
  fun `child is detached after failure`() = runBlocking {
    val failure = IllegalStateException("boom")

    var root: Execution? = null

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        } else {
          throw failure
        }

        Unit
      }
    )

    assertFailsWith<IllegalStateException> {
      root = engine.executeNextTurn()
    }

    assertNull(root)
    assertTrue(engine.snapshot().activeExecutions.isEmpty())
  }

  @Test
  fun `child execution emits parent id in started event`() = runBlocking {
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        }

        Unit
      },
      events = TurnEventSink { event ->
        events += event
      }
    )

    val root = engine.executeNextTurn()

    val childStarted = events
      .filterIsInstance<TurnEvent.ExecutionStarted>()
      .single { it.depth == 1 }

    assertEquals(root.id, childStarted.parentId)
    assertEquals(TurnActor("B"), childStarted.turnActor)
    assertEquals(root.turnId, childStarted.turnId)
  }

  @Test
  fun `nested execution events are emitted in lifecycle order`() = runBlocking {
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { context ->
        if (context.depth == 0) {
          context.scope.execute(TurnActor("B"))
        }

        context.depth
      },
      events = TurnEventSink { event ->
        events += event
      }
    )

    engine.executeNextTurn()

    assertEquals(5, events.size)

    assertIs<TurnEvent.TurnStarted>(events[0])

    val rootStarted = assertIs<TurnEvent.ExecutionStarted>(events[1])
    assertEquals(0, rootStarted.depth)

    val childStarted = assertIs<TurnEvent.ExecutionStarted>(events[2])
    assertEquals(1, childStarted.depth)

    val childCompleted = assertIs<TurnEvent.ExecutionCompleted>(events[3])
    assertEquals(childStarted.executionId, childCompleted.executionId)

    val rootCompleted = assertIs<TurnEvent.ExecutionCompleted>(events[4])
    assertEquals(rootStarted.executionId, rootCompleted.executionId)
  }

  @Test
  fun `engine applies decision after execution completes`() = runBlocking {
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = { FlowDecision.End },
      events = TurnEventSink { event ->
        events += event
      }
    )

    engine.executeNextTurn()

    val completedIndex = events.indexOfFirst {
      it is TurnEvent.ExecutionCompleted
    }
    val decisionIndex = events.indexOfFirst {
      it is TurnEvent.FlowDecisionApplied
    }

    assertTrue(completedIndex >= 0)
    assertTrue(decisionIndex > completedIndex)
  }

  @Test
  fun `engine eligibility predicate is passed to flow`() = runBlocking {
    var calls = 0

    val engine = TurnsEngine(
      flow = roundRobin("A", "B"),
      isEligible = {
        calls++
        it.turnActor == TurnActor("B")
      }
    )

    val execution = engine.executeNextTurn()

    assertEquals(TurnActor("B"), execution.turnActor)
    assertEquals(2, calls)
  }

  // ---------------------------------------------------------------------------
  // TurnsRuntime
  // ---------------------------------------------------------------------------

  @Test
  fun `runtime starts idle`() {
    val runtime = TurnsRuntime(testEngine())

    assertEquals(RuntimeState.IDLE, runtime.state)
  }

  @Test
  fun `runtime snapshot combines runtime and engine state`() {
    val runtime = TurnsRuntime(testEngine())

    val snapshot = runtime.snapshot()

    assertEquals(RuntimeState.IDLE, snapshot.state)
    assertNull(snapshot.engine.currentTurn)
    assertNull(snapshot.engine.currentExecutionId)
  }

  @Test
  fun `runtime starts and finishes when flow ends`() = runBlocking {
    val flow = roundRobin("A")
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = flow,
      handler = { FlowDecision.End }
    )

    val runtime = TurnsRuntime(
      engine = engine,
      events = { event -> events += event }
    )

    runtime.start()

    assertEquals(RuntimeState.FINISHED, runtime.state)
    assertTrue(engine.isFlowEnded())

    assertEquals(2, events.size)

    val started = assertIs<TurnEvent.RuntimeStateChanged>(events[0])
    assertEquals(RuntimeState.IDLE, started.from)
    assertEquals(RuntimeState.RUNNING, started.to)

    val finished = assertIs<TurnEvent.RuntimeStateChanged>(events[1])
    assertEquals(RuntimeState.RUNNING, finished.from)
    assertEquals(RuntimeState.FINISHED, finished.to)
  }

  @Test
  fun `runtime can finish without modifying engine flow when stopped`() = runBlocking {
    lateinit var runtime: TurnsRuntime
    var turns = 0

    val flow = roundRobin("A", "B")

    val engine = TurnsEngine(
      flow = flow,
      handler = {
        turns++
        runtime.stop()
        Unit
      }
    )

    runtime = TurnsRuntime(engine)

    runtime.start()

    assertEquals(RuntimeState.FINISHED, runtime.state)
    assertEquals(1, turns)
    assertFalse(engine.isFlowEnded())
  }

  @Test
  fun `runtime cannot be started twice`(): Unit = runBlocking {
    val flow = roundRobin("A")
    flow.apply(FlowDecision.End)

    val runtime = TurnsRuntime(testEngine(flow = flow))

    runtime.start()

    assertFailsWith<IllegalStateException> {
      runtime.start()
    }
  }

  @Test
  fun `pause has no effect while idle`() {
    val runtime = TurnsRuntime(testEngine())

    runtime.pause()

    assertEquals(RuntimeState.IDLE, runtime.state)
  }

  @Test
  fun `resume has no effect while idle`() {
    val runtime = TurnsRuntime(testEngine())

    runtime.resume()

    assertEquals(RuntimeState.IDLE, runtime.state)
  }

  @Test
  fun `stop has no effect while idle`() {
    val runtime = TurnsRuntime(testEngine())

    runtime.stop()

    assertEquals(RuntimeState.IDLE, runtime.state)
  }

  @Test
  fun `pause prevents next turn until resumed`() = runBlocking {
    lateinit var runtime: TurnsRuntime

    var executedTurns = 0
    val firstTurnPaused = CompletableDeferred<Unit>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        executedTurns++

        if (executedTurns == 1) {
          runtime.pause()
          firstTurnPaused.complete(Unit)
          Unit
        } else {
          FlowDecision.End
        }
      }
    )

    runtime = TurnsRuntime(engine)

    val job = launch {
      runtime.start()
    }

    firstTurnPaused.await()

    assertEquals(RuntimeState.PAUSED, runtime.state)

    repeat(3) {
      yield()
    }

    assertEquals(1, executedTurns)

    runtime.resume()

    job.join()

    assertEquals(2, executedTurns)
    assertEquals(RuntimeState.FINISHED, runtime.state)
  }

  @Test
  fun `pause and resume emit state change events`() = runBlocking {
    lateinit var runtime: TurnsRuntime

    val events = mutableListOf<TurnEvent>()
    var executedTurns = 0

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        executedTurns++

        if (executedTurns == 1) {
          runtime.pause()
          Unit
        } else {
          FlowDecision.End
        }
      }
    )

    runtime = TurnsRuntime(
      engine = engine,
      events = { event ->
        events += event
      }
    )

    val job = launch {
      runtime.start()
    }

    while (runtime.state != RuntimeState.PAUSED) {
      yield()
    }

    runtime.resume()
    job.join()

    val stateEvents = events.filterIsInstance<TurnEvent.RuntimeStateChanged>()

    assertEquals(4, stateEvents.size)

    assertEquals(
      RuntimeState.IDLE,
      stateEvents[0].from
    )
    assertEquals(
      RuntimeState.RUNNING,
      stateEvents[0].to
    )

    assertEquals(
      RuntimeState.RUNNING,
      stateEvents[1].from
    )
    assertEquals(
      RuntimeState.PAUSED,
      stateEvents[1].to
    )

    assertEquals(
      RuntimeState.PAUSED,
      stateEvents[2].from
    )
    assertEquals(
      RuntimeState.RUNNING,
      stateEvents[2].to
    )

    assertEquals(
      RuntimeState.RUNNING,
      stateEvents[3].from
    )
    assertEquals(
      RuntimeState.FINISHED,
      stateEvents[3].to
    )
  }

  @Test
  fun `repeated pause does not emit duplicate transition`() = runBlocking {
    lateinit var runtime: TurnsRuntime

    val events = mutableListOf<TurnEvent>()
    var firstTurn = true

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        if (firstTurn) {
          firstTurn = false

          runtime.pause()
          runtime.pause()

          Unit
        } else {
          FlowDecision.End
        }
      }
    )

    runtime = TurnsRuntime(
      engine
    ) { event ->
      events += event
    }

    val job = launch {
      runtime.start()
    }

    while (runtime.state != RuntimeState.PAUSED) {
      yield()
    }

    runtime.resume()

    job.join()

    val transitions =
      events.filterIsInstance<TurnEvent.RuntimeStateChanged>()

    assertEquals(
      listOf(
        TurnEvent.RuntimeStateChanged(
          RuntimeState.IDLE,
          RuntimeState.RUNNING
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.RUNNING,
          RuntimeState.PAUSED
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.PAUSED,
          RuntimeState.RUNNING
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.RUNNING,
          RuntimeState.FINISHED
        )
      ),
      transitions
    )
  }

  @Test
  fun `repeated resume does not emit duplicate transition`() = runBlocking {
    lateinit var runtime: TurnsRuntime

    var firstTurn = true
    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A"),
      handler = {
        if (firstTurn) {
          firstTurn = false
          runtime.pause()
          Unit
        } else {
          runtime.stop()
          Unit
        }
      }
    )

    runtime = TurnsRuntime(
      engine
    ) { event ->
      events += event
    }

    val job = launch {
      runtime.start()
    }

    while (runtime.state != RuntimeState.PAUSED) {
      yield()
    }

    runtime.resume()
    runtime.resume()

    job.join()

    val transitions =
      events.filterIsInstance<TurnEvent.RuntimeStateChanged>()

    assertEquals(
      listOf(
        TurnEvent.RuntimeStateChanged(
          RuntimeState.IDLE,
          RuntimeState.RUNNING
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.RUNNING,
          RuntimeState.PAUSED
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.PAUSED,
          RuntimeState.RUNNING
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.RUNNING,
          RuntimeState.FINISHED
        )
      ),
      transitions
    )
  }

  @Test
  fun `stop from running finishes runtime`() = runBlocking {
    lateinit var runtime: TurnsRuntime

    val events = mutableListOf<TurnEvent>()

    val engine = TurnsEngine(
      flow = roundRobin("A", "B"),
      handler = {
        runtime.stop()
        Unit
      }
    )

    runtime = TurnsRuntime(
      engine
    ) { event ->
      events += event
    }

    runtime.start()

    assertEquals(
      RuntimeState.FINISHED,
      runtime.state
    )

    val transitions =
      events.filterIsInstance<TurnEvent.RuntimeStateChanged>()

    assertEquals(
      listOf(
        TurnEvent.RuntimeStateChanged(
          RuntimeState.IDLE,
          RuntimeState.RUNNING
        ),
        TurnEvent.RuntimeStateChanged(
          RuntimeState.RUNNING,
          RuntimeState.FINISHED
        )
      ),
      transitions
    )
  }

  @Test
  fun `stop from finished is idempotent`() = runBlocking {
    val flow = roundRobin("A")
    flow.apply(FlowDecision.End)

    val events = mutableListOf<TurnEvent>()

    val runtime = TurnsRuntime(
      testEngine(flow = flow)
    ) { event ->
      events += event
    }

    runtime.start()
    runtime.stop()

    val transitions = events.filterIsInstance<TurnEvent.RuntimeStateChanged>()

    assertEquals(2, transitions.size)
    assertEquals(RuntimeState.FINISHED, runtime.state)
  }

  @Test
  fun `runtime finishes immediately when engine flow is already ended`() = runBlocking {
    val flow = roundRobin("A")
    flow.apply(FlowDecision.End)

    var executed = false

    val engine = TurnsEngine(
      flow = flow,
      handler = {
        executed = true
        Unit
      }
    )

    val runtime = TurnsRuntime(engine)

    runtime.start()

    assertEquals(RuntimeState.FINISHED, runtime.state)
    assertFalse(executed)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun roundRobin(vararg actors: String): RoundRobinTurnFlow =
    RoundRobinTurnFlow(
      actors.map(::TurnActor)
    )

  private fun testEngine(
    flow: TurnFlow = roundRobin("A"),
    handler: suspend (TurnContext) -> Any? = { },
    isEligible: (Turn) -> Boolean = { true },
    maximumExecutionDepth: Int = Int.MAX_VALUE,
    events: TurnEventSink = TurnEventSink {}
  ): TurnsEngine =
    TurnsEngine(
      handler = handler,
      flow = flow,
      isEligible = isEligible,
      maximumExecutionDepth = maximumExecutionDepth,
      events = events
    )

  private fun newExecution(
    id: Long = 0,
    turnId: Long = 0,
    actor: String = "A",
    depth: Int = 0,
    parent: Execution? = null
  ): Execution {
    val engine = testEngine()
    val scope = ExecutionScope(engine)

    val execution = Execution(
      TurnContext(
        turnActor = TurnActor(actor),
        turnId = TurnId(turnId),
        executionId = ExecutionId(id),
        depth = depth,
        parent = parent,
        scope = scope
      )
    )

    scope.attach(execution)

    return execution
  }

}

private class RecordingTurnFlow : TurnFlow {

  var appliedDecision: FlowDecision? = null

  override fun next(isEligible: (Turn) -> Boolean): Turn =
    Turn(
      id = TurnId(0),
      turnActor = TurnActor("A")
    )

  override fun isEnded(): Boolean = false

  override fun apply(decision: FlowDecision) {
    appliedDecision = decision
  }
}