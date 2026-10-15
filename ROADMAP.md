# exeris-caps-gateway-core — Roadmap

Status: **poc**. Version line: **0.1.x**. Licence: Exeris Commercial License (ADR-023), entitlement id
`exeris-caps-gateway-core`.

Milestones are versions, not dates. Dates appear only on what has shipped; nothing has shipped yet.

> **`1.0.0` is not on this roadmap.** It is a gate, described in the last section. Status moves
> `poc` → `snapshot` → `released`; the registry row in
> [`cap-license-registry.md`](https://github.com/exeris-systems/exeris-docs/blob/main/cap-license-registry.md)
> follows it.

---

## Where this sits

Layer 1 substrate aggregate of the Gateway family (HLA §3.2). Today it provides three services —
`GatewayLifecycle`, `IngressBootstrap` and `PolicyChain` — and is the only cap in the family that owns
a lifecycle (`@CapabilityLifecycle`, `CapabilityLifecycleHooks`). It is the host the API Gateway SKU
registers every policy into.

Gaps between the inventory and the code, found in the code:

- HLA §3.2 says the aggregate re-exports `RouteRegistry`, `UpstreamPool`,
  `PolicyChain`, `BackendHealthMonitor` and `AdminControlPlane`. The module provides none of the
  first, second, fourth and fifth; `upstream-pool` is a separate repository and the rest do not exist.
- `PolicyChain` is HLA's separate `policy-chain` cap, folded in here.

**Upstream constraints this repo does not own:**

| Constraint | Effect here | Owner |
|---|---|---|
| Kernel licence gate (ADR-088, ADR-089) ships in kernel `0.13.0-SNAPSHOT` | `EntitlementRequirement` and its TCK are only resolvable from that line | `exeris-kernel` |
| `exeris-caps-service-boundary-core` must be resolvable in CI | It is a test-scope dependency today and, from 0.2.0, an optional `@Requires` | `exeris-caps-service-boundary-core` |
| ADRs on the response-side seam and on request-path canonicalisation (both accepted before any 0.2.0 starts) | No policy cap starts its 0.2.0 before they are accepted | this repository, `exeris-docs` |
| No `composition.json` reader | Configuration cannot arrive from a SKU manifest; the SKU wires modules by hand | SKU scaffold |
| HLA §3.2 row differs from the code (see above) | The registry row describes a cap that does not exist | `exeris-docs` |

---

## 0.1.0 — the code PR

- [ ] Defects found in the pre-import review are fixed in the import, each with a test
- [ ] Code imported; first commit on `main`
- [ ] `@Provides` versions at contract `0.1.0` for `GatewayLifecycle`, `IngressBootstrap`, `PolicyChain`
- [ ] Kernel `0.13.0-SNAPSHOT`; SDK `0.12.0` and tooling `0.10.0` pins aligned
- [ ] Artefacts stay `0.1.0-SNAPSHOT` until kernel `0.13.0` is released; a non-snapshot `0.1.0` is cut only after that
- [ ] Licence enforcement: `GatewayCoreEntitlement` (`EntitlementRequirement`, id `exeris-caps-gateway-core`),
      registered under `META-INF/services`, with a TCK test and a boot test against the kernel's
      Phase 0 gate, as in `exeris-caps-credential-store`
- [ ] `build.yml` repaired (repository under test, kernel ref, credential-free where possible) and green on JDK 25 and 26
- [ ] `cap-manifest.json` regenerated and committed through the two-pass build
- [ ] POM description and README state what the module provides today, not the HLA list

**Exit gate:** `mvn verify` green on both JDK rows; the entitlement test fails when the id is absent from the manifest.

## 0.2.0 — `RequestFilterChain` replaces `PolicyChain`

Separate PR. Replaces this cap's own `PolicyChain` / `GatewayPolicy` / `PolicyResult` with
service-boundary-core's `RequestFilterChain` / `AdmissionFilter` (ADR-099).

Entry condition: an ADR on the response-side seam (where post-handler work lives: header mutation, outcome
recording, access logging) is accepted; no policy cap starts its 0.2.0 before it. The ADR on request-path
canonicalisation (below) is decided alongside it, before any 0.2.0. Policies bind to the chain;
service-boundary-core is an optional `@Requires` with range `[0.1.0,0.2.0)`, the shape
`exeris-caps-rate-limiting` already uses.

- [ ] Drop `@Provides PolicyChain`; add `@Requires(RequestFilterChain, "[0.1.0,0.2.0)", optional = true)`
- [ ] `IngressBootstrap` runs `RequestFilterChain.execute` instead of `executePreRouting`
- [ ] Decide the response side. Gap found in the code: `AdmissionFilter` is pre-handler only, while
      five of the seven current policy adapters (CORS, rate-limit, circuit-breaker, routing,
      observability) act in `postRouting` — header mutation, outcome recording, access logging.
      `PolicyInterceptingHttpExchange` is the only response hook today. The response-side ADR (entry
      condition above) settles where that work lives before any policy cap is migrated
- [ ] One request-path canonicalisation (percent-decoding, dot segments, repeated slashes, path parameters)
      applied once before the chain; routing, WAF and JWT see the same canonical path, with a shared test
      corpus. Decided in an ADR alongside the response-side one, before any 0.2.0
- [ ] Behaviour without a `RequestFilterChain` defined and tested: either the requirement is mandatory for
      this cap, or ingress refuses to start unless an explicit no-policy mode is configured. Never a silent pass-through
- [ ] `RouteMatch` in the chain is service-boundary-core's (`ApiSurfaceRoute`), not the gateway router's;
      decide how `request-routing` results reach filters
- [ ] Migration notes for every affected cap: `request-routing`, `circuit-breaker`, `waf-rules`,
      `gateway-observability`, and the sibling `cors-policy`, `rate-limiting`, `jwt-validation`

**Exit gate:** no `eu.exeris.caps.gatewaycore.api.PolicyChain` left; `GatewayToServiceBoundaryCompositionTest` passes against the real chain.

## 0.3.0 — Independent security review

- [ ] Covered by the API Gateway SKU review (one review; scope: the SKU and every composed cap); this
      repository's public API and documented behaviour are listed in that scope
- [ ] Threat model written and committed alongside the code
- [ ] Findings handled through `SECURITY.md` private reporting
- [ ] Fixes released, each with a regression test

## 0.4.0 — re-measurement

- [ ] Hot-path measurement of ingress dispatch in the `exeris-benchmarks` harness on the reference profile
- [ ] Results published as an artefact in `exeris-benchmarks`; this repository links to it and states no figure
- [ ] Snapshot artefact published to GitHub Packages; status `poc` → `snapshot`

**Exit gate:** a resolvable snapshot exists and the benchmark artefact is linked.

---

## 1.0.0 — a gate, not a milestone

Entry condition: the API Gateway SKU has run on this cap with the post-0.2.0 chain, the security review
is closed, and the measurement above is repeatable. Then `@Provides` versions freeze, a semver gate
(japicmp or revapi) is added against the previous release, and the status becomes `released`.

## Deliberately out of scope

- `RouteRegistry`, `BackendHealthMonitor`, `AdminControlPlane`, TLS termination: separate caps in HLA §3.2, none in this repository.
- Transport and TLS configuration: kernel concerns.
