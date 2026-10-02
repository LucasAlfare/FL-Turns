/* MD SUMARY HERE
# FLTurns — Public API Reference

Package: `com.lucasalfare.flturns`

FLTurns is a Kotlin turn-based execution engine. It provides the infrastructure required to model turn-based games as a sequence of turn opportunities, actor executions, nested child executions, flow decisions, execution lifecycle states, runtime lifecycle, and observable events.

The engine is intentionally unaware of game-specific concepts such as health, movement, cards, attacks, resources, teams, maps, victory conditions, or rules.

The application supplies the actors, the turn handler, and the game logic.

---

# Public API Overview

The public API is composed of the following concepts:

| Type                                     | Kind          | Purpose                                                  |
| ---------------------------------------- | ------------- | -------------------------------------------------------- |
| `ActorId`                                | value class   | Stable identity for an actor                             |
| `TurnActor`                              | class         | Actor eligible to receive turns                          |
| `TurnId`                                 | value class   | Identity of a turn opportunity                           |
| `ExecutionId`                            | value class   | Identity of an execution                                 |
| `ExecutionResult<T>`                     | class         | Container for application-defined execution results      |
| `Turn`                                   | data class    | Represents a turn opportunity                            |
| `FlowDecision`                           | sealed class  | Controls how the next turn flow is modified              |
| `ExecutionState`                         | enum          | Lifecycle state of an execution                          |
| `ExecutionScope`                         | class         | Allows the current execution to create child executions  |
| `TurnContext`                            | data class    | Context supplied to the turn handler                     |
| `Execution`                              | class         | Read-only public representation of an execution          |
| `TurnFlow`                               | data class    | Stores the ordered actors participating in the turn flow |
| `MaximumExecutionDepthExceededException` | exception     | Signals excessive child-execution depth                  |
| `TurnEvent`                              | sealed class  | Observable engine/runtime events                         |
| `TurnEventSink`                          | fun interface | Receives engine/runtime events                           |
| `TurnSnapshot`                           | data class    | Snapshot of observable engine state                      |
| `TurnEngine`                             | class         | Core turn/execution orchestrator                         |
| `RuntimeState`                           | enum          | Runtime lifecycle state                                  |
| `TurnRuntime`                            | class         | Continuously drives the engine                           |

The core relationship is:

`TurnActor -> Turn -> TurnContext -> Execution`

while:

`TurnEngine -> TurnFlow`

controls which turn happens next, and:

`TurnRuntime -> TurnEngine`

provides a continuous execution loop.

---

# Core Identity Types

## ActorId

`@JvmInline value class ActorId(val value: String)`

Stable identity for a `TurnActor`.

### Constructor

`ActorId(value: String)`

### Properties

`value: String`

The application-defined identifier of the actor.

The engine does not assign semantic meaning to this value.

Applications may use values such as:

* `"player"`
* `"enemy-1"`
* `"wizard"`
* `"team-red"`

The value should be stable for the lifetime of the corresponding actor identity.

---

# TurnActor

`class TurnActor`

Entity eligible to receive turns.

The engine knows only the actor's identity. Game-specific state belongs to the application.

### Constructor

`TurnActor(id: ActorId)`

### Properties

`id: ActorId`

Stable identity of the actor.

### Equality

Two `TurnActor` instances are equal when their `id` values are equal.

Therefore, actor identity is value-based rather than instance-based.

### String representation

`toString()` produces:

`TurnActor(<id>)`

---

# TurnId

`@JvmInline value class TurnId(val value: Long)`

Monotonic identity of a normal turn opportunity.

### Constructor

`TurnId(value: Long)`

### Properties

`value: Long`

Numeric identifier of the turn.

A `TurnId` identifies a specific turn opportunity, not an actor and not an execution.

A single actor may receive many different `TurnId` values over the course of a game.

---

# ExecutionId

`@JvmInline value class ExecutionId(val value: Long)`

Monotonic identity of a concrete execution.

### Constructor

`ExecutionId(value: Long)`

### Properties

`value: Long`

Numeric identifier of the execution.

A normal turn execution and each child execution have distinct execution IDs.

---

# ExecutionResult

`class ExecutionResult<out T>`

Opaque container for application-defined execution data.

### Constructor

`ExecutionResult(value: T)`

### Properties

`value: T`

The value produced by the execution.

The type is covariant.

The engine automatically wraps a handler return value in `ExecutionResult` when the value is not already an `ExecutionResult`.

Therefore a handler may conceptually return either:

`SomeGameValue`

or:

`ExecutionResult(SomeGameValue)`

and the engine exposes the result as an `ExecutionResult`.

---

# Turn

`data class Turn`

Represents one normal turn opportunity.

A turn binds an actor to a unique turn identity.

### Constructor

`Turn(id: TurnId, actor: TurnActor)`

### Properties

`id: TurnId`

Identity of this turn opportunity.

`actor: TurnActor`

Actor receiving the turn.

### Semantics

A `Turn` is not itself an execution.

It is the opportunity selected by the turn flow.

When `TurnEngine.executeNextTurn()` processes the opportunity, the engine creates an `Execution` associated with that turn.

---

# FlowDecision

`sealed class FlowDecision`

Instruction returned by an execution to reshape the normal turn flow.

A handler can return a `FlowDecision` as its result. When the top-level turn execution completes, the engine detects that result and applies it to the `TurnFlow`.

## Continue

`FlowDecision.Continue`

Keeps the normal flow unchanged.

The next turn is whatever the current turn flow would normally produce.

## Repeat

`FlowDecision.Repeat`

Repeats the actor that just received the turn.

With the built-in `TurnFlow`, the internal cursor is restored to the position of the current turn.

Conceptually:

`A -> B -> C`

If `B` returns `Repeat`:

`A -> B -> B -> C`

## Insert

`FlowDecision.Insert(actor: TurnActor)`

Inserts an actor into the current turn sequence.

The inserted actor becomes part of the circular flow.

This changes future turn opportunities, but does not retroactively change the current turn.

## Skip

`FlowDecision.Skip`

Skips the next actor in the current flow.

With the built-in flow, the cursor advances one additional position beyond its normal position.

Conceptually:

`A -> B -> C`

If `A` returns `Skip`, the next opportunity becomes `C` rather than `B`.

## JumpTo

`FlowDecision.JumpTo(actor: TurnActor)`

Moves the turn-flow cursor directly to the specified actor.

The actor must exist in the current flow.

Otherwise the built-in flow throws `IllegalArgumentException`.

## End

`FlowDecision.End`

Marks the turn flow as ended.

Once ended, no further normal turn opportunities should be executed.

---

# ExecutionState

`enum class ExecutionState`

Lifecycle state of a single `Execution`.

## RUNNING

The execution is currently executing its handler.

## SUSPENDED

The execution is temporarily suspended while it waits for a child execution.

A parent execution may enter this state when `ExecutionScope.execute()` creates a child execution.

## COMPLETED

The execution completed successfully and contains a result.

## FAILED

The execution failed because its handler or nested execution produced an exception.

The failure is available through `Execution.failure`.

## CANCELLED

The execution was cancelled.

Cancellation propagates through child executions.

---

# ExecutionScope

`class ExecutionScope`

Handle exposed to a handler to create child executions.

The scope belongs to the currently executing `Execution`.

The constructor is not public and is created by the engine.

Applications obtain a scope through `TurnContext.scope`.

## execute

`suspend fun execute(actor: TurnActor): ExecutionResult<*>`

Creates and executes one child execution for `actor`.

The child execution:

* belongs to the current execution;
* has a depth one greater than its parent;
* receives a new `ExecutionId`;
* inherits the parent's `TurnId`;
* executes the same engine handler;
* completes before the parent resumes.

The parent becomes `SUSPENDED` while the child is running.

The returned value is the child's `ExecutionResult`.

### Failure behavior

If the parent is already cancelled, execution fails with `CancellationException`.

If the parent is already failed, the parent's failure is propagated.

If child execution fails, the parent is also failed.

## executeAll

`suspend fun executeAll(vararg actors: TurnActor): List<ExecutionResult<*>>`

Executes multiple child actors sequentially.

Each actor produces one child execution.

The executions are performed in the same order as the supplied arguments.

This is equivalent to repeatedly invoking `execute()` for each actor.

The returned list contains the corresponding execution results in execution order.

## cancel

`fun cancel()`

Cancels the current execution.

Cancellation propagates to the execution and its children.

The method then throws `CancellationException`.

This method is intended to terminate the current handler immediately.

## fail

`fun fail(t: Throwable)`

Marks the current execution as failed with `t`.

The failure is propagated to child executions.

The supplied throwable is then thrown.

This method is intended to explicitly terminate the current execution as a failure.

---

# TurnContext

`data class TurnContext`

Immutable information describing the execution currently being handled.

This is the primary object passed to the turn handler.

### Constructor

`TurnContext(actor, turnId, executionId, depth, parent, scope)`

### Properties

`actor: TurnActor`

Actor associated with the current execution.

`turnId: TurnId`

Turn opportunity associated with the execution.

Top-level executions created by `executeNextTurn()` receive the new turn's ID.

Child executions inherit their parent's `TurnId`.

`executionId: ExecutionId`

Unique identity of this concrete execution.

Every child receives a new execution ID.

`depth: Int`

Depth of this execution in the execution tree.

Top-level executions have:

`depth == 0`

A child of a top-level execution has:

`depth == 1`

A grandchild has:

`depth == 2`

and so on.

`parent: Execution?`

The parent execution.

For a normal top-level turn this is `null`.

For a child execution it points to the execution that created it.

`scope: ExecutionScope?`

Scope used to create child executions.

For executions produced by `TurnEngine`, this is provided by the engine.

---

# Execution

`class Execution`

Read-only public representation of a concrete execution tracked by `TurnEngine`.

The constructor is internal. Applications obtain executions from the engine or from child-execution operations.

### Constructor

`internal constructor(context: TurnContext)`

Not directly constructible by application code.

### Properties

`context: TurnContext`

The immutable execution context.

`result: ExecutionResult<*>?`

The completed execution result.

The property is writable only internally by the engine.

It is `null` until the execution successfully completes.

`state: ExecutionState`

Current lifecycle state.

The state is changed internally by the engine.

`failure: Throwable?`

Exception responsible for execution failure.

This is `null` unless the execution is in a failed state.

### Convenience properties

`id: ExecutionId`

Equivalent to `context.executionId`.

`actor: TurnActor`

Equivalent to `context.actor`.

`turnId: TurnId`

Equivalent to `context.turnId`.

`depth: Int`

Equivalent to `context.depth`.

`parent: Execution?`

Equivalent to `context.parent`.

### Lifecycle semantics

An execution begins in:

`RUNNING`

It may transition to:

`SUSPENDED`

while waiting for a child.

It may eventually become:

`COMPLETED`

`FAILED`

or:

`CANCELLED`

Completed, failed, and cancelled executions are terminal states.

### Equality

Executions are equal when their `ExecutionId` values are equal.

### String representation

`toString()` produces:

`Execution(<id>)`

---

# TurnFlow

`data class TurnFlow`

Represents the actor collection used by the default turn sequence.

The actor list is circular.

### Constructor

`TurnFlow(actors: List<TurnActor>)`

The supplied actor list must not be empty.

An empty list causes `IllegalArgumentException`.

### Properties

`actors: List<TurnActor>`

The actors supplied to the flow.

The public property represents the actor configuration.

The flow's internal cursor and lifecycle are managed internally.

### Default ordering

The initial cursor points to the first actor.

For:

`[A, B, C]`

successive normal turns are:

`A, B, C, A, B, C, ...`

### Turn IDs

Each call that creates a normal turn opportunity receives a new monotonically increasing `TurnId`.

The sequence starts at `0`.

### Flow decisions

The built-in flow implements:

`Continue`

No change.

`Repeat`

Re-select the actor that just received the turn.

`Insert(actor)`

Insert the actor into the internal circular sequence.

`Skip`

Advance one additional position.

`JumpTo(actor)`

Move the cursor to the specified actor.

`End`

Mark the flow as ended.

### Important usage model

Application code does not normally advance the flow directly.

`TurnEngine` controls progression through the internal flow operations.

The application normally influences the flow by returning a `FlowDecision` from the turn handler.

---

# MaximumExecutionDepthExceededException

`class MaximumExecutionDepthExceededException : IllegalStateException`

Thrown when a child execution would exceed the configured maximum execution depth.

### Constructor

`MaximumExecutionDepthExceededException(maximumExecutionDepth: Int, attemptedDepth: Int)`

The exception message contains both values.

### Meaning

If the parent has depth `D`, its child would have:

`D + 1`

If that value is greater than `maximumExecutionDepth`, the child is rejected.

This prevents unbounded recursive execution trees.

---

# TurnEvent

`sealed class TurnEvent`

Events published by `TurnEngine` and `TurnRuntime` for observability.

Applications can use events for:

* logging;
* debugging;
* UI updates;
* replay systems;
* telemetry;
* test assertions;
* visualization;
* instrumentation.

Events are descriptive. They do not themselves modify game state.

## TurnStarted

`TurnEvent.TurnStarted(turn: Turn, executionId: ExecutionId)`

Published when a normal turn execution begins.

Contains the selected turn and its execution ID.

## ExecutionStarted

`TurnEvent.ExecutionStarted(executionId, actor, depth, parentId)`

Published when an execution begins running.

`executionId`

Identity of the execution.

`actor`

Actor executing it.

`depth`

Execution-tree depth.

`parentId`

Parent execution ID, or `null` for a top-level execution.

## ExecutionCompleted

`TurnEvent.ExecutionCompleted(executionId, result)`

Published after successful completion.

`result` contains the resulting `ExecutionResult`.

## ExecutionFailed

`TurnEvent.ExecutionFailed(executionId, failure)`

Published when execution fails with a throwable.

## ExecutionCancelled

`TurnEvent.ExecutionCancelled(executionId)`

Published when execution is cancelled.

## FlowDecisionApplied

`TurnEvent.FlowDecisionApplied(decision)`

Published after a returned `FlowDecision` is applied to the turn flow.

## FlowEnded

`TurnEvent.FlowEnded(turnId)`

Published when the flow becomes ended as a consequence of a turn.

The associated `turnId` identifies the turn after which the flow ended.

## RuntimeStateChanged

`TurnEvent.RuntimeStateChanged(from, to)`

Published when `TurnRuntime` changes lifecycle state.

---

# TurnEventSink

`fun interface TurnEventSink`

Consumer of `TurnEvent` instances.

### Method

`fun onEvent(event: TurnEvent)`

Receives each event emitted by the engine or runtime.

An event sink may perform arbitrary application-level observation such as logging or UI notification.

The event sink is optional. The engine and runtime provide an empty default sink.

---

# TurnSnapshot

`data class TurnSnapshot`

Immutable snapshot of observable engine state.

### Properties

`currentTurn: Turn?`

Currently active top-level turn, if any.

`currentActor: TurnActor?`

Actor associated with the deepest currently active execution.

If no execution is active, the current turn actor is used as fallback.

`currentExecutionId: ExecutionId?`

ID of the deepest currently active execution.

`depth: Int`

Depth of the deepest active execution.

When there is no active execution, this is `0`.

`pendingExecutions: List<ExecutionId>`

IDs of executions currently tracked as active.

The list represents currently executing work rather than future turns.

`flowEnded: Boolean`

Whether the underlying turn flow has ended.

`runtimeState: RuntimeState?`

Runtime lifecycle state when the snapshot is produced by `TurnRuntime`.

For snapshots produced directly by `TurnEngine`, this value is `null`.

---

# TurnEngine

`class TurnEngine`

Core orchestrator of turns and executions.

The engine is responsible for:

* selecting the next turn;
* filtering executable opportunities;
* creating executions;
* invoking the application handler;
* creating child executions;
* suspending and resuming parents;
* enforcing execution depth;
* tracking active executions;
* publishing execution events;
* interpreting returned `FlowDecision` values.

The engine contains no game-specific rules.

## Primary constructor

`TurnEngine(
    flow: TurnFlow,
    canExecute: (TurnActor, TurnContext) -> Boolean = { _, _ -> true },
    handler: suspend (TurnContext) -> Any? = ...,
    maximumExecutionDepth: Int = Int.MAX_VALUE,
    events: TurnEventSink = ...
)`

### flow

`TurnFlow`

The turn ordering mechanism.

### canExecute

`(TurnActor, TurnContext) -> Boolean`

Predicate determining whether a selected turn opportunity is currently executable.

The default implementation returns `true` for every turn.

The predicate is evaluated before the turn execution is created.

If a selected turn is not executable, the engine requests another turn from the flow until an executable one is found.

This can be used for game rules such as temporarily unavailable actors.

### handler

`suspend (TurnContext) -> Any?`

The application-defined function that performs the actual game logic for an execution.

The handler receives a `TurnContext`.

The handler's returned value becomes the execution result.

A handler may additionally return a `FlowDecision` to modify future turn flow.

The handler is conceptually the boundary between the generic engine and the game's rules.

### maximumExecutionDepth

`Int`

Maximum allowed depth for child executions.

The value must be non-negative.

`0` means that top-level turns may execute, but no child execution may be created.

The default is `Int.MAX_VALUE`.

### events

`TurnEventSink`

Event consumer.

Defaults to an empty sink.

## Secondary constructor

`TurnEngine(flow: TurnFlow, handler: suspend (TurnContext) -> Any?)`

Convenience constructor for the common case in which only the flow and handler need to be specified.

It uses the default execution predicate, maximum depth, and event sink.

---

## snapshot

`fun snapshot(): TurnSnapshot`

Returns an immutable snapshot of the engine's current observable state.

The snapshot does not mutate engine state.

### Deepest execution

When multiple executions are active, the snapshot identifies the deepest active execution as the current execution/actor context.

---

## executeNextTurn

`suspend fun executeNextTurn(): Execution`

Executes the next eligible turn from the configured flow.

The operation:

1. Requests the next turn from the flow.
2. Evaluates `canExecute`.
3. Continues requesting turns until an eligible turn is found.
4. Creates a top-level execution with depth `0`.
5. Creates an `ExecutionScope`.
6. Publishes `TurnStarted`.
7. Publishes `ExecutionStarted`.
8. Invokes the handler.
9. Completes, fails, or cancels the execution.
10. If the result is a `FlowDecision`, applies it to the flow.
11. Publishes the corresponding flow events.
12. Returns the resulting `Execution`.

### Returned value

The returned `Execution` represents the top-level execution associated with the selected turn.

Its result can be inspected through:

`execution.result`

Its final lifecycle state can be inspected through:

`execution.state`

### FlowDecision behavior

The engine treats the top-level handler result specially.

If:

`handler(context)` returns `FlowDecision.Repeat`

the engine applies `Repeat`.

If it returns:

`FlowDecision.Insert(actor)`

the engine applies that instruction, and so forth.

If the returned value is any other object, it is treated only as the execution result.

---

## isFlowEnded

`fun isFlowEnded(): Boolean`

Returns whether the configured `TurnFlow` has ended.

This is useful for manual engine loops and is also used by `TurnRuntime`.

---

# Turn execution model

A normal call to:

`executeNextTurn()`

creates an execution tree whose root is the current turn.

A root execution has:

`depth = 0`

A handler may use:

`context.scope.execute(actor)`

to create a child.

The child has:

`depth = parent.depth + 1`

The child receives:

* a new `ExecutionId`;
* the same `TurnId`;
* its own `TurnContext`;
* its own `ExecutionScope`;
* the parent as `context.parent`.

The parent is suspended while the child executes.

After successful child completion, the parent resumes.

This allows turn-based game logic to express nested actions without requiring the engine to understand what those actions mean.

For example, a game can interpret:

`Player turn`
-> `Attack`
-> `Enemy reaction`
-> `Status effect`
-> `Retaliation`

as an execution tree rather than forcing all of those operations to become independent top-level turns.

---

# Result handling

The handler can return arbitrary application-defined data.

Conceptually:

`TurnContext -> Any?`

The engine normalizes the returned value into:

`ExecutionResult<*>`

If the handler returns:

`42`

the execution receives an `ExecutionResult` whose value is `42`.

If the handler returns:

`ExecutionResult(42)`

that result is preserved as an execution result rather than being wrapped again.

A `FlowDecision` is therefore both:

* an ordinary execution result;
* a control instruction for the turn flow when returned by a top-level turn handler.

---

# Failure propagation

Exceptions thrown by the handler cause the corresponding execution to enter:

`ExecutionState.FAILED`

The thrown exception becomes:

`Execution.failure`

A child failure also fails its parent.

Failure therefore propagates upward through the execution tree.

A failed execution cannot later become completed.

---

# Cancellation propagation

Cancellation causes the corresponding execution to enter:

`ExecutionState.CANCELLED`

Cancellation propagates to child executions.

An execution that is already terminal is not transitioned again.

`ExecutionScope.cancel()` explicitly cancels the current execution and throws `CancellationException` to stop handler execution.

---

# Execution depth

The engine protects itself against excessively deep execution trees.

For a parent execution with depth:

`D`

the child receives:

`D + 1`

If:

`D + 1 > maximumExecutionDepth`

creation of the child fails with:

`MaximumExecutionDepthExceededException`

This mechanism is independent of normal turn-flow sequencing.

---

# RuntimeState

`enum class RuntimeState`

Lifecycle state of `TurnRuntime`.

## IDLE

Runtime has not started.

## RUNNING

Runtime is actively driving the engine.

## PAUSED

Runtime has been paused.

The runtime loop remains alive but does not execute new turns.

## FINISHED

Runtime has stopped and will not continue processing turns.

---

# TurnRuntime

`class TurnRuntime`

High-level driver that repeatedly calls `TurnEngine.executeNextTurn()` until the flow finishes or the runtime is stopped.

It provides a continuous execution loop on top of `TurnEngine`.

### Constructor

`TurnRuntime(
    engine: TurnEngine,
    events: TurnEventSink = ...
)`

### Properties

`state: RuntimeState`

Current runtime lifecycle state.

The setter is private.

Applications can observe the state but cannot directly assign it.

---

## snapshot

`fun snapshot(): TurnSnapshot`

Returns the current engine snapshot with the current `RuntimeState` included.

Unlike `TurnEngine.snapshot()`, the runtime snapshot populates:

`TurnSnapshot.runtimeState`

---

## start

`suspend fun start()`

Starts the runtime loop.

The runtime must currently be:

`IDLE`

Otherwise the method throws `IllegalStateException`.

After entering `RUNNING`, the runtime repeatedly:

1. yields to the coroutine scheduler;
2. waits while paused;
3. checks whether the engine flow has ended;
4. transitions to `FINISHED` when the flow ends;
5. otherwise executes the next turn.

The method returns when the runtime reaches a non-running terminal state.

A runtime instance is therefore intended to act as the continuous driver of one engine lifecycle.

---

## pause

`fun pause()`

Changes:

`RUNNING -> PAUSED`

When already paused, no transition occurs.

Pausing does not finish the runtime.

The runtime loop remains alive and can later resume.

---

## resume

`fun resume()`

Changes:

`PAUSED -> RUNNING`

When not paused, no transition occurs.

---

## stop

`fun stop()`

Changes:

`RUNNING -> FINISHED`

or:

`PAUSED -> FINISHED`

Stopping does not restart the engine.

---

# Recommended application architecture

A typical game should divide responsibilities as follows.

## Game layer

Owns:

* characters;
* enemies;
* cards;
* maps;
* health;
* resources;
* actions;
* victory conditions;
* game-specific rules.

## FLTurns layer

Owns:

* turn ordering;
* turn IDs;
* execution IDs;
* execution trees;
* nested execution;
* execution lifecycle;
* flow decisions;
* maximum execution depth;
* runtime lifecycle;
* observability events.

The game should not need to modify FLTurns internals.

---

# Minimal game integration model

A game generally needs to provide:

1. A collection of `TurnActor`s.
2. A `TurnFlow`.
3. A turn `handler`.
4. Optional `canExecute` logic.
5. Optional `TurnEventSink`.
6. Optional maximum execution depth.

The handler is the primary place where game-specific turn logic is executed.

The handler receives:

`TurnContext`

and can inspect:

* `context.actor`
* `context.turnId`
* `context.executionId`
* `context.depth`
* `context.parent`
* `context.scope`

It may:

* perform the actor's game action;
* create child executions;
* return a game result;
* return a `FlowDecision`;
* explicitly cancel;
* explicitly fail.

---

# Conceptual lifecycle of a normal turn

The normal lifecycle is:

`TurnFlow`
-> selects `Turn`
-> `TurnEngine`
-> creates `Execution`
-> creates `TurnContext`
-> invokes handler
-> handler returns result
-> execution completes
-> optional `FlowDecision` is applied
-> next turn becomes available

When a child is created:

`Parent Execution`
-> `ExecutionScope.execute(actor)`
-> `Child Execution`
-> parent becomes `SUSPENDED`
-> child executes
-> child completes/fails/cancels
-> parent resumes or propagates failure/cancellation

---

# API design principles

The public API intentionally exposes concepts rather than implementation details.

Applications should generally interact with:

* `TurnActor`
* `Turn`
* `TurnFlow`
* `TurnContext`
* `Execution`
* `ExecutionScope`
* `FlowDecision`
* `TurnEngine`
* `TurnRuntime`
* `TurnEvent`
* `TurnEventSink`
* `TurnSnapshot`

Application code should not depend on engine internals such as execution collections, internal cursor manipulation, internal lifecycle transitions, or child attachment.

---

# Important invariants

The following are fundamental API contracts.

## Actor identity

`TurnActor` equality is based on `ActorId`.

Two actor objects with the same ID represent the same actor identity to the engine.

## Turn identity

Each normal turn opportunity has a distinct `TurnId`.

## Execution identity

Each concrete execution has a distinct `ExecutionId`.

## Root depth

Normal turn executions start at depth `0`.

## Child depth

A child is always exactly one level deeper than its parent.

## Parent relationship

Top-level execution:

`parent == null`

Child execution:

`parent != null`

## Child suspension

Creating a child suspends the parent until the child completes.

## Failure propagation

Child failure propagates to the parent.

## Cancellation propagation

Cancellation propagates through the execution tree.

## Terminal execution states

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal.

## Flow termination

Once `TurnFlow` is ended, normal turn processing must stop.

## Empty turn flow

A `TurnFlow` must contain at least one actor.

---

# Public API index

## Identity

`ActorId`

`TurnId`

`ExecutionId`

## Actors and turns

`TurnActor`

`Turn`

`TurnFlow`

`FlowDecision`

## Execution

`Execution`

`ExecutionResult`

`ExecutionScope`

`TurnContext`

`ExecutionState`

`MaximumExecutionDepthExceededException`

## Engine

`TurnEngine`

## Runtime

`TurnRuntime`

`RuntimeState`

## Observability

`TurnEvent`

`TurnEventSink`

`TurnSnapshot`

---

# Intended division of control

The API has three distinct levels of control.

## Turn order control

`TurnFlow` and `FlowDecision`

Determine who receives the next normal turn.

## Execution control

`TurnContext`, `ExecutionScope`, and `Execution`

Determine what happens inside one turn and how nested actions are represented.

## Runtime control

`TurnRuntime`

Determines whether the engine continuously advances, pauses, or stops.

These concerns are deliberately separated.

---

# What FLTurns does NOT define

FLTurns does not define:

* player input;
* AI;
* combat;
* damage;
* movement;
* cards;
* inventory;
* animation;
* rendering;
* networking;
* persistence;
* save files;
* UI;
* win conditions;
* lose conditions;
* action menus;
* cooldowns;
* character classes;
* teams;
* game-specific rules.

Those concepts are expected to be implemented by the application using the public API.

---

# Canonical mental model

A useful way to understand the library is:

`TurnFlow` answers:

“Who gets the next normal turn?”

`Turn` answers:

“Which actor is receiving this particular turn opportunity?”

`TurnEngine` answers:

“How is that turn executed?”

`TurnContext` answers:

“What information does the current execution need?”

`ExecutionScope` answers:

“How can this execution invoke another actor?”

`Execution` answers:

“What happened to this concrete piece of work?”

`FlowDecision` answers:

“How should the normal turn sequence change afterward?”

`TurnRuntime` answers:

“Should the engine keep driving turns continuously?”

`TurnEvent` answers:

“What observable things happened?”

`TurnSnapshot` answers:

“What is the engine's observable state right now?”
 */

@file:Suppress("unused")

package com.lucasalfare.flturns

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.yield

/**
 * Identifies an actor participating in a turn-based system.
 *
 * An actor represents an entity that can own or execute a turn. Depending on
 * the game or application, an actor may represent a player, an AI opponent,
 * a game entity, a team, or any other participant capable of acting.
 *
 * The engine does not assign any semantic meaning to the string value. The
 * meaning and uniqueness of an [TurnActor] are the responsibility of the
 * application using the engine.
 *
 * @property id the application-defined identifier of the actor.
 */
@JvmInline
value class TurnActor(val id: String)

/**
 * Identifies a turn produced by a [TurnFlow].
 *
 * A turn represents an opportunity for an actor to execute an action.
 * Turn identifiers are distinct from actor identifiers: the same actor may
 * participate in many different turns during the lifetime of a game.
 *
 * @property value the unique numeric identifier of the turn.
 */
@JvmInline
value class TurnId(val value: Long)

/**
 * Identifies a single execution performed by the engine.
 *
 * An execution is the runtime representation of an action being processed
 * by the [TurnsEngine]. Executions form a hierarchy when an execution creates
 * child executions through [ExecutionScope.execute].
 *
 * A child execution has its own [ExecutionId] while retaining the same
 * [TurnId] as its parent execution.
 *
 * @property value the unique numeric identifier of the execution.
 */
@JvmInline
value class ExecutionId(val value: Long)

/**
 * Represents a single turn opportunity.
 *
 * A [Turn] associates a unique turn identifier with the actor that owns
 * that opportunity to act.
 *
 * A turn does not itself represent the execution of an action. It is the
 * unit produced by a [TurnFlow] and subsequently processed by a
 * [TurnsEngine].
 *
 * @property id the unique identifier of this turn.
 * @property turnActor the actor associated with this turn.
 */
data class Turn(val id: TurnId, val turnActor: TurnActor)

/**
 * Describes how a [TurnFlow] should continue after a turn has been executed.
 *
 * A flow decision is normally returned as the result of the root execution
 * of a turn. The [TurnsEngine] recognizes a returned [FlowDecision] and
 * applies it to the associated [TurnFlow].
 *
 * Flow decisions control the progression of turns without requiring the
 * execution handler to know the internal representation of the flow.
 *
 * The available decisions are:
 *
 * - [Continue] keeps the normal progression of the flow.
 * - [Repeat] causes the current actor to receive another turn.
 * - [Insert] inserts an additional actor into the flow.
 * - [Skip] advances past the next turn opportunity.
 * - [JumpTo] moves the flow directly to a specific actor.
 * - [End] terminates the flow.
 */
sealed class FlowDecision {
  /**
   * Continues the flow using its normal progression.
   *
   * For [RoundRobinTurnFlow], this means the next actor in the normal
   * circular sequence will receive the next turn.
   */
  object Continue : FlowDecision()

  /**
   * Repeats the actor that just received the current turn.
   *
   * For [RoundRobinTurnFlow], the flow index is moved back to the position
   * that produced the current turn.
   */
  object Repeat : FlowDecision()

  /**
   * Inserts an actor into the current position of the flow.
   *
   * The inserted actor becomes part of subsequent turn generation.
   *
   * @property turnActor the actor to insert into the flow.
   */
  data class Insert(val turnActor: TurnActor) : FlowDecision()

  /**
   * Skips the next turn opportunity in the flow.
   *
   * The exact meaning depends on the [TurnFlow] implementation.
   */
  object Skip : FlowDecision()

  /**
   * Moves the flow directly to a specific actor.
   *
   * The target actor must exist in the flow. If it does not, the flow
   * implementation may reject the decision.
   *
   * @property turnActor the actor that should receive the next turn.
   */
  data class JumpTo(val turnActor: TurnActor) : FlowDecision()

  /**
   * Ends the turn flow.
   *
   * Once applied, the flow no longer produces additional turns.
   */
  object End : FlowDecision()
}

/**
 * Describes the lifecycle state of an [Execution].
 *
 * Execution states are mutually exclusive. An execution begins in
 * [RUNNING] and eventually reaches one of the terminal states:
 *
 * - [COMPLETED]
 * - [FAILED]
 * - [CANCELLED]
 *
 * [SUSPENDED] represents an execution temporarily waiting for a child
 * execution to finish.
 */
enum class ExecutionState {

  /**
   * The execution is currently being processed by the engine.
   */
  RUNNING,

  /**
   * The execution is temporarily waiting for a child execution.
   *
   * A suspended execution is resumed after its child completes successfully.
   */
  SUSPENDED,

  /**
   * The execution completed successfully.
   */
  COMPLETED,

  /**
   * The execution terminated because an exception occurred.
   */
  FAILED,

  /**
   * The execution was cancelled.
   */
  CANCELLED
}

/**
 * Provides an execution with access to operations that affect its execution
 * tree.
 *
 * An [ExecutionScope] is associated with exactly one [Execution]. It allows
 * the execution handler to create child executions without exposing the
 * internal [TurnsEngine] directly.
 *
 * The scope therefore acts as the controlled API through which an execution
 * can request another actor to perform an action.
 *
 * A scope is created internally by the engine and attached to its execution
 * before the execution is exposed to the handler.
 *
 * @property engine the engine responsible for executing child executions.
 */
class ExecutionScope internal constructor(private val engine: TurnsEngine) {
  private var parent: Execution? = null

  /**
   * Associates this scope with an execution.
   *
   * This operation is internal to the engine and can only be performed once.
   *
   * @param execution the execution that owns this scope.
   * @throws IllegalStateException if this scope has already been attached.
   */
  internal fun attach(execution: Execution) {
    check(parent == null) { "ExecutionScope is already attached" }
    parent = execution
  }

  /**
   * Creates and executes a child execution on behalf of the owning execution.
   *
   * The current execution becomes suspended while the child executes.
   * Once the child completes successfully, control returns to the parent
   * execution.
   *
   * Child executions may themselves create additional child executions,
   * allowing the engine to represent arbitrarily deep execution trees,
   * subject to the configured maximum execution depth.
   *
   * @param turnActor the actor that should perform the child execution.
   * @return the completed child execution.
   *
   * @throws CancellationException if the parent or child execution is
   * cancelled.
   * @throws Throwable if the child execution fails.
   * @throws MaximumExecutionDepthExceededException if creating the child
   * would exceed the engine's configured execution depth.
   * @throws IllegalStateException if this scope has not been attached to
   * an execution.
   */
  suspend fun execute(turnActor: TurnActor): Execution {
    val current = requireNotNull(parent) { "ExecutionScope is not attached" }
    return engine.executeChild(current, turnActor)
  }
}

/**
 * Immutable context associated with an [Execution].
 *
 * [TurnContext] is supplied to the execution handler and provides all
 * information required to understand the execution's position within the
 * turn and execution hierarchy.
 *
 * The context deliberately contains the [ExecutionScope], allowing the
 * handler to request child executions while keeping the engine's internal
 * lifecycle management encapsulated.
 *
 * @property turnActor the actor responsible for this execution.
 * @property turnId the turn in which this execution originated.
 * @property executionId the unique identifier of this execution.
 * @property depth the execution's depth within the execution tree.
 * A root execution has depth `0`, its children have depth `1`, and so on.
 * @property parent the execution that created this execution, or `null`
 * when this is a root execution.
 * @property scope the scope through which this execution may create child
 * executions.
 */
data class TurnContext(
  val turnActor: TurnActor,
  val turnId: TurnId,
  val executionId: ExecutionId,
  val depth: Int,
  val parent: Execution? = null,
  val scope: ExecutionScope
)

/**
 * Represents one execution performed by the [TurnsEngine].
 *
 * An execution is the fundamental runtime unit used by the engine to process
 * an actor's action.
 *
 * Executions form a tree:
 *
 * ```
 * Root execution
 * ├── Child execution
 * │   └── Grandchild execution
 * └── Child execution
 * ```
 *
 * The root execution represents the normal execution of a turn. Child
 * executions represent additional actions that must be performed by other
 * actors before the parent action can continue.
 *
 * An execution transitions through [ExecutionState] values during its
 * lifecycle. Only the engine can mutate the execution state.
 *
 * @property context immutable contextual information associated with the
 * execution.
 */
class Execution internal constructor(val context: TurnContext) {
  /**
   * The value returned by the execution handler.
   *
   * This property remains `null` until the execution completes. A `null`
   * value may also represent a legitimate result produced by the handler.
   */
  var result: Any? = null
    private set

  /**
   * The current lifecycle state of the execution.
   *
   * The property is publicly readable but can only be modified internally
   * by the engine.
   */
  var state: ExecutionState = ExecutionState.RUNNING
    internal set

  /**
   * The exception that caused this execution to fail, if any.
   *
   * This property is `null` for executions that have not failed.
   */
  var failure: Throwable? = null
    internal set

  /**
   * The child executions currently associated with this execution.
   *
   * This collection is internal because execution-tree mutation is owned
   * exclusively by the engine.
   */
  internal val children = mutableListOf<Execution>()

  /**
   * The unique identifier of this execution.
   */
  val id: ExecutionId
    get() = context.executionId

  /**
   * The actor responsible for this execution.
   */
  val turnActor: TurnActor
    get() = context.turnActor

  /**
   * The turn from which this execution originated.
   */
  val turnId: TurnId get() = context.turnId

  /**
   * The depth of this execution within the execution tree.
   *
   * A root execution has depth `0`.
   */
  val depth: Int
    get() = context.depth

  /**
   * The parent execution, or `null` when this is a root execution.
   */
  val parent: Execution?
    get() = context.parent

  /**
   * The scope associated with this execution.
   *
   * The scope allows the execution handler to create child executions.
   */
  val scope: ExecutionScope
    get() = context.scope

  /**
   * Registers a child execution under this execution.
   *
   * @param child the child execution to associate with this execution.
   */
  internal fun attachChild(child: Execution) {
    children += child
  }

  /**
   * Removes a child execution from this execution.
   *
   * @param child the child execution to detach.
   */
  internal fun detachChild(child: Execution) {
    children -= child
  }

  /**
   * Suspends this execution while a child execution is running.
   *
   * An execution that is already in another state is not modified.
   */
  internal fun suspendForChild() {
    if (state == ExecutionState.RUNNING) {
      state = ExecutionState.SUSPENDED
    }
  }

  /**
   * Resumes this execution after a child execution has completed.
   *
   * Only a suspended execution can be resumed by this operation.
   */
  internal fun resumeAfterChild() {
    if (state == ExecutionState.SUSPENDED) {
      state = ExecutionState.RUNNING
    }
  }

  /**
   * Cancels this execution and all of its children.
   *
   * Terminal executions are not modified.
   */
  internal fun cancel() {
    if (isTerminal) return

    state = ExecutionState.CANCELLED
    children.toList().forEach(Execution::cancel)
  }

  /**
   * Marks this execution as failed and propagates the failure to its
   * children.
   *
   * Terminal executions are not modified.
   *
   * @param t the exception responsible for the failure.
   */
  internal fun fail(t: Throwable) {
    if (isTerminal) return

    state = ExecutionState.FAILED
    failure = t
    children.toList().forEach { it.fail(t) }
  }

  /**
   * Marks this execution as successfully completed.
   *
   * Terminal executions are not modified.
   *
   * @param value the result produced by the execution handler.
   */
  internal fun complete(value: Any?) {
    if (isTerminal) return

    result = value
    state = ExecutionState.COMPLETED
  }

  /**
   * Indicates whether the execution has reached a terminal state.
   */
  private val isTerminal: Boolean
    get() =
      state == ExecutionState.COMPLETED ||
          state == ExecutionState.FAILED ||
          state == ExecutionState.CANCELLED

  /**
   * Executions are identified exclusively by their [ExecutionId].
   *
   * @param other the object to compare with this execution.
   * @return `true` when both objects represent the same execution identifier.
   */
  override fun equals(other: Any?): Boolean =
    other is Execution && other.id == id

  /**
   * Returns the hash code derived from the execution identifier.
   */
  override fun hashCode(): Int = id.hashCode()

  /**
   * Returns a concise representation containing the execution identifier.
   */
  override fun toString(): String = "Execution(${id.value})"
}

/**
 * Defines how a sequence of turns is generated and manipulated.
 *
 * A [TurnFlow] is responsible only for turn progression. It does not execute
 * actions and does not know how an actor's action is implemented.
 *
 * This separation allows the same execution engine to work with different
 * turn-order strategies, such as:
 *
 * - round-robin turns;
 * - initiative-based turns;
 * - priority queues;
 * - scripted sequences;
 * - dynamically generated turns;
 * - custom game-specific scheduling rules.
 */
interface TurnFlow {

  /**
   * Produces the next executable turn.
   *
   * The implementation may inspect each candidate turn using [isEligible].
   * A turn that is not eligible is skipped.
   *
   * @param isEligible predicate used to determine whether a generated turn
   * can currently execute. Defaults to accepting every turn.
   * @return the next eligible turn.
   *
   * @throws NoExecutableTurnException when no executable turn can be found.
   * @throws IllegalStateException when the flow has already ended.
   */
  fun next(
    isEligible: (Turn) -> Boolean = { true }
  ): Turn

  /**
   * Indicates whether this flow has permanently ended.
   *
   * @return `true` when no additional turns should be generated.
   */
  fun isEnded(): Boolean

  /**
   * Applies a decision produced after executing a turn.
   *
   * The interpretation of the decision is implementation-specific.
   *
   * @param decision the decision that should modify the progression of
   * the flow.
   */
  fun apply(decision: FlowDecision)
}

/**
 * A [TurnFlow] implementation that cycles through actors sequentially.
 *
 * Actors are visited in their configured order and the sequence wraps around
 * when the end of the collection is reached.
 *
 * For example, given actors `A`, `B`, and `C`, the normal sequence is:
 *
 * `A -> B -> C -> A -> B -> C -> ...`
 *
 * The flow can be modified dynamically through [FlowDecision], allowing
 * actors to repeat, be inserted, skipped, or selected directly.
 *
 * @param turnActors the initial ordered collection of actors participating in
 * the flow.
 *
 * @throws IllegalArgumentException if [turnActors] is empty.
 */
class RoundRobinTurnFlow(
  turnActors: List<TurnActor>
) : TurnFlow {

  private val actors = turnActors.toMutableList()
  private var index = 0
  private var nextTurnId = 0L
  private var lastIndex = 0
  private var ended = false

  init {
    require(turnActors.isNotEmpty()) {
      "RoundRobinTurnFlow requires at least one actor"
    }
  }

  /**
   * Produces the next eligible turn in round-robin order.
   *
   * At most one complete pass through the current actor list is attempted.
   * This prevents the method from looping indefinitely when no actor is
   * currently eligible.
   *
   * @param isEligible predicate used to determine whether each generated
   * turn can execute.
   * @return the next eligible turn.
   *
   * @throws IllegalStateException if the flow has ended.
   * @throws NoExecutableTurnException if every actor opportunity checked
   * during this call was rejected by [isEligible].
   */
  override fun next(
    isEligible: (Turn) -> Boolean
  ): Turn {
    check(!ended) {
      "TurnFlow has ended"
    }

    repeat(actors.size) {
      lastIndex = index

      val actor = actors[index]
      index = (index + 1) % actors.size

      val turn = Turn(
        TurnId(nextTurnId++),
        actor
      )

      if (isEligible(turn)) {
        return turn
      }
    }

    throw NoExecutableTurnException(actors.size)
  }

  /**
   * Indicates whether this flow has been explicitly ended.
   *
   * @return `true` after [FlowDecision.End] has been applied.
   */
  override fun isEnded(): Boolean = ended

  /**
   * Applies a flow decision to the current round-robin position.
   *
   * The decisions are interpreted as follows:
   *
   * - [FlowDecision.Continue] does nothing.
   * - [FlowDecision.Repeat] returns the index to the actor that just acted.
   * - [FlowDecision.Insert] adds an actor at the current index.
   * - [FlowDecision.Skip] advances one additional position.
   * - [FlowDecision.JumpTo] moves directly to the requested actor.
   * - [FlowDecision.End] permanently ends the flow.
   *
   * @param decision the decision to apply.
   *
   * @throws IllegalArgumentException when [FlowDecision.JumpTo] references
   * an actor that does not exist in the flow.
   */
  override fun apply(decision: FlowDecision) {
    when (decision) {
      FlowDecision.Continue -> Unit

      FlowDecision.Repeat ->
        index = lastIndex

      is FlowDecision.Insert ->
        actors.add(index, decision.turnActor)

      FlowDecision.Skip ->
        index = (index + 1) % actors.size

      is FlowDecision.JumpTo -> {
        val target = actors.indexOf(decision.turnActor)

        require(target >= 0) {
          "Actor not found in TurnFlow"
        }

        index = target
      }

      FlowDecision.End ->
        ended = true
    }
  }
}

/**
 * Thrown when a [TurnFlow] cannot produce an eligible turn.
 *
 * This exception normally indicates that all currently available turn
 * opportunities were rejected by the engine's eligibility predicate.
 *
 * @param attempts the number of turn opportunities checked before failure.
 */
class NoExecutableTurnException(attempts: Int) :
  IllegalStateException("No executable turn found after checking $attempts turn opportunities")

/**
 * Thrown when creating an execution would exceed the configured execution
 * depth limit.
 *
 * Execution depth starts at zero for root executions. Therefore, a maximum
 * depth of `0` permits root executions but prohibits child executions.
 *
 * @param maximumExecutionDepth the maximum depth permitted by the engine.
 * @param attemptedDepth the depth that the engine attempted to create.
 */
class MaximumExecutionDepthExceededException(
  maximumExecutionDepth: Int,
  attemptedDepth: Int
) : IllegalStateException(
  "Maximum execution depth exceeded: " +
      "maximum=$maximumExecutionDepth attempted=$attemptedDepth"
)

/**
 * Represents an observable event emitted by the turn engine.
 *
 * [TurnEvent] provides a unified event model for monitoring, logging,
 * debugging, replay systems, user interfaces, analytics, and other
 * external observers.
 *
 * Events do not control the engine. They describe state transitions and
 * lifecycle events that have already occurred.
 */
sealed class TurnEvent {
  /**
   * Emitted when a new turn begins.
   *
   * @property turn the turn that has started.
   */
  data class TurnStarted(
    val turn: Turn
  ) : TurnEvent()

  /**
   * Emitted when an execution begins running.
   *
   * @property turnId the turn associated with the execution.
   * @property executionId the unique identifier of the execution.
   * @property turnActor the actor performing the execution.
   * @property depth the execution's depth within the execution tree.
   * @property parentId the identifier of the parent execution, or `null`
   * for a root execution.
   */
  data class ExecutionStarted(
    val turnId: TurnId,
    val executionId: ExecutionId,
    val turnActor: TurnActor,
    val depth: Int,
    val parentId: ExecutionId?
  ) : TurnEvent()

  /**
   * Emitted when an execution completes successfully.
   *
   * @property turnId the turn associated with the execution.
   * @property executionId the completed execution.
   * @property result the value returned by the execution handler.
   */
  data class ExecutionCompleted(
    val turnId: TurnId,
    val executionId: ExecutionId,
    val result: Any?
  ) : TurnEvent()

  /**
   * Emitted when an execution fails because its handler throws an exception.
   *
   * @property turnId the turn associated with the failed execution.
   * @property executionId the failed execution.
   * @property failure the exception that caused the failure.
   */
  data class ExecutionFailed(
    val turnId: TurnId,
    val executionId: ExecutionId,
    val failure: Throwable
  ) : TurnEvent()

  /**
   * Emitted when an execution is cancelled.
   *
   * @property turnId the turn associated with the cancelled execution.
   * @property executionId the cancelled execution.
   */
  data class ExecutionCancelled(
    val turnId: TurnId,
    val executionId: ExecutionId
  ) : TurnEvent()

  /**
   * Emitted after a [FlowDecision] has been applied to the turn flow.
   *
   * @property turnId the turn whose execution produced the decision.
   * @property decision the decision applied to the flow.
   */
  data class FlowDecisionApplied(
    val turnId: TurnId,
    val decision: FlowDecision
  ) : TurnEvent()

  /**
   * Emitted when the turn flow reaches its terminal state.
   *
   * @property turnId the turn during which the flow ended.
   */
  data class TurnFlowEnded(
    val turnId: TurnId
  ) : TurnEvent()

  /**
   * Emitted whenever the runtime changes its lifecycle state.
   *
   * @property from the previous runtime state.
   * @property to the new runtime state.
   */
  data class RuntimeStateChanged(
    val from: RuntimeState,
    val to: RuntimeState
  ) : TurnEvent()
}

/**
 * Receives events produced by the turn system.
 *
 * Event sinks are intentionally passive. They observe the engine without
 * participating in turn resolution.
 */
fun interface TurnEventSink {

  /**
   * Receives one engine event.
   *
   * @param event the event that occurred.
   */
  fun onEvent(event: TurnEvent)
}

/**
 * Represents the current operational state of a [TurnsEngine].
 *
 * A snapshot is a read-only representation intended for inspection,
 * debugging, interfaces, persistence, or external monitoring.
 *
 * @property currentTurn the currently executing root turn, if one exists.
 * @property currentTurnActor the actor associated with the deepest active
 * execution, or the current turn's actor when no execution is active.
 * @property currentExecutionId the deepest currently active execution,
 * or `null` when no execution is active.
 * @property depth the depth of the deepest active execution.
 * @property activeExecutions identifiers of executions currently active.
 * @property flowEnded whether the underlying turn flow has ended.
 */
data class TurnsSnapshot(
  val currentTurn: Turn?,
  val currentTurnActor: TurnActor?,
  val currentExecutionId: ExecutionId?,
  val depth: Int,
  val activeExecutions: List<ExecutionId>,
  val flowEnded: Boolean
)

/**
 * Represents the lifecycle state of a [TurnsRuntime].
 *
 * The runtime controls whether the engine is actively processing turns.
 */
enum class RuntimeState {

  /**
   * The runtime has not started yet.
   */
  IDLE,

  /**
   * The runtime is actively processing turns.
   */
  RUNNING,

  /**
   * The runtime is temporarily stopped between turns.
   */
  PAUSED,

  /**
   * The runtime will no longer process additional turns.
   */
  FINISHED
}

/**
 * Combined snapshot of the engine and runtime states.
 *
 * This object provides a single read-only representation of the state of
 * the complete turn-processing runtime.
 *
 * @property engine snapshot of the underlying [TurnsEngine].
 * @property state current lifecycle state of the runtime.
 */
data class RuntimeSnapshot(
  val engine: TurnsSnapshot,
  val state: RuntimeState
)

/**
 * Coordinates turn generation, execution, nested execution, and flow
 * decisions.
 *
 * [TurnsEngine] is the central execution component of the library.
 *
 * It deliberately separates several responsibilities:
 *
 * 1. [TurnFlow] determines which actor receives the next turn.
 * 2. [isEligible] determines whether a generated turn can currently execute.
 * 3. [handler] defines what actually happens during an execution.
 * 4. [ExecutionScope] allows executions to create child executions.
 * 5. [FlowDecision] allows an execution to influence future turn progression.
 * 6. [TurnEventSink] exposes lifecycle events to external observers.
 *
 * The engine itself does not know the rules of a particular game. It can
 * therefore be used for games and systems with very different notions of
 * turns, actions, reactions, and nested interactions.
 *
 * A typical execution hierarchy can be represented as:
 *
 * ```
 * Turn
 * └── Root Execution
 *     ├── Child Execution
 *     │   └── Child Execution
 *     └── Child Execution
 * ```
 *
 * A parent execution is suspended while its child is executing. Once the
 * child completes, control returns to the parent.
 *
 * @param flow strategy responsible for producing and manipulating turns.
 * @param isEligible predicate used to determine whether a generated turn
 * can currently execute. Defaults to accepting every turn.
 * @param handler suspendable function responsible for performing the actual
 * work of an execution. Its return value becomes the execution result.
 * @param maximumExecutionDepth maximum allowed depth for nested executions.
 * A value of `0` permits only root executions.
 * @param events sink that receives lifecycle events generated by the engine.
 */
class TurnsEngine(
  val handler: suspend (TurnContext) -> Any? = suspend {},
  private val flow: TurnFlow,
  private val isEligible: (Turn) -> Boolean = { true },
  private val maximumExecutionDepth: Int = Int.MAX_VALUE,
  private val events: TurnEventSink = TurnEventSink {}
) {
  init {
    require(maximumExecutionDepth >= 0) { "maximumExecutionDepth must be non-negative" }
  }

  private var nextExecutionId = 0L
  private var currentTurn: Turn? = null
  private val activeExecutions = linkedSetOf<Execution>()

  /**
   * Creates a read-only snapshot of the engine's current state.
   *
   * When nested executions are active, the deepest active execution is used
   * as the current execution represented by the snapshot.
   *
   * @return the current engine state.
   */
  fun snapshot(): TurnsSnapshot {
    val deepest = activeExecutions.maxByOrNull(Execution::depth)

    return TurnsSnapshot(
      currentTurn = currentTurn,
      currentTurnActor = deepest?.turnActor ?: currentTurn?.turnActor,
      currentExecutionId = deepest?.id,
      depth = deepest?.depth ?: 0,
      activeExecutions = activeExecutions.map(Execution::id),
      flowEnded = flow.isEnded()
    )
  }

  /**
   * Executes the next available turn.
   *
   * The method performs the complete root-execution lifecycle:
   *
   * 1. requests a turn from the [TurnFlow];
   * 2. creates the root [Execution];
   * 3. emits [TurnEvent.TurnStarted];
   * 4. executes the handler;
   * 5. processes nested executions if requested by the handler;
   * 6. interprets a returned [FlowDecision];
   * 7. applies that decision to the flow;
   * 8. emits a flow-ended event when appropriate.
   *
   * The returned execution is the root execution associated with the turn.
   *
   * @return the completed root execution.
   *
   * @throws NoExecutableTurnException if the flow cannot produce an
   * eligible turn.
   * @throws Throwable if the execution handler fails.
   */
  suspend fun executeNextTurn(): Execution {
    val turn = flow.next(isEligible)
    val execution = createExecution(turn.turnActor, turn.id, 0, null)

    currentTurn = turn
    events.onEvent(TurnEvent.TurnStarted(turn))
    runExecution(execution)

    val decision = execution.result as? FlowDecision
    if (decision != null) {
      flow.apply(decision)
      events.onEvent(TurnEvent.FlowDecisionApplied(turn.id, decision))
    }

    if (flow.isEnded()) events.onEvent(TurnEvent.TurnFlowEnded(turn.id))
    return execution
  }

  /**
   * Indicates whether the configured turn flow has ended.
   *
   * @return `true` when the flow cannot produce additional turns.
   */
  fun isFlowEnded(): Boolean = flow.isEnded()

  /**
   * Creates and executes a child execution.
   *
   * The child inherits the parent's [TurnId] but receives its own
   * [ExecutionId] and a depth one greater than the parent's.
   *
   * While the child is running, the parent is placed into
   * [ExecutionState.SUSPENDED].
   *
   * Failures and cancellation propagate to the parent execution.
   *
   * @param parent execution requesting the child execution.
   * @param turnActor actor responsible for the child execution.
   * @return the completed child execution.
   *
   * @throws CancellationException when the parent or child is cancelled.
   * @throws Throwable when the child execution fails.
   * @throws MaximumExecutionDepthExceededException when the configured
   * execution depth would be exceeded.
   */
  internal suspend fun executeChild(
    parent: Execution,
    turnActor: TurnActor
  ): Execution {
    ensureCanCreateChild(parent)

    val execution = createExecution(
      turnActor = turnActor,
      turnId = parent.turnId,
      depth = parent.depth + 1,
      parent = parent
    )

    parent.attachChild(execution)
    parent.suspendForChild()

    try {
      runExecution(execution)
      ensureParentCanResume(parent)
      parent.resumeAfterChild()

      return execution
    } catch (e: CancellationException) {
      parent.cancel()
      throw e
    } catch (e: Throwable) {
      parent.fail(e)
      throw e
    } finally {
      parent.detachChild(execution)

      if (parent.state == ExecutionState.SUSPENDED) {
        parent.resumeAfterChild()
      }
    }
  }

  /**
   * Creates a new execution and its associated execution scope.
   *
   * @param turnActor actor responsible for the new execution.
   * @param turnId turn associated with the execution.
   * @param depth depth of the execution within the execution tree.
   * @param parent parent execution, or `null` for a root execution.
   * @return the newly created execution.
   */
  private fun createExecution(
    turnActor: TurnActor,
    turnId: TurnId,
    depth: Int,
    parent: Execution?
  ): Execution {
    val scope = ExecutionScope(this)

    val execution = Execution(
      TurnContext(
        turnActor = turnActor,
        turnId = turnId,
        executionId = ExecutionId(nextExecutionId++),
        depth = depth,
        parent = parent,
        scope = scope
      )
    )

    scope.attach(execution)

    return execution
  }

  /**
   * Verifies whether a parent execution is allowed to create a child.
   *
   * @param parent execution requesting the child.
   *
   * @throws CancellationException when the parent is cancelled.
   * @throws Throwable when the parent has already failed.
   * @throws MaximumExecutionDepthExceededException when the requested child
   * would exceed the configured maximum depth.
   */
  private fun ensureCanCreateChild(parent: Execution) {
    if (parent.state == ExecutionState.CANCELLED) {
      throw CancellationException("Parent cancelled")
    }

    if (parent.state == ExecutionState.FAILED) {
      throw parent.failure
        ?: IllegalStateException("Parent failed")
    }

    val attemptedDepth = parent.depth + 1

    if (attemptedDepth > maximumExecutionDepth) {
      throw MaximumExecutionDepthExceededException(
        maximumExecutionDepth,
        attemptedDepth
      )
    }
  }

  /**
   * Verifies whether a parent execution may resume after its child finishes.
   *
   * @param parent execution that should resume.
   *
   * @throws CancellationException when the parent was cancelled.
   * @throws Throwable when the parent has failed.
   */
  private fun ensureParentCanResume(parent: Execution) {
    if (parent.state == ExecutionState.CANCELLED) {
      throw CancellationException("Parent cancelled")
    }

    if (parent.state == ExecutionState.FAILED) {
      throw parent.failure
        ?: IllegalStateException("Parent failed")
    }
  }

  /**
   * Runs a single execution through its complete lifecycle.
   *
   * The method invokes the configured [handler], records the resulting
   * state, emits the corresponding lifecycle event, and removes the
   * execution from the active execution set when finished.
   *
   * @param execution execution to run.
   *
   * @throws CancellationException when execution is cancelled.
   * @throws Throwable when the execution handler throws.
   */
  private suspend fun runExecution(execution: Execution) {
    ensureExecutionCanRun(execution)

    execution.state = ExecutionState.RUNNING
    activeExecutions += execution

    events.onEvent(
      TurnEvent.ExecutionStarted(
        turnId = execution.turnId,
        executionId = execution.id,
        turnActor = execution.turnActor,
        depth = execution.depth,
        parentId = execution.parent?.id
      )
    )

    try {
      val result = handler(execution.context)

      ensureExecutionCompletedNormally(execution)

      execution.complete(result)

      events.onEvent(
        TurnEvent.ExecutionCompleted(
          turnId = execution.turnId,
          executionId = execution.id,
          result = result
        )
      )
    } catch (e: CancellationException) {
      execution.cancel()

      events.onEvent(
        TurnEvent.ExecutionCancelled(
          turnId = execution.turnId,
          executionId = execution.id
        )
      )

      throw e
    } catch (e: Throwable) {
      execution.fail(e)

      events.onEvent(
        TurnEvent.ExecutionFailed(
          turnId = execution.turnId,
          executionId = execution.id,
          failure = e
        )
      )

      throw e
    } finally {
      activeExecutions -= execution
    }
  }

  /**
   * Verifies that an execution is in a state that permits execution.
   *
   * @param execution execution to validate.
   *
   * @throws CancellationException when the execution is cancelled.
   * @throws Throwable when the execution has failed.
   */
  private fun ensureExecutionCanRun(execution: Execution) {
    if (execution.state == ExecutionState.CANCELLED) {
      throw CancellationException("Execution cancelled")
    }

    if (execution.state == ExecutionState.FAILED) {
      throw execution.failure
        ?: IllegalStateException("Execution failed")
    }
  }

  /**
   * Verifies that an execution remained valid throughout its handler call.
   *
   * This check prevents an execution from being completed normally after
   * another operation has already moved it into a terminal state.
   *
   * @param execution execution whose state should be validated.
   *
   * @throws CancellationException when the execution was cancelled.
   * @throws Throwable when the execution failed.
   */
  private fun ensureExecutionCompletedNormally(execution: Execution) {
    if (execution.state == ExecutionState.CANCELLED) {
      throw CancellationException("Execution cancelled")
    }

    if (execution.state == ExecutionState.FAILED) {
      throw execution.failure
        ?: IllegalStateException("Execution failed")
    }
  }
}

/**
 * High-level lifecycle controller for a [TurnsEngine].
 *
 * [TurnsRuntime] repeatedly asks the engine to execute turns while its state
 * is [RuntimeState.RUNNING].
 *
 * It provides a convenient continuous execution loop on top of the lower
 * level [TurnsEngine] API.
 *
 * The runtime and engine have intentionally different responsibilities:
 *
 * - [TurnsEngine] processes one turn and manages execution trees.
 * - [TurnsRuntime] decides whether the engine should keep processing turns.
 *
 * Pausing affects the runtime loop but does not alter the underlying
 * [TurnsEngine] flow.
 *
 * @param engine engine that performs the actual turn processing.
 * @param events sink receiving runtime lifecycle events.
 */
class TurnsRuntime(
  private val engine: TurnsEngine,
  private val events: TurnEventSink = TurnEventSink {}
) {

  /**
   * Current lifecycle state of the runtime.
   *
   * The state can be observed publicly but is controlled exclusively by
   * the runtime itself.
   */
  var state: RuntimeState = RuntimeState.IDLE
    private set

  /**
   * Creates a combined snapshot of runtime and engine state.
   *
   * @return the current runtime snapshot.
   */
  fun snapshot(): RuntimeSnapshot =
    RuntimeSnapshot(
      engine = engine.snapshot(),
      state = state
    )

  /**
   * Changes the runtime state and emits a state-change event.
   *
   * Repeated transitions to the same state are ignored.
   *
   * @param next the state to transition to.
   */
  private fun transition(next: RuntimeState) {
    if (next == state) return

    val previous = state
    state = next

    events.onEvent(
      TurnEvent.RuntimeStateChanged(
        previous,
        next
      )
    )
  }

  /**
   * Starts the runtime and continuously processes turns until the runtime
   * reaches [RuntimeState.FINISHED].
   *
   * The method suspends between iterations using [yield], allowing other
   * coroutines to execute.
   *
   * Once the underlying engine's flow ends, the runtime transitions to
   * [RuntimeState.FINISHED].
   *
   * @throws IllegalStateException if the runtime has already been started
   * or finished.
   * @throws Throwable if execution of a turn fails.
   */
  suspend fun start() {
    check(state == RuntimeState.IDLE) {
      "TurnRuntime cannot start from $state"
    }

    transition(RuntimeState.RUNNING)

    while (
      state == RuntimeState.RUNNING ||
      state == RuntimeState.PAUSED
    ) {
      yield()

      if (state == RuntimeState.PAUSED) {
        continue
      }

      if (engine.isFlowEnded()) {
        transition(RuntimeState.FINISHED)
        continue
      }

      engine.executeNextTurn()
    }
  }

  /**
   * Pauses the runtime.
   *
   * If the runtime is currently [RuntimeState.RUNNING], no additional turn
   * will be started by the runtime loop until [resume] is called.
   *
   * Calling this method in any other state has no effect.
   */
  fun pause() {
    if (state == RuntimeState.RUNNING) {
      transition(RuntimeState.PAUSED)
    }
  }

  /**
   * Resumes a paused runtime.
   *
   * Calling this method when the runtime is not paused has no effect.
   */
  fun resume() {
    if (state == RuntimeState.PAUSED) {
      transition(RuntimeState.RUNNING)
    }
  }

  /**
   * Permanently stops the runtime.
   *
   * The underlying [TurnsEngine] is not modified; only the runtime lifecycle
   * is transitioned to [RuntimeState.FINISHED].
   *
   * Calling this method when the runtime is already idle or finished has no
   * effect.
   */
  fun stop() {
    if (
      state == RuntimeState.RUNNING ||
      state == RuntimeState.PAUSED
    ) {
      transition(RuntimeState.FINISHED)
    }
  }
}