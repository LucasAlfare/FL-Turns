package com.lucasalfare.flturns

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

class ExecutionScope internal constructor(private val engine: TurnEngine) {
  private var parent: Execution? = null
  internal fun attach(execution: Execution) {
    parent = execution
  }

  suspend fun execute(actor: TurnActor): ExecutionResult<*> {
    val current = requireNotNull(parent) { "ExecutionScope is not attached" }
    return engine.executeChild(current, actor)
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
  val id: ExecutionId get() = context.executionId
  val actor: TurnActor get() = context.actor
  val turnId: TurnId get() = context.turnId
  val depth: Int get() = context.depth
  val parent: Execution? get() = context.parent
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

class TurnEngine(
  private val flow: TurnFlow,
  private val handler: suspend (TurnContext) -> Any?
) {
  private var nextExecutionId = 0L

  suspend fun executeNextTurn(): Execution {
    val turn = flow.next()
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
    val raw = handler(execution.context)
    val result = raw as? ExecutionResult<*> ?: ExecutionResult(raw)
    execution.result = result
    (result.value as? FlowDecision)?.let { flow.apply(it) }
    return execution
  }

  fun isFlowEnded(): Boolean = flow.isEnded()

  internal suspend fun executeChild(parent: Execution, actor: TurnActor): ExecutionResult<*> {
    val scope = ExecutionScope(this)
    val execution = Execution(
      TurnContext(
        actor = actor,
        turnId = parent.turnId,
        executionId = ExecutionId(nextExecutionId++),
        depth = parent.depth + 1,
        parent = parent,
        scope = scope
      )
    )
    scope.attach(execution)
    val raw = handler(execution.context)
    val result = raw as? ExecutionResult<*> ?: ExecutionResult(raw)
    execution.result = result
    return result
  }
}

class TurnRuntime(private val engine: TurnEngine) {
  suspend fun run(isRunning: () -> Boolean) {
    while (isRunning()) engine.executeNextTurn()
  }
}