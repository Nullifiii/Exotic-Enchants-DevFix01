# Changelog

## 1.0.1
- Economy: exact diagnostics (Vault missing vs no economy provider), automatic late connection, `/exotic admin economy`.
- Shop purchases: tested charge/refund/stock flow, double-click guard.
- Exotic effects implemented for unusual item types; combinations without an effect are no longer offered
  (`exotic-effects.require-working-effect`); `effects-audit.txt` generated on start.
- Fixed: crash recovery could resurrect an old owner after an interrupted claim.

## 1.0.0
- Initial release.
