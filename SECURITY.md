# Security Policy

`exeris-caps-gateway-core` is a proof of concept. It has not had a security review, and nothing in it should be relied
on in production.

## Versions covered

| Version / branch | Security reports |
| --- | --- |
| `main` (default branch) | Reports accepted |
| tagged releases | none exist yet |

No artefact has been published from this repository, so a report is about source on `main`.

## Scope

In scope is anything this cap decides on the request path: the readiness gate and request dispatch of `IngressBootstrap`, the order in which `PolicyChain` runs policies and whether a short-circuit can be bypassed. A defect in a policy that a consuming SKU registers belongs to that policy's repository.

## Reporting a vulnerability

Use GitHub private vulnerability reporting on this repository
(<https://github.com/exeris-systems/exeris-caps-gateway-core/security/advisories/new>), or email security@exeris.eu.
Do not open a public issue, discussion or pull request for a suspected vulnerability.

A useful report names the affected commit, the configuration and the input that trigger it, what you
observed and what you expected. Do not include real credentials, tokens or personal data; synthetic
values are enough to demonstrate a defect.

The organisation-wide terms for acknowledgement and disclosure are in the
[Exeris security policy](https://github.com/exeris-systems/.github/blob/main/SECURITY.md).
