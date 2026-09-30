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

data class TurnContext(
  val actor: TurnActor,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null
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
  private val actors = actors.toList()
  private var index = 0
  private var nextTurnId = 0L

  init {
    require(this.actors.isNotEmpty()) { "TurnFlow requires at least one actor" }
  }

  fun next(): Turn {
    val actor = actors[index]
    index = (index + 1) % actors.size
    return Turn(TurnId(nextTurnId++), actor)
  }
}

class TurnEngine(
  private val flow: TurnFlow,
  private val handler: suspend (TurnContext) -> Any?
) {
  private var nextExecutionId = 0L

  suspend fun executeNextTurn(): Execution {
    val turn = flow.next()
    val execution = Execution(
      TurnContext(
        actor = turn.actor,
        turnId = turn.id,
        executionId = ExecutionId(nextExecutionId++),
        depth = 0,
        parent = null
      )
    )
    val raw = handler(execution.context)
    execution.result = raw as? ExecutionResult<*> ?: ExecutionResult(raw)
    return execution
  }
}

class TurnRuntime(private val engine: TurnEngine) {
  suspend fun run(isRunning: () -> Boolean) { while (isRunning()) engine.executeNextTurn() }
}