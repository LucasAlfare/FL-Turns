package com.lucasalfare.flturns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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