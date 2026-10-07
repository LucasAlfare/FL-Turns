# FL Turns — API pública

**Package:** `com.lucasalfare.flturns`

## 1. Propósito

A FL Turns é uma engine genérica para **gerenciamento de turnos e execução de ações encadeadas**.

Ela resolve quatro problemas principais:

* determinar **quem recebe o próximo turno**;
* executar a ação daquele ator;
* permitir que uma execução gere **execuções filhas**, suspendendo a execução pai;
* decidir como o fluxo de turnos continua depois da execução.

A biblioteca **não conhece regras de jogo**.

Ela não sabe o que é:

* ataque;
* defesa;
* dano;
* carta;
* magia;
* jogador;
* inimigo;
* recurso;
* vitória;
* animação;
* input.

Esses significados ficam na aplicação.

---

# 2. Modelo mental

A estrutura fundamental é:

**TurnFlow → Turn → Execution → Execution Tree**

Ou seja:

**Fluxo de turnos**
→ escolhe um ator
→ produz um `Turn`
→ cria uma `Execution`
→ o handler executa a ação
→ a execução pode criar filhos
→ ao terminar, pode retornar uma `FlowDecision`
→ o fluxo determina o próximo turno.

Uma execução pode formar uma árvore:

`Turn`
→ `Root Execution`
→ `Child Execution`
→ `Grandchild Execution`

Isso é particularmente importante para situações como **ações que precisam da resposta/intervenção de outro ator**.

---

# 3. Identificadores

### `TurnActor`

Representa quem está executando.

```text
TurnActor("player")
TurnActor("goblin")
TurnActor("player-2")
```

A biblioteca não interpreta o `id`.

---

### `TurnId`

Identifica exclusivamente um turno.

Um mesmo ator pode possuir vários `TurnId`s ao longo do jogo.

---

### `ExecutionId`

Identifica exclusivamente uma execução.

Cada execução recebe seu próprio ID, inclusive execuções filhas.

---

# 4. `Turn`

Representa uma **oportunidade de ação**.

Possui:

* `id: TurnId`
* `turnActor: TurnActor`

Importante:

**Turn não é a ação.**

É apenas a oportunidade dada a determinado ator para executar alguma coisa.

---

# 5. `TurnFlow`

É a abstração responsável por determinar **a sequência de turnos**.

Principais operações:

### `next(isEligible)`

Obtém o próximo `Turn` que pode executar.

A aplicação pode fornecer uma função de elegibilidade:

`Turn → Boolean`

Isso permite, por exemplo, que um ator existente no fluxo seja temporariamente incapaz de agir.

### `apply(decision)`

Modifica o fluxo depois que um turno terminou.

### `isEnded()`

Informa se o fluxo terminou definitivamente.

---

# 6. `RoundRobinTurnFlow`

Implementação pronta de `TurnFlow`.

Dado:

`A, B, C`

produz:

`A → B → C → A → B → C...`

O fluxo também pode ser alterado dinamicamente.

### `FlowDecision.Continue`

Continua normalmente.

`A → B → C`

### `FlowDecision.Repeat`

Repete o ator que acabou de agir.

`A → A → B`

### `FlowDecision.Insert(actor)`

Insere um novo ator no fluxo.

### `FlowDecision.Skip`

Pula a próxima oportunidade.

### `FlowDecision.JumpTo(actor)`

Move diretamente para determinado ator.

O ator precisa existir no fluxo.

### `FlowDecision.End`

Encerra permanentemente o fluxo.

---

# 7. `Execution`

É a unidade real de **execução de uma ação**.

Uma `Execution` possui:

* `id`
* `turnActor`
* `turnId`
* `depth`
* `parent`
* `scope`
* `result`
* `state`
* `failure`

Também permite consultar sua posição na árvore de execuções.

### Estados

`ExecutionState`:

* `RUNNING`
* `SUSPENDED`
* `COMPLETED`
* `FAILED`
* `CANCELLED`

A aplicação consegue **observar** esses estados, mas não modificá-los diretamente.

---

# 8. Execuções aninhadas

Essa é uma das partes mais importantes da FL Turns.

Uma execução recebe um:

`ExecutionScope`

E pode fazer:

**executar outro ator**

O resultado é uma nova `Execution` filha.

Durante isso:

`Parent → SUSPENDED`

e:

`Child → RUNNING`

Quando o filho termina:

`Child → COMPLETED`

e:

`Parent → RUNNING`

Isso pode continuar recursivamente.

Exemplo conceitual:

```text
Jogador usa habilidade
└── inimigo precisa responder
    └── outro efeito precisa ser resolvido
        └── terceiro ator precisa reagir
```

A FL Turns fornece a estrutura dessa cadeia, mas **não define o significado dessas ações**.

---

# 9. `TurnContext`

É o contexto fornecido ao handler.

Contém:

* `turnActor`
* `turnId`
* `executionId`
* `depth`
* `parent`
* `scope`

Portanto, dentro de uma ação, a aplicação consegue saber:

**quem está agindo → em qual turno → em qual execução → em qual profundidade → quem chamou essa execução → como criar
uma execução filha.**

---

# 10. `TurnsEngine`

É o **núcleo principal da biblioteca**.

Configuração:

* `handler`
* `flow`
* `isEligible`
* `maximumExecutionDepth`
* `events`

### Handler

O handler é:

`suspend (TurnContext) -> Any?`

É aqui que **o jogo fornece o significado da execução**.

A FL Turns chama o handler.

O jogo decide o que fazer.

O retorno do handler pode ser qualquer coisa.

Porém existe uma convenção especial:

**se o resultado for `FlowDecision`, ele será interpretado pelo engine como uma decisão sobre o fluxo.**

Isso permite que uma ação retorne, por exemplo:

`FlowDecision.Repeat`

sem o handler precisar conhecer diretamente o `TurnFlow`.

---

# 11. `executeNextTurn()`

É a operação fundamental do engine.

Executa **um único turno completo**.

Fluxo:

`TurnFlow.next()`

→ cria `Execution`

→ `TurnStarted`

→ executa handler

→ resolve possíveis execuções filhas

→ interpreta `FlowDecision`

→ aplica decisão ao fluxo

→ emite eventos correspondentes

→ retorna a `Execution` raiz.

Isso é provavelmente a API mais importante para o jogo quando quisermos controlar o processamento manualmente.

---

# 12. `TurnsRuntime`

É uma camada superior ao `TurnsEngine`.

A diferença fundamental:

### `TurnsEngine`

Executa **um turno**.

### `TurnsRuntime`

Fica executando turnos continuamente.

Seu ciclo é:

`RUNNING`

→ `executeNextTurn()`

→ próximo turno

→ `executeNextTurn()`

→ próximo turno...

até o fluxo terminar.

---

# 13. `RuntimeState`

Estados do runtime:

* `IDLE`
* `RUNNING`
* `PAUSED`
* `FINISHED`

Operações públicas:

### `start()`

Inicia o loop contínuo.

### `pause()`

Pausa entre turnos.

### `resume()`

Continua um runtime pausado.

### `stop()`

Finaliza o runtime.

Importante:

**pausar/parar o Runtime não altera o `TurnFlow`.**

Ele apenas controla o processamento contínuo.

---

# 14. Snapshots

A biblioteca oferece duas formas de observar o estado atual.

### `TurnsSnapshot`

Estado do engine:

* turno atual;
* ator atual;
* execução atual;
* profundidade;
* execuções ativas;
* estado do fluxo.

### `RuntimeSnapshot`

Combina:

`TurnsSnapshot + RuntimeState`

Isso é útil principalmente para:

* UI;
* debug;
* monitoramento;
* persistência;
* ferramentas de desenvolvimento.

---

# 15. Eventos

A FL Turns possui um sistema observável através de:

`TurnEventSink`

O consumidor simplesmente recebe:

`onEvent(event)`

Os eventos disponíveis são:

### `TurnStarted`

Um novo turno começou.

### `ExecutionStarted`

Uma execução começou.

### `ExecutionCompleted`

Uma execução terminou normalmente.

Inclui seu resultado.

### `ExecutionFailed`

Uma execução falhou.

Inclui a exceção.

### `ExecutionCancelled`

Uma execução foi cancelada.

### `FlowDecisionApplied`

Uma decisão foi aplicada ao fluxo.

### `TurnFlowEnded`

O fluxo terminou.

### `RuntimeStateChanged`

O runtime mudou de estado.

---

# 16. Exceções públicas

### `NoExecutableTurnException`

O `TurnFlow` não conseguiu encontrar um turno elegível.

### `MaximumExecutionDepthExceededException`

Uma execução tentou criar um filho além do limite configurado.

O limite começa em:

`depth = 0`

Portanto:

`maximumExecutionDepth = 0`

permite execução raiz, mas nenhuma execução filha.

---

# 17. Arquitetura conceitual da biblioteca

A divisão de responsabilidades pode ser resumida assim:

```text
                    ┌──────────────┐
                    │  TurnFlow    │
                    │ "quem agora?"│
                    └──────┬───────┘
                           │
                           ▼
                         Turn
                           │
                           ▼
                    ┌──────────────┐
                    │ TurnsEngine  │
                    │ "execute"    │
                    └──────┬───────┘
                           │
                           ▼
                      TurnContext
                           │
                           ▼
                        Handler
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
          ação normal          child execution
                                     │
                                     ▼
                               Execution Tree
                 │
                 ▼
          FlowDecision
                 │
                 ▼
             TurnFlow
```

---

# 18. O que a FL Turns fornece ao nosso jogo

Na prática, quando começarmos o jogo, essa biblioteca pode cuidar de:

**ordenação temporal de ações + execução hierárquica + continuidade do fluxo.**

Por exemplo, futuramente poderemos representar algo conceitualmente como:

`Jogador A recebe turno`

→ executa ação

→ ação exige resposta de Jogador B

→ Jogador B executa resposta

→ resposta exige reação de Jogador A

→ reação termina

→ Jogador B termina

→ Jogador A termina

→ decisão determina próximo turno.

A FL Turns fornece **toda a estrutura dessa cadeia**, sem precisar saber se isso é combate, diálogo, negociação, carta,
magia, interação social ou qualquer outra coisa.

---

## Referência rápida

| Componente           | Responsabilidade         |
|----------------------|--------------------------|
| `TurnActor`          | Identifica participante  |
| `TurnId`             | Identifica turno         |
| `ExecutionId`        | Identifica execução      |
| `Turn`               | Oportunidade de ação     |
| `TurnFlow`           | Estratégia de progressão |
| `RoundRobinTurnFlow` | Fluxo circular pronto    |
| `FlowDecision`       | Modifica o próximo fluxo |
| `Execution`          | Execução de uma ação     |
| `ExecutionScope`     | Criação de ações filhas  |
| `TurnContext`        | Contexto da ação         |
| `ExecutionState`     | Estado da execução       |
| `TurnsEngine`        | Processamento de turnos  |
| `TurnsRuntime`       | Loop contínuo            |
| `TurnsSnapshot`      | Estado do engine         |
| `RuntimeSnapshot`    | Estado engine + runtime  |
| `TurnEvent`          | Eventos observáveis      |
| `TurnEventSink`      | Consumidor de eventos    |

**Essencialmente:**

> **FL Turns não define o que acontece em um turno. Ela define como ações acontecem, se encadeiam e como o sistema
avança de um turno para outro.**