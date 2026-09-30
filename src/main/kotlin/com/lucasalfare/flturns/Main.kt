package com.lucasalfare.flturns

@JvmInline
value class ActorId(val value: String)

class TurnActor(val id: ActorId) {
  override fun equals(other: Any?): Boolean = other is TurnActor && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "TurnActor(${id.value})"
}