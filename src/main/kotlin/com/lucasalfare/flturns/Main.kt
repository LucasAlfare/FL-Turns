package com.lucasalfare.flturns

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.yield

@JvmInline
value class ActorId(val value: String)

class TurnActor(val id: ActorId) {
  override fun equals(other: Any?): Boolean = other is TurnActor && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "TurnActor(${id.value})"
}

@JvmInline
value class TurnId(val value: Long)

@JvmInline
value class ExecutionId(val value: Long)

class ExecutionResult<out T>(val value: T)

data class Turn(val id: TurnId, val actor: TurnActor)

sealed class FlowDecision {
  object Continue : FlowDecision()
  object Repeat : FlowDecision()
  data class Insert(val actor: TurnActor) : FlowDecision()
  object Skip : FlowDecision()
  data class JumpTo(val actor: TurnActor) : FlowDecision()
  object End : FlowDecision()
}

enum class ExecutionState { RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED }

class ExecutionScope internal constructor(private val engine: TurnEngine) {
  private var parent: Execution? = null
  internal fun attach(execution: Execution) {
    parent = execution
  }

  suspend fun execute(actor: TurnActor): ExecutionResult<*> {
    val current = requireNotNull(parent) { "ExecutionScope is not attached" }
    return engine.executeChild(current, actor)
  }

  suspend fun executeAll(vararg actors: TurnActor): List<ExecutionResult<*>> {
    val results = mutableListOf<ExecutionResult<*>>()
    for (actor in actors) results += execute(actor)
    return results
  }

  fun cancel() {
    parent?.cancel()
    throw CancellationException("Execution cancelled")
  }

  fun fail(t: Throwable) {
    parent?.fail(t)
    throw t
  }
}

data class TurnContext(
  val actor: TurnActor,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null,
  val scope: ExecutionScope? = null
)

class Execution(val context: TurnContext) {
  var result: ExecutionResult<*>? = null
  var state: ExecutionState = ExecutionState.RUNNING
    internal set
  var failure: Throwable? = null
    internal set
  internal val children = mutableListOf<Execution>()
  val id: ExecutionId get() = context.executionId
  val actor: TurnActor get() = context.actor
  val turnId: TurnId get() = context.turnId
  val depth: Int get() = context.depth
  val parent: Execution? get() = context.parent

  internal fun attachChild(child: Execution) {
    children += child
  }

  internal fun detachChild(child: Execution) {
    children -= child
  }

  fun cancel() {
    if (state == ExecutionState.COMPLETED || state == ExecutionState.FAILED || state == ExecutionState.CANCELLED) return
    state = ExecutionState.CANCELLED
    children.forEach { it.cancel() }
  }

  internal fun fail(t: Throwable) {
    if (state == ExecutionState.COMPLETED || state == ExecutionState.CANCELLED) return
    state = ExecutionState.FAILED
    failure = t
    children.forEach { it.fail(t) }
  }

  internal fun complete(result: ExecutionResult<*>) {
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

class TurnFlow(actors: List<TurnActor>) {
  private val actors = actors.toMutableList()
  private var index = 0
  private var nextTurnId = 0L
  private var lastIndex = 0
  private var ended = false

  init {
    require(this.actors.isNotEmpty()) { "TurnFlow requires at least one actor" }
  }

  fun next(): Turn {
    check(!ended) { "TurnFlow has ended" }
    lastIndex = index
    val actor = actors[index]
    index = (index + 1) % actors.size
    return Turn(TurnId(nextTurnId++), actor)
  }

  fun isEnded(): Boolean = ended

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
  val maximumExecutionDepth: Int,
  val attemptedDepth: Int
) : IllegalStateException("Maximum execution depth exceeded: maximum=$maximumExecutionDepth attempted=$attemptedDepth")

class TurnEngine(
  private val flow: TurnFlow,
  private val canExecute: (TurnActor, TurnContext) -> Boolean = { _, _ -> true },
  private val handler: suspend (TurnContext) -> Any?,
  private val maximumExecutionDepth: Int = Int.MAX_VALUE
) {
  constructor(flow: TurnFlow, handler: suspend (TurnContext) -> Any?) : this(flow, { _, _ -> true }, handler)

  init {
    require(maximumExecutionDepth >= 0) { "maximumExecutionDepth must be non-negative" }
  }

  private var nextExecutionId = 0L

  private fun opportunityContext(turn: Turn): TurnContext = TurnContext(
    actor = turn.actor,
    turnId = turn.id,
    executionId = ExecutionId(nextExecutionId),
    depth = 0,
    parent = null,
    scope = null
  )

  suspend fun executeNextTurn(): Execution {
    var turn = flow.next()
    while (!canExecute(turn.actor, opportunityContext(turn))) turn = flow.next()
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
    scope.attach(execution)
    val result = runExecution(execution) { handler(execution.context) }
    (result.value as? FlowDecision)?.let { flow.apply(it) }
    return execution
  }

  fun isFlowEnded(): Boolean = flow.isEnded()

  internal suspend fun executeChild(parent: Execution, actor: TurnActor): ExecutionResult<*> {
    if (parent.state == ExecutionState.CANCELLED) throw CancellationException("Parent cancelled")
    if (parent.state == ExecutionState.FAILED) throw parent.failure ?: IllegalStateException("Parent failed")
    val childDepth = parent.depth + 1
    if (childDepth > maximumExecutionDepth) throw MaximumExecutionDepthExceededException(
      maximumExecutionDepth,
      childDepth
    )
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

  private suspend fun runExecution(execution: Execution, block: suspend () -> Any?): ExecutionResult<*> {
    if (execution.state == ExecutionState.CANCELLED) throw CancellationException("Execution cancelled")
    if (execution.state == ExecutionState.FAILED) throw execution.failure ?: IllegalStateException("Execution failed")
    execution.state = ExecutionState.RUNNING
    try {
      val raw = block()
      if (execution.state == ExecutionState.CANCELLED) throw CancellationException("Execution cancelled")
      if (execution.state == ExecutionState.FAILED) throw execution.failure ?: IllegalStateException("Execution failed")
      val result = raw as? ExecutionResult<*> ?: ExecutionResult(raw)
      execution.complete(result)
      return result
    } catch (e: CancellationException) {
      execution.cancel()
      throw e
    } catch (e: Throwable) {
      execution.fail(e)
      throw e
    }
  }
}

enum class RuntimeState { IDLE, RUNNING, PAUSED, FINISHED }

class TurnRuntime(private val engine: TurnEngine) {
  var state: RuntimeState = RuntimeState.IDLE
    private set

  suspend fun start() {
    check(state == RuntimeState.IDLE) { "TurnRuntime cannot start from $state" }
    state = RuntimeState.RUNNING
    while (state == RuntimeState.RUNNING || state == RuntimeState.PAUSED) {
      yield()
      if (state == RuntimeState.PAUSED) continue
      if (engine.isFlowEnded()) {
        state = RuntimeState.FINISHED; continue
      }
      engine.executeNextTurn()
    }
  }

  fun pause() {
    if (state == RuntimeState.RUNNING) state = RuntimeState.PAUSED
  }

  fun resume() {
    if (state == RuntimeState.PAUSED) state = RuntimeState.RUNNING
  }

  fun stop() {
    if (state == RuntimeState.RUNNING || state == RuntimeState.PAUSED) state = RuntimeState.FINISHED
  }
}