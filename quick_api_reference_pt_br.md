# FL-Turns — Referência Rápida da API

**Package:** `com.lucasalfare.flturns`

## 1. Identidade

### `TurnActor`

* `id: String`

Identidade de quem executa um turno. O core não interpreta o significado do ator.

### `TurnId`

Identidade de um turno.

### `ExecutionId`

Identidade de uma execução.

---

# 2. Turno

### `Turn`

Representa uma unidade de execução pertencente ao fluxo de turnos.

O turno define **quem executa** e seu comportamento; o significado da ação pertence à aplicação.

---

# 3. Fluxo

### `TurnFlow`

Interface responsável por determinar a sequência normal dos turnos.

O fluxo controla a ordem entre turnos-raiz, mas não controla execuções filhas.

### `RoundRobinTurnFlow`

Implementação padrão de fluxo circular.

Percorre os atores em ordem e volta ao primeiro após o último.

---

# 4. Decisão de fluxo

### `FlowDecision`

Define o que acontece depois da execução de um turno.

Variantes:

* `Continue`
* `Repeat`
* `Insert`
* `Skip`
* `JumpTo`
* `End`

### `Continue`

Prossegue para o próximo turno normal.

### `Repeat`

Executa novamente o turno atual.

### `Insert`

Insere uma nova execução no fluxo conforme a decisão.

### `Skip`

Pula a próxima posição aplicável do fluxo.

### `JumpTo`

Salta para um turno específico.

### `End`

Finaliza o fluxo.

---

# 5. Execução

### `Execution`

Representa uma execução concreta de um turno.

Possui sua própria identidade e estado de execução.

Execuções formam uma árvore quando uma execução cria uma execução filha.

---

### `ExecutionScope`

Escopo disponível durante uma execução.

### `execute(...)`

Cria/executa uma execução filha.

A execução filha:

* pertence à execução atual;
* suspende a execução pai enquanto está ativa;
* não avança o `TurnFlow` normal;
* retorna o controle ao pai quando termina.

---

# 6. `ExecutionState`

Estados possíveis:

* `RUNNING`
* `SUSPENDED`
* `COMPLETED`
* `FAILED`
* `CANCELLED`

`SUSPENDED` representa uma execução que aguarda a conclusão de uma execução filha.

---

# 7. `TurnContext`

Contexto recebido durante a execução de um turno.

Contém:

* `turnActor`
* `turnId`
* `executionId`
* `depth`
* `parent`
* `scope`

Permite ao turno conhecer sua execução atual e criar execuções filhas.

---

# 8. Motor

### `TurnsEngine`

Orquestra o sistema de turnos.

Responsável por:

* executar turnos;
* aplicar `FlowDecision`;
* controlar o fluxo normal;
* controlar execuções filhas;
* manter a execução atual;
* impedir avanço normal enquanto existe execução filha ativa.

### Regra principal

Existe somente **um turno-raiz em execução por vez**.

Execuções filhas fazem parte da árvore de execução e não avançam o fluxo normal.

---

# 9. `TurnRuntime`

Responsável pelo loop de execução do motor.

A aplicação não precisa implementar seu próprio `while` para processar turnos.

O runtime mantém a evolução do motor enquanto houver trabalho de execução.

O loop de jogo/renderização da aplicação continua independente.

---

# 10. Eventos

### `TurnEvent`

Representa eventos relacionados à execução do sistema de turnos.

Serve para observar transições do motor sem acoplar o core à apresentação ou às regras do jogo.

---

# 11. Exceções

### `NoExecutableTurnException`

Indica que o fluxo não possui um turno executável quando uma execução é necessária.

### `MaximumExecutionDepthExceededException`

Indica que a profundidade máxima permitida para execuções aninhadas foi excedida.

Protege o motor contra recursão ilimitada de execuções filhas.

---

# 12. Regras fundamentais

### Fluxo normal × árvore de execução

São conceitos separados.

**TurnFlow** determina:

`A → B → C → ...`

**Execution** determina:

`A → execução filha → execução filha da filha → ...`

Uma execução filha não avança o fluxo normal.

---

### Suspensão

Quando uma execução cria uma filha:

`pai = SUSPENDED`

`filha = RUNNING`

Quando a filha termina, o pai pode continuar.

---

### Turno-raiz

Somente a execução-raiz participa do avanço normal do `TurnFlow`.

Execuções internas existem para representar respostas, interrupções, sequências condicionais e outras estruturas de
execução sem alterar a ordem normal dos turnos.

---

# 13. Modelo mental rápido

### Quem executa

`TurnActor`

### O que executa

`Turn`

### Em qual ordem

`TurnFlow`

### Como o fluxo reage

`FlowDecision`

### Execução concreta

`Execution`

### Execução dentro de execução

`ExecutionScope`

### Estado da execução

`ExecutionState`

### Informações disponíveis ao turno

`TurnContext`

### Coordenação

`TurnsEngine`

### Loop de processamento

`TurnRuntime`

### Observação

`TurnEvent`

### Erros estruturais

`NoExecutableTurnException` / `MaximumExecutionDepthExceededException`

### Regra central

**O jogo fornece o significado. O core fornece a estrutura de turnos e execuções.**
