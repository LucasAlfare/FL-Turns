package com.lucasalfare.flturns

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.yield

@JvmInline
value class ActorId(val value: String)

@JvmInline
value class TurnId(val value: Long)

@JvmInline
value class ExecutionId(val value: Long)

data class Turn(val id: TurnId, val actor: ActorId)

sealed class FlowDecision {
  object Continue : FlowDecision()
  object Repeat : FlowDecision()
  data class Insert(val actor: ActorId) : FlowDecision()
  object Skip : FlowDecision()
  data class JumpTo(val actor: ActorId) : FlowDecision()
  object End : FlowDecision()
}

enum class ExecutionState { RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED }

class ExecutionScope internal constructor(private val engine: TurnsEngine) {
  private var parent: Execution? = null

  internal fun attach(execution: Execution) {
    check(parent == null) { "ExecutionScope is already attached" }
    parent = execution
  }

  suspend fun execute(actor: ActorId): Any? {
    val current = requireNotNull(parent) { "ExecutionScope is not attached" }
    return engine.executeChild(current, actor)
  }
}

data class TurnContext(
  val actor: ActorId,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null,
  val scope: ExecutionScope? = null
)

class Execution internal constructor(val context: TurnContext) {
  var result: Any? = null
    private set
  var state: ExecutionState = ExecutionState.RUNNING
    internal set
  var failure: Throwable? = null
    internal set
  internal val children = mutableListOf<Execution>()

  val id: ExecutionId get() = context.executionId
  val actor: ActorId get() = context.actor
  val turnId: TurnId get() = context.turnId
  val depth: Int get() = context.depth
  val parent: Execution? get() = context.parent

  internal fun attachChild(child: Execution) {
    children += child
  }

  internal fun detachChild(child: Execution) {
    children -= child
  }

  internal fun suspendForChild() {
    if (state == ExecutionState.RUNNING) state = ExecutionState.SUSPENDED
  }

  internal fun resumeAfterChild() {
    if (state == ExecutionState.SUSPENDED) state = ExecutionState.RUNNING
  }

  internal fun cancel() {
    if (isTerminal) return
    state = ExecutionState.CANCELLED
    children.toList().forEach(Execution::cancel)
  }

  internal fun fail(t: Throwable) {
    if (isTerminal) return
    state = ExecutionState.FAILED
    failure = t
    children.toList().forEach { it.fail(t) }
  }

  internal fun complete(value: Any?) {
    if (isTerminal) return
    result = value
    state = ExecutionState.COMPLETED
  }

  private val isTerminal: Boolean get() = state == ExecutionState.COMPLETED || state == ExecutionState.FAILED || state == ExecutionState.CANCELLED

  override fun equals(other: Any?): Boolean = other is Execution && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "Execution(${id.value})"
}

interface TurnFlow {
  fun next(): Turn
  fun isEnded(): Boolean
  fun apply(decision: FlowDecision)
}

class RoundRobinTurnFlow(actors: List<ActorId>) : TurnFlow {
  private val actors = actors.toMutableList()
  private var index = 0
  private var nextTurnId = 0L
  private var lastIndex = 0
  private var ended = false

  init {
    require(actors.isNotEmpty()) { "RoundRobinTurnFlow requires at least one actor" }
  }

  override fun next(): Turn {
    check(!ended) { "TurnFlow has ended" }
    lastIndex = index
    val actor = actors[index]
    index = (index + 1) % actors.size
    return Turn(TurnId(nextTurnId++), actor)
  }

  override fun isEnded(): Boolean = ended

  override fun apply(decision: FlowDecision) {
    when (decision) {
      FlowDecision.Continue -> Unit
      FlowDecision.Repeat -> index = lastIndex
      is FlowDecision.Insert -> actors.add(index, decision.actor)
      FlowDecision.Skip -> index = (index + 1) % actors.size
      is FlowDecision.JumpTo -> {
        val target = actors.indexOf(decision.actor)
        require(target >= 0) { "Actor not found in TurnFlow" }
        index = target
      }

      FlowDecision.End -> ended = true
    }
  }
}

class MaximumExecutionDepthExceededException(
  maximumExecutionDepth: Int,
  attemptedDepth: Int
) : IllegalStateException("Maximum execution depth exceeded: maximum=$maximumExecutionDepth attempted=$attemptedDepth")

sealed class TurnEvent {
  data class TurnStarted(val turn: Turn, val executionId: ExecutionId) : TurnEvent()

  data class ExecutionStarted(
    val executionId: ExecutionId,
    val actor: ActorId,
    val depth: Int,
    val parentId: ExecutionId?
  ) : TurnEvent()

  data class ExecutionCompleted(val executionId: ExecutionId, val result: Any?) : TurnEvent()
  data class ExecutionFailed(val executionId: ExecutionId, val failure: Throwable) : TurnEvent()
  data class ExecutionCancelled(val executionId: ExecutionId) : TurnEvent()
  data class FlowDecisionApplied(val decision: FlowDecision) : TurnEvent()
  data class FlowEnded(val turnId: TurnId) : TurnEvent()
  data class RuntimeStateChanged(val from: RuntimeState, val to: RuntimeState) : TurnEvent()
}

fun interface TurnEventSink {
  fun onEvent(event: TurnEvent)
}

data class TurnSnapshot(
  val currentTurn: Turn?,
  val currentActor: ActorId?,
  val currentExecutionId: ExecutionId?,
  val depth: Int,
  val pendingExecutions: List<ExecutionId>,
  val flowEnded: Boolean,
  val runtimeState: RuntimeState?
)

class TurnsEngine(
  private val flow: TurnFlow,
  private val canExecute: (ActorId, Turn) -> Boolean = { _, _ -> true },
  val handler: suspend (TurnContext) -> Any? = suspend {},
  private val maximumExecutionDepth: Int = Int.MAX_VALUE,
  private val events: TurnEventSink = TurnEventSink {}
) {
  init {
    require(maximumExecutionDepth >= 0) { "maximumExecutionDepth must be non-negative" }
  }

  private var nextExecutionId = 0L
  private var currentTurn: Turn? = null
  private val activeExecutions = linkedSetOf<Execution>()

  fun snapshot(): TurnSnapshot {
    val deepest = activeExecutions.maxByOrNull(Execution::depth)
    return TurnSnapshot(
      currentTurn = currentTurn,
      currentActor = deepest?.actor ?: currentTurn?.actor,
      currentExecutionId = deepest?.id,
      depth = deepest?.depth ?: 0,
      pendingExecutions = activeExecutions.map(Execution::id),
      flowEnded = flow.isEnded(),
      runtimeState = null
    )
  }

  suspend fun executeNextTurn(): Execution {
    var turn = flow.next()
    while (!canExecute(turn.actor, turn)) turn = flow.next()

    val execution = createExecution(actor = turn.actor, turnId = turn.id, depth = 0, parent = null)

    currentTurn = turn
    events.onEvent(TurnEvent.TurnStarted(turn, execution.id))

    val result = runExecution(execution) { handler(execution.context) }

    if (result is FlowDecision) {
      flow.apply(result)
      events.onEvent(TurnEvent.FlowDecisionApplied(result))
    }

    if (flow.isEnded()) events.onEvent(TurnEvent.FlowEnded(turn.id))
    return execution
  }

  fun isFlowEnded(): Boolean = flow.isEnded()

  internal suspend fun executeChild(parent: Execution, actor: ActorId): Any? {
    ensureCanCreateChild(parent)

    val execution = createExecution(actor = actor, turnId = parent.turnId, depth = parent.depth + 1, parent = parent)

    parent.attachChild(execution)
    parent.suspendForChild()

    try {
      val result = runExecution(execution) { handler(execution.context) }
      ensureParentCanResume(parent)
      parent.resumeAfterChild()
      return result
    } catch (e: CancellationException) {
      parent.cancel()
      throw e
    } catch (e: Throwable) {
      parent.fail(e)
      throw e
    } finally {
      parent.detachChild(execution)
      if (parent.state == ExecutionState.SUSPENDED) parent.resumeAfterChild()
    }
  }

  private fun createExecution(actor: ActorId, turnId: TurnId, depth: Int, parent: Execution?): Execution {
    val scope = ExecutionScope(this)

    val execution = Execution(
      TurnContext(
        actor = actor,
        turnId = turnId,
        executionId = ExecutionId(nextExecutionId++),
        depth = depth,
        parent = parent,
        scope = scope
      )
    )

    scope.attach(execution)
    return execution
  }

  private fun ensureCanCreateChild(parent: Execution) {
    if (parent.state == ExecutionState.CANCELLED) throw CancellationException("Parent cancelled")
    if (parent.state == ExecutionState.FAILED) throw parent.failure ?: IllegalStateException("Parent failed")
    val attemptedDepth = parent.depth + 1
    if (attemptedDepth > maximumExecutionDepth) throw MaximumExecutionDepthExceededException(
      maximumExecutionDepth,
      attemptedDepth
    )
  }

  private fun ensureParentCanResume(parent: Execution) {
    if (parent.state == ExecutionState.CANCELLED) throw CancellationException("Parent cancelled")
    if (parent.state == ExecutionState.FAILED) throw parent.failure ?: IllegalStateException("Parent failed")
  }

  private suspend fun runExecution(
    execution: Execution,
    block: suspend () -> Any?
  ): Any? {
    ensureExecutionCanRun(execution)
    execution.state = ExecutionState.RUNNING
    activeExecutions += execution
    events.onEvent(
      TurnEvent.ExecutionStarted(
        executionId = execution.id,
        actor = execution.actor,
        depth = execution.depth,
        parentId = execution.parent?.id
      )
    )

    try {
      val result = block()
      ensureExecutionCompletedNormally(execution)
      execution.complete(result)

      events.onEvent(
        TurnEvent.ExecutionCompleted(
          executionId = execution.id,
          result = result
        )
      )

      return result
    } catch (e: CancellationException) {
      execution.cancel()
      events.onEvent(TurnEvent.ExecutionCancelled(execution.id))
      throw e
    } catch (e: Throwable) {
      execution.fail(e)
      events.onEvent(TurnEvent.ExecutionFailed(execution.id, e))
      throw e
    } finally {
      activeExecutions -= execution
    }
  }

  private fun ensureExecutionCanRun(execution: Execution) {
    if (execution.state == ExecutionState.CANCELLED)
      throw CancellationException("Execution cancelled")

    if (execution.state == ExecutionState.FAILED)
      throw execution.failure ?: IllegalStateException("Execution failed")
  }

  private fun ensureExecutionCompletedNormally(execution: Execution) {
    if (execution.state == ExecutionState.CANCELLED)
      throw CancellationException("Execution cancelled")

    if (execution.state == ExecutionState.FAILED)
      throw execution.failure ?: IllegalStateException("Execution failed")
  }
}

enum class RuntimeState {
  IDLE,
  RUNNING,
  PAUSED,
  FINISHED
}

class TurnsRuntime(
  private val engine: TurnsEngine,
  private val events: TurnEventSink = TurnEventSink {}
) {
  var state: RuntimeState = RuntimeState.IDLE
    private set

  fun snapshot(): TurnSnapshot = engine.snapshot().copy(runtimeState = state)

  private fun transition(next: RuntimeState) {
    if (next == state) return
    val previous = state
    state = next
    events.onEvent(TurnEvent.RuntimeStateChanged(previous, next))
  }

  suspend fun start() {
    check(state == RuntimeState.IDLE) { "TurnRuntime cannot start from $state" }
    transition(RuntimeState.RUNNING)

    while (state == RuntimeState.RUNNING || state == RuntimeState.PAUSED) {
      yield()
      if (state == RuntimeState.PAUSED) continue

      if (engine.isFlowEnded()) {
        transition(RuntimeState.FINISHED)
        continue
      }

      engine.executeNextTurn()
    }
  }

  fun pause() {
    if (state == RuntimeState.RUNNING) transition(RuntimeState.PAUSED)
  }

  fun resume() {
    if (state == RuntimeState.PAUSED) transition(RuntimeState.RUNNING)
  }

  fun stop() {
    if (state == RuntimeState.RUNNING || state == RuntimeState.PAUSED) transition(RuntimeState.FINISHED)
  }
}