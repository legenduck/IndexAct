"""JSONL bridge between Pi tools and the IndexAct interface."""
from __future__ import annotations

import argparse
import json
import signal
import sys
import time
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_interface(config):
    """Load the interface in an isolated bridge process for each task."""
    global decode_arguments, encode_parameters, IndexActEnvironment, indexact_tool_specs
    global jsonable, model_tool_result_json, Task, ToolCall, HTTPTransport, ProtocolClient
    repo = ROOT
    sys.path.insert(0, str(repo))
    from indexact.interface.schema import decode_arguments, encode_parameters
    from indexact.interface.tools.indexact import IndexActEnvironment, indexact_tool_specs
    from indexact.interface.tools.parsing import jsonable
    from indexact.interface.observation import model_tool_result_json
    from indexact.interface.types import Task, ToolCall
    from indexact.interface.client.protocol import HTTPTransport, ProtocolClient


def encode(value):
    return json.dumps(jsonable(value), ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def write_json(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(encode(value) + "\n", encoding="utf-8")
    temp.replace(path)


class Bridge:
    def __init__(self, config, artifacts):
        self.artifacts = Path(artifacts)
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.origin = time.monotonic()
        self.operation_offset = 0
        self.http_offset = 0
        self.seen_evidence = set()
        self.sequence = 0
        service = config["service"]
        self.transport = HTTPTransport(service["endpoint"], service.get("request_timeout_seconds", 30))
        snapshot_id = service["snapshot_id"]
        task = config["task"]
        # Gold answers, qrels and task metadata deliberately never cross this boundary.
        environment = IndexActEnvironment(ProtocolClient(self.transport), snapshot_id)
        self.session = environment.open(Task(task["task_id"], task["question"]))
        self.wrapped = set()
        self.tools = []
        for tool in self.session.tool_specs():
            parameters, wrapped = encode_parameters(tool.parameters)
            if wrapped:
                self.wrapped.add(tool.name)
            self.tools.append({"name": tool.name, "description": tool.description, "parameters": parameters})
        self.snapshot_id = snapshot_id
        self.flush()

    def append(self, name, value):
        with (self.artifacts / name).open("a", encoding="utf-8") as stream:
            stream.write(encode(value) + "\n")

    def flush(self):
        events = self.session.operation_events
        for event in events[self.operation_offset:]:
            self.append("operations.jsonl", event)
        self.operation_offset = len(events)
        trips = getattr(self.transport, "round_trips", ())
        for trip in trips[self.http_offset:]:
            self.append("service_http_round_trips.jsonl", trip)
        self.http_offset = len(trips)
        write_json(self.artifacts / "state_ledger.json", self.session.state_ledger())
        write_json(self.artifacts / "evidence_ledger.json", self.session.evidence_ledger())

    def execute(self, request):
        arguments = decode_arguments(request["name"], request["arguments"], self.wrapped)
        call = ToolCall(request["call_id"], request["name"], arguments)
        started = time.monotonic()
        result = self.session.execute(call)
        elapsed = time.monotonic() - started
        self.sequence += 1
        observation = model_tool_result_json(result)
        self.append("tool_calls.jsonl", {
            "sequence": self.sequence, "call_id": call.call_id, "name": call.name,
            "arguments": arguments, "result": result.to_json(), "observation": observation,
            "offset_seconds": started - self.origin, "duration_seconds": elapsed,
        })
        self.append("timings.jsonl", {"kind": "indexact_tool", "call_id": call.call_id,
                    "offset_seconds": started - self.origin, "duration_seconds": elapsed})
        evidence_rows = (result.value.get('evidence', [])
                         if call.name == 'index_read' and isinstance(result.value, dict) else [])
        for evidence in evidence_rows:
            if evidence["evidence_id"] not in self.seen_evidence:
                self.seen_evidence.add(evidence["evidence_id"])
                self.append("evidence.jsonl", evidence)
        submitted = call.name == "submit_answer" and self.session.submitted
        if submitted:
            write_json(self.artifacts / "final_answer.json", result.value)
        self.flush()
        return {"observation": observation, "submitted": submitted,
                "is_error": result.status.value == "error"}

    def close(self):
        self.session.close()
        self.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--artifacts", required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    load_interface(config)
    bridge = None
    def stop(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    try:
        bridge = Bridge(config, args.artifacts)
        print(encode({"type": "ready", "tools": bridge.tools, "snapshot_id": bridge.snapshot_id,
                      "schema_sha256": sha256(encode(bridge.tools).encode()).hexdigest()}), flush=True)
        for line in sys.stdin:
            request = json.loads(line)
            if request.get("op") == "close":
                break
            try:
                response = bridge.execute(request)
                print(encode({"id": request["id"], "result": response}), flush=True)
            except Exception as error:
                # Never retry a possibly committed state-changing request.
                print(encode({"id": request.get("id"), "error": str(error)}), flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        if bridge is not None:
            bridge.close()


if __name__ == "__main__":
    main()
