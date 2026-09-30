package com.lucasalfare.flturns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActorTests {

  @Test
  fun `actorId exposes raw value`() {
    assertEquals("A", ActorId("A").value)
  }

  @Test
  fun `actorIds with same value are equal`() {
    assertEquals(ActorId("A"), ActorId("A"))
    assertEquals(ActorId("A").hashCode(), ActorId("A").hashCode())
  }

  @Test
  fun `actorIds with different values are not equal`() {
    assertNotEquals(ActorId("A"), ActorId("B"))
  }

  @Test
  fun `turnActor holds its id`() {
    val id = ActorId("A")
    assertEquals(id, TurnActor(id).id)
  }

  @Test
  fun `actors with same id are equal`() {
    assertEquals(TurnActor(ActorId("A")), TurnActor(ActorId("A")))
    assertEquals(TurnActor(ActorId("A")).hashCode(), TurnActor(ActorId("A")).hashCode())
  }

  @Test
  fun `actors with different ids are not equal`() {
    assertNotEquals(TurnActor(ActorId("A")), TurnActor(ActorId("B")))
  }

  @Test
  fun `distinct actors A B C are identifiable`() {
    val a = TurnActor(ActorId("A"))
    val b = TurnActor(ActorId("B"))
    val c = TurnActor(ActorId("C"))
    assertNotEquals(a, b)
    assertNotEquals(b, c)
    assertNotEquals(a, c)
    assertEquals(setOf(a, b, c).size, 3)
  }

  @Test
  fun `actor works as map key`() {
    val a = TurnActor(ActorId("A"))
    val b = TurnActor(ActorId("B"))
    val map = mapOf(a to 1, b to 2)
    assertEquals(1, map[TurnActor(ActorId("A"))])
    assertEquals(2, map[TurnActor(ActorId("B"))])
  }

  @Test
  fun `actor toString includes id value`() {
    assertTrue(TurnActor(ActorId("A")).toString().contains("A"))
  }

  @Test
  fun `actor equality is symmetric and reflexive`() {
    val a = TurnActor(ActorId("A"))
    assertEquals(a, a)
    assertNotEquals(a, TurnActor(ActorId("B")))
    assertFalse(a.equals("not an actor"))
  }
}

class TurnExecutionTests {

  @Test
  fun `turnId exposes raw value`() {
    assertEquals(1L, TurnId(1L).value)
  }

  @Test
  fun `executionId exposes raw value`() {
    assertEquals(10L, ExecutionId(10L).value)
  }

  @Test
  fun `turn holds id and actor`() {
    val actor = TurnActor(ActorId("A"))
    val turn = Turn(TurnId(1L), actor)
    assertEquals(TurnId(1L), turn.id)
    assertEquals(actor, turn.actor)
  }

  @Test
  fun `root execution context identifies actor turn execution depth and no parent`() {
    val actor = TurnActor(ActorId("A"))
    val context = TurnContext(actor, TurnId(1L), ExecutionId(10L), 0)
    val execution = Execution(context)
    assertEquals(actor, execution.actor)
    assertEquals(TurnId(1L), execution.turnId)
    assertEquals(ExecutionId(10L), execution.id)
    assertEquals(0, execution.depth)
    assertNull(execution.parent)
    assertEquals(context, execution.context)
  }

  @Test
  fun `child execution context keeps parent and depth`() {
    val actor = TurnActor(ActorId("A"))
    val root = Execution(TurnContext(actor, TurnId(1L), ExecutionId(10L), 0))
    val child = Execution(TurnContext(actor, TurnId(1L), ExecutionId(11L), 1, root))
    assertEquals(root, child.parent)
    assertEquals(1, child.depth)
    assertEquals(ExecutionId(11L), child.id)
    assertEquals(actor, child.actor)
  }

  @Test
  fun `same execution id is equal`() {
    val actor = TurnActor(ActorId("A"))
    val a = Execution(TurnContext(actor, TurnId(1L), ExecutionId(10L), 0))
    val b = Execution(TurnContext(actor, TurnId(1L), ExecutionId(10L), 0))
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `different execution ids are not equal`() {
    val actor = TurnActor(ActorId("A"))
    val a = Execution(TurnContext(actor, TurnId(1L), ExecutionId(10L), 0))
    val b = Execution(TurnContext(actor, TurnId(1L), ExecutionId(11L), 0))
    assertNotEquals(a, b)
  }

  @Test
  fun `same turn id and actor are equal`() {
    val actor = TurnActor(ActorId("A"))
    assertEquals(Turn(TurnId(1L), actor), Turn(TurnId(1L), actor))
  }

  @Test
  fun `different turn ids are not equal`() {
    val actor = TurnActor(ActorId("A"))
    assertNotEquals(Turn(TurnId(1L), actor), Turn(TurnId(2L), actor))
  }

  @Test
  fun `same actor same turn but different executions are distinguishable`() {
    val actor = TurnActor(ActorId("A"))
    val first = Execution(TurnContext(actor, TurnId(1L), ExecutionId(10L), 0))
    val second = Execution(TurnContext(actor, TurnId(1L), ExecutionId(11L), 0))
    assertNotEquals(first, second)
    assertEquals(ExecutionId(10L), first.id)
    assertEquals(ExecutionId(11L), second.id)
  }
}