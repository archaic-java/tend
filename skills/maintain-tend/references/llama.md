# llama.cpp: deployment and AMD commissioning

The reviewed source is homelab `8fc54492c6d75d9713061703c5a6667347e1481b`.
`examples/llama/models.ini` is copied verbatim from its template, not reconstructed from chat.
The `server-rocm-b11277` upstream OCI index resolves to
`sha256:0689618b6237be0598b1e743e02847bd92e25def66302de9abb0cfeb29ce0aa8`;
its linux/amd64 manifest is `sha256:f0f948bb4e0096b12a80e499c9b90bd9295cb1eaf00cebc456ccb2d4f08c7a9c`.
API contracts were checked at llama.cpp source `eae11d2217fe9225d1aaba48773b6cca45ae4de9`.

The [XML fragment](../../../examples/llama/incus.xml) is an offline projection example;
its fingerprint, PCI address, network/project and pool must be resolved by installation
preparation. It is not an active installation. Use UID/GID 1000, shifted writable cache
`/var/cache/llama` with `1000:1000:0750`, read-only `/etc/llama/models.ini`, one loaded
model, router autoload enabled for inference, metrics enabled, and UI disabled. The GPU
is a physical PCI-bound device and `/dev/kfd` is unix-char, both `1000:1000:0660`.
There is no public ingress or model-management route. Pi retains normal outbound access.
Do not carry forward the unused legacy `llama-models` volume.

| API ID | Public HF repo / exact file | Context, parallel | Thinking / speculation |
|---|---|---|---|
| `mimo` | `bartowski/MiMo-V2.6-Distill-Qwen-9B-GGUF` / `MiMo-V2.6-Distill-Qwen-9B-Q5_K_M.gguf` | 131072 / 1 | thinking true; spec none |
| `qwen36` | `unsloth/Qwen3.6-35B-A3B-MTP-GGUF` / `Qwen3.6-35B-A3B-UD-IQ3_XXS.gguf` | 65536 / 1 | thinking true; draft-mtp; maximum 2 draft tokens |

Both inherit all GPU layers, flash attention on, Q8 K/V caches, Jinja, deduplicated downloads,
no startup loading, repetition 1.0 and presence 0.0. Qwen additionally uses temperature
0.6, top-p 0.95, top-k 20, min-p 0.0 and `no-mmproj=true`.
**The audited MiMo template's comment describes disabling projector download, but its
literal `no-mmproj=false` does not do that.** Preserve the reviewed value visibly; record
unexpected projector downloads as a commissioning blocker and revise the public preset
through review rather than silently changing it. Explicit HF filenames are not immutable
content pins. Record resolved repository commit, downloaded file size and SHA-256 on first
load (including any extra files); a later upstream replacement can change the download.

## Coverage boundaries

`LlamaDeviceProjection` checks actual compiler/reconciler projection and model settings.
The disposable native monitoring case starts `llama-protocol.py` using the cached Python
OCI image, with the same presets/cache permissions and both model IDs. It verifies discovery,
OpenAI selection/completion/SSE framing, switching, retained synthetic cache and actual
Prometheus `autoload=false` behavior. **This is controlled CPU protocol coverage, not the
llama binary, downloaded weights, model quality, context allocation, MTP or GPU proof.**
The substitutions remove exactly the GPU/KFD devices and replace the image/entrypoint;
the fixture identifies itself and creates only `.synthetic-cache` files.

## Operator hardware acceptance on the reset IncusOS host

Follow [remote bootstrap](incusos-bootstrap.md) for the recorded remote, project, ZFS pool,
LAN/TLS and full PCI identity. Use `incus admin os application list`, the `gpu-support`
firmware application, `incus admin os system kernel show` and `incus info REMOTE: --resources`.
Firmware does not supply drivers; the IncusOS base kernel must support this RX 9060 XT.
Do not install host packages, load host modules by shell, bind the card to VFIO, or substitute
a CPU image. Missing `amdgpu`, firmware, render node, KFD or compatible ROCm is a blocker.

After Tend creates the actual ROCm instance, use bounded remote `incus exec`:

```sh
timeout 30 incus exec "$TEND_REMOTE:llama" --project "$TEND_PROJECT" \
  --user 1000 --group 1000 -- sh -eu -c '
  id
  test -c /dev/kfd && test -r /dev/kfd && test -w /dev/kfd
  found=false
  for node in /dev/dri/renderD*; do
    test -c "$node" || continue
    test -r "$node" && test -w "$node"
    found=true
  done
  test "$found" = true
  test -r /etc/llama/models.ini
  test -w /var/cache/llama
  df -h /var/cache/llama
  '
```

Before downloads, record available ZFS capacity, actual GPU VRAM and expected weight/KV/MTP
memory. Context-per-slot is the declared context divided by parallel (currently 1). Do not
assume the advertised context fits in VRAM. Budget first download/load independently from
ordinary request timeouts; choose and record a finite bound based on disk/network and model
size. A failed bound requires diagnosis, not indefinite polling or silent reduced context.

From an authorized private client, record `/models` showing both unloaded presets. Scrape
`/metrics?model=mimo&autoload=false` and the Qwen equivalent; unloaded models must remain
unloaded and VRAM usage must not increase. `/health` is router readiness only.
For each preset, POST `{"model":"ID"}` to `/models/load`, then require bounded `loaded`
status, inspect private startup diagnostics for the actual GPU offload/allocated context,
and observe VRAM residency through supported GPU tooling within the workload. Send a real
`/v1/chat/completions` request and a streaming request with that explicit model ID. Record
successful response, effective context/parallel/cache settings, thinking behavior, and Qwen
MTP initialization plus accepted/drafted token counters if exposed. Missing MTP support or
VRAM allocation failure blocks that preset; never infer success from `/health`.

Switch `mimo -> qwen36 -> mimo`, proving maximum one resident model and cache reuse. Send
one authenticated Open WebUI chat and one Pi request per intended preset; record selected
model identity at the backend and client metadata, including context-per-slot. Keep prompts,
sessions and raw logs private; publish only sanitized outcomes. Restart llama and repeat
device access, cache identity, model load/completion and idle-scrape checks. The final
[rebuild gate](incus-smoke.md) also requires host reboot and retained workload/controller data.

Record repository revision, image digest/fingerprint, IncusOS/kernel/firmware revisions,
PCI/driver/VRAM identity, weight provenance and each per-model result. Hardware acceptance
remains **pending operator execution** until those observations exist; CI cannot close it.
