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

data class Turn(val id: TurnId, val actor: TurnActor)

data class TurnContext(
  val actor: TurnActor,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null
)

class Execution(val context: TurnContext) {
  val id: ExecutionId get() = context.executionId
  val actor: TurnActor get() = context.actor
  val turnId: TurnId get() = context.turnId
  val depth: Int get() = context.depth
  val parent: Execution? get() = context.parent

  override fun equals(other: Any?): Boolean = other is Execution && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "Execution(${id.value})"
}