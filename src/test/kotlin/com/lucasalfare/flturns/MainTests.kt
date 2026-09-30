package com.lucasalfare.flturns

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

class TurnFlowTests {

  private fun actors(vararg names: String) = names.map { TurnActor(ActorId(it)) }

  @Test
  fun `first turn is the first actor`() {
    val flow = TurnFlow(actors("A", "B", "C"))
    assertEquals(actors("A")[0], flow.next().actor)
  }

  @Test
  fun `cycles through actors in order`() {
    val flow = TurnFlow(actors("A", "B", "C"))
    assertEquals(listOf("A", "B", "C"), List(3) { flow.next().actor.id.value })
  }

  @Test
  fun `wraps around after the last actor`() {
    val flow = TurnFlow(actors("A", "B", "C"))
    assertEquals(listOf("A", "B", "C", "A", "B", "C"), List(6) { flow.next().actor.id.value })
  }

  @Test
  fun `each turn receives a distinct incremental id`() {
    val flow = TurnFlow(actors("A", "B", "C"))
    assertEquals(listOf(0L, 1L, 2L, 3L, 4L), List(5) { flow.next().id.value })
  }

  @Test
  fun `single actor repeats itself`() {
    val flow = TurnFlow(actors("A"))
    assertEquals(listOf("A", "A", "A"), List(3) { flow.next().actor.id.value })
  }

  @Test
  fun `empty actor list is rejected`() {
    assertFailsWith<IllegalArgumentException> { TurnFlow(emptyList()) }
  }

  @Test
  fun `turn exposes the actor from the flow`() {
    val a = TurnActor(ActorId("A"))
    val b = TurnActor(ActorId("B"))
    val flow = TurnFlow(listOf(a, b))
    val t0 = flow.next()
    val t1 = flow.next()
    assertEquals(a, t0.actor)
    assertEquals(b, t1.actor)
    assertNotEquals(t0.id, t1.id)
  }

  @Test
  fun `flow keeps producing beyond one full cycle`() {
    val flow = TurnFlow(actors("A", "B", "C"))
    val seq = List(9) { flow.next().actor.id.value }
    assertEquals(listOf("A", "B", "C", "A", "B", "C", "A", "B", "C"), seq)
  }
}

class TurnEngineTests {

  private fun actors(vararg names: String) = names.map { TurnActor(ActorId(it)) }

  @Test
  fun `executes handler for the next turn`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    var seen: TurnContext? = null
    val engine = TurnEngine(flow) { seen = it }

    val execution = engine.executeNextTurn()

    assertEquals("A", seen?.actor?.id?.value)
    assertEquals(TurnId(0L), seen?.turnId)
    assertEquals(ExecutionId(0L), seen?.executionId)
    assertEquals(0, seen?.depth)
    assertNull(seen?.parent)
    assertEquals(ExecutionId(0L), execution.id)
    assertEquals("A", execution.actor.id.value)
  }

  @Test
  fun `returns to engine after handler completes`() = runBlocking {
    val flow = TurnFlow(actors("A"))
    var completed = false
    val engine = TurnEngine(flow) { completed = true }

    engine.executeNextTurn()

    assertTrue(completed)
  }

  @Test
  fun `creates a new root execution per turn with incremental ids`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    val contexts = mutableListOf<TurnContext>()
    val engine = TurnEngine(flow) { contexts.add(it) }

    val first = engine.executeNextTurn()
    val second = engine.executeNextTurn()
    val third = engine.executeNextTurn()

    assertEquals(listOf(ExecutionId(0L), ExecutionId(1L), ExecutionId(2L)), contexts.map { it.executionId })
    assertEquals(listOf(0, 0, 0), contexts.map { it.depth })
    assertTrue(contexts.all { it.parent == null })
    assertEquals(listOf("A", "B", "C"), contexts.map { it.actor.id.value })
    assertEquals(listOf(TurnId(0L), TurnId(1L), TurnId(2L)), contexts.map { it.turnId })
    assertEquals(ExecutionId(0L), first.id)
    assertEquals(ExecutionId(1L), second.id)
    assertEquals(ExecutionId(2L), third.id)
  }

  @Test
  fun `handler receives actor from flow sequence`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    val seen = mutableListOf<String>()
    val engine = TurnEngine(flow) { seen.add(it.actor.id.value) }

    repeat(6) { engine.executeNextTurn() }

    assertEquals(listOf("A", "B", "C", "A", "B", "C"), seen)
  }

  @Test
  fun `suspend handler is awaited before returning`() = runBlocking {
    val flow = TurnFlow(actors("A"))
    var value = 0
    val engine = TurnEngine(flow) { value = 1 }

    engine.executeNextTurn()

    assertEquals(1, value)
  }
}

class TurnRuntimeTests {

  private fun actors(vararg names: String) = names.map { TurnActor(ActorId(it)) }

  @Test
  fun `runtime executes turns while active`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    val seen = mutableListOf<String>()
    val engine = TurnEngine(flow) { seen.add(it.actor.id.value) }
    val runtime = TurnRuntime(engine)

    runtime.run { seen.size < 6 }

    assertEquals(listOf("A", "B", "C", "A", "B", "C"), seen)
  }

  @Test
  fun `runtime does not execute when inactive`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    val seen = mutableListOf<String>()
    val engine = TurnEngine(flow) { seen.add(it.actor.id.value) }
    val runtime = TurnRuntime(engine)

    runtime.run { false }

    assertTrue(seen.isEmpty())
  }

  @Test
  fun `runtime stops once active predicate becomes false`() = runBlocking {
    val flow = TurnFlow(actors("A", "B", "C"))
    val seen = mutableListOf<String>()
    val engine = TurnEngine(flow) { seen.add(it.actor.id.value) }
    val runtime = TurnRuntime(engine)

    runtime.run { seen.size < 4 }

    assertEquals(listOf("A", "B", "C", "A"), seen)
  }

  @Test
  fun `runtime delegates each iteration to engine`() = runBlocking {
    val flow = TurnFlow(actors("A"))
    val seen = mutableListOf<TurnId>()
    val engine = TurnEngine(flow) { seen.add(it.turnId) }
    val runtime = TurnRuntime(engine)

    runtime.run { seen.size < 3 }

    assertEquals(listOf(TurnId(0L), TurnId(1L), TurnId(2L)), seen)
  }
}