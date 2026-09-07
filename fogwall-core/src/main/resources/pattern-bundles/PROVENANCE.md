# Pattern bundle provenance

fogwall's built-in PII content-pattern bundles are hand-ported from
[data-privacy-stack/presidio](https://github.com/data-privacy-stack/presidio) (MIT licensed — see
`UPSTREAM-LICENSE-presidio.txt` in this directory). fogwall does **not** run Presidio itself: no Python runtime, no NLP
models, no sidecar service. Only the regex patterns, context-keyword lists, and structural validation logic (Luhn
checksum, placeholder rejection, etc.) from Presidio's `PatternRecognizer` subclasses are translated into fogwall's own
Java implementation. Refresh this against upstream with `/refresh-pattern-bundles` (see `.claude/skills/`).

**Pinned commit:** `5e2fcea990aa3b99660d2aea9121d0ba74a8940b`

## Simplifications from upstream

Presidio's recognizers score multiple regex variants per data type at different confidence tiers (e.g. US SSN has 5
patterns from "very weak" bare-digit matches to a "medium" delimited match) and aggregate confidence with NLP context
signals. fogwall has no confidence-scoring engine — each check is binary (WARN or not). To keep precision reasonable
without that machinery:

- Only the **highest-confidence regex variant** per data type is ported (usually Presidio's "medium" tier);
  low-confidence bare-digit variants are dropped entirely.
- A context-keyword match is **always required**, not optional/scored — see `contextKeywords`/`contextWindow` on each
  `PatternRule`.
- Where upstream has an `invalidate_result` structural check (Luhn, placeholder/prefix rejection), it's ported verbatim
  as a named Java validator and always applied.

## Source file mapping

Bundle naming: `national-id-<cc>` bundles cover a single country's national-identity-registry number (SIN, SSN, NINO,
etc.) - not that country's full PII catalog. `generic-<type>` bundles cover a data type that isn't tied to a
jurisdiction (IBAN, credit card, crypto wallet addresses, US bank routing numbers) - see "Generic (non-jurisdiction)
bundles" below.

| fogwall bundle        | Jurisdiction   | Upstream source file                                                                                                          | Validator(s)                              |
| --------------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `national-id-ca.json` | Canada         | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/canada/ca_sin_recognizer.py`                     | `luhn`                                    |
| `national-id-us.json` | United States  | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/us/us_ssn_recognizer.py`                         | `us-ssn-structural`                       |
| `national-id-gb.json` | United Kingdom | `country_specific/uk/uk_nino_recognizer.py`, `country_specific/uk/uk_nhs_recognizer.py`                                       | none (NINO); `uk-nhs-number` (NHS number) |
| `national-id-au.json` | Australia      | `country_specific/australia/au_tfn_recognizer.py`, `au_abn_recognizer.py`, `au_acn_recognizer.py`                             | `au-tfn`; `au-abn`; `au-acn`              |
| `national-id-de.json` | Germany        | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/germany/de_social_security_recognizer.py`        | `de-rvnr`                                 |
| `national-id-in.json` | India          | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/india/in_aadhaar_recognizer.py`                  | `in-aadhaar`                              |
| `national-id-sg.json` | Singapore      | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/singapore/sg_fin_recognizer.py`                  | none (structural regex only)              |
| `national-id-za.json` | South Africa   | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/south_africa/za_id_number_recognizer.py`         | `za-id`                                   |
| `national-id-es.json` | Spain          | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/spain/es_nif_recognizer.py`                      | `es-nif`                                  |
| `national-id-se.json` | Sweden         | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/sweden/se_personnummer_recognizer.py`            | `se-personnummer`                         |
| `national-id-tr.json` | Turkey         | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/turkey/tr_national_id_recognizer.py`             | `tr-national-id`                          |
| `national-id-fi.json` | Finland        | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/finland/fi_personal_identity_code_recognizer.py` | `fi-personal-identity-code`               |
| `national-id-it.json` | Italy          | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/italy/it_fiscal_code_recognizer.py`              | `it-fiscal-code`                          |
| `national-id-kr.json` | South Korea    | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/korea/kr_rrn_recognizer.py`                      | `kr-rrn`                                  |
| `national-id-ng.json` | Nigeria        | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/nigeria/ng_nin_recognizer.py`                    | `ng-nin`                                  |
| `national-id-ph.json` | Philippines    | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/philippines/ph_umid_recognizer.py`               | none (structural regex only)              |
| `national-id-th.json` | Thailand       | `presidio-analyzer/presidio_analyzer/predefined_recognizers/country_specific/thai/th_tnin_recognizer.py`                      | `th-tnin`                                 |

Where a country ships multiple recognizers (e.g. Germany also has driving licence, passport, tax ID), only the
identity-registry-equivalent field(s) are ported - not that country's full field catalog. GB and AU are the exceptions:
NHS number (health service registration) and ABN/ACN (business registration) were pulled in alongside NINO/TFN because
they're checksum-backed and commonly handled alongside personal national IDs in the same review context. Presidio's
Verhoeff checksum implementation is identical between India and Nigeria, so it's shared as one `Verhoeff` utility class
rather than duplicated.

## Generic (non-jurisdiction) bundles

These aren't ISO-country-scoped and cover data types Presidio also recognizes, plus two (`us-bank-routing`,
`btc-bech32-address`/`eth-address`) that Presidio doesn't:

| fogwall bundle                 | Data type(s)                                         | Upstream source file                                                                                                                                                                                                        | Validator(s)                                                                                                                                             |
| ------------------------------ | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `generic-iban.json`            | IBAN                                                 | `presidio-analyzer/presidio_analyzer/predefined_recognizers/generic/iban_patterns.py`                                                                                                                                       | `iban` (ISO 7064 mod-97-10)                                                                                                                              |
| `generic-credit-card.json`     | Credit card number                                   | `presidio-analyzer/presidio_analyzer/predefined_recognizers/generic/credit_card_recognizer.py`                                                                                                                              | `luhn`                                                                                                                                                   |
| `generic-crypto-wallet.json`   | Bitcoin (legacy), Bitcoin (SegWit/Taproot), Ethereum | Bitcoin legacy ported from `presidio-analyzer/presidio_analyzer/predefined_recognizers/generic/crypto_recognizer.py`; SegWit/Taproot (BIP-173/BIP-350) and Ethereum (EIP-55) are fogwall's own additions, not from Presidio | `btc-address` (Base58Check); `btc-bech32-address` (Bech32/Bech32m); `eth-address` (EIP-55 Keccak-256 checksum, only applies to checksum-cased addresses) |
| `generic-us-bank-routing.json` | US bank routing number (ABA)                         | Not from Presidio - the publicly published ABA routing-number checksum algorithm                                                                                                                                            | `us-bank-routing` (weighted mod-10)                                                                                                                      |

## Group aliases

`national-id-all-geos` and `generic-all` are resolved dynamically (prefix match against the bundle name, see
`BuiltInPatternBundleSource.expandAlias`) rather than stored as their own bundle file - adding a new
`national-id-*`/`generic-*` bundle picks it up automatically, no alias file to keep in sync.

## Changelog

- 2026-07-20 — initial import, pinned to `517d13eee659794ed3a55d188752d014be574c2a`. Canada (SIN + Luhn), US (SSN +
  structural checks), UK/GB (NINO) — the three financially-significant jurisdictions in scope for #413's first cut.
- 2026-07-20 — expanded to 14 more jurisdictions (AU, DE, IN, SG, ZA, ES, SE, TR, FI, IT, KR, NG, PH, TH), same pinned
  commit, for broader global coverage rather than financially-significant jurisdictions only. `pii-canada` renamed to
  `pii-ca` and `pii-uk` renamed to `pii-gb` for consistency with the ISO 3166-1 alpha-2 codes used by every other
  bundle.
- 2026-07-20 — renamed every `pii-<cc>` bundle to `national-id-<cc>` for accuracy: these bundles only ever covered a
  single national-identity-registry number, not a country's full PII surface. Added `national-id-all-geos`/
  `generic-all` group aliases. Added NHS number to `national-id-gb` and ABN/ACN to `national-id-au` (both
  checksum-backed, both from Presidio). Split off a new "generic" tier of non-jurisdiction bundles: `generic-iban`,
  `generic-credit-card`, `generic-crypto-wallet` (Bitcoin legacy + SegWit/Taproot + Ethereum), and
  `generic-us-bank-routing` - the latter two chains and the ABA routing checksum aren't from Presidio. Default
  `fogwall.yml` enables `national-id-all-geos` plus the two strongest-checksum generic bundles (`generic-iban`,
  `generic-crypto-wallet`); `generic-credit-card`/`generic-us-bank-routing` (Luhn-strength mod-10, noisier) stay opt-in.
- 2026-09-07 — re-pinned to `5e2fcea990aa3b99660d2aea9121d0ba74a8940b`, no bundle changes. Of the 23 tracked upstream
  files, only `uk_nino_recognizer.py` changed: upstream dropped an optional space between the invalid-prefix negative
  lookahead and the letter pair, which had let a NINO preceded by a space bypass the BG/GB/NK/KN/NT/TN/ZZ exclusions.
  fogwall's `national-id-gb` NINO regex never had that space, so it already behaves as the fixed upstream one does.
  Corrected the three `generic-*` upstream source paths (they live under `predefined_recognizers/generic/`) and the
  refresh command name.
