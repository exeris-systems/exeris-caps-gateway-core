# exeris-caps-gateway-core

The substrate of the Gateway family as an Exeris capability. It provides `GatewayLifecycle` (the initialize, ready, drain and terminate phases of a gateway), `IngressBootstrap` (the kernel HTTP handler of the gateway: it checks readiness, counts served requests, runs the policy chain and dispatches to a path-prefix handler or a delegate) and `PolicyChain` (registration and execution of `GatewayPolicy` instances before and after routing).

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT on JDK 25 LTS. The API is not stable and no artefact has been published.

## Where it sits

- **Layer:** Layer 1 — Substrate aggregate (Gateway family), in the Tier 2 capability map of [`high-level-architecture.md`](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.2.
- **Depends on:** `exeris-kernel-spi` (the HTTP types a handler and a policy are written against) and `exeris-sdk-composition-lifecycle`. The annotations are `provided` and every Exeris annotation is `@Retention(SOURCE)`.
- **Provides** (`@Provides`, contract version 0.1.0): `GatewayLifecycle`, `IngressBootstrap`, `PolicyChain`.
- **Requires:** no other capability.
- **Wall (ADR-055):** `eu.exeris.caps.gatewaycore.api` is the cross-cap surface; `eu.exeris.caps.gatewaycore.internal` is private, and a sibling cap reaching into it fails the build. No Spring, servlet, Netty or reactive type may appear (ADR-006).

## What the source contains

- `GatewayCoreModule` carries `@CapabilityModule` and the three `@Provides` declarations, and owns the lifecycle through `CapabilityLifecycleHooks`.
- A `GatewayPolicy` either lets the exchange proceed or short-circuits it with its own `HttpResponse` (`PolicyResult`); it can also act on the upstream response after routing. `PolicyChain` registers policies by name and executes them in a deterministic order.
- `GatewayLifecycleHook` lets other components join the four lifecycle phases: `initialize` and `ready` run in registration order, `drain` and `terminate` in reverse.
- `IngressBootstrap` routes by path prefix to registered handlers and falls back to a delegate handler.
- The source here contains those three services only; a route registry, an upstream pool and a backend health monitor are not part of this repository.

## Build & test

Requires JDK 25 and Maven 3.9+.

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — the processor writes capability metadata
mvn verify                               # pass 2 — generate cap-manifest.json, validate the graph, scan the Wall
```

Both passes are needed after a `clean`: `exeris:generate` runs before `compile`, and `compile` is when the processor writes the metadata it reads. `src/main/generated/java/cap-manifest.json` is committed, so a clean `git status` after `mvn verify` shows the manifest matches the declaration.

The upstreams are not on Maven Central. `exeris-sdk` and `exeris-tooling` are built from source, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`.

## Documentation

- [`AGENTS.md`](AGENTS.md) — the repository's operating contract and invariants.
- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — how a cap declares `@CapabilityModule`, `@Provides`, `@Requires` and `@CapabilityLifecycle`.
- [Capability licence registry](https://github.com/exeris-systems/exeris-docs/blob/main/cap-license-registry.md) — the licence value of every cap.

Where they disagree, the ADRs below and the HLA outrank this README.

## Governing ADRs

- [ADR-006](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-006-spring-free-kernel-boundary.md) — Spring-free kernel boundary.
- [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md) — capability licensing taxonomy.
- [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) — capability composition model.
- [ADR-053](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-053-sku-composition-manifest-format.md) — composition manifest format.
- [ADR-055](https://github.com/exeris-systems/exeris-tooling/blob/main/docs/adr/ADR-055-cap-tier-wall-guard.md) — cap-tier Wall guard.
- [ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md) — signed licence manifest.
- [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md) — capability entitlement enforcement.

Registry: [`adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

## License

Exeris Commercial License 1.1 — see [`LICENSE`](LICENSE). `exeris-caps-gateway-core` is a `commercial` capability under [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md): the source is available to read, build, test and evaluate, and production use requires a commercial licence.

Its entitlement id is `exeris-caps-gateway-core`, the artifactId ([ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md), [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md)).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Report a suspected vulnerability as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
