# Phase 3 persistent and transient state ledger

This ledger covers every Phase 3 Work, Luna automated-verification, Developer
manual-verification, and Gate requirement in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md).
It was created before Phase 3 implementation and is updated only with repository
evidence. Agent review recorded here is a self-review, not an independent review.

## Requirement ledger

| ID | Original requirement | Implementation or decision | Automated evidence | Negative or adversarial case | Developer observation | Status |
| --- | --- | --- | --- | --- | --- | --- |
| W3.1 | Define task records that persist only player-meaningful progress. | Existing durable owners remain the task records; no parallel task-record model is added, because the production task contract belongs to Phase 11. Execution-only fields are removed from their schemas. | Pending. | An encoded attachment must not contain begging session fields. | None. | BLOCKED |
| W3.2 | Make leases, movement attempts, retries, ladder claims, and path-analysis caches session-local. | Pending audit below. | Pending. | A decoded village must have no lease or claim. | None. | BLOCKED |
| W3.3 | Centralize cleanup for death/removal, profession change, dimension change, level unload, and server stop. | `VillagerSessionCleanup` owns these boundaries. | Pending. | A bystander's state must survive each boundary. | None. | BLOCKED |
| W3.4 | Ensure cleanup is keyed by village, profession, villager UUID, task, and world/dimension where applicable. | Pending. | Pending. | One village cannot release another's work; one level's unload cannot release another's. | None. | BLOCKED |
| A3.1 | Add save/reload tests proving durable task progress survives where intended while transient ownership does not. | Pending. | Pending. | Pending. | None. | BLOCKED |
| A3.2 | Add lifecycle tests for each cleanup boundary and negative tests showing one village cannot release another village's work. | Pending. | Pending. | Pending. | None. | BLOCKED |
| M3.1 | Temporary `state_reload` scenario, mid-job marker, read-only lease inspection, Save and Quit, reload; the same villager safely resumes or reacquires once with no duplicated world effect, frozen movement, or pre-reload transient claim. | Pending. | Not applicable. | Not applicable. | Required from the developer. | BLOCKED |
| G3.1 | All transient state has a single cleanup owner and no execution-only record is serialized as authoritative gameplay state. | Pending. | Pending. | A release call outside the cleanup owner must be rejected. | None. | BLOCKED |

## Audit of persisted task state

Pending.

## Audit of transient state

Pending.
