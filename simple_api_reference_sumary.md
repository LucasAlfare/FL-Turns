# API Reference — FL-Turns

Kotlin library for turn-based systems with support for nested executions, flow decisions, and coroutine-driven runtime
control.

---

## Identifiers

### `TurnActor`

```kotlin
@JvmInline
value class TurnActor(val id: String)
```

**Purpose:** Identifies an actor (player, AI, entity, team, etc.) that can own or execute a turn.  
The engine assigns no semantic meaning to the value — that is the responsibility of the application.

| Input        | Output |
|--------------|--------|
| `id: String` | —      |

---

### `TurnId`

```kotlin
@JvmInline
value class TurnId(val value: Long)
```

**Purpose:** Unique identifier of a turn produced by a `TurnFlow`.

| Input         | Output |
|---------------|--------|
| `value: Long` | —      |

---

### `ExecutionId`

```kotlin
@JvmInline
value class ExecutionId(val value: Long)
```

**Purpose:** Unique identifier of an execution (node in the execution tree).

| Input         | Output |
|---------------|--------|
| `value: Long` | —      |

---

## Core Types

### `Turn`

```kotlin
data class Turn(val id: TurnId, val turnActor: TurnActor)
```

**Purpose:** Represents a single turn opportunity. Associates a `TurnId` with the actor that owns the turn.

| Property    | Type        | Description                   |
|-------------|-------------|-------------------------------|
| `id`        | `TurnId`    | Unique identifier of the turn |
| `turnActor` | `TurnActor` | Actor that owns this turn     |

---

### `FlowDecision` (sealed)

**Purpose:** Describes how the `TurnFlow` should continue after a turn has been executed. Normally returned as the
result of the root execution handler.

| Subtype             | Purpose                                              | Input                  |
|---------------------|------------------------------------------------------|------------------------|
| `Continue`          | Continues the normal progression of the flow         | —                      |
| `Repeat`            | Causes the current actor to receive another turn     | —                      |
| `Insert(turnActor)` | Inserts an actor at the current position of the flow | `turnActor: TurnActor` |
| `Skip`              | Advances past the next turn opportunity              | —                      |
| `JumpTo(turnActor)` | Moves the flow directly to a specific actor          | `turnActor: TurnActor` |
| `End`               | Terminates the flow permanently                      | —                      |

---

### `ExecutionState` (enum)

**Purpose:** Lifecycle state of an `Execution`. Terminal states: `COMPLETED`, `FAILED`, `CANCELLED`.

| Value       | Meaning                                             |
|-------------|-----------------------------------------------------|
| `RUNNING`   | Currently being processed by the engine             |
| `SUSPENDED` | Temporarily waiting for a child execution to finish |
| `COMPLETED` | Completed successfully                              |
| `FAILED`    | Terminated because an exception occurred            |
| `CANCELLED` | Cancelled                                           |

---

### `ExecutionScope`

```kotlin
class ExecutionScope internal constructor(private val engine: TurnsEngine)
```

**Purpose:** Controlled API that allows an execution handler to create child executions without exposing the internal
engine.

#### `suspend fun execute(turnActor: TurnActor): Execution`

Creates and executes a child execution. The current execution becomes `SUSPENDED` while the child runs.

| Input                  | Output                        | Throws                                                                                                                                          |
|------------------------|-------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `turnActor: TurnActor` | `Execution` (completed child) | `CancellationException`, any `Throwable` from the child, `MaximumExecutionDepthExceededException`, `IllegalStateException` (scope not attached) |

---

### `TurnContext`

```kotlin
data class TurnContext(
  val turnActor: TurnActor,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null,
  val scope: ExecutionScope
)
```

**Purpose:** Immutable context supplied to the execution handler. Provides everything needed to understand the
execution’s position in the tree and to create children.

| Property      | Type             | Description                                |
|---------------|------------------|--------------------------------------------|
| `turnActor`   | `TurnActor`      | Actor responsible for this execution       |
| `turnId`      | `TurnId`         | Turn in which this execution originated    |
| `executionId` | `ExecutionId`    | Unique identifier of this execution        |
| `depth`       | `Int`            | Depth within the execution tree (root = 0) |
| `parent`      | `Execution?`     | Parent execution, or `null` for root       |
| `scope`       | `ExecutionScope` | Scope used to create child executions      |

---

### `Execution`

```kotlin
class Execution internal constructor(val context: TurnContext)
```

**Purpose:** Fundamental runtime unit. Represents the execution of an actor’s action. Forms a tree (root + children).

#### Public properties (read-only)

| Property    | Type             | Description                                          |
|-------------|------------------|------------------------------------------------------|
| `context`   | `TurnContext`    | Immutable contextual information                     |
| `result`    | `Any?`           | Value returned by the handler (null until completed) |
| `state`     | `ExecutionState` | Current lifecycle state                              |
| `failure`   | `Throwable?`     | Exception that caused failure (if any)               |
| `id`        | `ExecutionId`    | Shortcut for `context.executionId`                   |
| `turnActor` | `TurnActor`      | Shortcut                                             |
| `turnId`    | `TurnId`         | Shortcut                                             |
| `depth`     | `Int`            | Shortcut                                             |
| `parent`    | `Execution?`     | Shortcut                                             |
| `scope`     | `ExecutionScope` | Shortcut                                             |

**Equality:** based exclusively on `ExecutionId`.

---

## Turn Flow

### `TurnFlow` (interface)

**Purpose:** Defines how a sequence of turns is generated and manipulated. Does not execute actions.

#### `fun next(isEligible: (Turn) -> Boolean = { true }): Turn`

Produces the next eligible turn.

| Input                           | Output | Throws                                                                    |
|---------------------------------|--------|---------------------------------------------------------------------------|
| `isEligible: (Turn) -> Boolean` | `Turn` | `NoExecutableTurnException`, `IllegalStateException` (flow already ended) |

#### `fun isEnded(): Boolean`

Indicates whether this flow has permanently ended.

#### `fun apply(decision: FlowDecision)`

Applies a decision produced after executing a turn.

---

### `RoundRobinTurnFlow`

```kotlin
class RoundRobinTurnFlow(turnActors: List<TurnActor>) : TurnFlow
```

**Purpose:** Cycles through actors sequentially (A → B → C → A → …). Supports all `FlowDecision` variants.

| Input                                             | Throws                              |
|---------------------------------------------------|-------------------------------------|
| `turnActors: List<TurnActor>` (must not be empty) | `IllegalArgumentException` if empty |

**Behavior of `apply`:**

| Decision   | Effect                                             |
|------------|----------------------------------------------------|
| `Continue` | No-op                                              |
| `Repeat`   | Moves the index back to the actor that just acted  |
| `Insert`   | Inserts the actor at the current index             |
| `Skip`     | Advances one additional position                   |
| `JumpTo`   | Moves directly to the requested actor (must exist) |
| `End`      | Permanently ends the flow                          |

---

## Exceptions

### `NoExecutableTurnException`

```kotlin
class NoExecutableTurnException(attempts: Int) : IllegalStateException
```

Thrown when a `TurnFlow` cannot produce an eligible turn after checking `attempts` opportunities.

### `MaximumExecutionDepthExceededException`

```kotlin
class MaximumExecutionDepthExceededException(
  maximumExecutionDepth: Int,
  attemptedDepth: Int
) : IllegalStateException
```

Thrown when creating an execution would exceed the configured depth limit. Depth 0 allows only root executions.

---

## Events

### `TurnEvent` (sealed)

**Purpose:** Unified event model for monitoring, logging, debugging, replay, UI, analytics, etc. Events are passive —
they do not control the engine.

| Event                           | Properties                                                |
|---------------------------------|-----------------------------------------------------------|
| `TurnStarted(turn)`             | `turn: Turn`                                              |
| `ExecutionStarted(...)`         | `turnId`, `executionId`, `turnActor`, `depth`, `parentId` |
| `ExecutionCompleted(...)`       | `turnId`, `executionId`, `result`                         |
| `ExecutionFailed(...)`          | `turnId`, `executionId`, `failure`                        |
| `ExecutionCancelled(...)`       | `turnId`, `executionId`                                   |
| `FlowDecisionApplied(...)`      | `turnId`, `decision`                                      |
| `TurnFlowEnded(turnId)`         | `turnId`                                                  |
| `RuntimeStateChanged(from, to)` | `from: RuntimeState`, `to: RuntimeState`                  |

---

### `TurnEventSink` (fun interface)

```kotlin
fun interface TurnEventSink {
  fun onEvent(event: TurnEvent)
}
```

**Purpose:** Passive receiver of events produced by the turn system.

---

## Snapshots

### `TurnsSnapshot`

```kotlin
data class TurnsSnapshot(
  val currentTurn: Turn?,
  val currentTurnActor: TurnActor?,
  val currentExecutionId: ExecutionId?,
  val depth: Int,
  val activeExecutions: List<ExecutionId>,
  val flowEnded: Boolean
)
```

**Purpose:** Read-only snapshot of the `TurnsEngine` state.

### `RuntimeState` (enum)

| Value      | Meaning                                 |
|------------|-----------------------------------------|
| `IDLE`     | Not started yet                         |
| `RUNNING`  | Actively processing turns               |
| `PAUSED`   | Temporarily stopped between turns       |
| `FINISHED` | Will no longer process additional turns |

### `RuntimeSnapshot`

```kotlin
data class RuntimeSnapshot(
  val engine: TurnsSnapshot,
  val state: RuntimeState
)
```

**Purpose:** Combined snapshot of engine + runtime state.

---

## Engine

### `TurnsEngine`

```kotlin
class TurnsEngine(
  val handler: suspend (TurnContext) -> Any? = suspend {},
  private val flow: TurnFlow,
  private val isEligible: (Turn) -> Boolean = { true },
  private val maximumExecutionDepth: Int = Int.MAX_VALUE,
  private val events: TurnEventSink = TurnEventSink {}
)
```

**Purpose:** Central execution component. Coordinates turn generation, execution, nested executions, and flow decisions.

| Parameter               | Type                            | Default         | Description                                               |
|-------------------------|---------------------------------|-----------------|-----------------------------------------------------------|
| `handler`               | `suspend (TurnContext) -> Any?` | `{}`            | Function that performs the actual work of an execution    |
| `flow`                  | `TurnFlow`                      | —               | Strategy responsible for producing and manipulating turns |
| `isEligible`            | `(Turn) -> Boolean`             | `{ true }`      | Predicate used to filter generated turns                  |
| `maximumExecutionDepth` | `Int`                           | `Int.MAX_VALUE` | Maximum allowed nesting depth (≥ 0)                       |
| `events`                | `TurnEventSink`                 | no-op           | Sink that receives lifecycle events                       |

#### Public methods

| Method                          | Input | Output             | Description                                 |
|---------------------------------|-------|--------------------|---------------------------------------------|
| `snapshot()`                    | —     | `TurnsSnapshot`    | Current engine state                        |
| `suspend fun executeNextTurn()` | —     | `Execution` (root) | Executes the next available turn completely |
| `isFlowEnded()`                 | —     | `Boolean`          | Whether the underlying flow has ended       |

**Lifecycle of `executeNextTurn()`:**

1. Requests a turn from the flow
2. Creates the root `Execution`
3. Emits `TurnStarted`
4. Runs the handler (and any nested executions)
5. Interprets a returned `FlowDecision`
6. Applies the decision to the flow
7. Emits `TurnFlowEnded` when appropriate

---

## Runtime

### `TurnsRuntime`

```kotlin
class TurnsRuntime(
  private val engine: TurnsEngine,
  private val events: TurnEventSink = TurnEventSink {}
)
```

**Purpose:** High-level lifecycle controller. Continuously asks the engine to execute turns while its state is
`RUNNING`.

| Property | Type           | Description                                                     |
|----------|----------------|-----------------------------------------------------------------|
| `state`  | `RuntimeState` | Current lifecycle state (publicly readable, privately writable) |

#### Public methods

| Method                | Input | Output            | Description                                        |
|-----------------------|-------|-------------------|----------------------------------------------------|
| `snapshot()`          | —     | `RuntimeSnapshot` | Combined runtime + engine snapshot                 |
| `suspend fun start()` | —     | —                 | Starts the continuous turn-processing loop         |
| `pause()`             | —     | —                 | Pauses the runtime (only effective when `RUNNING`) |
| `resume()`            | —     | —                 | Resumes a paused runtime                           |
| `stop()`              | —     | —                 | Permanently stops the runtime                      |

**Notes:**

- `start()` may only be called from `IDLE`.
- Pausing does not alter the underlying `TurnsEngine` state.
- The loop yields between iterations via `yield()`.

---

## Architecture Overview

```
TurnFlow  →  produces Turn
     ↓
TurnsEngine  →  creates root Execution
     ↓
handler(TurnContext)  →  may call scope.execute() → child Executions
     ↓
result as? FlowDecision  →  flow.apply(decision)
     ↓
TurnEventSink  ←  lifecycle events
```

Clear separation of responsibilities:

- **TurnFlow** → turn order
- **isEligible** → eligibility filtering
- **handler** → action logic
- **ExecutionScope** → nesting
- **FlowDecision** → influence on future progression
- **TurnEventSink** → external observation