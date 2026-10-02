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

  internal fun cancel() {
    if (state == ExecutionState.COMPLETED || state == ExecutionState.FAILED || state == ExecutionState.CANCELLED) return
    state = ExecutionState.CANCELLED
    children.forEach(Execution::cancel)
  }

  internal fun fail(t: Throwable) {
    if (state == ExecutionState.COMPLETED || state == ExecutionState.CANCELLED) return
    state = ExecutionState.FAILED
    failure = t
    children.forEach { it.fail(t) }
  }

  internal fun complete(result: Any?) {
    if (state == ExecutionState.CANCELLED || state == ExecutionState.FAILED) return
    this.result = result
    state = ExecutionState.COMPLETED
  }

  internal fun suspend() {
    if (state == ExecutionState.RUNNING) state = ExecutionState.SUSPENDED
  }

  internal fun resume() {
    if (state == ExecutionState.SUSPENDED) state = ExecutionState.RUNNING
  }

  override fun equals(other: Any?): Boolean = other is Execution && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "Execution(${id.value})"
}

class TurnsFlow(actors: List<ActorId>) {
  private val actors = actors.toMutableList()
  private var index = 0
  private var nextTurnId = 0L
  private var lastIndex = 0
  private var ended = false

  init {
    require(actors.isNotEmpty()) { "TurnFlow requires at least one actor" }
  }

  internal fun next(): Turn {
    check(!ended) { "TurnFlow has ended" }
    lastIndex = index
    val actor = actors[index]
    index = (index + 1) % actors.size
    return Turn(TurnId(nextTurnId++), actor)
  }

  internal fun isEnded(): Boolean = ended

  internal fun apply(decision: FlowDecision) {
    when (decision) {
      FlowDecision.Continue -> {}
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
  private val flow: TurnsFlow,
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
    val deepest = activeExecutions.maxByOrNull { it.depth }
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

    val scope = ExecutionScope(this)
    val execution = Execution(
      TurnContext(
        actor = turn.actor,
        turnId = turn.id,
        executionId = ExecutionId(nextExecutionId++),
        depth = 0,
        parent = null,
        scope = scope
      )
    )

    currentTurn = turn
    scope.attach(execution)
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
    if (parent.state == ExecutionState.CANCELLED) throw CancellationException("Parent cancelled")
    if (parent.state == ExecutionState.FAILED) throw parent.failure ?: IllegalStateException("Parent failed")

    val childDepth = parent.depth + 1
    if (childDepth > maximumExecutionDepth)
      throw MaximumExecutionDepthExceededException(maximumExecutionDepth, childDepth)

    val scope = ExecutionScope(this)
    val execution = Execution(
      TurnContext(
        actor = actor,
        turnId = parent.turnId,
        executionId = ExecutionId(nextExecutionId++),
        depth = childDepth,
        parent = parent,
        scope = scope
      )
    )

    parent.attachChild(execution)
    scope.attach(execution)
    parent.suspend()

    try {
      val result = runExecution(execution) { handler(execution.context) }
      if (parent.state == ExecutionState.CANCELLED) throw CancellationException("Parent cancelled")
      if (parent.state == ExecutionState.FAILED) throw parent.failure ?: IllegalStateException("Parent failed")
      parent.resume()
      return result
    } catch (e: CancellationException) {
      parent.cancel()
      throw e
    } catch (e: Throwable) {
      parent.fail(e)
      throw e
    } finally {
      parent.detachChild(execution)
      if (parent.state == ExecutionState.SUSPENDED) parent.resume()
    }
  }

  private suspend fun runExecution(execution: Execution, block: suspend () -> Any?): Any? {
    if (execution.state == ExecutionState.CANCELLED) throw CancellationException("Execution cancelled")
    if (execution.state == ExecutionState.FAILED) throw execution.failure ?: IllegalStateException("Execution failed")

    execution.state = ExecutionState.RUNNING
    activeExecutions += execution
    events.onEvent(
      TurnEvent.ExecutionStarted(
        execution.id,
        execution.actor,
        execution.depth,
        execution.parent?.id
      )
    )

    try {
      val raw = block()
      if (execution.state == ExecutionState.CANCELLED) throw CancellationException("Execution cancelled")
      if (execution.state == ExecutionState.FAILED) throw execution.failure ?: IllegalStateException("Execution failed")

      execution.complete(raw)
      events.onEvent(TurnEvent.ExecutionCompleted(execution.id, raw))
      return raw
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
}

enum class RuntimeState { IDLE, RUNNING, PAUSED, FINISHED }

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