package com.lucasalfare.flturns

class DummyGame {
  val a = TurnActor(ActorId("A"))
  val b = TurnActor(ActorId("B"))
  val c = TurnActor(ActorId("C"))
  val d = TurnActor(ActorId("D"))
  val log = mutableListOf<String>()
  val events = mutableListOf<TurnEvent>()
  val sink = TurnEventSink { events += it }

  fun engine(
    actors: List<TurnActor> = listOf(a, b, c),
    canExecute: (TurnActor, TurnContext) -> Boolean = { _, _ -> true },
    maxDepth: Int = Int.MAX_VALUE,
    handler: suspend (TurnContext) -> Any?
  ): TurnEngine = TurnEngine(TurnFlow(actors), canExecute, handler, maxDepth, sink)

  fun runtime(engine: TurnEngine): TurnRuntime = TurnRuntime(engine, sink)
}