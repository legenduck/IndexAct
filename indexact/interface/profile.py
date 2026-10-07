"""Inspect the public interface without opening a service or calling a model."""
import argparse
import hashlib
import json
from pathlib import Path
import sys


def _configured_path(value, config_path):
    path = Path(value)
    if not path.is_absolute():
        path = config_path.resolve().parent / path
    if path.is_file():
        return path
    raise ValueError(f"Configured file does not exist: {value}")


def tool_specs():
    """Use the interface's actual schema pipeline, without recreating it."""
    from indexact.interface.tools.indexact import indexact_tool_specs
    return indexact_tool_specs()


def inspect(repo, config):
    repo = Path(repo).resolve()
    sys.path.insert(0, str(repo))
    from indexact.interface.config import load_runtime_config
    from indexact.interface.schema import encode_parameters

    cfg = load_runtime_config(Path(config))
    base = cfg.canonical_value()
    runtime = base['runtime']
    prompt = _configured_path(runtime['prompt'], Path(config)).resolve()
    runtime['prompt'] = str(prompt)
    text = prompt.read_text()
    tools = []
    for spec in tool_specs():
        parameters, _ = encode_parameters(spec.parameters)
        tools.append({'name': spec.name, 'description': spec.description, 'parameters': parameters})
    files = []
    for package in ('indexact/interface',):
        files.extend((repo / package).rglob('*.py'))
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(str(path.relative_to(repo)).encode() + b'\0' + path.read_bytes() + b'\0')
    return {'repo': str(repo), 'config_path': str(Path(config).resolve()), 'base_config': base,
            'prompt_path': str(prompt), 'prompt_text': text,
            'prompt_sha256': hashlib.sha256(text.encode()).hexdigest(),
            'tools': tools, 'source_sha256': digest.hexdigest(),
            'observation_mode': 'exact', 'capability_mode': 'full'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True)
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    print(json.dumps(inspect(args.repo, args.config), ensure_ascii=False))
