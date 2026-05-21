#!/usr/bin/env python3
"""Build resources/litellm-snapshot.json from LiteLLM's pricing data.

Reads LiteLLM's model_prices_and_context_window.json, keeps only the
providers we have SDK adapters for, and normalises to a leaner shape
the Clojure side can load without further parsing.

Source:
  https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json
  (or the litellm-ref local clone at LITELLM_REPO env / argv[1])

Output:
  resources/litellm-snapshot.json

To refresh:
  python3 scripts/build_litellm_snapshot.py [path-to-litellm-repo]
"""
import json
import os
import sys
from pathlib import Path

# --- LiteLLM provider name → our SDK provider keyword (as string) ---
PROVIDER_MAP = {
    "openai": "openai",
    "text-completion-openai": "openai",
    "anthropic": "anthropic",
    "gemini": "gemini-native",
    "vertex_ai-language-models": "vertex-gemini",
    "vertex_ai-anthropic_models": "vertex-anthropic",
    "openrouter": "openrouter",
    "deepseek": "deepseek",
    "text-completion-deepseek": "deepseek",
    "moonshot": "kimi",
    "mistral": "mistral",
    "groq": "groq",
    "cerebras": "cerebras",
    "together_ai": "together",
    "xai": "xai",
    "perplexity": "perplexity",
    "cohere": "cohere",
    "cohere_chat": "cohere",
    "voyage": "voyage",
    "jina_ai": "jina",
    "bedrock": "bedrock",
    "bedrock_converse": "bedrock",
    "huggingface": "huggingface",
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
        (OpenRouter model ids ARE namespaced — keep the inner path)
      bedrock_converse keys stay as-is (region-prefix carries meaning).
    """
    if litellm_provider in ("bedrock", "bedrock_converse"):
        return key
    if "/" in key:
        head, _, tail = key.partition("/")
        return tail
    return key


# Providers where LiteLLM stores routing fictions (e.g.
# 'perplexity/anthropic/claude-opus' isn't a real wire model id on
# api.perplexity.ai). When the stripped id still contains a '/',
# the entry is a routing curio rather than a real model — skip it.
ROUTING_LITERAL_PROVIDERS = {"perplexity"}


def per_million(val):
    if isinstance(val, (int, float)):
        return val * 1_000_000.0
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
    if (sdk_provider in ROUTING_LITERAL_PROVIDERS) and ("/" in model_id):
        return None  # skip routing-curio entries

    cost = {}
    if (v := per_million(raw.get("input_cost_per_token"))) is not None:
        cost["input_per_million"] = v
    if (v := per_million(raw.get("output_cost_per_token"))) is not None:
        cost["output_per_million"] = v
    # LiteLLM uses different field names across providers; capture the
    # common ones.
    for src_key, dst_key in [
        ("cache_read_input_token_cost", "cache_read_per_million"),
        ("input_cost_per_token_cached", "cache_read_per_million"),
        ("cache_creation_input_token_cost", "cache_write_per_million"),
    ]:
        if (v := per_million(raw.get(src_key))) is not None:
            cost.setdefault(dst_key, v)

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

    # Skip entries with no useful data (no context, no cost, no caps).
    if (out.keys() <= {"id", "provider"}):
        return None

    return out


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
        "LITELLM_REPO",
        "/Users/hvyasanakere/Projects/MyriadSelf/litellm-ref",
    )
    src = Path(repo) / "model_prices_and_context_window.json"
    if not src.exists():
        print(f"source not found: {src}", file=sys.stderr)
        sys.exit(1)

    with src.open() as f:
        data = json.load(f)

    out = {}  # {sdk-provider: {model-id: entry}}
    skipped = 0
    for key, raw in data.items():
        if not isinstance(raw, dict):
            continue
        if key == "sample_spec":
            continue
        normalized = normalize_entry(key, raw)
        if normalized is None:
            skipped += 1
            continue
        provider = normalized.pop("provider")
        model_id = normalized.pop("id")
        out.setdefault(provider, {})[model_id] = normalized

    dest = Path(__file__).resolve().parent.parent / "resources" / "litellm-snapshot.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    with dest.open("w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
    sizes = {p: len(m) for p, m in out.items()}
    total = sum(sizes.values())
    print(f"wrote {dest} — {total} entries across {len(out)} providers")
    for p, c in sorted(sizes.items(), key=lambda kv: -kv[1]):
        print(f"  {p}: {c}")
    print(f"skipped (not in PROVIDER_MAP or empty): {skipped}")


if __name__ == "__main__":
    main()
