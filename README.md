<div align="center">

```
 ▄▄▄▄▄▄▄ ▄▄▄           ▄▄▄▄▄▄▄▄▄                      
███▀▀▀▀▀ ███           ▀▀▀███▀▀▀                      
███▄▄    ███              ███ ██ ██ ████▄ ████▄ ▄█▀▀▀ 
███▀▀    ███      ▀▀▀▀▀   ███ ██ ██ ██ ▀▀ ██ ██ ▀███▄ 
███      ████████         ███ ▀██▀█ ██    ██ ██ ▄▄▄█▀ 
```

> A Kotlin turn engine for building turn-based games and interactive systems.

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-blueviolet?logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![JitPack](<JITPACK_BADGE_URL>)](<JITPACK_PROJECT_URL>)

</div>

**The game gives meaning. FL-Turns provides structure.**

FL-Turns is a lightweight, game-agnostic turn engine written entirely in Kotlin. It provides the infrastructure required to coordinate turns, execute actions, model nested interactions, control turn progression, and observe runtime state — while leaving game rules and game state entirely to the application.

It is designed for games such as:

- Chess and checkers
- Hangman and guessing games
- Naval Battle
- Gladiatus-style combat
- One Piece TCG
- Yu-Gi-Oh! TCG
- Pokémon TCG
- Card and board games
- Strategy and tactical games
- Any application whose rules can be expressed through discrete turns and actions

FL-Turns does not know what an attack, card, piece, player, damage, spell, resource, victory condition, or animation means. Those concepts belong to your game.

---

## Why FL-Turns?

Turn-based games often share the same structural problems even when their rules are completely different:

- Who acts next?
- Can this actor act right now?
- What happens during a turn?
- Can an action trigger another action?
- Can another actor respond before the original action continues?
- Should the same actor receive another turn?
- Should someone be skipped or inserted into the turn order?
- How can the application observe what the engine is doing?
- How can the turn-processing loop be separated from rendering, input, networking, or other application loops?

FL-Turns separates these concerns into a small set of explicit abstractions.

```text
TurnFlow
    ↓
produces a Turn
    ↓
TurnsEngine
    ↓
creates an Execution
    ↓
handler executes the game action
    ↓
FlowDecision modifies future turn progression
```

Nested interactions are represented independently from normal turn progression:

```text
Turn
└── Root Execution
    ├── Child Execution
    │   └── Grandchild Execution
    └── Child Execution
```

The result is a reusable execution model that can support simple games and complex reaction chains without forcing game-specific rules into the engine.

---

## Features

- **Game-agnostic turn processing** — build chess, TCGs, board games, combat systems, puzzles, and more.
- **Custom turn flows** — provide your own `TurnFlow` implementation or use the included `RoundRobinTurnFlow`.
- **Dynamic turn control** — continue, repeat, insert, skip, jump to another actor, or end the flow.
- **Nested executions** — an execution can request another actor to perform a child execution.
- **Execution trees** — parent and child executions retain their relationship and lifecycle state.
- **Coroutine-native execution** — handlers are `suspend` functions and can naturally perform asynchronous work.
- **Eligibility filtering** — prevent actors from receiving executable turns without embedding game rules in the flow itself.
- **Lifecycle events** — observe turns, executions, flow decisions, and runtime state changes.
- **State snapshots** — inspect the engine and runtime without exposing internal mutable structures.
- **Engine/runtime separation** — process one turn at a time with `TurnsEngine`, or continuously with `TurnsRuntime`.
- **Small core** — the engine provides structure, not a game framework with opinions about your rules.

---

## Design Philosophy

FL-Turns follows one central principle:

> **The game provides meaning. The core provides structure.**

The library deliberately does **not** implement:

- game rules;
- combat systems;
- card systems;
- movement rules;
- scoring rules;
- victory or defeat conditions;
- player input;
- rendering or UI;
- animations;
- persistence;
- networking;
- AI behavior.

Instead, your application supplies those rules through the handler, turn-flow implementation, eligibility predicate, and surrounding game state.

This keeps the engine reusable across games with completely different mechanics.

---

## What FL-Turns Is Not

FL-Turns is not a full game engine.

It does not render sprites, manage scenes, play animations, read keyboard input, implement physics, synchronize multiplayer sessions, or provide game-specific state models.

It is deliberately narrower:

> **FL-Turns is an execution and turn-progression engine for applications whose rules are turn-based.**

That narrow scope is what allows the library to be reused across very different games.

---

## Installation

FL-Turns is written entirely in Kotlin and is intended to be consumed as a Kotlin/JVM library through Gradle.

### JitPack

Add JitPack to your repositories:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add FL-Turns as a dependency:

```kotlin
dependencies {
    implementation("com.github.<GITHUB_USERNAME>:<REPOSITORY_NAME>:<VERSION>")
}
```

### Local development

Until the first public release, you can consume FL-Turns directly from the repository as a local Gradle module or publish the library to your local Maven repository during development.

---

## Quick Start

The smallest useful FL-Turns setup consists of three things:

1. actors;
2. a `TurnFlow`;
3. a `TurnsEngine` with a handler.

### 1. Define the actors

```kotlin
val alice = TurnActor("alice")
val bob = TurnActor("bob")
val charlie = TurnActor("charlie")
```

`TurnActor` intentionally carries only an application-defined `String` identifier. FL-Turns does not assign any game-specific meaning to it.

### 2. Create a turn flow

```kotlin
val flow = RoundRobinTurnFlow(
    listOf(alice, bob, charlie)
)
```

The default sequence is:

```text
alice → bob → charlie → alice → bob → charlie → ...
```

### 3. Create the engine

```kotlin
val engine = TurnsEngine(
    flow = flow,
    handler = { context ->
        println("Turn for ${context.turnActor.id}")
        FlowDecision.Continue
    }
)
```

### 4. Execute turns

`TurnsEngine` processes one root turn at a time:

```kotlin
engine.executeNextTurn()
engine.executeNextTurn()
engine.executeNextTurn()
```

Each call returns the root `Execution` associated with that turn.

---

## Choosing Between `TurnsEngine` and `TurnsRuntime`

FL-Turns exposes two levels of control.

### `TurnsEngine`

Use `TurnsEngine` when your application wants explicit control over when the next turn is executed.

```kotlin
val execution = engine.executeNextTurn()

println(execution.turnActor.id)
println(execution.state)
println(execution.result)
```

This is useful when the surrounding application already has its own loop, scheduler, server tick, UI cycle, or other orchestration mechanism.

### `TurnsRuntime`

Use `TurnsRuntime` when you want FL-Turns to continuously process turns until the flow ends or the runtime is stopped.

```kotlin
val runtime = TurnsRuntime(engine)
runtime.start()
```

The runtime has its own lifecycle:

```text
IDLE → RUNNING → FINISHED
          ↓
        PAUSED
          ↓
        RUNNING
```

Pause and resume affect the runtime loop, not the underlying `TurnFlow`:

```kotlin
runtime.pause()
runtime.resume()
```

To permanently stop the runtime:

```kotlin
runtime.stop()
```

`TurnsEngine` is the execution layer. `TurnsRuntime` is the continuous lifecycle controller built on top of it.

---

## How a Turn Works

A turn in FL-Turns is an opportunity for an actor to act. It is not the action itself.

```text
Turn
 ├── TurnId
 └── TurnActor
```

The engine then creates a root execution for that turn:

```text
Turn
└── Execution
    ├── ExecutionId
    ├── ExecutionState
    └── TurnContext
```

The configured handler receives the `TurnContext` and determines what the application does during that execution.

For example, a game may interpret a turn as:

```text
Chess      → player chooses and moves a piece
TCG        → player performs an action during their phase
Battleship → player selects a coordinate
Hangman    → player guesses a letter
Gladiatus  → player performs a combat action
```

FL-Turns does not decide which interpretation is correct.

---

## Turn Flow

`TurnFlow` is responsible for deciding **which turn comes next**.

```kotlin
interface TurnFlow {
    fun next(
        isEligible: (Turn) -> Boolean = { true }
    ): Turn

    fun isEnded(): Boolean

    fun apply(decision: FlowDecision)
}
```

The engine never needs to understand the internal algorithm used by a flow.

This makes it possible to build flows based on:

- round-robin order;
- initiative values;
- priority queues;
- speed statistics;
- scripted sequences;
- dynamically generated turns;
- game-specific scheduling rules.

### Built-in round-robin flow

`RoundRobinTurnFlow` cycles through actors in the configured order.

```kotlin
val flow = RoundRobinTurnFlow(
    listOf(
        TurnActor("player-1"),
        TurnActor("player-2"),
        TurnActor("player-3")
    )
)
```

Normal progression:

```text
player-1 → player-2 → player-3 → player-1 → ...
```

---

## Flow Decisions

A handler may return a `FlowDecision` from the root execution to modify how the next turn is generated.

### Continue

```kotlin
FlowDecision.Continue
```

Keep the normal progression defined by the flow.

### Repeat

```kotlin
FlowDecision.Repeat
```

Give the same actor another turn.

### Insert

```kotlin
FlowDecision.Insert(TurnActor("new-actor"))
```

Insert another actor into the current flow.

### Skip

```kotlin
FlowDecision.Skip
```

Skip the next turn opportunity according to the flow's implementation.

### JumpTo

```kotlin
FlowDecision.JumpTo(TurnActor("player-2"))
```

Move the next turn directly to the requested actor.

### End

```kotlin
FlowDecision.End
```

End the turn flow.

A key point is that `FlowDecision` affects **future turn progression**. It is not a game-rule system by itself.

---

## Eligibility

A `TurnFlow` can receive an eligibility predicate when generating its next turn.

```kotlin
val engine = TurnsEngine(
    flow = flow,
    isEligible = { turn ->
        /* application-defined rule */
        true
    },
    handler = { context ->
        // execute the action
    }
)
```

This lets the application prevent a generated turn from executing without requiring the flow implementation to understand why the actor is temporarily unavailable.

For example, an application may use eligibility for situations such as:

```text
eliminated actor
stunned actor
inactive player
waiting participant
locked game phase
```

The rule itself remains application-owned.

If a flow checks its available opportunities and cannot find an eligible turn, it throws `NoExecutableTurnException`.

---

## Nested Executions

One of FL-Turns's core features is the ability for an execution to create another execution.

This is useful when an action triggers a response from another actor without advancing the normal turn flow.

A common pattern looks like:

```text
Root Turn: Player A
    │
    └── Root Execution: Player A acts
             │
             └── Child Execution: Player B responds
                      │
                      └── Child Execution: Player C reacts
```

The parent execution is suspended while the child is running.

### Creating a child execution

The handler receives an `ExecutionScope` through `TurnContext`:

```kotlin
handler = { context ->
    val response = context.scope.execute(TurnActor("player-b"))

    println("Response: ${response.result}")

    FlowDecision.Continue
}
```

The important distinction is:

```text
TurnFlow
    controls normal turn progression

ExecutionScope
    controls nested execution within the current turn
```

A child execution receives a new `ExecutionId` but retains the same `TurnId` as its parent.

```text
TurnId:       same
ExecutionId:  different
Depth:        +1
Parent:       previous execution
```

This allows response chains, triggered effects, reactions, and other nested interactions without treating each response as a new normal turn.

### Execution depth

Nested executions can be limited with `maximumExecutionDepth`:

```kotlin
val engine = TurnsEngine(
    flow = flow,
    maximumExecutionDepth = 8,
    handler = { context ->
        // game action
    }
)
```

Depth starts at `0` for the root execution. A limit of `0` therefore allows root executions but prevents child executions.

Exceeding the limit produces `MaximumExecutionDepthExceededException`.

---

## Execution Lifecycle

Every execution has a lifecycle state represented by `ExecutionState`:

```text
RUNNING
   │
   ├── SUSPENDED ──→ RUNNING
   │
   ├── COMPLETED
   ├── FAILED
   └── CANCELLED
```

### `RUNNING`

The execution is currently being processed.

### `SUSPENDED`

The execution is waiting for a child execution.

### `COMPLETED`

The handler returned successfully.

### `FAILED`

The handler terminated with an exception.

### `CANCELLED`

The execution was canceled.

The application can inspect these states, while lifecycle mutation remains controlled by the engine.

---

## Execution Results

A handler may return any value:

```kotlin
handler = { context ->
    "action-result"
}
```

The value becomes the execution's `result`:

```kotlin
val execution = engine.executeNextTurn()

println(execution.result)
```

The engine does not interpret arbitrary result values.

The one special case is `FlowDecision`: when the **root execution** returns a `FlowDecision`, `TurnsEngine` applies that decision to the associated `TurnFlow`.

This makes ordinary results available to the game while giving the engine a dedicated mechanism for turn progression.

---

## Events

FL-Turns can emit lifecycle events through `TurnEventSink`.

```kotlin
val events = TurnEventSink { event ->
    println(event)
}

val engine = TurnsEngine(
    flow = flow,
    events = events,
    handler = { context ->
        FlowDecision.Continue
    }
)
```

The public `TurnEvent` model includes events for:

```text
TurnStarted
ExecutionStarted
ExecutionCompleted
ExecutionFailed
ExecutionCancelled
FlowDecisionApplied
TurnFlowEnded
RuntimeStateChanged
```

Events are observational. They do not control the engine.

This makes them suitable for:

- logging;
- debugging;
- UI updates;
- analytics;
- replay systems;
- telemetry;
- tests;
- development tools.

---

## Snapshots

For state inspection, FL-Turns exposes immutable snapshots.

### Engine snapshot

```kotlin
val snapshot = engine.snapshot()
```

`TurnsSnapshot` provides information such as:

```text
currentTurn
currentTurnActor
currentExecutionId
depth
activeExecutions
flowEnded
```

### Runtime snapshot

```kotlin
val snapshot = runtime.snapshot()
```

`RuntimeSnapshot` combines the engine snapshot with the current runtime state.

Snapshots are useful when your UI, debugger, game state inspector, or monitoring layer needs to observe the engine without gaining access to its internal mutable collections.

---

## Building a Game

FL-Turns is intentionally not your game architecture. A typical game can sit around the engine like this:

```text
                    Your Game
                       │
        ┌──────────────┼──────────────┐
        │              │              │
     Game State      Rules          Input/UI
        │              │              │
        └──────────────┼──────────────┘
                       │
                       ▼
                 FL-Turns Engine
                       │
          ┌────────────┼────────────┐
          │            │            │
       TurnFlow      Handler      Events
          │            │            │
          └────────────┼────────────┘
                       │
                       ▼
                   Next Turn
```

For example, a Pokémon TCG implementation might have application-owned concepts such as:

```text
Card
Player
Deck
Bench
Active Pokémon
Energy
Damage
Effects
Turn Rules
Victory Conditions
```

None of those belong in FL-Turns itself.

The engine only needs to know which actor is acting, which turn is being executed, what the handler does, and how turn progression should continue.

---

## Example: A Simple Turn-Based Game

The following example shows a small score-based game. The game state and rules live entirely outside FL-Turns.

```kotlin
val playerA = TurnActor("player-a")
val playerB = TurnActor("player-b")

var scoreA = 0
var scoreB = 0
var rounds = 0

val engine = TurnsEngine(
    flow = RoundRobinTurnFlow(listOf(playerA, playerB)),
    handler = { context ->
        when (context.turnActor) {
            playerA -> scoreA += 1
            playerB -> scoreB += 1
        }

        rounds += 1

        if (rounds >= 6) {
            FlowDecision.End
        } else {
            FlowDecision.Continue
        }
    }
)

suspend fun main() {
    TurnsRuntime(engine).start()

    println("Player A: $scoreA")
    println("Player B: $scoreB")
}
```

The example deliberately keeps the rules outside the engine. FL-Turns only coordinates the turns.

---

## Example: Nested Response

Nested executions can model a response chain without changing the normal turn order.

```kotlin
val playerA = TurnActor("player-a")
val playerB = TurnActor("player-b")

val engine = TurnsEngine(
    flow = RoundRobinTurnFlow(listOf(playerA, playerB)),
    handler = { context ->
        when (context.turnActor) {
            playerA -> {
                val response = context.scope.execute(playerB)
                "Player A received: ${response.result}"
            }

            playerB -> {
                "Player B responds"
            }

            else -> Unit
        }
    }
)
```

Conceptually, the execution tree becomes:

```text
Turn: player-a
└── Execution: player-a
    └── Execution: player-b
```

The child execution is part of the same turn and does not cause the normal `TurnFlow` to advance.

---

## Custom Turn Flows

When round-robin order is not enough, implement `TurnFlow` with your own scheduling rules.

```kotlin
class InitiativeTurnFlow(
    private val actors: List<TurnActor>
) : TurnFlow {
    // Implement application-specific turn ordering.
}
```

The important boundary is that the custom flow decides **who gets the next turn**, while the engine decides **how that turn is executed**.

This allows the same engine to support very different game systems without creating specialized engine classes for each one.

---

## Error Handling

FL-Turns uses exceptions for execution failures and invalid engine states.

Common exceptions include:

```kotlin
try {
    engine.executeNextTurn()
} catch (e: NoExecutableTurnException) {
    // No eligible turn is currently available.
} catch (e: MaximumExecutionDepthExceededException) {
    // The execution tree reached the configured depth limit.
}
```

Exceptions thrown by the game handler are propagated to the caller and reflected in the corresponding `ExecutionState` and `TurnEvent`.

Cancellation is represented through Kotlin coroutine cancellation.

---

## Coroutine Support

Execution handlers are suspendable functions:

```kotlin
handler = { context ->
    // suspendable game logic
}
```

This allows a game to perform asynchronous operations without making the engine itself responsible for a particular UI, networking, database, or rendering framework.

FL-Turns uses Kotlin coroutines for its execution model and yields between runtime iterations when `TurnsRuntime` is running.

---

## API Overview

The public API is organized around a few core concepts.

### Actors and turns

| Type          | Purpose                                        |
|---------------|------------------------------------------------|
| `TurnActor`   | Identifies an application-defined participant. |
| `TurnId`      | Identifies a turn.                             |
| `ExecutionId` | Identifies an execution.                       |
| `Turn`        | Represents an opportunity for an actor to act. |

### Turn flow

| Type                 | Purpose                                                    |
|----------------------|------------------------------------------------------------|
| `TurnFlow`           | Defines how turns are generated and manipulated.           |
| `RoundRobinTurnFlow` | Built-in sequential turn flow.                             |
| `FlowDecision`       | Controls how progression continues after a root execution. |
|
### Execution

| Type             | Purpose                                 |
|------------------|-----------------------------------------|
| `TurnContext`    | Context supplied to execution handlers. |
| `Execution`      | Runtime representation of an action.    |
| `ExecutionScope` | Creates nested child executions.        |
| `ExecutionState` | Execution lifecycle state.              |
|
### Engine and runtime

| Type           | Purpose                                                |
|----------------|--------------------------------------------------------|
| `TurnsEngine`  | Executes individual turns and manages execution trees. |
| `TurnsRuntime` | Continuously drives the engine through its lifecycle.  |
| `RuntimeState` | Runtime lifecycle state.                               |
|
### Observability

| Type              | Purpose                                   |
|-------------------|-------------------------------------------|
| `TurnEvent`       | Events emitted by the engine and runtime. |
| `TurnEventSink`   | Receives lifecycle events.                |
| `TurnsSnapshot`   | Read-only engine state snapshot.          |
| `RuntimeSnapshot` | Combined engine and runtime snapshot.     |
|
### Errors

| Type                                     | Purpose                                               |
|------------------------------------------|-------------------------------------------------------|
| `NoExecutableTurnException`              | No eligible turn could be produced.                   |
| `MaximumExecutionDepthExceededException` | Nested execution exceeded the configured depth limit. |

---

## Project Status

FL-Turns is currently in active development and is being prepared for its first public release.

The intended public distribution channel is **JitPack**.

Before the first release, update this README with:

- the final JitPack coordinates;
- the first stable version;
- the JitPack version badge;
- links to published API documentation, if available;
- example projects or samples, as they are added.

---

## Documentation

The source code contains KDoc for the public API and is intended to serve as the authoritative reference for the library's contracts and behavior.

**API documentation:** `<DOCUMENTATION_URL>`

---

## Contributing

Contributions, bug reports, ideas, and discussions are welcome.

When proposing changes to the core API, please keep the main design principle in mind:

> **The game provides meaning. The core provides structure.**

Features that add game-specific rules directly to the engine are intentionally outside the scope of FL-Turns.

---

## License

FL-Turns is available under the [MIT License](LICENSE).

---

<p align="center">
  <strong>FL-Turns</strong><br>
  A small core for building turn-based worlds.
</p>