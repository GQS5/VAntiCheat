# VAntiCheat 0.1.6

## Apollo Runtime Diagnostics

VAntiCheat still requires the official Apollo server plugin for Lunar policy
integration. The `com.lunarclient:apollo-api:1.2.9` Maven dependency is only
the provided compile-time API and is not the server runtime.

This release adds explicit startup status and policy request success logging,
and verifies Apollo support before tracking a registration. The disposable
Folia test server was validated with the official `Apollo-Folia 1.2.9` plugin.

Xaero probes remain separate and are not enabled by Apollo.
