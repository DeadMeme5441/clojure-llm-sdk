#!/usr/bin/env python3
"""Build the bundled LiteLLM and models.dev catalog snapshots.

The default inputs are immutable public revisions:

* LiteLLM model pricing:
  https://github.com/BerriAI/litellm/blob/b1a61f510c90ce7e4533e89247c941fa201ada4f/model_prices_and_context_window.json
* models.dev catalog:
  https://github.com/anomalyco/models.dev/tree/a2a673950c6e09af0dbcb4744116401fc1ee0048

Only providers backed by SDK adapters are retained. The models.dev snapshot is
built directly from the pinned repository archive rather than the mutable
models.dev API response.

LiteLLM may be overridden with argv[1], LITELLM_SOURCE, or LITELLM_REPO. The
override may be a URL, pricing JSON path, or directory containing
model_prices_and_context_window.json. MODELS_DEV_SOURCE may similarly point to
a models.dev repository archive or checkout.

Outputs:
  resources/litellm-snapshot.json
  resources/models-dev-snapshot.json
"""
import copy
import io
import json
import os
import sys
import tarfile
import tempfile
import tomllib
import urllib.request
from pathlib import Path

LITELLM_REVISION = "b1a61f510c90ce7e4533e89247c941fa201ada4f"
DEFAULT_SOURCE_URL = (
    "https://raw.githubusercontent.com/BerriAI/litellm/"
    f"{LITELLM_REVISION}/model_prices_and_context_window.json"
)
LITELLM_BLOB_URL = (
    "https://github.com/BerriAI/litellm/blob/"
    f"{LITELLM_REVISION}/model_prices_and_context_window.json"
)
MODELS_DEV_REVISION = "a2a673950c6e09af0dbcb4744116401fc1ee0048"
MODELS_DEV_SOURCE_URL = (
    "https://github.com/anomalyco/models.dev/archive/"
    f"{MODELS_DEV_REVISION}.tar.gz"
)
MODELS_DEV_TREE_URL = (
    "https://github.com/anomalyco/models.dev/tree/"
    f"{MODELS_DEV_REVISION}"
)
PRICING_FILENAME = "model_prices_and_context_window.json"

# --- LiteLLM provider name → our SDK provider keyword (as string) ---
PROVIDER_MAP = {
    "openai": "openai",
    "text-completion-openai": "openai",
    "anthropic": "anthropic",
    "gemini": "gemini-native",
    "vertex_ai-language-models": "vertex-gemini",
    "openrouter": "openrouter",
    "deepseek": "deepseek",
    "text-completion-deepseek": "deepseek",
    "moonshot": "kimi",
    "mistral": "mistral",
    "groq": "groq",
    "cerebras": "cerebras",
    "together_ai": "together",
    "xai": "xai",
    "zai": "zai",
    "perplexity": "perplexity",
    "cohere": "cohere",
    "cohere_chat": "cohere",
    "voyage": "voyage",
    "jina_ai": "jina",
    "bedrock": "bedrock",
    "bedrock_converse": "bedrock",
    "huggingface": "huggingface",
}

# models.dev provider ids corresponding to registered SDK providers. Aliases
# such as :codex reuse "openai" and therefore do not duplicate snapshot data.
MODELS_DEV_PROVIDER_IDS = {
    "amazon-bedrock",
    "anthropic",
    "cerebras",
    "cohere",
    "deepseek",
    "google",
    "google-vertex",
    "groq",
    "huggingface",
    "kimi-for-coding",
    "mistral",
    "openai",
    "openrouter",
    "perplexity",
    "togetherai",
    "xai",
    "zai",
}

# The :vertex-gemini transport always addresses publishers/google/models.
# models.dev's google-vertex catalog also contains Anthropic and other MaaS
# publishers whose wire protocols this adapter does not implement.
MODELS_DEV_MODEL_PREFIXES = {"google-vertex": ("gemini-",)}

LITELLM_KEY_PREFIXES = {
    "cohere_chat": {"cohere", "cohere_chat"},
    "text-completion-deepseek": {"deepseek", "text-completion-deepseek"},
    "text-completion-openai": {"openai", "text-completion-openai"},
    "together_ai": {"together", "together_ai"},
}

# A slash left after stripping the LiteLLM provider prefix is a valid native id
# only for adapters whose wire model ids are themselves namespaced.
NAMESPACED_MODEL_PROVIDERS = {
    "groq",
    "bedrock",
    "huggingface",
    "openrouter",
    "perplexity",
    "together",
}

# --- mode → capabilities (will be merged with supports_* flags) ---
MODE_CAPS = {
    "chat": ["chat", "streaming"],
    "completion": ["chat"],
    "embedding": ["embedding"],
    "rerank": ["rerank"],
    "image_generation": ["image-generation"],
    "audio_transcription": ["audio-transcription"],
    "audio_speech": ["audio-tts"],
    "realtime": ["realtime"],
    "search": ["web-search"],
    "moderation": ["moderation"],
    "moderations": ["moderation"],
    "responses": ["chat", "streaming", "responses"],
}

SUPPORTS_FLAG_CAPS = {
    "supports_function_calling": "tools",
    "supports_tool_choice": "tools",
    "supports_response_schema": "json-schema",
    "supports_vision": "vision",
    "supports_pdf_input": "pdf",
    "supports_audio_input": "audio-input",
    "supports_audio_output": "audio-output",
    "supports_prompt_caching": "cache",
    "supports_reasoning": "reasoning",
    "supports_web_search": "web-search",
    "supports_computer_use": "computer-use",
}


def strip_model_prefix(key: str, litellm_provider: str) -> str:
    """Drop the LiteLLM provider prefix when present in the key.

    Examples:
      cohere/embed-v4.0 → embed-v4.0
      mistral/codestral-2405 → codestral-2405
      gemini/gemini-2.5-pro → gemini-2.5-pro
      openrouter/anthropic/claude-3-haiku → anthropic/claude-3-haiku
      perplexity/openai/gpt-5.6-sol → openai/gpt-5.6-sol
        (these providers use namespaced native model ids)
      bedrock_converse keys stay as-is (region-prefix carries meaning).
    """
    if litellm_provider in ("bedrock", "bedrock_converse"):
        return key
    if "/" not in key:
        return key
    head, _, tail = key.partition("/")
    prefixes = LITELLM_KEY_PREFIXES.get(
        litellm_provider, {litellm_provider}
    )
    return tail if head in prefixes else key




def per_million(val):
    if isinstance(val, (int, float)):
        return val * 1_000_000.0
    return None


def numeric(val):
    if isinstance(val, (int, float)):
        return float(val)
    return None


def first_numeric(*vals):
    for val in vals:
        n = numeric(val)
        if n is not None:
            return n
    return None


def derive_capabilities(entry):
    caps = set()
    mode = entry.get("mode")
    if mode in MODE_CAPS:
        caps.update(MODE_CAPS[mode])
    for flag, cap in SUPPORTS_FLAG_CAPS.items():
        if entry.get(flag):
            caps.add(cap)
    return sorted(caps)


def normalize_entry(key: str, raw: dict):
    """Return a slim dict matching our ModelEntry-ish shape, or None
    if the raw entry is too thin to keep."""
    litellm_provider = raw.get("litellm_provider")
    sdk_provider = PROVIDER_MAP.get(litellm_provider)
    if not sdk_provider:
        return None  # skip providers we don't have

    model_id = strip_model_prefix(key, litellm_provider)
    # Vertex/Gemini Imagen endpoints were discontinued on 2026-06-30.
    # The SDK's image adapter now uses Gemini image-generation models.
    if sdk_provider == "gemini-native" and model_id.startswith("imagen-"):
        return None
    if sdk_provider not in NAMESPACED_MODEL_PROVIDERS and "/" in model_id:
        return None

    cost = {}
    if (v := per_million(raw.get("input_cost_per_token"))) is not None:
        cost["input_per_million"] = v
    if (v := per_million(raw.get("output_cost_per_token"))) is not None:
        cost["output_per_million"] = v
    # LiteLLM uses different field names across providers; only explicitly
    # typed rates are promoted. In particular, a generic/text cache rate is
    # never reused as an image or audio cache rate.
    for src_key, dst_key in [
        ("cache_read_input_token_cost", "cache_read_per_million"),
        ("input_cost_per_token_cached", "cache_read_per_million"),
        ("cache_creation_input_token_cost", "cache_write_per_million"),
        ("input_cost_per_image_token", "image_input_per_million"),
        ("output_cost_per_image_token", "image_output_per_million"),
        ("input_cost_per_audio_token", "audio_input_per_million"),
        ("output_cost_per_audio_token", "audio_output_per_million"),
        ("cache_read_input_image_token_cost", "image_cache_read_per_million"),
        ("cache_read_input_audio_token_cost", "audio_cache_read_per_million"),
    ]:
        if (v := per_million(raw.get(src_key))) is not None:
            cost.setdefault(dst_key, v)
    request_parts = [
        n for n in [
            numeric(raw.get("input_cost_per_request")),
            numeric(raw.get("output_cost_per_request")),
        ]
        if n is not None
    ]
    if request_parts:
        cost["request_cost"] = sum(request_parts)
    if (v := first_numeric(raw.get("output_cost_per_image"),
                           raw.get("input_cost_per_image"))) is not None:
        cost["image_per_image"] = v
    if (v := first_numeric(raw.get("output_cost_per_pixel"),
                           raw.get("input_cost_per_pixel"))) is not None:
        cost["image_per_megapixel"] = v * 1_000_000.0
    mode = raw.get("mode")
    if mode == "audio_transcription":
        if (v := first_numeric(raw.get("input_cost_per_second"),
                               raw.get("output_cost_per_second"))) is not None:
            cost["transcription_per_minute"] = v * 60.0
    if mode == "audio_speech":
        if (v := first_numeric(raw.get("input_cost_per_character"),
                               raw.get("output_cost_per_character"))) is not None:
            cost["tts_per_million_chars"] = v * 1_000_000.0
    if mode == "rerank":
        if (v := numeric(raw.get("input_cost_per_query"))) is not None:
            cost["rerank_per_search_unit"] = v

    out = {
        "id": model_id,
        "provider": sdk_provider,
    }
    if raw.get("max_input_tokens"):
        try:
            out["context_length"] = int(raw["max_input_tokens"])
        except (ValueError, TypeError):
            pass
    if raw.get("max_output_tokens"):
        try:
            out["max_output_tokens"] = int(raw["max_output_tokens"])
        except (ValueError, TypeError):
            pass

    caps = derive_capabilities(raw)
    if caps:
        out["capabilities"] = caps
    if cost:
        out["cost"] = cost
    if (mode := raw.get("mode")):
        out["mode"] = mode
    if (deprecation_date := raw.get("deprecation_date")):
        out["deprecation_date"] = deprecation_date

    # Skip entries with no useful data (no context, no cost, no caps).
    if (out.keys() <= {"id", "provider"}):
        return None

    return out


def fetch_bytes(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": "clojure-llm-sdk-catalog-refresh"}
    )
    with urllib.request.urlopen(request) as response:
        return response.read()


def load_source(override):
    """Load LiteLLM's pricing JSON from a URL, file, checkout, or the pin."""
    if not override:
        print(f"fetching {DEFAULT_SOURCE_URL}", file=sys.stderr)
        return json.loads(fetch_bytes(DEFAULT_SOURCE_URL).decode("utf-8"))

    if override.startswith(("http://", "https://")):
        print(f"fetching {override}", file=sys.stderr)
        return json.loads(fetch_bytes(override).decode("utf-8"))

    path = Path(override)
    if path.is_dir():
        path = path / PRICING_FILENAME
    if not path.exists():
        raise FileNotFoundError(f"LiteLLM source not found: {path}")
    print(f"reading {path}", file=sys.stderr)
    with path.open() as source:
        return json.load(source)


def deep_merge(base, overrides):
    out = copy.deepcopy(base)
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def omit_path(value, dotted_path):
    parts = dotted_path.split(".")
    current = value
    for part in parts[:-1]:
        current = current.get(part)
        if not isinstance(current, dict):
            return
    current.pop(parts[-1], None)


def load_toml_tree(root):
    base_models = {}
    models_root = root / "models"
    for path in models_root.rglob("*.toml"):
        model_id = path.relative_to(models_root).with_suffix("").as_posix()
        with path.open("rb") as source:
            base_models[model_id] = tomllib.load(source)

    providers = {}
    for provider_id in sorted(MODELS_DEV_PROVIDER_IDS):
        provider_root = root / "providers" / provider_id
        provider_file = provider_root / "provider.toml"
        models_root = provider_root / "models"
        if not provider_file.exists() or not models_root.exists():
            continue
        with provider_file.open("rb") as source:
            provider = tomllib.load(source)
        provider["id"] = provider_id
        provider["models"] = {}

        prefixes = MODELS_DEV_MODEL_PREFIXES.get(provider_id)
        for path in sorted(models_root.rglob("*.toml")):
            model_id = path.relative_to(models_root).with_suffix("").as_posix()
            if prefixes and not model_id.startswith(prefixes):
                continue
            with path.open("rb") as source:
                authored = tomllib.load(source)
            base_model = authored.pop("base_model", None)
            omit = authored.pop("base_model_omit", [])
            if base_model:
                if base_model not in base_models:
                    raise ValueError(
                        f"{provider_id}/{model_id} has unknown base_model {base_model}"
                    )
                authored = deep_merge(base_models[base_model], authored)
                for dotted_path in omit:
                    omit_path(authored, dotted_path)
            authored["id"] = model_id
            provider["models"][model_id] = normalize_models_dev_entry(authored)
        if provider["models"]:
            providers[provider_id] = provider
    return providers


def load_models_dev_source(override):
    if override and Path(override).is_dir():
        print(f"reading {override}", file=sys.stderr)
        return load_toml_tree(Path(override))

    source = override or MODELS_DEV_SOURCE_URL
    print(f"fetching {source}", file=sys.stderr)
    if source.startswith(("http://", "https://")):
        archive = fetch_bytes(source)
    else:
        archive = Path(source).read_bytes()
    with tempfile.TemporaryDirectory() as temp_dir:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:*") as bundle:
            bundle.extractall(temp_dir, filter="data")
        roots = [path for path in Path(temp_dir).iterdir() if path.is_dir()]
        if len(roots) != 1:
            raise ValueError("models.dev archive must contain one repository root")
        return load_toml_tree(roots[0])


def normalize_models_dev_entry(entry):
    out = copy.deepcopy(entry)
    raw_cost = out.get("cost")
    if not isinstance(raw_cost, dict):
        return out
    cost = copy.deepcopy(raw_cost)
    for source_key, dest_key in [
        ("input", "input_per_million"),
        ("output", "output_per_million"),
        ("cache_read", "cache_read_per_million"),
        ("cache_write", "cache_write_per_million"),
        ("input_image", "image_input_per_million"),
        ("output_image", "image_output_per_million"),
        ("input_audio", "audio_input_per_million"),
        ("output_audio", "audio_output_per_million"),
        ("cache_read_image", "image_cache_read_per_million"),
        ("cache_read_audio", "audio_cache_read_per_million"),
        ("rerank", "rerank_per_search_unit"),
    ]:
        if source_key in cost:
            cost[dest_key] = cost.pop(source_key)
    out["cost"] = cost
    return out


def write_snapshot(filename, source_url, revision, providers):
    destination = (
        Path(__file__).resolve().parent.parent / "resources" / filename
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    snapshot = {
        "_meta": {
            "source_revision": revision,
            "source_url": source_url,
        },
        "providers": providers,
    }
    with destination.open("w") as output:
        json.dump(snapshot, output, separators=(",", ":"), sort_keys=True)
    sizes = {
        provider: len(value.get("models", value))
        for provider, value in providers.items()
    }
    total = sum(sizes.values())
    print(
        f"wrote resources/{filename} — "
        f"{total} entries across {len(providers)} providers"
    )
    for provider, count in sorted(sizes.items(), key=lambda item: -item[1]):
        print(f"  {provider}: {count}")


def main():
    override = sys.argv[1] if len(sys.argv) > 1 else (
        os.environ.get("LITELLM_SOURCE") or os.environ.get("LITELLM_REPO")
    )
    data = load_source(override)

    providers = {}
    skipped = 0
    for key, raw in data.items():
        if not isinstance(raw, dict) or key == "sample_spec":
            continue
        normalized = normalize_entry(key, raw)
        if normalized is None:
            skipped += 1
            continue
        provider = normalized.pop("provider")
        model_id = normalized.pop("id")
        providers.setdefault(provider, {})[model_id] = normalized

    write_snapshot(
        "litellm-snapshot.json",
        LITELLM_BLOB_URL,
        LITELLM_REVISION,
        providers,
    )
    print(f"skipped (not in PROVIDER_MAP or empty): {skipped}")

    models_dev = load_models_dev_source(os.environ.get("MODELS_DEV_SOURCE"))
    write_snapshot(
        "models-dev-snapshot.json",
        MODELS_DEV_TREE_URL,
        MODELS_DEV_REVISION,
        models_dev,
    )


if __name__ == "__main__":
    main()
