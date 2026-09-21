# Astra feature notes — v4.15.1

Already implemented in the source:
- gpt-6-astra
- reasoning.effort: low / medium / high / xhigh / max
- Auto effort: lexical baseline + conservative feedback-based calibration
- `configuration_update` in standard mode
- streaming + `previous_response_id`
- `web_search` + `url_citation`
- File Search + `file_citation`
- Code Interpreter + `container_file_citation` download
- Image Generation result -> local PNG preview
- image input via Files API `file_id`
- SQLite chat history with legacy migration
- lightweight chat summaries
- diagnostics and effort telemetry
- rich text renderer with HTTP(S) Markdown/bare links
- safe ZIP streaming
- FileProvider / Storage Access Framework file actions

Not implemented as full end-to-end executors:
- Computer Use
- arbitrary custom function/MCP execution
- Realtime voice UI

The live streaming display intentionally keeps a simple text buffer for low-latency rendering. The completed response path uses `AstraResponseModel`, annotations and tool metadata.


## v4.15.1 additions
- live tool activity strip with stale-request guards;
- local Astra file library for generated/downloaded files;
- background Responses (`background=true`) with automatic resume/poll after Activity recreation or app restart;
- composer draft persistence per chat, including attachment metadata and pending upload source URIs;
- background upload callbacks are scoped to their originating chat so attachments cannot land in a different chat after switching;
- send-button state remains disabled while uploads are pending or a response is active;
- usage/cost summary (today / 7 days / all time);
- long-chat preflight warning;
- schema v10 active-response state + estimated cost telemetry.
