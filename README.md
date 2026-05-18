# Bridge Inspection Agent

Java 17 + Spring AI bridge inspection Agent project focused on interview-friendly engineering depth: observable runtime, governed tools, checkpoint/resume foundations, RAG citations and evaluation, memory context assembly, and a local trace console.

## What Makes It Resume-Ready

- Runtime trace: every run records LLM calls, tool calls, guardrail warnings, checkpoints, approvals and termination reasons.
- Governed tools: each tool has scene allow-lists, timeout, sensitivity, failure policy and approval metadata.
- Durable execution foundation: `CheckpointStore` stores resumable run snapshots, with an in-memory demo store and a clear prod extension point.
- HITL flow: write/high-risk tools pause with `PENDING_APPROVAL`; `/api/v2/agent/runs/{runId}/resume` accepts approve, edit or reject.
- RAG v4 layer: wraps hybrid search with citations, retrieval traces and evaluator metrics such as recall@k and MRR.
- Memory v2: assembles short-term session memory, bridge long-term memory and user preference memory into one observable context.

## Main APIs

- `POST /api/v2/agent/runs`: create a run.
- `POST /api/v2/agent/runs/stream`: stream run events as SSE.
- `GET /api/v2/agent/runs/{runId}`: inspect a run, events and checkpoints.
- `GET /api/v2/agent/runs/{runId}/events`: inspect event timeline.
- `POST /api/v2/agent/runs/{runId}/resume`: resolve a pending approval.

The legacy `/api/agent/chat` endpoint is kept as a compatibility wrapper.

## Verify

```powershell
$env:JAVA_HOME='D:\jdk17\zulu17.56.15-ca-jdk17.0.14-win_x64'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn test
```

## Local Demo

The static trace console is available at `/demo/index.html` after the application starts. The demo profile is intended for local showcase work and keeps the trace/checkpoint/HITL surfaces visible without changing the production profile.
