# Thread-safety audit report

**Date:** 2026-10-01  
**Scope:** four concrete lifecycle/thread-safety claims requested after PR G.

## Result

All four requested invariants now have a dedicated PR and a dedicated regression test at the strongest practical level available in this repository.

| Claim | PR | Runtime/pure proof | Result |
|---|---:|---|---|
| 1. Stop cancels `openRouterActiveCall` and releases `confirmGitHubWrite().decision.get()` | #59 | Android instrumentation / ActivityScenario | Fixed and covered |
| 2. Rotation with GitHub confirmation open cannot leave `decision.get()` waiting | #60 | Android instrumentation / ActivityScenario.recreate() | Fixed and covered |
| 3. PR G copy/unzip/model-capability preflight obey `chatId + generation` and stay safe on navigation/destroy | #61 | JVM policy tests + Android instrumentation | Fixed and covered |
| 4. New OpenRouter message stops the previous tool loop like the OpenAI path | #62 | JVM local HTTP-server tool-loop test + Android instrumentation | Fixed and covered |

## PR #59 — Stop cancels confirmation and OpenRouter Call

The root cause was an unowned `CompletableFuture<Boolean>` inside `confirmGitHubWrite()`. Once the dialog was shown, Stop/onStop/onDestroy had no reference with which to resolve the waiting `decision.get()`.

The fix gives the confirmation an explicit Activity-owned future and dialog reference. Stop, onStop and onDestroy resolve the pending decision as `false` and dismiss the dialog. The existing OpenRouter Call cancellation remains part of the same Stop path.

Regression:
`MainActivityLifecycleTest.stopCancelsOpenRouterCallAndPendingGitHubConfirmation()`.

## PR #60 — rotation proof

Rotation destroys and recreates the Activity, so the important property is not only that Stop cancels the future, but that the old dialog and old waiter are both finished before the old Activity disappears.

Regression:
`MainActivityLifecycleTest.recreateCancelsOpenGitHubConfirmationDecision()`.

The test opens a real AlertDialog, calls `ActivityScenario.recreate()`, waits for the background confirmation worker to finish, verifies result `false`, verifies the old dialog is no longer showing, and verifies the recreated Activity has no pending confirmation.

## PR #61 — PR G background ownership

The audit found three concrete stale-completion hazards:

1. OpenRouter picker copy/unzip captured no request ownership before applying results to the current composer.
2. OpenRouter ZIP preparation could complete after navigation and still mutate the current Activity state.
3. Model-capability preflight used a blocking HTTP call with no request-generation ownership gate or lifecycle cancellation.

The fix introduces `OpenRouterBackgroundOwnershipPolicy` with explicit CURRENT_COMPOSER / SOURCE_CHAT / DISCARD outcomes using:

`sourceChatId + sourceGeneration + currentChatId + currentGeneration + activityVisible`.

For stale picker/ZIP results, the attachment is persisted into the source chat instead of being applied to the current composer. OpenRouter picker state is intentionally separate from the older OpenAI `pending_uploads` resume path, so a recreation cannot accidentally route an OpenRouter attachment into the OpenAI uploader.

Model-capability preflight now owns a cancellable OkHttp Call, is cancelled on lifecycle/navigation/new OpenRouter request boundaries, and is checked both before and after the network operation.

A correctness detail fixed in the same slice: successful non-ZIP OpenRouter local copies are no longer deleted immediately after being attached; only the temporary ZIP source file is deleted after extraction.

Tests:
- `OpenRouterBackgroundOwnershipPolicyTest` — current/source/discard routing.
- `MainActivityLifecycleTest.stopCancelsOpenRouterModelPreflightCall()`.
- `MainActivityLifecycleTest.staleOpenRouterAttachmentCanPersistToSourceChatAfterNavigation()`.

## PR #62 — OpenRouter tool-loop cancellation

The OpenRouter client previously cancelled the active HTTP Call, but there was no explicit ownership check around the synchronous tool loop itself. A new message could therefore cancel one HTTP round while the old loop still progressed into another round after a tool returned.

The fix adds a `shouldContinue` ownership callback to `OpenRouterChatClient.complete()`. The callback is checked:
- before each round;
- immediately after an HTTP response;
- before/after each tool execution;
- after `onCallCreated()` to close the cancellation/onCallCreated race.

MainActivity now cancels the previous OpenRouter active Call and any pending GitHub confirmation before starting a new OpenRouter message. The new request then advances the generation, so the old `shouldContinue` callback becomes false.

Tests:
- JVM `OpenRouterChatClientTest.toolLoopStopsBeforeStartingNextRoundAfterOwnershipLoss()` uses a local HTTP server, blocks inside the first tool, invalidates ownership, then proves the old loop sends exactly one HTTP round.
- Android `MainActivityLifecycleTest.newOpenRouterMessageCancelsPreviousActiveCall()` proves new-message wiring cancels the previous active OpenRouter Call.

## Final CI validation

To close the audit, temporary `push` triggers were enabled only for the final validation cycle, then removed again.

The final cumulative main after PR #64 was validated by GitHub Actions:
- Debug run `36865960013` — **success**; Verify handoff, source verifier, verifier mutation matrix, JVM tests/lint/build, and Android lifecycle instrumentation all completed successfully.
- Release run `36865960153` — **success**; source verifier, release lint/tests, assembleRelease and bundleRelease completed successfully.

An earlier PR #63-head validation also passed:
- Debug `36861232439` — success.
- Release `36861232390` — success.

PR #64 was a test-stability follow-up only; it did not alter runtime behavior. Its validation was superseded by the final cumulative main Debug/Release runs above.

The temporary `push` triggers are now removed again. `.github/workflows/build.yml` and `release.yml` are back to `workflow_dispatch:` only.

One earlier PR #64-head Release attempt (`36861029734`) failed in `OpenRouterChatClientTest.toolLoopStopsBeforeStartingNextRoundAfterOwnershipLoss` at the initial tool-start wait. The test was subsequently stabilized, and the final cumulative Debug/Release runs are green.

## Final architecture conclusion

The audit now has explicit ownership boundaries for all four requested cases:

`chatId + generation` owns request/result applicability;  
`assistantItemId` remains required when a callback mutates a specific assistant item;  
pending GitHub confirmation has explicit future/dialog ownership;  
OpenRouter transport has a cancellation gate that spans HTTP rounds and tool execution boundaries;  
PR G background attachment/preflight work can complete after lifecycle changes without mutating the wrong composer.

No mass synchronization or global concurrent collections were added.
