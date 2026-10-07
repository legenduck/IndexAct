"""Only the interface, budgets and retry settings consumed by the Pi runner."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from pathlib import Path
import re
from typing import Any, Mapping
from urllib.parse import urlsplit

import yaml


class ConfigError(ValueError):
    """An interface setting is missing, unsupported, or invalid."""


@dataclass(frozen=True, slots=True)
class ServiceConfig:
    endpoint: str
    snapshot_id: str
    request_timeout_seconds: float


@dataclass(frozen=True, slots=True)
class RuntimeBudgets:
    max_turns: int
    max_tool_calls_per_turn: int
    max_input_tokens: int | None
    max_total_output_tokens: int | None
    max_wall_clock_seconds: float | None


@dataclass(frozen=True, slots=True)
class RetryConfig:
    max_retries: int = 1
    initial_retry_delay_seconds: float = 0.25
    max_retry_delay_seconds: float = 2.0


@dataclass(frozen=True, slots=True)
class RuntimeConfig:
    service: ServiceConfig
    budgets: RuntimeBudgets
    prompt_path: str
    prompt_version: str
    retry: RetryConfig

    def canonical_value(self) -> dict[str, Any]:
        return {
            "service": asdict(self.service),
            "runtime": {
                "prompt": self.prompt_path,
                "prompt_version": self.prompt_version,
                **asdict(self.budgets),
            },
            "retry": asdict(self.retry),
        }


def load_runtime_config(path: str | Path) -> RuntimeConfig:
    with Path(path).open(encoding="utf-8") as stream:
        return parse_runtime_config(yaml.safe_load(stream))


def parse_runtime_config(raw: Mapping[str, Any]) -> RuntimeConfig:
    root = _fields(raw, "configuration", {"service", "runtime"}, {"retry"})
    service = _fields(
        root["service"], "service", {"endpoint", "snapshot_id", "request_timeout_seconds"}
    )
    endpoint = _text(service["endpoint"], "service.endpoint")
    parsed = urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ConfigError("service.endpoint must be an absolute HTTP(S) URL")
    if parsed.username is not None or parsed.password is not None:
        raise ConfigError("service.endpoint must not contain credentials")
    snapshot_id = _text(service["snapshot_id"], "service.snapshot_id")
    if re.fullmatch(r"snap-[0-9a-f]{64}", snapshot_id) is None:
        raise ConfigError("service.snapshot_id must be a SnapshotId")
    runtime = _fields(
        root["runtime"], "runtime",
        {"prompt", "max_turns", "max_wall_clock_seconds"},
        {"prompt_version", "max_tool_calls_per_turn", "max_input_tokens", "max_total_output_tokens"},
    )
    prompt = _text(runtime["prompt"], "runtime.prompt")
    retry = _fields(
        root.get("retry", {}), "retry", set(),
        {"max_retries", "initial_retry_delay_seconds", "max_retry_delay_seconds"},
    )
    retry_config = RetryConfig(
        max_retries=_int(retry.get("max_retries", 1), "retry.max_retries", minimum=0),
        initial_retry_delay_seconds=_number(
            retry.get("initial_retry_delay_seconds", 0.25), "retry.initial_retry_delay_seconds",
            allow_zero=True,
        ),
        max_retry_delay_seconds=_number(
            retry.get("max_retry_delay_seconds", 2.0), "retry.max_retry_delay_seconds",
            allow_zero=True,
        ),
    )
    if retry_config.max_retries > 1:
        raise ConfigError("retry.max_retries must be 0 or 1")
    if retry_config.max_retry_delay_seconds < retry_config.initial_retry_delay_seconds:
        raise ConfigError("retry max delay must be at least its initial delay")
    return RuntimeConfig(
        service=ServiceConfig(
            endpoint, snapshot_id,
            _number(service["request_timeout_seconds"], "service.request_timeout_seconds"),
        ),
        budgets=RuntimeBudgets(
            max_turns=_int(runtime["max_turns"], "runtime.max_turns"),
            max_tool_calls_per_turn=_int(
                runtime.get("max_tool_calls_per_turn", 16), "runtime.max_tool_calls_per_turn"
            ),
            max_input_tokens=_optional_budget(runtime.get("max_input_tokens"), "max_input_tokens"),
            max_total_output_tokens=_optional_budget(
                runtime.get("max_total_output_tokens"), "max_total_output_tokens"
            ),
            max_wall_clock_seconds=(
                None if runtime["max_wall_clock_seconds"] is None else _number(
                    runtime["max_wall_clock_seconds"], "runtime.max_wall_clock_seconds"
                )
            ),
        ),
        prompt_path=prompt,
        prompt_version=_text(runtime.get("prompt_version", Path(prompt).stem), "runtime.prompt_version"),
        retry=retry_config,
    )


def _fields(value, name, required, optional=frozenset()):
    if not isinstance(value, Mapping) or not all(isinstance(key, str) for key in value):
        raise ConfigError(f"{name} must be an object")
    missing, unknown = required - value.keys(), value.keys() - required - optional
    if missing or unknown:
        raise ConfigError(f"{name}: missing fields {sorted(missing)}, unsupported fields {sorted(unknown)}")
    return value


def _text(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{name} must be non-empty text")
    return value


def _int(value, name, *, minimum=1):
    if type(value) is not int or value < minimum:
        raise ConfigError(f"{name} must be an integer >= {minimum}")
    return value


def _optional_budget(value, name):
    return None if value is None else _int(value, f"runtime.{name}", minimum=0)


def _number(value, name, *, allow_zero=False):
    if (type(value) not in (int, float) or not math.isfinite(value)
            or value < 0 or (value == 0 and not allow_zero)):
        raise ConfigError(f"{name} must be finite and {'non-negative' if allow_zero else 'positive'}")
    return float(value)
